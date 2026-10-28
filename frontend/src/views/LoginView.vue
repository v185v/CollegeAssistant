<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { ApiClientError, setStoredToken } from '../api/client'
import { login, register } from '../api/endpoints'
import type { UserSummaryDto } from '../api/types'
import { session } from '../auth/session'

type AuthMode = 'login' | 'register'

const MAX_PASSWORD_UTF8_BYTES = 72
const utf8Length = (value: string): number => new TextEncoder().encode(value).byteLength

const props = withDefaults(defineProps<{
  sessionNotice?: string
}>(), {
  sessionNotice: '',
})

const route = useRoute()
const router = useRouter()

const mode = ref<AuthMode>('login')
const username = ref('')
const password = ref('')
const displayName = ref('')
const submitting = ref(false)
const errorMessage = ref('')

const isRegistering = computed(() => mode.value === 'register')
const visibleSessionNotice = computed(
  () => props.sessionNotice || session.notice.value,
)

function chooseMode(nextMode: AuthMode): void {
  mode.value = nextMode
  errorMessage.value = ''
}

async function submit(): Promise<void> {
  if (submitting.value) {
    return
  }
  const normalizedUsername = username.value.trim()
  const normalizedDisplayName = displayName.value.trim()
  const validationMessage = validateCredentials(
    normalizedUsername,
    password.value,
    normalizedDisplayName,
  )
  if (validationMessage) {
    errorMessage.value = validationMessage
    return
  }
  submitting.value = true
  errorMessage.value = ''

  try {
    const response = isRegistering.value
      ? await register({
          username: normalizedUsername,
          password: password.value,
          displayName: normalizedDisplayName || null,
        })
      : await login({
          username: normalizedUsername,
          password: password.value,
        })

    if (!response) {
      errorMessage.value = '登录响应缺少凭证，请重试'
      return
    }
    if (!setStoredToken(response.accessToken)) {
      errorMessage.value = '浏览器无法保存登录状态，请检查隐私设置后重试'
      return
    }
    session.establish(response.user)
    await router.replace(destinationFor(response.user))
  } catch (error) {
    errorMessage.value = error instanceof ApiClientError
      ? error.message
      : '登录暂时无法完成，请稍后重试'
  } finally {
    submitting.value = false
  }
}

function destinationFor(user: UserSummaryDto): string {
  const requestedRedirect = route.query.redirect
  if (
    typeof requestedRedirect === 'string'
    && requestedRedirect.startsWith('/')
    && !requestedRedirect.startsWith('//')
  ) {
    const resolved = router.resolve(requestedRedirect)
    if (resolved.matched.length > 0 && resolved.name !== 'login') {
      return requestedRedirect
    }
  }
  return user.role === 'ADMIN' ? '/admin' : '/chat'
}

function validateCredentials(
  normalizedUsername: string,
  rawPassword: string,
  normalizedDisplayName: string,
): string | null {
  if (!normalizedUsername || !rawPassword.trim()) {
    return '用户名和密码不能为空'
  }
  if (utf8Length(rawPassword) > MAX_PASSWORD_UTF8_BYTES) {
    return '密码不能超过 72 个 UTF-8 字节'
  }
  if (!isRegistering.value) {
    return null
  }
  if (!/^[A-Za-z0-9_-]{3,32}$/.test(normalizedUsername)) {
    return '用户名需为 3–32 位，仅可使用字母、数字、下划线和连字符'
  }
  if (rawPassword.length < 8) {
    return '密码不能少于 8 位'
  }
  if (isRegistering.value && normalizedDisplayName.length > 32) {
    return '显示名称不能超过 32 位'
  }
  return null
}
</script>

<template>
  <main id="main-content" class="login-view" tabindex="-1">
    <section class="login-intro" aria-labelledby="login-intro-title">
      <p class="section-folio">校务公报 · 身份核验</p>
      <h1 id="login-intro-title">
        每一条校园答复，
        <span>先从你的身份开始。</span>
      </h1>
      <p>
        登录后可查阅有出处的问答、补充个人学业画像，并管理你提交的校园经验。
      </p>
      <dl class="login-ledger" aria-label="服务说明">
        <div>
          <dt>官方资料</dt>
          <dd>公文标注</dd>
        </div>
        <div>
          <dt>同学经验</dt>
          <dd>便签标注</dd>
        </div>
        <div>
          <dt>回答原则</dt>
          <dd>无依据则不下结论</dd>
        </div>
      </dl>
    </section>

    <section class="auth-sheet" :aria-labelledby="`${mode}-title`">
      <div class="auth-sheet__stamp" aria-hidden="true">核验</div>
      <p class="auth-sheet__overline">ACCESS DESK · 01</p>
      <h2 :id="`${mode}-title`">
        {{ isRegistering ? '登记新账号' : '登录校园智答' }}
      </h2>

      <p v-if="visibleSessionNotice" class="form-message form-message--error" role="alert">
        {{ visibleSessionNotice }}
      </p>

      <div class="auth-mode" aria-label="选择登录或注册">
        <button
          type="button"
          name="auth-mode"
          value="login"
          :aria-pressed="mode === 'login'"
          @click="chooseMode('login')"
        >
          登录
        </button>
        <button
          type="button"
          name="auth-mode"
          value="register"
          :aria-pressed="mode === 'register'"
          @click="chooseMode('register')"
        >
          注册
        </button>
      </div>

      <form class="auth-form" @submit.prevent="submit">
        <label for="auth-username">用户名</label>
        <input
          id="auth-username"
          v-model="username"
          name="username"
          autocomplete="username"
          required
          :minlength="isRegistering ? 3 : undefined"
          :maxlength="isRegistering ? 32 : undefined"
          :pattern="isRegistering ? '[A-Za-z0-9_-]+' : undefined"
        >

        <label for="auth-password">密码</label>
        <input
          id="auth-password"
          v-model="password"
          name="password"
          type="password"
          :autocomplete="isRegistering ? 'new-password' : 'current-password'"
          required
          :minlength="isRegistering ? 8 : undefined"
          :maxlength="isRegistering ? 72 : undefined"
        >

        <template v-if="isRegistering">
          <label for="auth-display-name">
            显示名称 <span>选填</span>
          </label>
          <input
            id="auth-display-name"
            v-model="displayName"
            name="displayName"
            autocomplete="name"
            minlength="1"
            maxlength="32"
          >
        </template>

        <p v-if="errorMessage" class="form-message form-message--error" role="alert">
          {{ errorMessage }}
        </p>
        <p v-else class="auth-form__note">
          凭证仅用于本机登录状态，不会展示在页面或日志中。
        </p>

        <button class="primary-action" type="submit" :disabled="submitting">
          <span>{{ submitting ? '正在核验…' : (isRegistering ? '创建账号' : '进入编辑台') }}</span>
          <span aria-hidden="true">→</span>
        </button>
      </form>
    </section>
  </main>
</template>

<style scoped>
.login-view {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(320px, 0.72fr);
  gap: clamp(2.5rem, 7vw, 7rem);
  align-items: center;
  min-height: calc(100vh - 8rem);
  padding: clamp(3rem, 8vw, 7rem) clamp(1.5rem, 7vw, 6rem);
}

.login-intro {
  max-width: 46rem;
}

.section-folio,
.auth-sheet__overline {
  margin: 0 0 1.1rem;
  color: var(--vermilion);
  font-family: Georgia, "Times New Roman", serif;
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.2em;
  text-transform: uppercase;
}

.login-intro h1 {
  margin: 0;
  color: var(--green-950);
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  font-size: clamp(2.8rem, 6vw, 5.6rem);
  font-weight: 600;
  line-height: 1.06;
}

.login-intro h1 span {
  display: block;
  color: var(--vermilion);
}

.login-intro > p:not(.section-folio) {
  max-width: 37rem;
  margin: 2rem 0 0;
  color: var(--ink-soft);
  font-size: 1.05rem;
  line-height: 1.9;
}

.login-ledger {
  margin: 2.2rem 0 0;
  border-top: 3px double var(--rule);
}

.login-ledger div {
  display: grid;
  grid-template-columns: 7rem 1fr;
  gap: 1rem;
  padding: 0.72rem 0;
  border-bottom: 1px dotted var(--rule);
}

.login-ledger dt,
.login-ledger dd {
  margin: 0;
  font-size: 0.82rem;
}

.login-ledger dt {
  color: var(--ink-soft);
}

.login-ledger dd {
  color: var(--green-800);
  font-weight: 700;
}

.auth-sheet {
  position: relative;
  padding: clamp(1.6rem, 4vw, 2.6rem);
  background: rgba(248, 242, 226, 0.88);
  border: 1px solid var(--green-800);
  box-shadow: 12px 12px 0 var(--paper-deep);
}

.auth-sheet::before {
  position: absolute;
  inset: 0.45rem;
  border: 1px solid rgba(18, 59, 50, 0.18);
  pointer-events: none;
  content: "";
}

.auth-sheet__stamp {
  position: absolute;
  top: 1.2rem;
  right: 1.25rem;
  display: grid;
  width: 3rem;
  height: 3rem;
  place-items: center;
  color: var(--vermilion);
  border: 2px solid var(--vermilion);
  border-radius: 50%;
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  font-size: 0.8rem;
  transform: rotate(8deg);
}

.auth-sheet h2 {
  position: relative;
  margin: 0;
  color: var(--green-950);
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  font-size: 2rem;
}

.auth-mode {
  position: relative;
  display: grid;
  grid-template-columns: 1fr 1fr;
  margin-top: 1.6rem;
  border-bottom: 1px solid var(--rule);
}

.auth-mode button {
  padding: 0.72rem;
  color: var(--ink-soft);
  background: transparent;
  border: 0;
  border-bottom: 3px solid transparent;
  cursor: pointer;
}

.auth-mode button[aria-pressed="true"] {
  color: var(--green-900);
  border-bottom-color: var(--vermilion);
  font-weight: 800;
}

.auth-form {
  position: relative;
  display: grid;
  gap: 0.55rem;
  margin-top: 1.4rem;
}

.auth-form label {
  margin-top: 0.55rem;
  color: var(--green-800);
  font-size: 0.82rem;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.auth-form label span {
  color: var(--ink-soft);
  font-size: 0.72rem;
  font-weight: 400;
}

.auth-form input {
  width: 100%;
  padding: 0.75rem 0.15rem 0.65rem;
  color: var(--ink);
  background: transparent;
  border: 0;
  border-bottom: 1px solid var(--green-700);
  border-radius: 0;
  outline: none;
}

.auth-form input:focus-visible {
  border-bottom-color: var(--vermilion);
  box-shadow: 0 2px 0 var(--vermilion);
}

.auth-form__note,
.form-message {
  min-height: 2.7rem;
  margin: 0.8rem 0 0;
  font-size: 0.75rem;
  line-height: 1.55;
}

.auth-form__note {
  color: var(--ink-soft);
}

.form-message--error {
  color: #842f25;
}

.primary-action {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  margin-top: 0.4rem;
  padding: 0.9rem 1rem;
  color: var(--paper);
  background: var(--green-900);
  border: 1px solid var(--green-900);
  cursor: pointer;
}

.primary-action:hover:not(:disabled) {
  background: var(--vermilion);
  border-color: var(--vermilion);
}

.primary-action:disabled {
  cursor: wait;
  opacity: 0.68;
}

@media (max-width: 760px) {
  .login-view {
    grid-template-columns: 1fr;
    min-height: auto;
    padding: 3.5rem 1.4rem 5rem;
  }

  .login-intro h1 {
    font-size: clamp(2.5rem, 13vw, 4.3rem);
  }

  .auth-sheet {
    box-shadow: 7px 7px 0 var(--paper-deep);
  }
}
</style>
