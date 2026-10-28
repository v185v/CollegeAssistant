import { ref, shallowRef } from 'vue'

import {
  ApiClientError,
  apiRequest,
  clearStoredToken,
  getStoredToken,
} from '../api/client'
import type { UserSummaryDto } from '../api/types'

const currentUser = shallowRef<UserSummaryDto | null>(null)
const sessionReady = ref(false)
const sessionNotice = ref('')
const authorizationError = ref('')
let pendingRestore: Promise<void> | null = null

async function restoreCurrentUser(): Promise<void> {
  authorizationError.value = ''
  const token = getStoredToken()
  if (!token) {
    currentUser.value = null
    sessionReady.value = true
    return
  }

  try {
    currentUser.value = await apiRequest<UserSummaryDto>('/auth/me')
    sessionNotice.value = ''
  } catch (error) {
    if (error instanceof ApiClientError && error.status === 401) {
      clearStoredToken()
      currentUser.value = null
      sessionNotice.value = '登录状态已失效，请重新登录。'
      return
    }
    if (error instanceof ApiClientError && error.status === 403) {
      authorizationError.value = error.message
    }
    throw error
  } finally {
    sessionReady.value = true
  }
}

export const session = {
  user: currentUser,
  ready: sessionReady,
  notice: sessionNotice,
  authorizationError,

  restore(): Promise<void> {
    if (!pendingRestore) {
      pendingRestore = restoreCurrentUser().finally(() => {
        pendingRestore = null
      })
    }
    return pendingRestore
  },

  establish(user: UserSummaryDto): void {
    currentUser.value = user
    sessionReady.value = true
    sessionNotice.value = ''
    authorizationError.value = ''
  },

  clear(notice = ''): void {
    clearStoredToken()
    currentUser.value = null
    sessionReady.value = true
    sessionNotice.value = notice
    authorizationError.value = ''
  },
}
