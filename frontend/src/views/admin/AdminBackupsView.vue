<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  createBackup,
  deleteBackup,
  downloadBackup,
  getDatabaseOverview,
  listBackups,
  validateBackup,
} from '../../api/adminEndpoints'
import type { DatabaseBackupDto } from '../../api/adminTypes'
import { ApiClientError } from '../../api/client'
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

const BACKUP_COLUMNS: AdminTableColumn[] = [
  { key: 'id', label: '归档编号', sortable: true },
  { key: 'status', label: '状态', sortable: true },
  { key: 'format', label: '格式' },
  { key: 'sizeBytes', label: '体积', sortable: true },
  { key: 'retentionDays', label: '保留天数' },
  { key: 'createdAt', label: '创建时间', sortable: true },
]

const backups = ref<DatabaseBackupDto[]>([])
const loading = ref(false)
const errorMessage = ref('')
const page = ref(1)
const pages = ref(1)
const total = ref(0)
const retentionDays = ref(30)
const backupCapability = ref(false)
const creating = ref(false)
const validatingId = ref<number | null>(null)
const downloadingId = ref<number | null>(null)
const deletingTarget = ref<DatabaseBackupDto | null>(null)
const deleting = ref(false)
const sortKey = ref('createdAt')
const sortDirection = ref<'asc' | 'desc'>('desc')
const restoreInstruction = 'pg_restore --clean --if-exists --no-owner --dbname=<database_url> <backup.dump>'

const orderedBackups = computed(() => sortAdminRows(backups.value, sortKey.value, sortDirection.value))
const tableRows = computed(
  () => orderedBackups.value as unknown as Array<Record<string, unknown>>,
)

function backupRow(row: Record<string, unknown>): DatabaseBackupDto {
  return row as unknown as DatabaseBackupDto
}

function formatSize(value: number | null): string {
  if (value === null) {
    return '—'
  }
  if (value < 1024) {
    return `${value} B`
  }
  if (value < 1024 ** 2) {
    return `${(value / 1024).toFixed(1)} KB`
  }
  return `${(value / 1024 ** 2).toFixed(1)} MB`
}

function backupCell(row: Record<string, unknown>, column: AdminTableColumn): string {
  const backup = backupRow(row)
  if (column.key === 'createdAt') {
    return formatAdminDate(backup.createdAt)
  }
  if (column.key === 'sizeBytes') {
    return formatSize(backup.sizeBytes)
  }
  return String(backup[column.key as keyof DatabaseBackupDto] ?? '—')
}

async function loadCapability(): Promise<void> {
  try {
    const overview = await getDatabaseOverview()
    backupCapability.value = overview.storageAvailable
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '备份能力状态读取失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  }
}

async function loadBackups(requestedPage = page.value): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listBackups({ page: requestedPage, size: 20 })
    backups.value = result.items
    page.value = result.page
    pages.value = Math.max(result.pages, 1)
    total.value = result.total
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '备份列表加载失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    loading.value = false
  }
}

function capabilityFailure(error: unknown, fallback: string): string {
  if (error instanceof ApiClientError && error.code === 50301) {
    backupCapability.value = false
    return '当前服务器未提供 pg_dump/pg_restore 或备份存储能力。'
  }
  return adminFailureMessage(error, fallback, () => emit('unauthorized'))
}

async function requestBackup(): Promise<void> {
  creating.value = true
  errorMessage.value = ''
  try {
    await createBackup({ retentionDays: retentionDays.value })
    await loadBackups(1)
  } catch (error) {
    errorMessage.value = capabilityFailure(error, '备份任务创建失败，请稍后重试。')
  } finally {
    creating.value = false
  }
}

async function validateArchive(backup: DatabaseBackupDto): Promise<void> {
  validatingId.value = backup.id
  errorMessage.value = ''
  try {
    await validateBackup(backup.id)
    await loadBackups(page.value)
  } catch (error) {
    errorMessage.value = capabilityFailure(error, '归档校验失败，请稍后重试。')
  } finally {
    validatingId.value = null
  }
}

async function downloadArchive(backup: DatabaseBackupDto): Promise<void> {
  downloadingId.value = backup.id
  errorMessage.value = ''
  try {
    const download = await downloadBackup(backup.id)
    const objectUrl = URL.createObjectURL(download.blob)
    const anchor = document.createElement('a')
    anchor.href = objectUrl
    anchor.download = download.filename || `backup-${backup.id}.dump`
    anchor.style.display = 'none'
    document.body.append(anchor)
    anchor.click()
    anchor.remove()
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 0)
  } catch (error) {
    errorMessage.value = capabilityFailure(error, '备份下载失败，请稍后重试。')
  } finally {
    downloadingId.value = null
  }
}

async function confirmDelete(): Promise<void> {
  if (!deletingTarget.value) {
    return
  }
  deleting.value = true
  errorMessage.value = ''
  try {
    await deleteBackup(deletingTarget.value.id)
    deletingTarget.value = null
    await loadBackups(page.value)
  } catch (error) {
    errorMessage.value = capabilityFailure(error, '备份删除失败，请稍后重试。')
  } finally {
    deleting.value = false
  }
}

async function copyRestoreInstruction(): Promise<void> {
  try {
    await navigator.clipboard.writeText(restoreInstruction)
  } catch {
    errorMessage.value = '无法写入剪贴板，请手动复制恢复指令。'
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
  void loadCapability()
  void loadBackups()
})
</script>

<template>
  <section class="admin-page" data-view="admin-backups" aria-labelledby="admin-backups-title">
    <header class="admin-page__header">
      <div>
        <p class="section-folio">运维 · 逻辑归档</p>
        <h2 id="admin-backups-title">数据库备份</h2>
        <p>仅生成、校验和下载逻辑归档；恢复必须在受控的离线运维环境中执行。</p>
      </div>
    </header>

    <section class="admin-panel backup-create" aria-labelledby="backup-create-title">
      <header>
        <div>
          <p class="section-folio">新建归档</p>
          <h3 id="backup-create-title">创建数据库备份</h3>
        </div>
        <AdminJobStatus :status="backupCapability ? 'ACTIVE' : 'FAILED'" />
      </header>
      <label>
        <span>保留天数</span>
        <input v-model.number="retentionDays" type="number" min="1" max="3650">
      </label>
      <button
        type="button"
        data-test="create-backup"
        class="admin-button admin-button--primary"
        :disabled="!backupCapability || creating"
        @click="requestBackup"
      >
        {{ creating ? '创建中…' : '创建备份' }}
      </button>
      <p v-if="!backupCapability" class="backup-create__notice">
        当前服务器尚未报告可用的备份存储能力，无法创建新的归档。
      </p>
    </section>

    <p v-if="errorMessage" class="admin-alert" role="alert">{{ errorMessage }}</p>

    <AdminDataTable
      :columns="BACKUP_COLUMNS"
      :rows="tableRows"
      :loading="loading"
      :error="errorMessage"
      :page="page"
      :pages="pages"
      :total="total"
      empty-message="暂无数据库备份"
      :row-key="(row) => backupRow(row).id"
      @page="loadBackups"
      @retry="loadBackups(page)"
      @sort="changeSort"
    >
      <template #cell="{ row, column, value }">
        <AdminJobStatus v-if="column.key === 'status'" :status="backupRow(row).status" />
        <span v-else :title="String(value)">{{ backupCell(row, column) }}</span>
      </template>
      <template #actions="{ row }">
        <button
          type="button"
          class="admin-text-button"
          :disabled="validatingId === backupRow(row).id"
          @click="validateArchive(backupRow(row))"
        >
          {{ validatingId === backupRow(row).id ? '校验中…' : '校验' }}
        </button>
        <button
          v-if="backupRow(row).downloadable"
          type="button"
          class="admin-text-button"
          :data-test="`download-backup-${backupRow(row).id}`"
          :disabled="downloadingId === backupRow(row).id"
          @click="downloadArchive(backupRow(row))"
        >
          {{ downloadingId === backupRow(row).id ? '准备中…' : '下载' }}
        </button>
        <button type="button" class="admin-text-button admin-text-button--danger" @click="deletingTarget = backupRow(row)">
          删除
        </button>
      </template>
    </AdminDataTable>

    <section class="admin-panel backup-restore" aria-labelledby="restore-title">
      <header>
        <div>
          <p class="section-folio">离线恢复提示</p>
          <h3 id="restore-title">恢复指令</h3>
        </div>
        <button type="button" class="admin-button admin-button--quiet" @click="copyRestoreInstruction">复制指令</button>
      </header>
      <code>{{ restoreInstruction }}</code>
      <p>请在隔离环境确认目标库和归档校验值后执行。管理控制台不会执行恢复命令。</p>
    </section>

    <ConfirmActionDialog
      :model-value="Boolean(deletingTarget)"
      title="删除备份归档"
      description="归档文件将被永久删除，之后无法通过此归档恢复数据。"
      :target="`备份 #${deletingTarget?.id ?? ''}`"
      :confirmation-text="String(deletingTarget?.id ?? '')"
      confirm-label="永久删除归档"
      :busy="deleting"
      @update:model-value="(open) => { if (!open) deletingTarget = null }"
      @confirm="confirmDelete"
    />
  </section>
</template>

<style scoped>
.backup-create {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 9rem auto;
  gap: 0.85rem;
  align-items: end;
  margin: 1.2rem 0;
}

.backup-create header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  grid-column: 1 / -1;
}

.backup-create h3,
.backup-restore h3 {
  margin: 0.25rem 0 0;
  color: var(--green-950);
  font-family: STKaiti, KaiTi, serif;
  font-size: 1.15rem;
}

.backup-create label {
  display: grid;
  gap: 0.35rem;
}

.backup-create label span {
  color: var(--ink-soft);
  font-size: 0.66rem;
}

.backup-create input {
  min-height: 2.35rem;
  padding: 0.45rem 0.6rem;
  background: #fffdf7;
  border: 1px solid var(--rule);
}

.backup-create__notice {
  grid-column: 1 / -1;
  margin: 0;
  color: #735d1b;
  font-size: 0.72rem;
}

.backup-restore {
  margin-top: 1.2rem;
}

.backup-restore header {
  display: flex;
  gap: 1rem;
  align-items: flex-end;
  justify-content: space-between;
}

.backup-restore code {
  display: block;
  margin-top: 0.9rem;
  padding: 0.75rem;
  overflow-x: auto;
  color: #d8e5de;
  background: var(--green-950);
  font-family: Consolas, monospace;
  font-size: 0.72rem;
}

.backup-restore p {
  margin: 0.75rem 0 0;
  color: var(--ink-soft);
  font-size: 0.72rem;
  line-height: 1.6;
}

@media (max-width: 540px) {
  .backup-create {
    grid-template-columns: 1fr;
  }

  .backup-restore header,
  .backup-create header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
