// @vitest-environment happy-dom

import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'

import { session } from './auth/session'
import type { UserSummaryDto } from './api/types'
import { createAppRouter } from './router'

const student: UserSummaryDto = {
  id: 7,
  username: 'lin',
  displayName: '林同学',
  role: 'STUDENT',
}

const administrator: UserSummaryDto = {
  id: 1,
  username: 'admin',
  displayName: '值班管理员',
  role: 'ADMIN',
}

describe('application router guards', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    session.user.value = null
    session.ready.value = true
    session.notice.value = ''
    session.authorizationError.value = ''
  })

  it('redirects anonymous users to login and retains the intended route', async () => {
    const router = createAppRouter(createMemoryHistory())

    await router.push('/profile')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/profile')
  })

  it('redirects a student away from admin routes', async () => {
    session.user.value = student
    const router = createAppRouter(createMemoryHistory())

    await router.push('/admin')
    await router.isReady()

    expect(router.currentRoute.value.fullPath).toBe('/chat')
  })

  it('allows administrators to enter the nested admin overview route', async () => {
    session.user.value = administrator
    const router = createAppRouter(createMemoryHistory())

    await router.push('/admin')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('admin-overview')
    expect(router.currentRoute.value.matched.map((route) => route.name))
      .toEqual(['admin', 'admin-overview'])
  })

  it('waits for session restoration before deciding a protected route', async () => {
    session.ready.value = false
    const restore = vi.spyOn(session, 'restore').mockImplementation(async () => {
      await Promise.resolve()
      session.user.value = student
      session.ready.value = true
    })
    const router = createAppRouter(createMemoryHistory())

    await router.push('/ugc')
    await router.isReady()

    expect(restore).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.fullPath).toBe('/ugc')
  })

  it('does not discard an established administrator after a 403 restore error', async () => {
    session.user.value = administrator
    session.ready.value = false
    vi.spyOn(session, 'restore').mockImplementation(async () => {
      session.ready.value = true
      session.authorizationError.value = '没有权限读取此资源'
      throw new Error('forbidden')
    })
    const router = createAppRouter(createMemoryHistory())

    await router.push('/admin')
    await router.isReady()

    expect(session.user.value).toEqual(administrator)
    expect(session.authorizationError.value).toContain('没有权限')
    expect(router.currentRoute.value.fullPath).toBe('/admin')
  })
})
