<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'

import { session } from '../auth/session'

const router = useRouter()
const currentUser = computed(() => session.user.value)

async function signOut(): Promise<void> {
  session.clear()
  await router.replace({ name: 'login' })
}

async function handleUnauthorized(): Promise<void> {
  session.clear('登录状态已失效，请重新登录。')
  await router.replace({ name: 'login' })
}
</script>

<template>
  <div class="admin-shell">
    <aside class="admin-rail">
      <RouterLink class="admin-brand" :to="{ name: 'admin-overview' }">
        <span class="admin-brand__seal" aria-hidden="true">管</span>
        <span>
          <strong>校园智答</strong>
          <small>管理控制台</small>
        </span>
      </RouterLink>

      <nav data-admin-navigation aria-label="后台管理导航">
        <p>总览</p>
        <RouterLink :to="{ name: 'admin-overview' }">
          <span aria-hidden="true">01</span>
          工作台
        </RouterLink>
        <p>资料管理</p>
        <RouterLink :to="{ name: 'admin-sources' }">
          <span aria-hidden="true">02</span>
          资料来源
        </RouterLink>
        <RouterLink :to="{ name: 'admin-imports' }">
          <span aria-hidden="true">03</span>
          本地导入
        </RouterLink>
        <RouterLink :to="{ name: 'admin-documents' }">
          <span aria-hidden="true">04</span>
          文档与索引
        </RouterLink>
        <p>治理</p>
        <RouterLink :to="{ name: 'admin-moderation' }">
          <span aria-hidden="true">05</span>
          内容审核
        </RouterLink>
        <RouterLink :to="{ name: 'admin-users' }">
          <span aria-hidden="true">06</span>
          用户与令牌
        </RouterLink>
        <p>运维</p>
        <RouterLink :to="{ name: 'admin-database' }">
          <span aria-hidden="true">07</span>
          数据库状态
        </RouterLink>
        <RouterLink :to="{ name: 'admin-backups' }">
          <span aria-hidden="true">08</span>
          数据库备份
        </RouterLink>
        <RouterLink :to="{ name: 'admin-models' }">
          <span aria-hidden="true">09</span>
          模型与版本
        </RouterLink>
        <RouterLink :to="{ name: 'admin-audit' }">
          <span aria-hidden="true">10</span>
          审计记录
        </RouterLink>
      </nav>

      <div class="admin-rail__footer">
        <RouterLink :to="{ name: 'chat' }">返回学生端</RouterLink>
        <button type="button" data-action="logout" @click="signOut">退出登录</button>
      </div>
    </aside>

    <div class="admin-stage">
      <header class="admin-topbar">
        <div>
          <p>ADMINISTRATION DESK</p>
          <h1>后台管理</h1>
        </div>
        <div class="admin-identity">
          <span>管理员</span>
          <strong>{{ currentUser?.displayName || currentUser?.username }}</strong>
        </div>
      </header>

      <main id="main-content" class="admin-content" tabindex="-1">
        <RouterView v-slot="{ Component }">
          <component :is="Component" @unauthorized="handleUnauthorized" />
        </RouterView>
      </main>
    </div>
  </div>
</template>

<style scoped>
.admin-shell {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 17.5rem minmax(0, 1fr);
  min-height: calc(100vh - 38px);
  background: var(--paper);
}

.admin-rail {
  display: flex;
  min-height: 100%;
  padding: 2rem 1.4rem 1.4rem;
  color: var(--paper);
  flex-direction: column;
  background: var(--green-950);
  border-right: 5px double var(--paper-deep);
}

.admin-brand {
  display: flex;
  gap: 0.8rem;
  align-items: center;
  color: inherit;
  text-decoration: none;
}

.admin-brand__seal {
  display: grid;
  width: 2.8rem;
  height: 2.8rem;
  place-items: center;
  color: var(--vermilion-soft);
  border: 2px solid currentColor;
  font-family: STKaiti, KaiTi, serif;
  font-size: 1.35rem;
  transform: rotate(-3deg);
}

.admin-brand > span:last-child {
  display: grid;
  gap: 0.2rem;
}

.admin-brand strong {
  font-family: STKaiti, KaiTi, serif;
  font-size: 1.35rem;
  letter-spacing: 0.08em;
}

.admin-brand small {
  color: #9eb0a8;
  font-size: 0.62rem;
  letter-spacing: 0.15em;
}

.admin-rail nav {
  display: grid;
  gap: 0.35rem;
  margin-top: 3rem;
}

.admin-rail nav p {
  margin: 0 0 0.55rem;
  color: #82978d;
  font-size: 0.62rem;
  letter-spacing: 0.18em;
}

.admin-rail nav a {
  display: flex;
  gap: 0.65rem;
  padding: 0.8rem 0.75rem;
  color: #cbd7d0;
  border-left: 3px solid transparent;
  text-decoration: none;
}

.admin-rail nav a[aria-current="page"] {
  color: var(--paper);
  background: rgba(242, 234, 214, 0.08);
  border-left-color: var(--vermilion-soft);
}

.admin-rail nav a span {
  color: var(--vermilion-soft);
  font-family: Georgia, serif;
  font-size: 0.68rem;
}

.admin-rail__footer {
  display: grid;
  gap: 0.8rem;
  margin-top: auto;
  padding-top: 1.2rem;
  border-top: 1px solid rgba(242, 234, 214, 0.2);
}

.admin-rail__footer a,
.admin-rail__footer button {
  width: fit-content;
  padding: 0;
  color: #b8c7bf;
  background: transparent;
  border: 0;
  border-bottom: 1px dotted currentColor;
  cursor: pointer;
  font-size: 0.72rem;
  text-decoration: none;
}

.admin-stage {
  min-width: 0;
}

.admin-topbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1rem;
  min-height: 7.25rem;
  padding: 1.25rem clamp(1.5rem, 4vw, 3.8rem);
  background: var(--paper-light);
  border-bottom: 4px double var(--rule);
}

.admin-topbar p,
.admin-topbar h1 {
  margin: 0;
}

.admin-topbar p {
  color: var(--vermilion);
  font-family: Georgia, serif;
  font-size: 0.62rem;
  letter-spacing: 0.18em;
}

.admin-topbar h1 {
  margin-top: 0.35rem;
  color: var(--green-950);
  font-family: STKaiti, KaiTi, serif;
  font-size: 2rem;
  font-weight: 600;
}

.admin-identity {
  display: grid;
  gap: 0.25rem;
  text-align: right;
}

.admin-identity span {
  color: var(--ink-soft);
  font-size: 0.64rem;
  letter-spacing: 0.12em;
}

.admin-identity strong {
  color: var(--green-900);
  font-size: 0.82rem;
}

.admin-content {
  min-height: calc(100vh - 10rem);
  outline: none;
}

@media (max-width: 760px) {
  .admin-shell {
    grid-template-columns: 1fr;
  }

  .admin-rail {
    min-height: auto;
    padding: 1rem;
    border-right: 0;
    border-bottom: 4px double var(--paper-deep);
  }

  .admin-rail nav {
    margin-top: 1rem;
  }

  .admin-rail__footer {
    grid-template-columns: repeat(2, auto);
    justify-content: space-between;
    margin-top: 1rem;
  }
}
</style>
