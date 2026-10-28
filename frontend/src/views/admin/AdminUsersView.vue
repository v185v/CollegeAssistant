<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import {
  invalidateUserTokens,
  listAdminUsers,
  setUserEnabled,
  updateUserRole,
} from '../../api/adminEndpoints'
import type { AdminUserDto } from '../../api/adminTypes'
import { ApiClientError } from '../../api/client'
import type { UserRole } from '../../api/types'
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

const USER_COLUMNS: AdminTableColumn[] = [
  { key: 'username', label: '账号', sortable: true },
  { key: 'displayName', label: '显示名' },
  { key: 'role', label: '角色', sortable: true },
  { key: 'enabled', label: '状态', sortable: true },
  { key: 'tokenVersion', label: '令牌版本' },
  { key: 'createdAt', label: '创建时间', sortable: true },
]

const users = ref<AdminUserDto[]>([])
const loading = ref(false)
const errorMessage = ref('')
const page = ref(1)
const pages = ref(1)
const total = ref(0)
const sortKey = ref('createdAt')
const sortDirection = ref<'asc' | 'desc'>('desc')
const changingUserId = ref<number | null>(null)
const invalidateTarget = ref<AdminUserDto | null>(null)
const invalidating = ref(false)

const orderedUsers = computed(() => sortAdminRows(users.value, sortKey.value, sortDirection.value))
const tableRows = computed(
  () => orderedUsers.value as unknown as Array<Record<string, unknown>>,
)

function userRow(row: Record<string, unknown>): AdminUserDto {
  return row as unknown as AdminUserDto
}

function userCell(row: Record<string, unknown>, column: AdminTableColumn): string {
  const user = userRow(row)
  if (column.key === 'createdAt') {
    return formatAdminDate(user.createdAt)
  }
  if (column.key === 'role') {
    return user.role === 'ADMIN' ? '管理员' : '学生'
  }
  if (column.key === 'enabled') {
    return user.enabled ? '已启用' : '已禁用'
  }
  return String(user[column.key as keyof AdminUserDto] ?? '—')
}

async function loadUsers(requestedPage = page.value): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listAdminUsers({ page: requestedPage, size: 20 })
    users.value = result.items
    page.value = result.page
    pages.value = Math.max(result.pages, 1)
    total.value = result.total
  } catch (error) {
    errorMessage.value = failureMessage(error, '用户列表加载失败，请稍后重试。')
  } finally {
    loading.value = false
  }
}

function failureMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiClientError && error.code === 40905) {
    return '至少保留一名管理员，无法执行此操作。'
  }
  return adminFailureMessage(error, fallback, () => emit('unauthorized'))
}

async function changeRole(user: AdminUserDto, role: UserRole): Promise<void> {
  if (user.role === role) {
    return
  }
  changingUserId.value = user.id
  errorMessage.value = ''
  try {
    await updateUserRole(user.id, role)
    await loadUsers(page.value)
  } catch (error) {
    errorMessage.value = failureMessage(error, '用户角色更新失败，请稍后重试。')
  } finally {
    changingUserId.value = null
  }
}

function onRoleChange(user: AdminUserDto, event: Event): void {
  const target = event.target
  if (target instanceof HTMLSelectElement) {
    void changeRole(user, target.value as UserRole)
  }
}

async function toggleEnabled(user: AdminUserDto): Promise<void> {
  changingUserId.value = user.id
  errorMessage.value = ''
  try {
    await setUserEnabled(user.id, !user.enabled)
    await loadUsers(page.value)
  } catch (error) {
    errorMessage.value = failureMessage(error, '账号状态更新失败，请稍后重试。')
  } finally {
    changingUserId.value = null
  }
}

async function confirmInvalidate(): Promise<void> {
  if (!invalidateTarget.value) {
    return
  }
  invalidating.value = true
  errorMessage.value = ''
  try {
    await invalidateUserTokens(invalidateTarget.value.id)
    invalidateTarget.value = null
    await loadUsers(page.value)
  } catch (error) {
    errorMessage.value = failureMessage(error, '令牌失效操作失败，请稍后重试。')
  } finally {
    invalidating.value = false
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
  void loadUsers()
})
</script>

<template>
  <section class="admin-page" data-view="admin-users" aria-labelledby="admin-users-title">
    <header class="admin-page__header">
      <div>
        <p class="section-folio">治理 · 账号权限</p>
        <h2 id="admin-users-title">用户与令牌</h2>
        <p>角色变更、禁用账号和令牌失效会立即影响既有登录会话。</p>
      </div>
      <button type="button" class="admin-button admin-button--quiet" @click="loadUsers(page)">刷新用户</button>
    </header>

    <p v-if="errorMessage" class="admin-alert" role="alert">{{ errorMessage }}</p>

    <AdminDataTable
      :columns="USER_COLUMNS"
      :rows="tableRows"
      :loading="loading"
      :error="errorMessage"
      :page="page"
      :pages="pages"
      :total="total"
      empty-message="没有用户记录"
      :row-key="(row) => userRow(row).id"
      @page="loadUsers"
      @retry="loadUsers(page)"
      @sort="changeSort"
    >
      <template #cell="{ row, column, value }">
        <AdminJobStatus
          v-if="column.key === 'enabled'"
          :status="userRow(row).enabled ? 'ACTIVE' : 'RETIRED'"
        />
        <span v-else :title="String(value)">{{ userCell(row, column) }}</span>
      </template>
      <template #actions="{ row }">
        <select
          :data-test="`role-user-${userRow(row).id}`"
          class="user-role-select"
          :value="userRow(row).role"
          :disabled="changingUserId === userRow(row).id"
          @change="onRoleChange(userRow(row), $event)"
        >
          <option value="STUDENT">学生</option>
          <option value="ADMIN">管理员</option>
        </select>
        <button
          type="button"
          class="admin-text-button"
          :disabled="changingUserId === userRow(row).id"
          @click="toggleEnabled(userRow(row))"
        >
          {{ userRow(row).enabled ? '禁用' : '启用' }}
        </button>
        <button type="button" class="admin-text-button admin-text-button--danger" @click="invalidateTarget = userRow(row)">
          令牌失效
        </button>
      </template>
    </AdminDataTable>

    <ConfirmActionDialog
      :model-value="Boolean(invalidateTarget)"
      title="令牌立即失效"
      description="该用户所有已有登录令牌将立即失效，需要重新登录才能继续使用系统。"
      :target="invalidateTarget?.username ?? ''"
      :confirmation-text="invalidateTarget?.username ?? ''"
      confirm-label="使令牌失效"
      :busy="invalidating"
      @update:model-value="(open) => { if (!open) invalidateTarget = null }"
      @confirm="confirmInvalidate"
    />
  </section>
</template>

<style scoped>
.user-role-select {
  min-height: 1.85rem;
  padding: 0.2rem 0.3rem;
  color: var(--green-900);
  background: #fffdf7;
  border: 1px solid var(--rule);
  font-size: 0.68rem;
}
</style>
