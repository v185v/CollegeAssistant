<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  deleteDocument,
  downloadDocument,
  listDocuments,
  reindexDocument,
} from '../../api/adminEndpoints'
import type {
  DocumentDto,
  DocumentListParams,
  DocumentReferenceType,
  DocumentStatus,
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

const DOCUMENT_COLUMNS: AdminTableColumn[] = [
  { key: 'title', label: '资料标题', sortable: true },
  { key: 'referenceType', label: '来源类型', sortable: true },
  { key: 'documentStatus', label: '文档状态', sortable: true },
  { key: 'sourceId', label: '资料源' },
  { key: 'degree', label: '适用范围' },
  { key: 'ingestedAt', label: '入库时间', sortable: true },
]

const documents = ref<DocumentDto[]>([])
const loading = ref(false)
const errorMessage = ref('')
const actionError = ref('')
const page = ref(1)
const pages = ref(1)
const total = ref(0)
const sortKey = ref('ingestedAt')
const sortDirection = ref<'asc' | 'desc'>('desc')
const titleFilter = ref('')
const sourceIdFilter = ref('')
const statusFilter = ref<DocumentStatus | ''>('')
const referenceTypeFilter = ref<DocumentReferenceType | ''>('')
const reindexingId = ref<number | null>(null)
const downloadingId = ref<number | null>(null)
const deleteTarget = ref<DocumentDto | null>(null)
const deleting = ref(false)

const orderedDocuments = computed(() =>
  sortAdminRows(documents.value, sortKey.value, sortDirection.value),
)
const tableRows = computed(
  () => orderedDocuments.value as unknown as Array<Record<string, unknown>>,
)

function documentRow(row: Record<string, unknown>): DocumentDto {
  return row as unknown as DocumentDto
}

function documentCell(
  row: Record<string, unknown>,
  column: AdminTableColumn,
): string {
  const document = documentRow(row)
  if (column.key === 'ingestedAt') {
    return formatAdminDate(document.ingestedAt)
  }
  if (column.key === 'sourceId') {
    return document.sourceId ? `#${document.sourceId}` : '本地导入'
  }
  if (column.key === 'degree') {
    return [document.degree, document.college, document.campus]
      .filter(Boolean)
      .join(' · ') || '未标注'
  }
  if (column.key === 'referenceType') {
    return document.referenceType === 'INTERNAL' ? '内部文件' : '外部资料'
  }
  return String(document[column.key as keyof DocumentDto] ?? '—')
}

function buildFilters(): DocumentListParams {
  const parsedSourceId = Number(sourceIdFilter.value)
  return {
    page: page.value,
    size: 20,
    title: titleFilter.value.trim() || undefined,
    sourceId: Number.isSafeInteger(parsedSourceId) && parsedSourceId > 0
      ? parsedSourceId
      : undefined,
    status: statusFilter.value || undefined,
    referenceType: referenceTypeFilter.value || undefined,
  }
}

async function loadDocuments(requestedPage = page.value): Promise<void> {
  page.value = requestedPage
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listDocuments(buildFilters())
    documents.value = result.items
    page.value = result.page
    pages.value = Math.max(result.pages, 1)
    total.value = result.total
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '文档目录加载失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    loading.value = false
  }
}

function applyFilters(): void {
  void loadDocuments(1)
}

function resetFilters(): void {
  titleFilter.value = ''
  sourceIdFilter.value = ''
  statusFilter.value = ''
  referenceTypeFilter.value = ''
  void loadDocuments(1)
}

async function requestReindex(document: DocumentDto): Promise<void> {
  reindexingId.value = document.id
  actionError.value = ''
  try {
    await reindexDocument(document.id)
    await loadDocuments(page.value)
  } catch (error) {
    actionError.value = adminFailureMessage(
      error,
      '重新索引任务创建失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    reindexingId.value = null
  }
}

async function downloadOriginal(document: DocumentDto): Promise<void> {
  downloadingId.value = document.id
  actionError.value = ''
  try {
    const download = await downloadDocument(document.id)
    const objectUrl = URL.createObjectURL(download.blob)
    const anchor = window.document.createElement('a')
    anchor.href = objectUrl
    anchor.download = download.filename || document.originalFilename || `${document.title}.bin`
    anchor.style.display = 'none'
    window.document.body.append(anchor)
    anchor.click()
    anchor.remove()
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 0)
  } catch (error) {
    actionError.value = adminFailureMessage(
      error,
      '文件下载失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    downloadingId.value = null
  }
}

async function confirmDelete(): Promise<void> {
  if (!deleteTarget.value) {
    return
  }
  deleting.value = true
  actionError.value = ''
  try {
    await deleteDocument(deleteTarget.value.id)
    deleteTarget.value = null
    await loadDocuments(page.value)
  } catch (error) {
    actionError.value = adminFailureMessage(
      error,
      '文档删除任务创建失败，请稍后重试。',
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
  void loadDocuments()
})
</script>

<template>
  <section class="admin-page" data-view="admin-documents" aria-labelledby="admin-documents-title">
    <header class="admin-page__header">
      <div>
        <p class="section-folio">资料管理 · 内容库</p>
        <h2 id="admin-documents-title">文档与索引</h2>
        <p>查看入库文档及其处理状态；索引和删除均通过可追踪的后台任务执行。</p>
      </div>
    </header>

    <form class="admin-filter-grid" @submit.prevent="applyFilters">
      <label>
        <span>标题关键词</span>
        <input v-model="titleFilter" type="search" placeholder="按标题搜索">
      </label>
      <label>
        <span>资料源编号</span>
        <input v-model="sourceIdFilter" inputmode="numeric" pattern="[0-9]*" placeholder="例如 12">
      </label>
      <label>
        <span>文档状态</span>
        <select v-model="statusFilter">
          <option value="">全部状态</option>
          <option value="PROCESSING">处理中</option>
          <option value="ACTIVE">可用</option>
          <option value="FAILED">失败</option>
          <option value="DELETING">删除中</option>
          <option value="DELETE_FAILED">删除失败</option>
        </select>
      </label>
      <label>
        <span>来源类型</span>
        <select v-model="referenceTypeFilter">
          <option value="">全部类型</option>
          <option value="INTERNAL">内部文件</option>
          <option value="EXTERNAL">外部资料</option>
        </select>
      </label>
      <div class="admin-filter-grid__actions">
        <button type="submit" class="admin-button admin-button--primary">应用筛选</button>
        <button type="button" class="admin-button admin-button--quiet" @click="resetFilters">清除</button>
      </div>
    </form>

    <p v-if="errorMessage || actionError" class="admin-alert" role="alert">
      {{ errorMessage || actionError }}
    </p>

    <AdminDataTable
      :columns="DOCUMENT_COLUMNS"
      :rows="tableRows"
      :loading="loading"
      :error="errorMessage"
      :page="page"
      :pages="pages"
      :total="total"
      empty-message="未找到符合条件的文档"
      :row-key="(row) => documentRow(row).id"
      @page="loadDocuments"
      @retry="loadDocuments(page)"
      @sort="changeSort"
    >
      <template #cell="{ row, column, value }">
        <AdminJobStatus
          v-if="column.key === 'documentStatus'"
          :status="documentRow(row).documentStatus"
        />
        <span v-else :title="String(value)">{{ documentCell(row, column) }}</span>
      </template>
      <template #actions="{ row }">
        <button
          type="button"
          class="admin-text-button"
          :disabled="reindexingId === documentRow(row).id"
          @click="requestReindex(documentRow(row))"
        >
          {{ reindexingId === documentRow(row).id ? '提交中…' : '重建索引' }}
        </button>
        <button
          v-if="documentRow(row).downloadable"
          type="button"
          class="admin-text-button"
          :disabled="downloadingId === documentRow(row).id"
          @click="downloadOriginal(documentRow(row))"
        >
          {{ downloadingId === documentRow(row).id ? '准备中…' : '下载原件' }}
        </button>
        <button
          type="button"
          class="admin-text-button admin-text-button--danger"
          :data-test="`delete-document-${documentRow(row).id}`"
          @click="deleteTarget = documentRow(row)"
        >
          删除
        </button>
      </template>
    </AdminDataTable>

    <ConfirmActionDialog
      :model-value="Boolean(deleteTarget)"
      title="删除文档"
      description="将创建后台删除任务，并清理文档对象与所有向量索引版本中的对应内容。此操作不可直接撤销。"
      :target="deleteTarget?.title ?? ''"
      :confirmation-text="deleteTarget?.title ?? ''"
      confirm-label="创建删除任务"
      :busy="deleting"
      @update:model-value="(open) => { if (!open) deleteTarget = null }"
      @confirm="confirmDelete"
    />
  </section>
</template>

<style scoped>
.admin-filter-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0.75rem;
  align-items: end;
  margin-bottom: 1rem;
  padding: 1rem;
  background: rgba(228, 216, 189, 0.32);
  border-top: 3px double var(--rule);
  border-bottom: 1px solid var(--rule);
}

.admin-filter-grid label {
  display: grid;
  gap: 0.35rem;
}

.admin-filter-grid label > span {
  color: var(--ink-soft);
  font-size: 0.65rem;
}

.admin-filter-grid input,
.admin-filter-grid select {
  min-height: 2.3rem;
  padding: 0.45rem 0.55rem;
  color: var(--ink);
  background: #fffdf7;
  border: 1px solid var(--rule);
}

.admin-filter-grid__actions {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

@media (max-width: 960px) {
  .admin-filter-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 540px) {
  .admin-filter-grid {
    grid-template-columns: 1fr;
  }
}
</style>
