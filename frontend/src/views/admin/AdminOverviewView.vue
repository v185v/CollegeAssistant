<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { getAdminOverview } from '../../api/adminEndpoints'
import type { DatabaseOverviewDto } from '../../api/adminTypes'
import { adminFailureMessage } from './adminViewSupport'

const emit = defineEmits<{ unauthorized: [] }>()

const overview = ref<DatabaseOverviewDto | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const storageLabel = computed(() => overview.value?.storageAvailable ? '可用' : '不可用')
const modelLabel = computed(() => overview.value?.modelStatus.configured ? '已配置' : '未配置')

function formatSize(bytes: number | undefined): string {
  if (bytes === undefined) {
    return '—'
  }
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
    overview.value = await getAdminOverview()
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '运行总览加载失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadOverview()
})
</script>

<template>
  <section
    class="admin-page admin-overview"
    data-view="admin-overview"
    aria-labelledby="admin-overview-title"
  >
    <header class="admin-page__header">
      <div>
        <p class="section-folio">控制台 · 运行总览</p>
        <h2 id="admin-overview-title">资料与服务管理台</h2>
        <p>从资料接入到检索索引的运行入口。后台操作均会创建可追踪任务。</p>
      </div>
      <button type="button" class="admin-button admin-button--quiet" :disabled="loading" @click="loadOverview">
        {{ loading ? '读取中…' : '刷新状态' }}
      </button>
    </header>

    <p v-if="errorMessage" class="admin-alert" role="alert">{{ errorMessage }}</p>

    <div class="overview-grid" aria-label="管理模块概览">
      <RouterLink class="overview-card" :to="{ name: 'admin-imports' }">
        <p>01 / INGEST</p>
        <h3>资料导入</h3>
        <strong>{{ overview?.recentIngestionFailures.length ?? 0 }} 条近期异常</strong>
        <span>管理本地文件、导入运行和失败重试</span>
      </RouterLink>
      <RouterLink class="overview-card" :to="{ name: 'admin-documents' }">
        <p>02 / STORAGE</p>
        <h3>数据管理</h3>
        <strong>{{ formatSize(overview?.databaseSizeBytes) }} 数据库</strong>
        <span>存储 {{ storageLabel }} · 索引 {{ formatSize(overview?.indexSizeBytes) }}</span>
      </RouterLink>
      <RouterLink class="overview-card" :to="{ name: 'admin-sources' }">
        <p>03 / SOURCE</p>
        <h3>资料来源</h3>
        <strong>{{ modelLabel }}的 {{ overview?.modelStatus.model ?? '模型服务' }}</strong>
        <span>查看外部来源、采集边界和调度配置</span>
      </RouterLink>
    </div>

    <section v-if="overview" class="admin-overview__status" aria-label="服务状态">
      <dl>
        <div>
          <dt>数据库</dt>
          <dd>{{ overview.databaseVersion }}</dd>
        </div>
        <div>
          <dt>向量扩展</dt>
          <dd>{{ overview.pgvectorVersion }}</dd>
        </div>
        <div>
          <dt>迁移版本</dt>
          <dd>{{ overview.flywayVersion }}</dd>
        </div>
        <div>
          <dt>活动连接</dt>
          <dd>{{ overview.activeConnections }}</dd>
        </div>
      </dl>
    </section>

    <aside class="admin-overview__boundary">
      <span aria-hidden="true">阅</span>
      <div>
        <h3>管理边界已启用</h3>
        <p>仅 ADMIN 角色可进入；敏感操作会经由任务队列和审计记录处理。</p>
      </div>
    </aside>
  </section>
</template>

<style scoped>
.overview-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1rem;
  margin-top: 1.5rem;
}

.overview-card {
  display: grid;
  gap: 0.55rem;
  min-height: 12rem;
  padding: 1.25rem;
  color: inherit;
  background: rgba(255, 255, 255, 0.38);
  border: 1px solid rgba(18, 59, 50, 0.18);
  box-shadow: 5px 5px 0 rgba(228, 216, 189, 0.68);
  text-decoration: none;
}

.overview-card:hover {
  border-color: var(--vermilion);
}

.overview-card p,
.overview-card h3,
.overview-card strong,
.overview-card span {
  margin: 0;
}

.overview-card p {
  color: var(--vermilion);
  font-family: Georgia, serif;
  font-size: 0.62rem;
  letter-spacing: 0.14em;
}

.overview-card h3 {
  color: var(--green-900);
  font-family: STKaiti, KaiTi, serif;
  font-size: 1.45rem;
}

.overview-card strong {
  color: var(--ink);
  font-size: 0.82rem;
}

.overview-card span {
  align-self: end;
  color: var(--ink-soft);
  font-size: 0.72rem;
  line-height: 1.65;
}

.admin-overview__status {
  margin-top: 1.5rem;
  padding: 1rem 1.25rem;
  background: rgba(228, 216, 189, 0.34);
  border-top: 3px double var(--rule);
}

.admin-overview__status dl {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0.8rem;
  margin: 0;
}

.admin-overview__status dl div {
  display: grid;
  gap: 0.25rem;
}

.admin-overview__status dt {
  color: var(--ink-soft);
  font-size: 0.63rem;
}

.admin-overview__status dd {
  margin: 0;
  color: var(--green-900);
  font-family: Georgia, serif;
  font-size: 0.76rem;
}

.admin-overview__boundary {
  display: flex;
  gap: 1rem;
  align-items: center;
  margin-top: 1.5rem;
  padding: 1.1rem 1.25rem;
  color: var(--paper);
  background: var(--green-900);
  border-left: 5px solid var(--vermilion);
}

.admin-overview__boundary > span {
  display: grid;
  width: 2.8rem;
  height: 2.8rem;
  flex: 0 0 auto;
  place-items: center;
  color: var(--vermilion-soft);
  border: 1px solid currentColor;
  font-family: STKaiti, KaiTi, serif;
}

.admin-overview__boundary h3,
.admin-overview__boundary p {
  margin: 0;
}

.admin-overview__boundary h3 {
  font-size: 0.9rem;
}

.admin-overview__boundary p {
  margin-top: 0.35rem;
  color: #bdcbc4;
  font-size: 0.72rem;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .overview-grid,
  .admin-overview__status dl {
    grid-template-columns: 1fr;
  }

  .overview-card {
    min-height: 9rem;
  }
}
</style>
