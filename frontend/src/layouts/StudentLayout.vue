<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  onBeforeRouteLeave,
  onBeforeRouteUpdate,
  RouterLink,
  RouterView,
  useRoute,
  useRouter,
} from 'vue-router'

import { session } from '../auth/session'

const route = useRoute()
const router = useRouter()
const chatStreaming = ref(false)
const currentUser = computed(() => session.user.value)
const chatListeners = computed(() => route.name === 'chat'
  ? { 'streaming-change': handleStreamingChange }
  : {})

function handleStreamingChange(isStreaming: boolean): void {
  chatStreaming.value = isStreaming
}

function confirmStreamingChatExit(): boolean {
  if (route.name !== 'chat' || !chatStreaming.value) {
    return true
  }
  return window.confirm('回答仍在生成，离开将取消本次查阅。是否继续？')
}

function resetStreamingState(): void {
  chatStreaming.value = false
}

onBeforeRouteUpdate(() => {
  if (!confirmStreamingChatExit()) {
    return false
  }
  resetStreamingState()
  return true
})

onBeforeRouteLeave(() => {
  if (!confirmStreamingChatExit()) {
    return false
  }
  resetStreamingState()
  return true
})

async function signOut(): Promise<void> {
  if (!confirmStreamingChatExit()) {
    return
  }
  resetStreamingState()
  session.clear()
  await router.replace({ name: 'login' })
}

async function handleUnauthorized(): Promise<void> {
  resetStreamingState()
  session.clear('登录状态已失效，请重新登录。')
  await router.replace({ name: 'login' })
}

</script>

<template>
  <div class="app-shell">
    <header class="app-masthead">
      <RouterLink class="brand" :to="{ name: 'chat' }">
        <span class="brand__seal" aria-hidden="true"></span>
        <span class="brand__copy">
          <strong>校园智答</strong>
          <small>校园公报编辑台</small>
        </span>
      </RouterLink>

      <nav class="app-navigation" data-app-navigation aria-label="主要导航">
        <RouterLink data-view-button="chat" :to="{ name: 'chat' }">
          <span aria-hidden="true">01</span>
          问答台
        </RouterLink>
        <RouterLink data-view-button="profile" :to="{ name: 'profile' }">
          <span aria-hidden="true">02</span>
          个人画像
        </RouterLink>
        <RouterLink data-view-button="ugc" :to="{ name: 'ugc' }">
          <span aria-hidden="true">03</span>
          经验来稿
        </RouterLink>
      </nav>

      <div class="app-session">
        <span>{{ currentUser?.displayName || currentUser?.username || '学生版' }}</span>
        <RouterLink
          v-if="currentUser?.role === 'ADMIN'"
          class="app-session__link"
          :to="{ name: 'admin-overview' }"
        >
          后台管理
        </RouterLink>
        <button type="button" data-action="logout" @click="signOut">
          退出
        </button>
      </div>
    </header>

    <main id="main-content" class="app-content" tabindex="-1">
      <RouterView v-slot="{ Component, route: childRoute }">
        <KeepAlive :max="3">
          <component
            :is="Component"
            :key="String(childRoute.name)"
            v-on="chatListeners"
            @unauthorized="handleUnauthorized"
          />
        </KeepAlive>
      </RouterView>
    </main>

    <footer class="app-colophon">
      <p>校园信息，由出处说话。</p>
      <p><span>CA</span> / 校务公报编辑台 · 二〇二六</p>
    </footer>
  </div>
</template>
