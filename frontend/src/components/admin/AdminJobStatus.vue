<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ status: string | null | undefined }>()

const label = computed(() => {
  const labels: Record<string, string> = {
    ACTIVE: '可用',
    CANCELLED: '已取消',
    DEFERRED: '已延后',
    DELETE_FAILED: '删除失败',
    DELETING: '删除中',
    FAILED: '失败',
    PARTIAL_SUCCESS: '部分完成',
    PENDING: '等待处理',
    PROCESSING: '处理中',
    QUEUED: '已排队',
    RETIRED: '已停用',
    RUNNING: '进行中',
    SUCCESS: '已完成',
    SUCCEEDED: '已完成',
    VALIDATED: '已校验',
  }
  return props.status ? labels[props.status] ?? props.status : '未知'
})

const tone = computed(() => {
  if (!props.status) {
    return 'muted'
  }
  if (props.status.includes('FAILED') || props.status === 'DELETE_FAILED') {
    return 'danger'
  }
  if (['RUNNING', 'PROCESSING', 'QUEUED', 'PENDING', 'DEFERRED'].includes(props.status)) {
    return 'pending'
  }
  if (['SUCCESS', 'SUCCEEDED', 'ACTIVE', 'VALIDATED'].includes(props.status)) {
    return 'success'
  }
  return 'muted'
})
</script>

<template>
  <span class="admin-job-status" :class="`admin-job-status--${tone}`">
    {{ label }}
  </span>
</template>

<style scoped>
.admin-job-status {
  display: inline-flex;
  align-items: center;
  min-height: 1.55rem;
  padding: 0.2rem 0.48rem;
  border: 1px solid currentColor;
  font-size: 0.64rem;
  line-height: 1;
  white-space: nowrap;
}

.admin-job-status--success {
  color: #2b654b;
  background: #e5eee5;
}

.admin-job-status--pending {
  color: #735d1b;
  background: #f4ead0;
}

.admin-job-status--danger {
  color: #8a3329;
  background: #f5ddd6;
}

.admin-job-status--muted {
  color: var(--ink-soft);
  background: rgba(228, 216, 189, 0.46);
}
</style>
