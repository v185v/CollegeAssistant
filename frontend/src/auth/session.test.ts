// @vitest-environment happy-dom

import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiClientError } from '../api/client'
import type { UserSummaryDto } from '../api/types'
import { session } from './session'

const mocks = vi.hoisted(() => ({
  apiRequest: vi.fn(),
  clearStoredToken: vi.fn(),
  getStoredToken: vi.fn(),
}))

vi.mock('../api/client', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/client')>()
  return {
    ...original,
    apiRequest: mocks.apiRequest,
    clearStoredToken: mocks.clearStoredToken,
    getStoredToken: mocks.getStoredToken,
  }
})

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

describe('role-aware session', () => {
  beforeEach(() => {
    mocks.apiRequest.mockReset()
    mocks.clearStoredToken.mockReset()
    mocks.getStoredToken.mockReset()
    mocks.getStoredToken.mockReturnValue(null)
    mocks.clearStoredToken.mockReturnValue(true)
    session.user.value = null
    session.ready.value = false
    session.notice.value = ''
    session.authorizationError.value = ''
  })

  it('becomes ready without requesting the server when no token exists', async () => {
    await session.restore()

    expect(mocks.apiRequest).not.toHaveBeenCalled()
    expect(session.user.value).toBeNull()
    expect(session.ready.value).toBe(true)
  })

  it('restores the current user through /api/auth/me', async () => {
    mocks.getStoredToken.mockReturnValue('signed-token')
    mocks.apiRequest.mockResolvedValue(student)

    await session.restore()

    expect(mocks.apiRequest).toHaveBeenCalledWith('/auth/me')
    expect(session.user.value).toEqual(student)
    expect(session.ready.value).toBe(true)
  })

  it('clears the token and user when restoration receives 401', async () => {
    mocks.getStoredToken.mockReturnValue('expired-token')
    session.user.value = student
    mocks.apiRequest.mockRejectedValue(
      new ApiClientError('登录状态已失效', 40103, 401),
    )

    await session.restore()

    expect(mocks.clearStoredToken).toHaveBeenCalledTimes(1)
    expect(session.user.value).toBeNull()
    expect(session.notice.value).toContain('登录状态已失效')
    expect(session.ready.value).toBe(true)
  })

  it('preserves the token and current user when restoration receives 403', async () => {
    mocks.getStoredToken.mockReturnValue('admin-token')
    session.user.value = administrator
    mocks.apiRequest.mockRejectedValue(
      new ApiClientError('没有权限读取此资源', 40301, 403),
    )

    await expect(session.restore()).rejects.toMatchObject({ status: 403 })

    expect(mocks.clearStoredToken).not.toHaveBeenCalled()
    expect(session.user.value).toEqual(administrator)
    expect(session.authorizationError.value).toBe('没有权限读取此资源')
    expect(session.ready.value).toBe(true)
  })

  it('establishes a login response locally and clears it explicitly', () => {
    session.establish(administrator)

    expect(session.user.value).toEqual(administrator)
    expect(session.ready.value).toBe(true)

    session.clear('已安全退出。')

    expect(mocks.clearStoredToken).toHaveBeenCalledTimes(1)
    expect(session.user.value).toBeNull()
    expect(session.notice.value).toBe('已安全退出。')
  })
})
