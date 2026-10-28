<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import {
  getImportRun,
  listImportRuns,
  listSources,
  retryImportRun,
  uploadDocuments,
} from '../../api/adminEndpoints'
import type {
  ImportMetadata,
  IngestRunDetailDto,
  IngestRunDto,
  SourceDto,
} from '../../api/adminTypes'
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

const POLL_INTERVAL = 5_000
const RUN_COLUMNS: AdminTableColumn[] = [
  { key: 'id', label: '运行编号', sortable: true },
  { key: 'status', label: '状态', sortable: true },
  { key: 'added', label: '新增', sortable: true },
  { key: 'updated', label: '更新', sortable: true },
  { key: 'failed', label: '失败', sortable: true },
  { key: 'startedAt', label: '开始时间', sortable: true },
]

const sources = ref<SourceDto[]>([])
const selectedSourceId = ref<number | null>(null)
const selectedFiles = ref<File[]>([])
const uploadError = ref('')
const uploadNotice = ref('')
const uploading = ref(false)
const runs = ref<IngestRunDto[]>([])
const loadingRuns = ref(false)
const runError = ref('')
const page = ref(1)
const pages = ref(1)
const total = ref(0)
const sortKey = ref('startedAt')
const sortDirection = ref<'asc' | 'desc'>('desc')
const selectedRun = ref<IngestRunDetailDto | null>(null)
const detailLoading = ref(false)
const retryingRunId = ref<number | null>(null)
let pollTimer: ReturnType<typeof setInterval> | undefined

const orderedRuns = computed(() => sortAdminRows(runs.value, sortKey.value, sortDirection.value))
const tableRows = computed(
  () => orderedRuns.value as unknown as Array<Record<string, unknown>>,
)
const activeRuns = computed(() => runs.value.some((run) =>
  run.status === 'RUNNING' || run.status === 'DEFERRED',
))

function runRow(row: Record<string, unknown>): IngestRunDto {
  return row as unknown as IngestRunDto
}

function runCell(row: Record<string, unknown>, column: AdminTableColumn): string {
  const run = runRow(row)
  if (column.key === 'startedAt') {
    return formatAdminDate(run.startedAt)
  }
  return String(run[column.key as keyof IngestRunDto] ?? '—')
}

function handleFileSelection(event: Event): void {
  const input = event.target as HTMLInputElement
  selectedFiles.value = Array.from(input.files ?? [])
  uploadError.value = ''
  uploadNotice.value = ''
}

function fileTitle(file: File): string {
  return file.name.replace(/\.[^.]+$/, '') || file.name
}

function buildMetadata(): ImportMetadata {
  return {
    sourceId: selectedSourceId.value,
    items: selectedFiles.value.map((file, index) => ({
      clientId: `${index}-${file.name}`,
      displayTitle: fileTitle(file),
      externalSourceUrl: null,
      replaceDocumentId: null,
      degree: null,
      college: null,
      campus: null,
      gradeRange: null,
    })),
  }
}

async function loadSources(): Promise<void> {
  try {
    const result = await listSources({ page: 1, size: 100 })
    sources.value = result.items.filter((source) => source.enabled)
  } catch (error) {
    uploadError.value = adminFailureMessage(
      error,
      '资料源选项加载失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  }
}

async function loadRuns(requestedPage = page.value, silent = false): Promise<void> {
  if (!silent) {
    loadingRuns.value = true
  }
  runError.value = ''
  try {
    const result = await listImportRuns({ page: requestedPage, size: 20 })
    runs.value = result.items
    page.value = result.page
    pages.value = Math.max(result.pages, 1)
    total.value = result.total
  } catch (error) {
    runError.value = adminFailureMessage(
      error,
      '导入运行记录加载失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    loadingRuns.value = false
  }
}

async function submitUpload(): Promise<void> {
  if (!selectedFiles.value.length) {
    uploadError.value = '请先选择至少一个本地文件。'
    return
  }
  uploading.value = true
  uploadError.value = ''
  uploadNotice.value = ''
  try {
    const detail = await uploadDocuments(buildMetadata(), selectedFiles.value)
    selectedRun.value = detail
    selectedFiles.value = []
    uploadNotice.value = `已创建导入运行 #${detail.id}，可在下方查看处理进度。`
    await loadRuns(1, true)
  } catch (error) {
    uploadError.value = adminFailureMessage(
      error,
      '本地文件导入失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    uploading.value = false
  }
}

async function inspectRun(run: IngestRunDto): Promise<void> {
  detailLoading.value = true
  runError.value = ''
  try {
    selectedRun.value = await getImportRun(run.id)
  } catch (error) {
    runError.value = adminFailureMessage(
      error,
      '导入运行详情加载失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    detailLoading.value = false
  }
}

async function retryRun(run: IngestRunDto): Promise<void> {
  retryingRunId.value = run.id
  runError.value = ''
  try {
    selectedRun.value = await retryImportRun(run.id)
    await loadRuns(page.value, true)
  } catch (error) {
    runError.value = adminFailureMessage(
      error,
      '重试导入任务失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    retryingRunId.value = null
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
  void loadRuns()
  pollTimer = setInterval(() => {
    if (activeRuns.value && !loadingRuns.value) {
      void loadRuns(page.value, true)
    }
  }, POLL_INTERVAL)
})

onBeforeUnmount(() => {
  if (pollTimer) {
    clearInterval(pollTimer)
  }
})
</script>

<template>
  <section class="admin-page" data-view="admin-imports" aria-labelledby="admin-imports-title">
    <header class="admin-page__header">
      <div>
        <p class="section-folio">资料管理 · 本地文件</p>
        <h2 id="admin-imports-title">本地资料导入</h2>
        <p>每个文件都会创建可追踪的导入条目；服务器不会使用浏览器提供的文件路径。</p>
      </div>
    </header>

    <section class="admin-panel admin-import-panel" aria-labelledby="upload-title">
      <header>
        <div>
          <p class="section-folio">上传队列</p>
          <h3 id="upload-title">选择要入库的文件</h3>
        </div>
        <span>{{ selectedFiles.length }} 个文件</span>
      </header>

      <div class="admin-form-grid">
        <label class="admin-form-grid__wide">
          <span>本地文件</span>
          <input data-test="upload-files" type="file" multiple @change="handleFileSelection">
        </label>
        <label>
          <span>关联资料源</span>
          <select v-model="selectedSourceId">
            <option :value="null">不关联已有来源</option>
            <option v-for="source in sources" :key="source.id" :value="source.id">
              {{ source.name }}
            </option>
          </select>
        </label>
      </div>

      <ul v-if="selectedFiles.length" class="admin-file-list" aria-label="待上传文件">
        <li v-for="(file, index) in selectedFiles" :key="`${file.name}-${index}`">
          <strong>{{ fileTitle(file) }}</strong>
          <span>{{ file.name }} · {{ file.size }} B</span>
        </li>
      </ul>

      <p v-if="uploadError" class="admin-alert" role="alert">{{ uploadError }}</p>
      <p v-if="uploadNotice" class="admin-notice" role="status">{{ uploadNotice }}</p>
      <div class="admin-form-actions">
        <button
          type="button"
          data-test="upload-submit"
          class="admin-button admin-button--primary"
          :disabled="uploading || !selectedFiles.length"
          @click="submitUpload"
        >
          {{ uploading ? '正在创建导入…' : '开始导入' }}
        </button>
      </div>
    </section>

    <header class="admin-subsection-header">
      <div>
        <p class="section-folio">运行记录</p>
        <h3>导入处理状态</h3>
      </div>
      <button type="button" class="admin-button admin-button--quiet" @click="loadRuns(page)">刷新记录</button>
    </header>

    <p v-if="runError" class="admin-alert" role="alert">{{ runError }}</p>

    <AdminDataTable
      :columns="RUN_COLUMNS"
      :rows="tableRows"
      :loading="loadingRuns"
      :error="runError"
      :page="page"
      :pages="pages"
      :total="total"
      empty-message="尚无本地导入运行记录"
      :row-key="(row) => runRow(row).id"
      @page="loadRuns"
      @retry="loadRuns(page)"
      @sort="changeSort"
    >
      <template #cell="{ row, column, value }">
        <AdminJobStatus v-if="column.key === 'status'" :status="runRow(row).status" />
        <span v-else :title="String(value)">{{ runCell(row, column) }}</span>
      </template>
      <template #actions="{ row }">
        <button type="button" class="admin-text-button" @click="inspectRun(runRow(row))">查看</button>
        <button
          v-if="['FAILED', 'PARTIAL_SUCCESS'].includes(runRow(row).status)"
          type="button"
          class="admin-text-button"
          :disabled="retryingRunId === runRow(row).id"
          @click="retryRun(runRow(row))"
        >
          {{ retryingRunId === runRow(row).id ? '提交中…' : '重试' }}
        </button>
      </template>
    </AdminDataTable>

    <section v-if="selectedRun" class="admin-panel admin-run-detail" aria-live="polite">
      <header>
        <div>
          <p class="section-folio">运行 #{{ selectedRun.id }}</p>
          <h3>任务检查点</h3>
        </div>
        <AdminJobStatus :status="selectedRun.status" />
      </header>
      <p v-if="detailLoading" role="status">正在读取运行详情…</p>
      <p v-else-if="selectedRun.errorDetail" class="admin-alert" role="alert">
        {{ selectedRun.errorDetail }}
      </p>
      <ul v-else class="admin-run-items">
        <li v-for="item in selectedRun.items" :key="item.id">
          <span>{{ item.itemKey }}</span>
          <AdminJobStatus :status="item.status" />
          <small v-if="item.errorSummary">{{ item.errorSummary }}</small>
        </li>
        <li v-if="!selectedRun.items.length">暂无项目级检查点。</li>
      </ul>
    </section>
  </section>
</template>

<style scoped>
.admin-import-panel {
  margin-bottom: 1.45rem;
}

.admin-import-panel > header,
.admin-run-detail > header,
.admin-subsection-header {
  display: flex;
  gap: 1rem;
  align-items: flex-end;
  justify-content: space-between;
}

.admin-import-panel h3,
.admin-run-detail h3,
.admin-subsection-header h3 {
  margin: 0.25rem 0 0;
  color: var(--green-950);
  font-family: STKaiti, KaiTi, serif;
  font-size: 1.25rem;
}

.admin-import-panel > header > span {
  color: var(--ink-soft);
  font-size: 0.72rem;
}

.admin-file-list,
.admin-run-items {
  display: grid;
  gap: 0.45rem;
  margin: 1rem 0 0;
  padding: 0;
  list-style: none;
}

.admin-file-list li {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  justify-content: space-between;
  padding: 0.65rem;
  background: rgba(228, 216, 189, 0.35);
  font-size: 0.74rem;
}

.admin-file-list span {
  color: var(--ink-soft);
}

.admin-subsection-header {
  margin: 1.2rem 0 0.7rem;
}

.admin-run-detail {
  margin-top: 1.2rem;
}

.admin-run-items li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 0.6rem;
  align-items: center;
  padding: 0.6rem 0;
  border-bottom: 1px solid rgba(18, 59, 50, 0.12);
  font-size: 0.74rem;
}

.admin-run-items li:last-child {
  border-bottom: 0;
}

.admin-run-items small {
  grid-column: 1 / -1;
  color: #8a3329;
}

@media (max-width: 540px) {
  .admin-import-panel > header,
  .admin-run-detail > header,
  .admin-subsection-header,
  .admin-file-list li {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
