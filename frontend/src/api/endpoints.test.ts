import { beforeEach, describe, expect, it, vi } from 'vitest'

import { apiRequest } from './client'
import {
  createUgcPost,
  getChatSession,
  getProfile,
  listChatSessions,
  listMyUgc,
  login,
  register,
  updateProfile,
} from './endpoints'

vi.mock('./client', async (importOriginal) => {
  const original = await importOriginal<typeof import('./client')>()
  return {
    ...original,
    apiRequest: vi.fn(),
  }
})

const apiRequestMock = vi.mocked(apiRequest)

describe('JSON API endpoints', () => {
  beforeEach(() => {
    apiRequestMock.mockResolvedValue(null)
  })

  it('posts login and registration payloads as JSON', async () => {
    await login({ username: 'lin', password: 'secret123' })
    await register({
      username: 'zhou',
      password: 'secret123',
      displayName: '周同学',
    })

    expect(apiRequestMock).toHaveBeenNthCalledWith(
      1,
      '/auth/login',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({ username: 'lin', password: 'secret123' }),
      }),
    )
    expect(new Headers(apiRequestMock.mock.calls[0]?.[1]?.headers).get(
      'Content-Type',
    )).toBe('application/json')
    expect(apiRequestMock).toHaveBeenNthCalledWith(
      2,
      '/auth/register',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          username: 'zhou',
          password: 'secret123',
          displayName: '周同学',
        }),
      }),
    )
  })

  it('gets and updates the current profile with the contract payload', async () => {
    const payload = {
      major: null,
      grade: 2026,
      campus: '东校区',
      courseRecords: [
        { courseCode: 'CS101', courseName: '程序设计', term: null },
      ],
    }

    await getProfile()
    await updateProfile(payload)

    expect(apiRequestMock).toHaveBeenNthCalledWith(1, '/profile')
    expect(apiRequestMock).toHaveBeenNthCalledWith(
      2,
      '/profile',
      expect.objectContaining({
        method: 'PUT',
        body: JSON.stringify(payload),
      }),
    )
  })

  it('creates UGC and lists only the signed-in author submissions', async () => {
    const payload = {
      title: '选课经验',
      content: '先看培养方案。',
      tags: ['选课'],
      relatedCourse: null,
      relatedLocation: '教务处',
    }

    await createUgcPost(payload)
    await listMyUgc(2, 12)

    expect(apiRequestMock).toHaveBeenNthCalledWith(
      1,
      '/ugc',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify(payload),
      }),
    )
    expect(apiRequestMock).toHaveBeenNthCalledWith(
      2,
      '/ugc?mine=true&page=2&size=12',
    )
  })

  it('lists paged chat sessions and loads one session detail', async () => {
    await listChatSessions(3, 8)
    await getChatSession(42)

    expect(apiRequestMock).toHaveBeenNthCalledWith(
      1,
      '/chat/sessions?page=3&size=8',
    )
    expect(apiRequestMock).toHaveBeenNthCalledWith(
      2,
      '/chat/sessions/42',
    )
  })
})
