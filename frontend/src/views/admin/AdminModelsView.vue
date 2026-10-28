<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  activateEmbeddingRebuild,
  createModelConfiguration,
  discardEmbeddingRebuild,
  getDatabaseOverview,
  listEmbeddingRebuilds,
  listModelConfigurations,
  startEmbeddingRebuild,
  testModelConfiguration,
} from '../../api/adminEndpoints'
import type {
  AdminJobDto,
  ModelConfigurationDto,
  ModelConfigurationRequest,
  ModelKind,
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

const MODEL_COLUMNS: AdminTableColumn[] = [
  { key: 'configurationKey', label: '配置版本', sortable: true },
  { key: 'kind', label: '类型', sortable: true },
  { key: 'provider', label: '提供方' },
  { key: 'model', label: '模型' },
  { key: 'status', label: '状态', sortable: true },
  { key: 'updatedAt', label: '更新时间', sortable: true },
]

const REBUILD_COLUMNS: AdminTableColumn[] = [
  { key: 'targetId', label: '候选配置', sortable: true },
  { key: 'status', label: '重建状态', sortable: true },
  { key: 'createdAt', label: '创建时间', sortable: true },
  { key: 'errorSummary', label: '结果' },
]

const configurations = ref<ModelConfigurationDto[]>([])
const rebuilds = ref<AdminJobDto[]>([])
const loading = ref(false)
const errorMessage = ref('')
const modelCapability = ref(false)
const sortKey = ref('updatedAt')
const sortDirection = ref<'asc' | 'desc'>('desc')
const form = ref({
  kind: 'CHAT' as ModelKind,
  provider: 'openai',
  model: '',
  baseUrl: '',
  temperature: 0.2,
  dimension: 1536,
  apiKey: '',
  keepExistingSecret: false,
  existingConfigurationId: null as number | null,
})
const testing = ref(false)
const saving = ref(false)
const startRebuildId = ref<number | null>(null)
const activatingId = ref<number | null>(null)
const discardTarget = ref<ModelConfigurationDto | null>(null)
const discarding = ref(false)

const orderedConfigurations = computed(() =>
  sortAdminRows(configurations.value, sortKey.value, sortDirection.value),
)
const modelRows = computed(
  () => orderedConfigurations.value as unknown as Array<Record<string, unknown>>,
)
const rebuildRows = computed(
  () => rebuilds.value as unknown as Array<Record<string, unknown>>,
)

function modelRow(row: Record<string, unknown>): ModelConfigurationDto {
  return row as unknown as ModelConfigurationDto
}

function rebuildRow(row: Record<string, unknown>): AdminJobDto {
  return row as unknown as AdminJobDto
}

function modelCell(row: Record<string, unknown>, column: AdminTableColumn): string {
  const model = modelRow(row)
  if (column.key === 'updatedAt') {
    return formatAdminDate(model.updatedAt)
  }
  if (column.key === 'kind') {
    return model.kind === 'EMBEDDING' ? '嵌入' : '对话'
  }
  return String(model[column.key as keyof ModelConfigurationDto] ?? '—')
}

function rebuildCell(row: Record<string, unknown>, column: AdminTableColumn): string {
  const rebuild = rebuildRow(row)
  if (column.key === 'createdAt') {
    return formatAdminDate(rebuild.createdAt)
  }
  if (column.key === 'targetId') {
    return rebuild.targetId ? `配置 #${rebuild.targetId}` : '—'
  }
  return String(rebuild[column.key as keyof AdminJobDto] ?? '—')
}

function requestFromForm(): ModelConfigurationRequest {
  return {
    kind: form.value.kind,
    provider: form.value.provider.trim(),
    model: form.value.model.trim(),
    baseUrl: form.value.baseUrl.trim() || null,
    temperature: form.value.kind === 'CHAT' ? form.value.temperature : null,
    dimension: form.value.kind === 'EMBEDDING' ? form.value.dimension : null,
    ...(form.value.apiKey.trim() ? { apiKey: form.value.apiKey } : {}),
    keepExistingSecret: form.value.keepExistingSecret,
    existingConfigurationId: form.value.existingConfigurationId,
  }
}

function formIsValid(): boolean {
  return Boolean(form.value.provider.trim() && form.value.model.trim())
}

async function loadModels(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const [models, jobs, overview] = await Promise.all([
      listModelConfigurations(),
      listEmbeddingRebuilds(),
      getDatabaseOverview(),
    ])
    configurations.value = models
    rebuilds.value = jobs
    modelCapability.value = overview.modelStatus.configured
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '模型配置加载失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    loading.value = false
  }
}

async function testConfiguration(): Promise<void> {
  if (!formIsValid()) {
    errorMessage.value = '请填写模型提供方和模型名称。'
    return
  }
  testing.value = true
  errorMessage.value = ''
  try {
    await testModelConfiguration(requestFromForm())
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '模型连通性测试失败，请检查配置与密钥。',
      () => emit('unauthorized'),
    )
  } finally {
    testing.value = false
  }
}

async function saveConfiguration(): Promise<void> {
  if (!formIsValid()) {
    errorMessage.value = '请填写模型提供方和模型名称。'
    return
  }
  saving.value = true
  errorMessage.value = ''
  try {
    const created = await createModelConfiguration(requestFromForm())
    configurations.value = [created, ...configurations.value]
    form.value.apiKey = ''
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '模型配置保存失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    saving.value = false
  }
}

function latestRebuild(configurationId: number): AdminJobDto | undefined {
  return rebuilds.value.find((job) => job.targetId === configurationId)
}

function manifestVerified(configurationId: number): boolean {
  const job = latestRebuild(configurationId)
  return job?.status === 'SUCCEEDED'
    && job.items.some((item) => item.itemKey === 'manifest:verified'
      && item.status === 'SUCCEEDED'
      && item.checkpoint.manifestVerified === true)
}

function canStartRebuild(configuration: ModelConfigurationDto): boolean {
  return configuration.kind === 'EMBEDDING'
    && configuration.status === 'PENDING'
    && !latestRebuild(configuration.id)
}

function canDiscard(configuration: ModelConfigurationDto): boolean {
  const job = latestRebuild(configuration.id)
  return configuration.kind === 'EMBEDDING'
    && configuration.status === 'PENDING'
    && job?.status !== 'RUNNING'
}

async function startRebuild(configuration: ModelConfigurationDto): Promise<void> {
  startRebuildId.value = configuration.id
  errorMessage.value = ''
  try {
    const rebuild = await startEmbeddingRebuild({ configurationId: configuration.id })
    rebuilds.value = [rebuild, ...rebuilds.value]
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '嵌入重建任务创建失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    startRebuildId.value = null
  }
}

async function activate(configuration: ModelConfigurationDto): Promise<void> {
  if (!manifestVerified(configuration.id)) {
    return
  }
  activatingId.value = configuration.id
  errorMessage.value = ''
  try {
    await activateEmbeddingRebuild(configuration.id)
    await loadModels()
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '嵌入配置激活失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    activatingId.value = null
  }
}

async function confirmDiscard(): Promise<void> {
  if (!discardTarget.value) {
    return
  }
  discarding.value = true
  errorMessage.value = ''
  try {
    await discardEmbeddingRebuild(discardTarget.value.id)
    discardTarget.value = null
    await loadModels()
  } catch (error) {
    errorMessage.value = adminFailureMessage(
      error,
      '候选嵌入配置清理失败，请稍后重试。',
      () => emit('unauthorized'),
    )
  } finally {
    discarding.value = false
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
  void loadModels()
})
</script>

<template>
  <section class="admin-page" data-view="admin-models" aria-labelledby="admin-models-title">
    <header class="admin-page__header">
      <div>
        <p class="section-folio">运维 · AI 配置</p>
        <h2 id="admin-models-title">模型与嵌入版本</h2>
        <p>密钥只用于本次提交，列表始终只显示服务端返回的遮罩状态。</p>
      </div>
      <AdminJobStatus :status="modelCapability ? 'ACTIVE' : 'FAILED'" />
    </header>

    <section class="admin-panel model-form" aria-labelledby="model-form-title">
      <header>
        <div>
          <p class="section-folio">新建不可变版本</p>
          <h3 id="model-form-title">模型配置</h3>
        </div>
      </header>
      <div class="admin-form-grid">
        <label>
          <span>类型</span>
          <select v-model="form.kind">
            <option value="CHAT">对话模型</option>
            <option value="EMBEDDING">嵌入模型</option>
          </select>
        </label>
        <label>
          <span>提供方</span>
          <input v-model="form.provider" maxlength="64" placeholder="openai">
        </label>
        <label>
          <span>模型名称</span>
          <input v-model="form.model" data-test="model-name" maxlength="256" placeholder="gpt-4.1-mini">
        </label>
        <label>
          <span>服务地址</span>
          <input v-model="form.baseUrl" type="url" maxlength="2048" placeholder="https://api.example.com/v1">
        </label>
        <label v-if="form.kind === 'CHAT'">
          <span>温度</span>
          <input v-model.number="form.temperature" type="number" min="0" max="2" step="0.1">
        </label>
        <label v-else>
          <span>向量维度</span>
          <input v-model.number="form.dimension" type="number" min="1">
        </label>
        <label>
          <span>API 密钥</span>
          <input v-model="form.apiKey" data-test="model-api-key" type="password" autocomplete="new-password" placeholder="仅在本次提交时发送">
        </label>
        <label class="admin-checkbox">
          <input v-model="form.keepExistingSecret" type="checkbox">
          <span>保留已有密钥</span>
        </label>
      </div>
      <p v-if="errorMessage" class="admin-alert" role="alert">{{ errorMessage }}</p>
      <div class="admin-form-actions">
        <button type="button" class="admin-button admin-button--quiet" :disabled="testing" @click="testConfiguration">
          {{ testing ? '测试中…' : '测试配置' }}
        </button>
        <button type="button" data-test="save-model" class="admin-button admin-button--primary" :disabled="saving" @click="saveConfiguration">
          {{ saving ? '保存中…' : '保存新版本' }}
        </button>
      </div>
    </section>

    <section class="model-maintenance-note">
      <strong>嵌入维护窗口</strong>
      <span>待激活的嵌入候选会暂停内容写入，直到完成激活或明确丢弃并清理。</span>
    </section>

    <AdminDataTable
      :columns="MODEL_COLUMNS"
      :rows="modelRows"
      :loading="loading"
      :error="errorMessage"
      :total="configurations.length"
      empty-message="尚无模型配置"
      :row-key="(row) => modelRow(row).id"
      @retry="loadModels"
      @sort="changeSort"
    >
      <template #cell="{ row, column, value }">
        <AdminJobStatus v-if="column.key === 'status'" :status="modelRow(row).status" />
        <span v-else :title="String(value)">{{ modelCell(row, column) }}</span>
      </template>
      <template #actions="{ row }">
        <span v-if="modelRow(row).restartRequired" class="model-restart">等待重启</span>
        <span v-if="modelRow(row).secretConfigured" class="model-secret">已配置密钥</span>
        <button
          v-if="canStartRebuild(modelRow(row))"
          type="button"
          class="admin-text-button"
          :disabled="startRebuildId === modelRow(row).id"
          @click="startRebuild(modelRow(row))"
        >
          {{ startRebuildId === modelRow(row).id ? '创建中…' : '开始重建' }}
        </button>
        <button
          v-if="modelRow(row).kind === 'EMBEDDING' && modelRow(row).status === 'PENDING'"
          type="button"
          :data-test="`activate-rebuild-${modelRow(row).id}`"
          class="admin-text-button"
          :disabled="!manifestVerified(modelRow(row).id) || activatingId === modelRow(row).id"
          @click="activate(modelRow(row))"
        >
          {{ activatingId === modelRow(row).id ? '激活中…' : '激活候选' }}
        </button>
        <button
          v-if="canDiscard(modelRow(row))"
          type="button"
          class="admin-text-button admin-text-button--danger"
          @click="discardTarget = modelRow(row)"
        >
          丢弃并清理
        </button>
      </template>
    </AdminDataTable>

    <section class="admin-panel model-rebuilds" aria-labelledby="rebuild-title">
      <header>
        <div>
          <p class="section-folio">嵌入任务</p>
          <h3 id="rebuild-title">重建与验证记录</h3>
        </div>
        <button type="button" class="admin-button admin-button--quiet" @click="loadModels">刷新记录</button>
      </header>
      <AdminDataTable
        :columns="REBUILD_COLUMNS"
        :rows="rebuildRows"
        :total="rebuilds.length"
        empty-message="暂无嵌入重建任务"
        :row-key="(row) => rebuildRow(row).id"
      >
        <template #cell="{ row, column, value }">
          <AdminJobStatus v-if="column.key === 'status'" :status="rebuildRow(row).status" />
          <span v-else :title="String(value)">{{ rebuildCell(row, column) }}</span>
        </template>
      </AdminDataTable>
    </section>

    <ConfirmActionDialog
      :model-value="Boolean(discardTarget)"
      title="丢弃嵌入候选"
      description="候选分区将进入清理任务，维护窗口才会解除；已生成的候选向量不可恢复。"
      :target="discardTarget?.configurationKey ?? ''"
      :confirmation-text="discardTarget?.configurationKey ?? ''"
      confirm-label="丢弃并清理"
      :busy="discarding"
      @update:model-value="(open) => { if (!open) discardTarget = null }"
      @confirm="confirmDiscard"
    />
  </section>
</template>

<style scoped>
.model-form {
  margin: 1.2rem 0;
}

.model-form h3,
.model-rebuilds h3 {
  margin: 0.25rem 0 0;
  color: var(--green-950);
  font-family: STKaiti, KaiTi, serif;
  font-size: 1.15rem;
}

.model-maintenance-note {
  display: grid;
  gap: 0.25rem;
  margin: 1rem 0;
  padding: 0.8rem 0.95rem;
  color: #735d1b;
  background: #f4ead0;
  border-left: 4px solid #b48924;
  font-size: 0.74rem;
}

.model-restart,
.model-secret {
  display: inline-block;
  margin-right: 0.45rem;
  color: var(--ink-soft);
  font-size: 0.66rem;
}

.model-restart {
  color: #735d1b;
}

.model-rebuilds {
  margin-top: 1.2rem;
}

.model-rebuilds > header {
  display: flex;
  gap: 1rem;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 0.8rem;
}
</style>
