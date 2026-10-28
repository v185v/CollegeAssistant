<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { listAuditLogs } from '../../api/adminEndpoints'
import type { AdminAuditLogDto, AdminAuditResult } from '../../api/adminTypes'
import AdminDataTable, {
  type AdminTableColumn,
} from '../../components/admin/AdminDataTable.vue'
import AdminJobStatus from '../../components/admin/AdminJobStatus.vue'
import {
  adminFailureMessage,
  formatAdminDate,
  sortAdminRows,
} from './adminViewSupport'

const emit = defineEmits<{ unauthorized: [] }>()

const AUDIT_COLUMNS: AdminTableColumn[] = [
  { key: 'createdAt', label: '发生时间', sortable: true },
  { key: 'actorId', label: '操作者' },
  { key: 'action', label: '操作', sortable: true },
  { key: 'targetType', label: '目标类型' },
  { key: 'targetId', label: '目标编号' },
  { key: 'result', label: '结果', sortable: true },
]

const rows = ref<AdminAuditLogDto[]>([])
const loading = ref(false)
const errorMessage = ref('')
const page = ref(1)
const pages = ref(1)
const total = ref(0)
const actorId = ref('')
const action = ref('')
const targetType = ref('')
const targetId = ref('')
const result = ref<AdminAuditResult | ''>('')
const createdFrom = ref('')
const createdTo = ref('')
const sortKey = ref('createdAt')
const sortDirection = ref<'asc' | 'desc'>('desc')

const orderedRows = computed(() => sortAdminRows(rows.value, sortKey.value, sortDirection.value))
const tableRows = computed(
  () => orderedRows.value as unknown as Array<Record<string, unknown>>,
)

function auditRow(row: Record<string, unknown>): AdminAuditLogDto {
  return row as unknown as AdminAuditLogDto
}

function auditCell(row: Record<string, unknown>, column: AdminTableColumn): string {
  const entry = auditRow(row)
  if (column.key === 'createdAt') {
    return formatAdminDate(entry.createdAt)
  }
  if (column.key === 'actorId') {
    return entry.actorId ? `#${entry.actorId}` : '系统'
  }
  if (column.key === 'targetId') {
    return entry.targetId ? `#${entry.targetId}` : '—'
  }
  return String(entry[column.key as keyof AdminAuditLogDto] ?? '—')
}

function positiveNumber(value: string): number | undefined {
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined
}

function isoDate(value: string): string | undefined {
  return value ? new Date(value).toISOString() : undefined
}

async function loadAudit(requestedPage = page.value): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const response = await listAuditLogs({
      page: requestedPage,
      size: 20,
      actorId: positiveNumber(actorId.value),
      action: action.value.trim() || undefined,
      targetType: targetType.value.trim() || undefined,
      targetId: positiveNumber(targetId.value),
      result: result.value || undefined,
      createdFrom: isoDate(createdFrom.value),
      createdTo: isoDate(createdTo.value),
    })
    rows.value = response.items
    page.value = response.page
    pages.value = Math.max(response.pages, 1)
    total.value = response.total
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '审计记录加载失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    loading.value = false
  }
}

function resetFilters(): void {
  actorId.value = ''
  action.value = ''
  targetType.value = ''
  targetId.value = ''
  result.value = ''
  createdFrom.value = ''
  createdTo.value = ''
  void loadAudit(1)
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
  void loadAudit()
})
</script>

<template>
  <section class="admin-page" data-view="admin-audit" aria-labelledby="admin-audit-title">
    <header class="admin-page__header">
      <div>
        <p class="section-folio">治理 · 操作留痕</p>
        <h2 id="admin-audit-title">审计记录</h2>
        <p>记录只包含脱敏摘要；不会展示密码、密钥、令牌或原始对象路径。</p>
      </div>
    </header>

    <form class="admin-filter-grid audit-filters" @submit.prevent="loadAudit(1)">
      <label><span>操作者编号</span><input v-model="actorId" inputmode="numeric" placeholder="例如 7"></label>
      <label><span>操作</span><input v-model="action" placeholder="例如 MODEL_SAVE"></label>
      <label><span>目标类型</span><input v-model="targetType" placeholder="例如 USER"></label>
      <label><span>目标编号</span><input v-model="targetId" inputmode="numeric" placeholder="例如 11"></label>
      <label><span>结果</span><select v-model="result"><option value="">全部</option><option value="SUCCEEDED">成功</option><option value="FAILED">失败</option></select></label>
      <label><span>开始时间</span><input v-model="createdFrom" type="datetime-local"></label>
      <label><span>结束时间</span><input v-model="createdTo" type="datetime-local"></label>
      <div class="admin-filter-grid__actions">
        <button type="submit" class="admin-button admin-button--primary">筛选</button>
        <button type="button" class="admin-button admin-button--quiet" @click="resetFilters">清除</button>
      </div>
    </form>

    <p v-if="errorMessage" class="admin-alert" role="alert">{{ errorMessage }}</p>
    <AdminDataTable
      :columns="AUDIT_COLUMNS"
      :rows="tableRows"
      :loading="loading"
      :error="errorMessage"
      :page="page"
      :pages="pages"
      :total="total"
      empty-message="没有符合条件的审计记录"
      :row-key="(row) => auditRow(row).id"
      @page="loadAudit"
      @retry="loadAudit(page)"
      @sort="changeSort"
    >
      <template #cell="{ row, column, value }">
        <AdminJobStatus v-if="column.key === 'result'" :status="auditRow(row).result === 'SUCCEEDED' ? 'SUCCEEDED' : 'FAILED'" />
        <span v-else :title="String(value)">{{ auditCell(row, column) }}</span>
      </template>
    </AdminDataTable>
  </section>
</template>
