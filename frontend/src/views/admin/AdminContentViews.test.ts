// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiClientError } from '../../api/client'

const api = vi.hoisted(() => ({
  deleteDocument: vi.fn(),
  getImportRun: vi.fn(),
  listDocuments: vi.fn(),
  listImportRuns: vi.fn(),
  listSources: vi.fn(),
  reindexDocument: vi.fn(),
  retryImportRun: vi.fn(),
  uploadDocuments: vi.fn(),
}))

vi.mock('../../api/adminEndpoints', () => api)

import AdminDocumentsView from './AdminDocumentsView.vue'
import AdminImportsView from './AdminImportsView.vue'
import AdminSourcesView from './AdminSourcesView.vue'

const emptyPage = { items: [], total: 0, page: 1, size: 20, pages: 1 }

describe('admin content views', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    api.listSources.mockResolvedValue(emptyPage)
    api.listImportRuns.mockResolvedValue({
      ...emptyPage,
      items: [
        {
          id: 21,
          sourceId: null,
          status: 'RUNNING',
          added: 0,
          updated: 0,
          skipped: 0,
          failed: 0,
          errorDetail: null,
          startedAt: '2026-07-26T08:00:00Z',
          finishedAt: null,
        },
      ],
    })
    api.listDocuments.mockResolvedValue(emptyPage)
    api.uploadDocuments.mockResolvedValue({ id: 21, items: [] })
    api.retryImportRun.mockResolvedValue({ id: 22, items: [] })
    api.reindexDocument.mockResolvedValue({ id: 31 })
    api.deleteDocument.mockResolvedValue({ id: 32 })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('passes every selected local file to the import endpoint and stops polling on unmount', async () => {
    const wrapper = mount(AdminImportsView)
    await flushPromises()

    const input = wrapper.get('[data-test="upload-files"]')
    const files = [
      new File(['first'], 'first.pdf', { type: 'application/pdf' }),
      new File(['second'], 'second.txt', { type: 'text/plain' }),
    ]
    Object.defineProperty(input.element, 'files', {
      configurable: true,
      value: files,
    })
    await input.trigger('change')
    await wrapper.get('[data-test="upload-submit"]').trigger('click')
    await flushPromises()

    expect(api.uploadDocuments).toHaveBeenCalledWith(
      expect.objectContaining({
        items: expect.arrayContaining([
          expect.objectContaining({ displayTitle: 'first' }),
          expect.objectContaining({ displayTitle: 'second' }),
        ]),
      }),
      files,
    )

    const requestsBeforeUnmount = api.listImportRuns.mock.calls.length
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(15_000)
    expect(api.listImportRuns).toHaveBeenCalledTimes(requestsBeforeUnmount)
  })

  it('emits unauthorized for 401 but leaves an existing 403 session in place', async () => {
    api.listSources.mockRejectedValueOnce(
      new ApiClientError('登录状态已失效', null, 401),
    )
    const unauthorized = mount(AdminSourcesView)
    await flushPromises()
    expect(unauthorized.emitted('unauthorized')).toHaveLength(1)

    api.listSources.mockRejectedValueOnce(
      new ApiClientError('没有资料源管理权限', 40301, 403),
    )
    const forbidden = mount(AdminSourcesView)
    await flushPromises()
    expect(forbidden.emitted('unauthorized')).toBeUndefined()
    expect(forbidden.get('[role="alert"]').text()).toContain('没有资料源管理权限')
  })

  it('requires typed confirmation before deleting an internal document', async () => {
    api.listDocuments.mockResolvedValueOnce({
      ...emptyPage,
      items: [
        {
          id: 42,
          sourceId: null,
          title: '校历原件',
          sourceUrl: null,
          sourceOrg: null,
          publishedAt: null,
          contentHash: 'hash',
          referenceType: 'INTERNAL',
          externalSourceUrl: null,
          originalFilename: 'calendar.pdf',
          documentStatus: 'ACTIVE',
          downloadable: true,
          ingestedAt: '2026-07-26T08:00:00Z',
          lastSeenAt: null,
          lastCheckedAt: null,
          degree: null,
          college: null,
          campus: null,
          gradeRange: null,
        },
      ],
    })
    const wrapper = mount(AdminDocumentsView)
    await flushPromises()

    await wrapper.get('[data-test="delete-document-42"]').trigger('click')
    expect(api.deleteDocument).not.toHaveBeenCalled()
    const confirmation = document.querySelector<HTMLInputElement>(
      '[data-test="confirmation-input"]',
    )
    const confirmAction = document.querySelector<HTMLButtonElement>(
      '[data-test="confirm-action"]',
    )
    expect(confirmation).not.toBeNull()
    expect(confirmAction).not.toBeNull()
    confirmation!.value = '校历原件'
    confirmation!.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
    confirmAction!.click()
    await flushPromises()

    expect(api.deleteDocument).toHaveBeenCalledWith(42)
  })
})
