<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    description: string
    target: string
    confirmLabel?: string
    confirmationText?: string
    busy?: boolean
  }>(),
  {
    confirmLabel: '确认执行',
    confirmationText: '',
    busy: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: []
}>()

const confirmation = ref('')
const requiresTyping = computed(() => Boolean(props.confirmationText))
const canConfirm = computed(
  () => !props.busy
    && (!requiresTyping.value || confirmation.value.trim() === props.confirmationText),
)

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      confirmation.value = ''
    }
  },
)

function cancel(): void {
  if (!props.busy) {
    emit('update:modelValue', false)
  }
}

function confirm(): void {
  if (canConfirm.value) {
    emit('confirm')
  }
}
</script>

<template>
  <Teleport to="body">
    <div v-if="modelValue" class="confirm-dialog-backdrop" @click.self="cancel">
      <section
        class="confirm-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
      >
        <header>
          <p>高风险操作</p>
          <h2 id="confirm-dialog-title">{{ title }}</h2>
        </header>
        <p class="confirm-dialog__description">{{ description }}</p>
        <dl>
          <dt>目标</dt>
          <dd>{{ target }}</dd>
        </dl>
        <label v-if="requiresTyping" class="confirm-dialog__typing">
          <span>输入“{{ confirmationText }}”以继续</span>
          <input
            v-model="confirmation"
            data-test="confirmation-input"
            type="text"
            :disabled="busy"
            autocomplete="off"
            @keyup.enter="confirm"
          >
        </label>
        <footer>
          <button type="button" class="admin-button admin-button--quiet" :disabled="busy" @click="cancel">
            取消
          </button>
          <button
            type="button"
            data-test="confirm-action"
            class="admin-button admin-button--danger"
            :disabled="!canConfirm"
            @click="confirm"
          >
            {{ busy ? '处理中…' : confirmLabel }}
          </button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.confirm-dialog-backdrop {
  position: fixed;
  z-index: 50;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 1rem;
  background: rgba(8, 41, 35, 0.62);
}

.confirm-dialog {
  width: min(31rem, 100%);
  padding: 1.4rem;
  color: var(--ink);
  background: var(--paper-light);
  border: 4px double var(--green-900);
  box-shadow: 0 1rem 3rem rgba(3, 24, 20, 0.38);
}

.confirm-dialog header p,
.confirm-dialog header h2,
.confirm-dialog__description,
.confirm-dialog dl {
  margin: 0;
}

.confirm-dialog header p {
  color: var(--vermilion);
  font-family: Georgia, serif;
  font-size: 0.63rem;
  letter-spacing: 0.14em;
}

.confirm-dialog h2 {
  margin-top: 0.38rem;
  color: var(--green-950);
  font-family: STKaiti, KaiTi, serif;
  font-size: 1.42rem;
}

.confirm-dialog__description {
  margin-top: 1rem;
  color: var(--ink-soft);
  font-size: 0.8rem;
  line-height: 1.65;
}

.confirm-dialog dl {
  display: grid;
  grid-template-columns: 4rem minmax(0, 1fr);
  gap: 0.5rem;
  margin-top: 1rem;
  padding: 0.75rem;
  background: rgba(228, 216, 189, 0.42);
  border-left: 3px solid var(--vermilion);
  font-size: 0.75rem;
}

.confirm-dialog dt {
  color: var(--ink-soft);
}

.confirm-dialog dd {
  margin: 0;
  font-weight: 600;
}

.confirm-dialog__typing {
  display: grid;
  gap: 0.45rem;
  margin-top: 1rem;
  color: var(--ink-soft);
  font-size: 0.72rem;
}

.confirm-dialog__typing input {
  min-height: 2.35rem;
  padding: 0.45rem 0.6rem;
  color: var(--ink);
  background: #fffdf7;
  border: 1px solid var(--rule);
}

.confirm-dialog footer {
  display: flex;
  gap: 0.7rem;
  justify-content: flex-end;
  margin-top: 1.3rem;
}
</style>
