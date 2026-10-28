<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  listUgcPosts,
  listUgcReports,
  removeUgcPost,
  reviewUgcPost,
  updateUgcReport,
} from '../../api/adminEndpoints'
import type {
  ReviewDecision,
  UgcPostDto,
  UgcReportDto,
  UgcReportStatus,
} from '../../api/adminTypes'
import type { UgcStatus } from '../../api/types'
import AdminDataTable, {
  type AdminTableColumn,
} from '../../components/admin/AdminDataTable.vue'
import AdminJobStatus from '../../components/admin/AdminJobStatus.vue'
import ConfirmActionDialog from '../../components/admin/ConfirmActionDialog.vue'
import {
  adminFailureMessage,
  formatAdminDate,
  sortAdminRows,
} from './adminViewSupport'

const emit = defineEmits<{ unauthorized: [] }>()

const POST_COLUMNS: AdminTableColumn[] = [
  { key: 'title', label: '内容', sortable: true },
  { key: 'authorName', label: '作者' },
  { key: 'status', label: '审核状态', sortable: true },
  { key: 'reports', label: '举报数', sortable: true },
  { key: 'createdAt', label: '提交时间', sortable: true },
]

const REPORT_COLUMNS: AdminTableColumn[] = [
  { key: 'reporterName', label: '举报人' },
  { key: 'reason', label: '原因' },
  { key: 'status', label: '状态', sortable: true },
  { key: 'createdAt', label: '提交时间', sortable: true },
]

const posts = ref<UgcPostDto[]>([])
const loading = ref(false)
const errorMessage = ref('')
const page = ref(1)
const pages = ref(1)
const total = ref(0)
const statusFilter = ref<UgcStatus | ''>('PENDING')
const sortKey = ref('createdAt')
const sortDirection = ref<'asc' | 'desc'>('desc')
const reviewTarget = ref<UgcPostDto | null>(null)
const reviewDecision = ref<ReviewDecision>('REJECT')
const rejectionReason = ref('')
const reviewSaving = ref(false)
const reportTarget = ref<UgcPostDto | null>(null)
const reports = ref<UgcReportDto[]>([])
const reportLoading = ref(false)
const reportError = ref('')
const removingTarget = ref<UgcPostDto | null>(null)
const removing = ref(false)

const orderedPosts = computed(() => sortAdminRows(posts.value, sortKey.value, sortDirection.value))
const postRows = computed(
  () => orderedPosts.value as unknown as Array<Record<string, unknown>>,
)
const reportRows = computed(
  () => reports.value as unknown as Array<Record<string, unknown>>,
)

function postRow(row: Record<string, unknown>): UgcPostDto {
  return row as unknown as UgcPostDto
}

function reportRow(row: Record<string, unknown>): UgcReportDto {
  return row as unknown as UgcReportDto
}

function postCell(row: Record<string, unknown>, column: AdminTableColumn): string {
  const post = postRow(row)
  if (column.key === 'createdAt') {
    return formatAdminDate(post.createdAt)
  }
  return String(post[column.key as keyof UgcPostDto] ?? '—')
}

function reportCell(row: Record<string, unknown>, column: AdminTableColumn): string {
  const report = reportRow(row)
  if (column.key === 'createdAt') {
    return formatAdminDate(report.createdAt)
  }
  return String(report[column.key as keyof UgcReportDto] ?? '—')
}

async function loadPosts(requestedPage = page.value): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listUgcPosts({
      page: requestedPage,
      size: 20,
      status: statusFilter.value || undefined,
    })
    posts.value = result.items
    page.value = result.page
    pages.value = Math.max(result.pages, 1)
    total.value = result.total
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '审核内容加载失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    loading.value = false
  }
}

async function applyReview(post: UgcPostDto, decision: ReviewDecision, reason?: string): Promise<void> {
  reviewSaving.value = true
  errorMessage.value = ''
  try {
    await reviewUgcPost(post.id, {
      decision,
      ...(reason?.trim() ? { reason: reason.trim() } : {}),
    })
    reviewTarget.value = null
    rejectionReason.value = ''
    await loadPosts(page.value)
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '审核操作失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    reviewSaving.value = false
  }
}

function startReject(post: UgcPostDto): void {
  reviewTarget.value = post
  reviewDecision.value = 'REJECT'
  rejectionReason.value = ''
}

async function submitRejection(): Promise<void> {
  if (!reviewTarget.value || !rejectionReason.value.trim()) {
    errorMessage.value = '拒绝时必须填写原因。'
    return
  }
  await applyReview(reviewTarget.value, reviewDecision.value, rejectionReason.value)
}

async function openReports(post: UgcPostDto): Promise<void> {
  reportTarget.value = post
  reportLoading.value = true
  reportError.value = ''
  try {
    const result = await listUgcReports(post.id, { page: 1, size: 50 })
    reports.value = result.items
  } catch (error) {
    reportError.value = adminFailureMessage(
      error,
      '举报明细加载失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    reportLoading.value = false
  }
}

async function resolveReport(report: UgcReportDto, status: UgcReportStatus): Promise<void> {
  if (!reportTarget.value) {
    return
  }
  reportError.value = ''
  try {
    await updateUgcReport(reportTarget.value.id, report.id, status)
    await openReports(reportTarget.value)
  } catch (error) {
    reportError.value = adminFailureMessage(
      error,
      '举报状态更新失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  }
}

async function confirmRemove(): Promise<void> {
  if (!removingTarget.value) {
    return
  }
  removing.value = true
  errorMessage.value = ''
  try {
    await removeUgcPost(removingTarget.value.id)
    removingTarget.value = null
    await loadPosts(page.value)
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '移除内容失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    removing.value = false
  }
}

function changeSort(key: string): void {
  if (sortKey.value === key) {
    sortDirection.value = sortDirection.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = key
    sortDirection.value = 'asc'
  }
}

onMounted(() => {
  void loadPosts()
})
</script>

<template>
  <section class="admin-page" data-view="admin-moderation" aria-labelledby="admin-moderation-title">
    <header class="admin-page__header">
      <div>
        <p class="section-folio">治理 · UGC 审核</p>
        <h2 id="admin-moderation-title">经验内容审核</h2>
        <p>只有通过审核的经验会被纳入检索；移除会同时清理相关向量内容。</p>
      </div>
    </header>

    <form class="admin-toolbar" @submit.prevent="loadPosts(1)">
      <label>
        <span>审核状态</span>
        <select v-model="statusFilter">
          <option value="">全部内容</option>
          <option value="PENDING">待审核</option>
          <option value="APPROVED">已通过</option>
          <option value="REJECTED">已拒绝</option>
          <option value="REMOVED">已移除</option>
        </select>
      </label>
      <button type="submit" class="admin-button admin-button--quiet">刷新列表</button>
    </form>

    <p v-if="errorMessage" class="admin-alert" role="alert">{{ errorMessage }}</p>

    <AdminDataTable
      :columns="POST_COLUMNS"
      :rows="postRows"
      :loading="loading"
      :error="errorMessage"
      :page="page"
      :pages="pages"
      :total="total"
      empty-message="没有符合条件的经验内容"
      :row-key="(row) => postRow(row).id"
      @page="loadPosts"
      @retry="loadPosts(page)"
      @sort="changeSort"
    >
      <template #cell="{ row, column, value }">
        <AdminJobStatus v-if="column.key === 'status'" :status="postRow(row).status" />
        <span v-else :title="String(value)">{{ postCell(row, column) }}</span>
      </template>
      <template #actions="{ row }">
        <button
          v-if="postRow(row).status === 'PENDING'"
          type="button"
          class="admin-text-button"
          :data-test="`approve-post-${postRow(row).id}`"
          @click="applyReview(postRow(row), 'APPROVE')"
        >
          通过
        </button>
        <button
          v-if="postRow(row).status === 'PENDING'"
          type="button"
          class="admin-text-button"
          @click="startReject(postRow(row))"
        >
          拒绝
        </button>
        <button type="button" class="admin-text-button" @click="openReports(postRow(row))">
          举报{{ postRow(row).reports ? ` (${postRow(row).reports})` : '' }}
        </button>
        <button
          v-if="postRow(row).status !== 'REMOVED'"
          type="button"
          class="admin-text-button admin-text-button--danger"
          @click="removingTarget = postRow(row)"
        >
          移除
        </button>
      </template>
    </AdminDataTable>

    <section v-if="reviewTarget" class="admin-panel moderation-decision" aria-labelledby="reject-title">
      <header>
        <div>
          <p class="section-folio">拒绝审核</p>
          <h3 id="reject-title">{{ reviewTarget.title }}</h3>
        </div>
        <button type="button" class="admin-icon-button" aria-label="关闭拒绝审核" @click="reviewTarget = null">×</button>
      </header>
      <label>
        <span>拒绝原因</span>
        <textarea v-model="rejectionReason" rows="3" maxlength="500" placeholder="说明需修改或不予通过的原因"></textarea>
      </label>
      <div class="admin-form-actions">
        <button type="button" class="admin-button admin-button--danger" :disabled="reviewSaving" @click="submitRejection">
          {{ reviewSaving ? '提交中…' : '确认拒绝' }}
        </button>
      </div>
    </section>

    <section v-if="reportTarget" class="admin-panel moderation-reports" aria-labelledby="report-title">
      <header>
        <div>
          <p class="section-folio">举报明细</p>
          <h3 id="report-title">{{ reportTarget.title }}</h3>
        </div>
        <button type="button" class="admin-icon-button" aria-label="关闭举报明细" @click="reportTarget = null">×</button>
      </header>
      <p v-if="reportError" class="admin-alert" role="alert">{{ reportError }}</p>
      <AdminDataTable
        :columns="REPORT_COLUMNS"
        :rows="reportRows"
        :loading="reportLoading"
        :error="reportError"
        :total="reports.length"
        empty-message="该内容暂无举报明细"
        :row-key="(row) => reportRow(row).id"
        @retry="openReports(reportTarget!)"
      >
        <template #cell="{ row, column, value }">
          <AdminJobStatus v-if="column.key === 'status'" :status="reportRow(row).status" />
          <span v-else :title="String(value)">{{ reportCell(row, column) }}</span>
        </template>
        <template #actions="{ row }">
          <button
            v-if="reportRow(row).status === 'OPEN'"
            type="button"
            class="admin-text-button"
            @click="resolveReport(reportRow(row), 'RESOLVED')"
          >
            标记已处理
          </button>
          <button
            v-if="reportRow(row).status === 'OPEN'"
            type="button"
            class="admin-text-button"
            @click="resolveReport(reportRow(row), 'DISMISSED')"
          >
            驳回举报
          </button>
        </template>
      </AdminDataTable>
    </section>

    <ConfirmActionDialog
      :model-value="Boolean(removingTarget)"
      title="移除经验内容"
      description="内容会从公开区域和所有检索向量版本中移除，不能直接恢复。"
      :target="removingTarget?.title ?? ''"
      :confirmation-text="removingTarget?.title ?? ''"
      confirm-label="确认移除"
      :busy="removing"
      @update:model-value="(open) => { if (!open) removingTarget = null }"
      @confirm="confirmRemove"
    />
  </section>
</template>

<style scoped>
.moderation-decision,
.moderation-reports {
  margin-top: 1.2rem;
}

.moderation-decision > header,
.moderation-reports > header {
  display: flex;
  gap: 1rem;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 0.9rem;
}

.moderation-decision h3,
.moderation-reports h3 {
  margin: 0.25rem 0 0;
  color: var(--green-950);
  font-family: STKaiti, KaiTi, serif;
  font-size: 1.2rem;
}

.moderation-decision label {
  display: grid;
  gap: 0.4rem;
}

.moderation-decision label > span {
  color: var(--ink-soft);
  font-size: 0.68rem;
}

.moderation-decision textarea {
  width: 100%;
  padding: 0.6rem;
  color: var(--ink);
  background: #fffdf7;
  border: 1px solid var(--rule);
  resize: vertical;
}
</style>
