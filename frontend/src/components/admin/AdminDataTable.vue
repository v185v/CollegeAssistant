<script setup lang="ts">
import { computed } from 'vue'

export interface AdminTableColumn {
  key: string
  label: string
  sortable?: boolean
  value?: (row: Record<string, unknown>) => string | number | null | undefined
}

const props = withDefaults(
  defineProps<{
    columns: AdminTableColumn[]
    rows: Array<Record<string, unknown>>
    loading?: boolean
    error?: string
    emptyMessage?: string
    page?: number
    pages?: number
    total?: number
    rowKey?: (row: Record<string, unknown>, index: number) => string | number
  }>(),
  {
    loading: false,
    error: '',
    emptyMessage: '暂无记录',
    page: 1,
    pages: 1,
    total: 0,
    rowKey: (_row, index) => index,
  },
)

const emit = defineEmits<{
  page: [page: number]
  sort: [key: string]
  retry: []
}>()

const hasMultiplePages = computed(() => props.pages > 1)

function cellValue(
  row: Record<string, unknown>,
  column: AdminTableColumn,
): string {
  const value = column.value ? column.value(row) : row[column.key]
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  return String(value)
}

function goToPage(page: number): void {
  if (!props.loading && page >= 1 && page <= props.pages) {
    emit('page', page)
  }
}
</script>

<template>
  <section class="admin-data-table" :aria-busy="loading">
    <p v-if="loading" class="admin-data-table__notice" role="status">正在读取记录…</p>

    <div v-else-if="error" class="admin-data-table__notice admin-data-table__notice--error" role="alert">
      <span>{{ error }}</span>
      <button type="button" @click="emit('retry')">重试</button>
    </div>

    <p v-else-if="rows.length === 0" class="admin-data-table__notice">
      {{ emptyMessage }}
    </p>

    <template v-else>
      <div class="admin-data-table__desktop">
        <table>
          <thead>
            <tr>
              <th v-for="column in columns" :key="column.key" scope="col">
                <button
                  v-if="column.sortable"
                  type="button"
                  class="admin-data-table__sort"
                  @click="emit('sort', column.key)"
                >
                  {{ column.label }}
                  <span aria-hidden="true">↕</span>
                </button>
                <template v-else>{{ column.label }}</template>
              </th>
              <th v-if="$slots.actions" scope="col" class="admin-data-table__actions-heading">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in rows" :key="rowKey(row, index)">
              <td v-for="column in columns" :key="column.key" :data-label="column.label">
                <slot name="cell" :row="row" :column="column" :value="cellValue(row, column)">
                  {{ cellValue(row, column) }}
                </slot>
              </td>
              <td v-if="$slots.actions" class="admin-data-table__actions">
                <slot name="actions" :row="row" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="admin-data-table__mobile">
        <details v-for="(row, index) in rows" :key="rowKey(row, index)">
          <summary>
            <span>{{ cellValue(row, columns[0]!) }}</span>
            <small>{{ columns[0]?.label }}</small>
          </summary>
          <dl>
            <template v-for="column in columns" :key="column.key">
              <dt>{{ column.label }}</dt>
              <dd>
                <slot name="cell" :row="row" :column="column" :value="cellValue(row, column)">
                  {{ cellValue(row, column) }}
                </slot>
              </dd>
            </template>
          </dl>
          <div v-if="$slots.actions" class="admin-data-table__mobile-actions">
            <slot name="actions" :row="row" />
          </div>
        </details>
      </div>
    </template>

    <footer v-if="!loading && !error && (rows.length > 0 || hasMultiplePages)" class="admin-data-table__footer">
      <span>共 {{ total }} 条</span>
      <div v-if="hasMultiplePages" class="admin-data-table__pagination" aria-label="分页">
        <button type="button" :disabled="page <= 1" @click="goToPage(page - 1)">上一页</button>
        <span>{{ page }} / {{ pages }}</span>
        <button type="button" :disabled="page >= pages" @click="goToPage(page + 1)">下一页</button>
      </div>
    </footer>
  </section>
</template>

<style scoped>
.admin-data-table {
  overflow: hidden;
  background: rgba(255, 255, 255, 0.25);
  border: 1px solid var(--rule);
}

.admin-data-table__desktop {
  overflow-x: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.76rem;
}

th,
td {
  padding: 0.78rem 0.9rem;
  text-align: left;
  vertical-align: top;
  border-bottom: 1px solid rgba(18, 59, 50, 0.12);
}

th {
  color: var(--ink-soft);
  background: rgba(228, 216, 189, 0.38);
  font-size: 0.64rem;
  font-weight: 600;
  letter-spacing: 0.08em;
  white-space: nowrap;
}

tbody tr:last-child td {
  border-bottom: 0;
}

.admin-data-table__sort {
  display: inline-flex;
  gap: 0.35rem;
  align-items: center;
  padding: 0;
  color: inherit;
  background: transparent;
  border: 0;
  cursor: pointer;
  font: inherit;
  letter-spacing: inherit;
}

.admin-data-table__sort span {
  color: var(--vermilion);
}

.admin-data-table__actions-heading,
.admin-data-table__actions {
  white-space: nowrap;
}

.admin-data-table__actions,
.admin-data-table__mobile-actions {
  display: flex;
  gap: 0.45rem;
  flex-wrap: wrap;
  align-items: center;
}

.admin-data-table__notice {
  margin: 0;
  padding: 1.4rem;
  color: var(--ink-soft);
  font-size: 0.78rem;
}

.admin-data-table__notice--error {
  display: flex;
  gap: 1rem;
  align-items: center;
  justify-content: space-between;
  color: #762d24;
  background: #f4ddd4;
}

.admin-data-table__notice--error button {
  padding: 0.3rem 0;
  color: inherit;
  background: transparent;
  border: 0;
  border-bottom: 1px dotted currentColor;
  cursor: pointer;
}

.admin-data-table__footer {
  display: flex;
  gap: 1rem;
  align-items: center;
  justify-content: space-between;
  min-height: 3.2rem;
  padding: 0.65rem 0.9rem;
  color: var(--ink-soft);
  border-top: 1px solid var(--rule);
  font-size: 0.7rem;
}

.admin-data-table__pagination {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}

.admin-data-table__pagination button {
  padding: 0.35rem 0.55rem;
  color: var(--green-900);
  background: transparent;
  border: 1px solid var(--rule);
  cursor: pointer;
  font-size: 0.7rem;
}

.admin-data-table__mobile {
  display: none;
}

@media (max-width: 540px) {
  .admin-data-table__desktop {
    display: none;
  }

  .admin-data-table__mobile {
    display: grid;
  }

  .admin-data-table__mobile details {
    border-bottom: 1px solid rgba(18, 59, 50, 0.14);
  }

  .admin-data-table__mobile details:last-child {
    border-bottom: 0;
  }

  .admin-data-table__mobile summary {
    display: grid;
    gap: 0.2rem;
    padding: 0.85rem 1rem;
    cursor: pointer;
  }

  .admin-data-table__mobile summary span {
    color: var(--green-900);
    font-size: 0.82rem;
    font-weight: 600;
  }

  .admin-data-table__mobile summary small {
    color: var(--ink-soft);
    font-size: 0.64rem;
  }

  .admin-data-table__mobile dl {
    display: grid;
    grid-template-columns: minmax(5.4rem, 0.8fr) minmax(0, 1.2fr);
    gap: 0.55rem 0.8rem;
    margin: 0;
    padding: 0.2rem 1rem 0.9rem;
    font-size: 0.73rem;
  }

  .admin-data-table__mobile dt {
    color: var(--ink-soft);
  }

  .admin-data-table__mobile dd {
    margin: 0;
  }

  .admin-data-table__mobile-actions {
    padding: 0 1rem 1rem;
  }

  .admin-data-table__footer {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
