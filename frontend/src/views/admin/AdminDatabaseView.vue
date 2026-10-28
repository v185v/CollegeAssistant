<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

import {
  getConsistencyScan,
  getDatabaseOverview,
  startConsistencyScan,
} from '../../api/adminEndpoints'
import type { AdminJobDto, DatabaseOverviewDto } from '../../api/adminTypes'
import AdminJobStatus from '../../components/admin/AdminJobStatus.vue'
import { adminFailureMessage, formatAdminDate } from './adminViewSupport'

const emit = defineEmits<{ unauthorized: [] }>()
const SCAN_POLL_INTERVAL = 4_000

const overview = ref<DatabaseOverviewDto | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const scanJob = ref<AdminJobDto | null>(null)
const startingScan = ref(false)
let scanTimer: ReturnType<typeof setInterval> | undefined

function formatSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 ** 2) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  if (bytes < 1024 ** 3) {
    return `${(bytes / 1024 ** 2).toFixed(1)} MB`
  }
  return `${(bytes / 1024 ** 3).toFixed(1)} GB`
}

async function loadOverview(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    overview.value = await getDatabaseOverview()
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '数据库状态加载失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    loading.value = false
  }
}

async function startScan(): Promise<void> {
  startingScan.value = true
  errorMessage.value = ''
  try {
    scanJob.value = await startConsistencyScan()
    startScanPolling()
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '一致性扫描任务创建失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    startingScan.value = false
  }
}

function startScanPolling(): void {
  if (scanTimer) {
    clearInterval(scanTimer)
  }
  scanTimer = setInterval(() => {
    if (scanJob.value?.status === 'QUEUED' || scanJob.value?.status === 'RUNNING') {
      void refreshScan()
    }
  }, SCAN_POLL_INTERVAL)
}

async function refreshScan(): Promise<void> {
  if (!scanJob.value) {
    return
  }
  try {
    scanJob.value = await getConsistencyScan(scanJob.value.id)
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '一致性扫描状态读取失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  }
}

onMounted(() => {
  void loadOverview()
})

onBeforeUnmount(() => {
  if (scanTimer) {
    clearInterval(scanTimer)
  }
})
</script>

<template>
  <section class="admin-page" data-view="admin-database" aria-labelledby="admin-database-title">
    <header class="admin-page__header">
      <div>
        <p class="section-folio">运维 · 数据库状态</p>
        <h2 id="admin-database-title">数据库与一致性</h2>
        <p>此页面只提供健康信息与一致性检查，不提供任意 SQL、恢复或数据修改入口。</p>
      </div>
      <button type="button" class="admin-button admin-button--quiet" :disabled="loading" @click="loadOverview">
        {{ loading ? '读取中…' : '刷新状态' }}
      </button>
    </header>

    <p v-if="errorMessage" class="admin-alert" role="alert">{{ errorMessage }}</p>

    <div v-if="overview" class="database-stat-grid" aria-label="数据库概况">
      <article>
        <span>数据库体积</span>
        <strong>{{ formatSize(overview.databaseSizeBytes) }}</strong>
      </article>
      <article>
        <span>索引体积</span>
        <strong>{{ formatSize(overview.indexSizeBytes) }}</strong>
      </article>
      <article>
        <span>活动连接</span>
        <strong>{{ overview.activeConnections }}</strong>
      </article>
      <article>
        <span>对象存储</span>
        <strong>{{ overview.storageAvailable ? '可用' : '不可用' }}</strong>
      </article>
    </div>

    <section v-if="overview" class="admin-panel database-details">
      <dl>
        <div><dt>PostgreSQL</dt><dd>{{ overview.databaseVersion }}</dd></div>
        <div><dt>pgvector</dt><dd>{{ overview.pgvectorVersion }}</dd></div>
        <div><dt>Flyway</dt><dd>{{ overview.flywayVersion }}</dd></div>
        <div><dt>模型</dt><dd>{{ overview.modelStatus.provider }} / {{ overview.modelStatus.model }}</dd></div>
      </dl>
      <div class="database-tables">
        <h3>主要表记录数</h3>
        <ul>
          <li v-for="(count, table) in overview.tableCounts" :key="table">
            <span>{{ table }}</span><strong>{{ count }}</strong>
          </li>
        </ul>
      </div>
    </section>

    <section class="admin-panel database-scan" aria-labelledby="scan-title">
      <header>
        <div>
          <p class="section-folio">只读检查</p>
          <h3 id="scan-title">数据一致性扫描</h3>
          <p>检查数据库记录、对象存储与索引引用的一致性，不会自动删除或修复数据。</p>
        </div>
        <button type="button" class="admin-button admin-button--primary" :disabled="startingScan" @click="startScan">
          {{ startingScan ? '创建中…' : '开始扫描' }}
        </button>
      </header>
      <div v-if="scanJob" class="database-scan__job">
        <AdminJobStatus :status="scanJob.status" />
        <span>任务 #{{ scanJob.id }}</span>
        <time v-if="scanJob.startedAt">{{ formatAdminDate(scanJob.startedAt) }}</time>
        <p v-if="scanJob.errorSummary" class="admin-alert" role="alert">{{ scanJob.errorSummary }}</p>
      </div>
    </section>
  </section>
</template>

<style scoped>
.database-stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0.75rem;
  margin: 1.2rem 0;
}

.database-stat-grid article {
  display: grid;
  gap: 0.35rem;
  min-height: 5.5rem;
  padding: 0.9rem;
  background: rgba(228, 216, 189, 0.34);
  border-top: 3px double var(--rule);
}

.database-stat-grid span,
.database-details dt {
  color: var(--ink-soft);
  font-size: 0.66rem;
}

.database-stat-grid strong {
  color: var(--green-900);
  font-family: Georgia, serif;
  font-size: 1.05rem;
}

.database-details {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 1rem;
}

.database-details dl {
  display: grid;
  gap: 0.65rem;
  margin: 0;
}

.database-details dl div {
  display: grid;
  grid-template-columns: 6rem minmax(0, 1fr);
  gap: 0.5rem;
}

.database-details dd {
  margin: 0;
  color: var(--green-900);
  font-size: 0.75rem;
}

.database-tables h3,
.database-scan h3,
.database-scan p {
  margin: 0;
}

.database-tables h3,
.database-scan h3 {
  color: var(--green-950);
  font-family: STKaiti, KaiTi, serif;
  font-size: 1.1rem;
}

.database-tables ul {
  display: grid;
  gap: 0.4rem;
  margin: 0.7rem 0 0;
  padding: 0;
  list-style: none;
}

.database-tables li {
  display: flex;
  justify-content: space-between;
  padding-bottom: 0.35rem;
  border-bottom: 1px solid rgba(18, 59, 50, 0.12);
  font-size: 0.74rem;
}

.database-scan {
  margin-top: 1.2rem;
}

.database-scan > header {
  display: flex;
  gap: 1rem;
  align-items: flex-end;
  justify-content: space-between;
}

.database-scan header p:not(.section-folio) {
  max-width: 43rem;
  margin-top: 0.4rem;
  color: var(--ink-soft);
  font-size: 0.74rem;
  line-height: 1.55;
}

.database-scan__job {
  display: flex;
  gap: 0.65rem;
  flex-wrap: wrap;
  align-items: center;
  margin-top: 1rem;
  font-size: 0.73rem;
}

.database-scan__job time {
  color: var(--ink-soft);
}

.database-scan__job .admin-alert {
  width: 100%;
}

@media (max-width: 900px) {
  .database-stat-grid,
  .database-details {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 540px) {
  .database-stat-grid,
  .database-details {
    grid-template-columns: 1fr;
  }

  .database-scan > header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
