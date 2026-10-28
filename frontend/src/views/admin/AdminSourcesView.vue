<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  createSource,
  deleteSource,
  getSource,
  listSources,
  previewSource,
  setSourceEnabled,
  testSource,
  updateSource,
} from '../../api/adminEndpoints'
import type {
  SourceConnectorType,
  SourceDto,
  SourcePreviewItem,
  SourceRequest,
  SourceScheduleType,
} from '../../api/adminTypes'
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

interface SourceForm {
  name: string
  type: string
  connectorType: SourceConnectorType
  baseUrl: string
  enabled: boolean
  scheduleType: SourceScheduleType
  scheduleZone: string
  cronExpression: string
  sameHostOnly: boolean
  maxDepth: number
  maxPages: number
  maxConcurrency: number
  includePatterns: string
  excludePatterns: string
}

const SOURCE_COLUMNS: AdminTableColumn[] = [
  { key: 'name', label: '资料源', sortable: true },
  { key: 'connectorType', label: '连接方式', sortable: true },
  { key: 'baseUrl', label: '起始地址' },
  { key: 'enabled', label: '状态', sortable: true },
  { key: 'latestRunStatus', label: '最近运行' },
  { key: 'updatedAt', label: '更新时间', sortable: true },
]

const sources = ref<SourceDto[]>([])
const loading = ref(false)
const errorMessage = ref('')
const page = ref(1)
const pages = ref(1)
const total = ref(0)
const keyword = ref('')
const sortKey = ref('updatedAt')
const sortDirection = ref<'asc' | 'desc'>('desc')
const showForm = ref(false)
const editingId = ref<number | null>(null)
const formSaving = ref(false)
const formError = ref('')
const previewItems = ref<SourcePreviewItem[]>([])
const previewLoading = ref(false)
const previewError = ref('')
const deleteTarget = ref<SourceDto | null>(null)
const deleting = ref(false)
const changingEnabledId = ref<number | null>(null)

const form = ref<SourceForm>(emptyForm())

const visibleSources = computed(() => {
  const needle = keyword.value.trim().toLocaleLowerCase()
  const filtered = needle
    ? sources.value.filter((source) => [source.name, source.type, source.baseUrl]
      .filter((value): value is string => Boolean(value))
      .some((value) => value.toLocaleLowerCase().includes(needle)))
    : sources.value
  return sortAdminRows(filtered, sortKey.value, sortDirection.value)
})

const tableRows = computed(
  () => visibleSources.value as unknown as Array<Record<string, unknown>>,
)

function emptyForm(): SourceForm {
  return {
    name: '',
    type: 'OFFICIAL',
    connectorType: 'SINGLE_URL',
    baseUrl: '',
    enabled: true,
    scheduleType: 'MANUAL',
    scheduleZone: 'Asia/Shanghai',
    cronExpression: '',
    sameHostOnly: true,
    maxDepth: 1,
    maxPages: 100,
    maxConcurrency: 2,
    includePatterns: '',
    excludePatterns: '',
  }
}

function sourceRow(row: Record<string, unknown>): SourceDto {
  return row as unknown as SourceDto
}

function sourceCell(
  row: Record<string, unknown>,
  column: AdminTableColumn,
): string {
  const source = sourceRow(row)
  if (column.key === 'updatedAt') {
    return formatAdminDate(source.updatedAt)
  }
  if (column.key === 'enabled') {
    return source.enabled ? '已启用' : '已停用'
  }
  return String(source[column.key as keyof SourceDto] ?? '—')
}

function toRequest(): SourceRequest {
  return {
    name: form.value.name.trim(),
    type: form.value.type.trim(),
    connectorType: form.value.connectorType,
    baseUrl: nullableText(form.value.baseUrl),
    enabled: form.value.enabled,
    scheduleType: form.value.scheduleType,
    scheduleZone: nullableText(form.value.scheduleZone),
    cronExpression: nullableText(form.value.cronExpression),
    sameHostOnly: form.value.sameHostOnly,
    maxDepth: form.value.maxDepth,
    maxPages: form.value.maxPages,
    maxConcurrency: form.value.maxConcurrency,
    includePatterns: splitLines(form.value.includePatterns),
    excludePatterns: splitLines(form.value.excludePatterns),
  }
}

function nullableText(value: string): string | null {
  const normalized = value.trim()
  return normalized || null
}

function splitLines(value: string): string[] {
  return value
    .split(/\r?\n|,/)
    .map((entry) => entry.trim())
    .filter(Boolean)
}

async function loadSources(requestedPage = page.value): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listSources({ page: requestedPage, size: 20 })
    sources.value = result.items
    page.value = result.page
    pages.value = Math.max(result.pages, 1)
    total.value = result.total
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '资料源列表加载失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    loading.value = false
  }
}

function startCreate(): void {
  form.value = emptyForm()
  editingId.value = null
  formError.value = ''
  previewItems.value = []
  previewError.value = ''
  showForm.value = true
}

async function startEdit(source: SourceDto): Promise<void> {
  formError.value = ''
  previewItems.value = []
  previewError.value = ''
  try {
    const detail = await getSource(source.id)
    editingId.value = detail.id
    form.value = {
      name: detail.name,
      type: detail.type,
      connectorType: detail.connectorType,
      baseUrl: detail.baseUrl ?? '',
      enabled: detail.enabled,
      scheduleType: detail.scheduleType,
      scheduleZone: detail.scheduleZone ?? '',
      cronExpression: detail.cronExpression ?? '',
      sameHostOnly: detail.sameHostOnly,
      maxDepth: detail.maxDepth,
      maxPages: detail.maxPages,
      maxConcurrency: detail.maxConcurrency,
      includePatterns: detail.includePatterns.join('\n'),
      excludePatterns: detail.excludePatterns.join('\n'),
    }
    showForm.value = true
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '资料源详情加载失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  }
}

async function saveSource(): Promise<void> {
  if (!form.value.name.trim() || !form.value.type.trim()) {
    formError.value = '请填写资料源名称与类型。'
    return
  }
  formSaving.value = true
  formError.value = ''
  try {
    const request = toRequest()
    if (editingId.value) {
      await updateSource(editingId.value, request)
    } else {
      await createSource(request)
    }
    showForm.value = false
    await loadSources(editingId.value ? page.value : 1)
  } catch (error) {
    formError.value = adminFailureMessage(
      error,
      '资料源保存失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    formSaving.value = false
  }
}

async function testCurrentSource(): Promise<void> {
  if (!form.value.name.trim() || !form.value.type.trim()) {
    formError.value = '请先填写资料源名称与类型。'
    return
  }
  previewLoading.value = true
  previewError.value = ''
  try {
    previewItems.value = await testSource(toRequest())
  } catch (error) {
    previewError.value = adminFailureMessage(
      error,
      '连接测试失败，请检查资料源配置。',
      () => emit('unauthorized'),
    )
  } finally {
    previewLoading.value = false
  }
}

async function previewExisting(source: SourceDto): Promise<void> {
  await startEdit(source)
  if (editingId.value !== source.id || !showForm.value) {
    return
  }
  previewLoading.value = true
  previewError.value = ''
  previewItems.value = []
  try {
    previewItems.value = await previewSource(source.id)
  } catch (error) {
    previewError.value = adminFailureMessage(
      error,
      '资料源预览失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    previewLoading.value = false
  }
}

async function toggleSource(source: SourceDto): Promise<void> {
  changingEnabledId.value = source.id
  errorMessage.value = ''
  try {
    await setSourceEnabled(source.id, !source.enabled)
    await loadSources(page.value)
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '资料源状态更新失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    changingEnabledId.value = null
  }
}

async function confirmDelete(): Promise<void> {
  if (!deleteTarget.value) {
    return
  }
  deleting.value = true
  try {
    await deleteSource(deleteTarget.value.id)
    deleteTarget.value = null
    await loadSources(page.value)
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '资料源删除任务创建失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    deleting.value = false
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
  void loadSources()
})
</script>

<template>
  <section class="admin-page" data-view="admin-sources" aria-labelledby="admin-sources-title">
    <header class="admin-page__header">
      <div>
        <p class="section-folio">资料管理 · 来源目录</p>
        <h2 id="admin-sources-title">资料数据来源</h2>
        <p>配置本地上传与远程采集来源；连接测试只返回元数据，不写入内容库。</p>
      </div>
      <button type="button" class="admin-button admin-button--primary" @click="startCreate">
        新增资料源
      </button>
    </header>

    <form class="admin-toolbar" @submit.prevent="loadSources(1)">
      <label>
        <span>筛选当前页</span>
        <input v-model="keyword" type="search" placeholder="名称、类型或地址">
      </label>
      <button type="submit" class="admin-button admin-button--quiet">刷新目录</button>
    </form>

    <p v-if="errorMessage" class="admin-alert" role="alert">{{ errorMessage }}</p>

    <section v-if="showForm" class="admin-panel admin-source-form" aria-labelledby="source-form-title">
      <header>
        <div>
          <p class="section-folio">{{ editingId ? '编辑现有资料源' : '建立新资料源' }}</p>
          <h3 id="source-form-title">{{ editingId ? '修改采集配置' : '来源配置' }}</h3>
        </div>
        <button type="button" class="admin-icon-button" aria-label="关闭来源配置" @click="showForm = false">×</button>
      </header>

      <form @submit.prevent="saveSource">
        <div class="admin-form-grid">
          <label>
            <span>名称</span>
            <input v-model="form.name" required maxlength="128" placeholder="例如：教务处通知">
          </label>
          <label>
            <span>业务类型</span>
            <input v-model="form.type" required maxlength="32" placeholder="例如：OFFICIAL">
          </label>
          <label>
            <span>连接方式</span>
            <select v-model="form.connectorType">
              <option value="SINGLE_URL">单一页面</option>
              <option value="SITE">网站抓取</option>
              <option value="RSS">RSS 订阅</option>
              <option value="SITEMAP">站点地图</option>
              <option value="LOCAL_UPLOAD">本地上传</option>
            </select>
          </label>
          <label>
            <span>调度</span>
            <select v-model="form.scheduleType">
              <option value="MANUAL">仅手动</option>
              <option value="DAILY">每日</option>
              <option value="WEEKLY">每周</option>
              <option value="CRON">Cron</option>
            </select>
          </label>
          <label class="admin-form-grid__wide">
            <span>起始地址</span>
            <input v-model="form.baseUrl" type="url" placeholder="https://example.edu/notices">
          </label>
          <label>
            <span>时区</span>
            <input v-model="form.scheduleZone" placeholder="Asia/Shanghai">
          </label>
          <label v-if="form.scheduleType === 'CRON'">
            <span>Cron 表达式</span>
            <input v-model="form.cronExpression" placeholder="0 0 2 * * *">
          </label>
        </div>

        <details class="admin-form-details">
          <summary>采集边界</summary>
          <div class="admin-form-grid">
            <label>
              <span>最大深度</span>
              <input v-model.number="form.maxDepth" type="number" min="0" max="10">
            </label>
            <label>
              <span>最大页面数</span>
              <input v-model.number="form.maxPages" type="number" min="1" max="10000">
            </label>
            <label>
              <span>最大并发</span>
              <input v-model.number="form.maxConcurrency" type="number" min="1" max="20">
            </label>
            <label class="admin-checkbox">
              <input v-model="form.enabled" type="checkbox">
              <span>保存后立即启用</span>
            </label>
            <label class="admin-checkbox">
              <input v-model="form.sameHostOnly" type="checkbox">
              <span>仅允许同主机链接</span>
            </label>
            <label>
              <span>包含规则（每行一项）</span>
              <textarea v-model="form.includePatterns" rows="3" placeholder="/notice/.*"></textarea>
            </label>
            <label>
              <span>排除规则（每行一项）</span>
              <textarea v-model="form.excludePatterns" rows="3" placeholder="/archive/.*"></textarea>
            </label>
          </div>
        </details>

        <p v-if="formError" class="admin-alert" role="alert">{{ formError }}</p>
        <p v-if="previewError" class="admin-alert" role="alert">{{ previewError }}</p>
        <div class="admin-form-actions">
          <button type="button" class="admin-button admin-button--quiet" :disabled="previewLoading" @click="testCurrentSource">
            {{ previewLoading ? '测试中…' : '测试连接' }}
          </button>
          <button type="submit" class="admin-button admin-button--primary" :disabled="formSaving">
            {{ formSaving ? '保存中…' : '保存资料源' }}
          </button>
        </div>
      </form>

      <div v-if="previewItems.length" class="admin-preview-list" aria-live="polite">
        <p>发现 {{ previewItems.length }} 个候选对象</p>
        <ul>
          <li v-for="item in previewItems" :key="item.sourceUrl">
            <strong>{{ item.filename }}</strong>
            <span>{{ item.mediaType }} · {{ item.byteSize }} B</span>
          </li>
        </ul>
      </div>
    </section>

    <AdminDataTable
      :columns="SOURCE_COLUMNS"
      :rows="tableRows"
      :loading="loading"
      :error="errorMessage"
      :page="page"
      :pages="pages"
      :total="total"
      empty-message="尚未配置资料源"
      :row-key="(row) => sourceRow(row).id"
      @page="loadSources"
      @retry="loadSources(page)"
      @sort="changeSort"
    >
      <template #cell="{ row, column, value }">
        <AdminJobStatus
          v-if="column.key === 'enabled'"
          :status="sourceRow(row).enabled ? 'ACTIVE' : 'RETIRED'"
        />
        <AdminJobStatus
          v-else-if="column.key === 'latestRunStatus'"
          :status="sourceRow(row).latestRunStatus"
        />
        <span v-else :title="String(value)">{{ sourceCell(row, column) }}</span>
      </template>
      <template #actions="{ row }">
        <button type="button" class="admin-text-button" @click="startEdit(sourceRow(row))">编辑</button>
        <button type="button" class="admin-text-button" @click="previewExisting(sourceRow(row))">预览</button>
        <button
          type="button"
          class="admin-text-button"
          :disabled="changingEnabledId === sourceRow(row).id"
          @click="toggleSource(sourceRow(row))"
        >
          {{ sourceRow(row).enabled ? '停用' : '启用' }}
        </button>
        <button type="button" class="admin-text-button admin-text-button--danger" @click="deleteTarget = sourceRow(row)">
          删除
        </button>
      </template>
    </AdminDataTable>

    <ConfirmActionDialog
      :model-value="Boolean(deleteTarget)"
      title="删除资料源"
      description="将创建异步删除任务，关联文档和索引会按后台流程清理，不能直接撤销。"
      :target="deleteTarget?.name ?? ''"
      :confirmation-text="deleteTarget?.name ?? ''"
      confirm-label="创建删除任务"
      :busy="deleting"
      @update:model-value="(open) => { if (!open) deleteTarget = null }"
      @confirm="confirmDelete"
    />
  </section>
</template>

<style scoped>
.admin-source-form {
  margin-bottom: 1.2rem;
}

.admin-source-form > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 1rem;
}

.admin-source-form h3,
.admin-preview-list p {
  margin: 0;
  color: var(--green-950);
  font-family: STKaiti, KaiTi, serif;
  font-size: 1.25rem;
}

.admin-form-details {
  margin-top: 1rem;
  padding-top: 0.8rem;
  border-top: 1px solid var(--rule);
}

.admin-form-details summary {
  color: var(--green-900);
  cursor: pointer;
  font-size: 0.78rem;
  font-weight: 600;
}

.admin-form-details .admin-form-grid {
  margin-top: 0.85rem;
}

.admin-form-actions {
  display: flex;
  gap: 0.65rem;
  justify-content: flex-end;
  margin-top: 1rem;
}

.admin-preview-list {
  margin-top: 1rem;
  padding-top: 1rem;
  border-top: 1px solid var(--rule);
}

.admin-preview-list p {
  font-size: 1rem;
}

.admin-preview-list ul {
  display: grid;
  gap: 0.5rem;
  margin: 0.75rem 0 0;
  padding: 0;
  list-style: none;
}

.admin-preview-list li {
  display: flex;
  gap: 0.75rem;
  justify-content: space-between;
  padding: 0.65rem;
  background: rgba(228, 216, 189, 0.34);
  font-size: 0.72rem;
}

.admin-preview-list li span {
  color: var(--ink-soft);
}
</style>
