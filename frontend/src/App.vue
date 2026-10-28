<script setup lang="ts">
import { nextTick, watch } from 'vue'
import { RouterView, useRoute } from 'vue-router'

import { session } from './auth/session'

const route = useRoute()

watch(
  () => route.fullPath,
  () => {
    void nextTick(() => {
      document.querySelector<HTMLElement>('#main-content')?.focus()
    })
  },
  { flush: 'post' },
)
</script>

<template>
  <div class="app-root">
    <a class="skip-link" href="#main-content">跳到主要内容</a>

    <div
      v-if="session.authorizationError.value"
      class="authorization-banner"
      data-authorization-error
      role="alert"
    >
      <strong>权限提示</strong>
      <span>{{ session.authorizationError.value }}</span>
      <button
        type="button"
        aria-label="关闭权限提示"
        @click="session.authorizationError.value = ''"
      >
        ×
      </button>
    </div>

    <RouterView />
  </div>
</template>

<style scoped>
.authorization-banner {
  position: relative;
  z-index: 20;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 0.75rem;
  align-items: center;
  padding: 0.7rem clamp(1rem, 4vw, 3rem);
  color: #67271f;
  background: #f4ddd4;
  border-bottom: 1px solid #c27566;
  font-size: 0.76rem;
}

.authorization-banner strong {
  letter-spacing: 0.08em;
}

.authorization-banner button {
  padding: 0;
  color: inherit;
  background: transparent;
  border: 0;
  cursor: pointer;
  font-size: 1.1rem;
}
</style>
