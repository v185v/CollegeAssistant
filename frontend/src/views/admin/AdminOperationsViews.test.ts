// @vitest-environment happy-dom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiClientError } from '../../api/client'

const api = vi.hoisted(() => ({
  activateEmbeddingRebuild: vi.fn(),
  createBackup: vi.fn(),
  createModelConfiguration: vi.fn(),
  deleteBackup: vi.fn(),
  discardEmbeddingRebuild: vi.fn(),
  downloadBackup: vi.fn(),
  getConsistencyScan: vi.fn(),
  getDatabaseOverview: vi.fn(),
  invalidateUserTokens: vi.fn(),
  listAdminUsers: vi.fn(),
  listAuditLogs: vi.fn(),
  listBackups: vi.fn(),
  listEmbeddingRebuilds: vi.fn(),
  listModelConfigurations: vi.fn(),
  listUgcPosts: vi.fn(),
  listUgcReports: vi.fn(),
  removeUgcPost: vi.fn(),
  reviewUgcPost: vi.fn(),
  setUserEnabled: vi.fn(),
  startConsistencyScan: vi.fn(),
  startEmbeddingRebuild: vi.fn(),
  testModelConfiguration: vi.fn(),
  updateUgcReport: vi.fn(),
  updateUserRole: vi.fn(),
}))

vi.mock('../../api/adminEndpoints', () => api)

import AdminBackupsView from './AdminBackupsView.vue'
import AdminModelsView from './AdminModelsView.vue'
import AdminModerationView from './AdminModerationView.vue'
import AdminUsersView from './AdminUsersView.vue'

const emptyPage = { items: [], total: 0, page: 1, size: 20, pages: 1 }

const databaseOverview = {
  databaseVersion: 'PostgreSQL 16',
  pgvectorVersion: '0.8',
  flywayVersion: '14',
  databaseSizeBytes: 1_024,
  indexSizeBytes: 512,
  tableCounts: {},
  activeConnections: 2,
  storageAvailable: true,
  modelStatus: { provider: 'openai', model: 'gpt-test', configured: true, dimension: 1536 },
  recentIngestionFailures: [],
}

describe('admin operations views', () => {
  beforeEach(() => {
    api.getDatabaseOverview.mockResolvedValue(databaseOverview)
    api.listUgcPosts.mockResolvedValue(emptyPage)
    api.listUgcReports.mockResolvedValue(emptyPage)
    api.reviewUgcPost.mockResolvedValue({})
    api.removeUgcPost.mockResolvedValue({})
    api.updateUgcReport.mockResolvedValue({})
    api.listAdminUsers.mockResolvedValue(emptyPage)
    api.updateUserRole.mockResolvedValue({})
    api.setUserEnabled.mockResolvedValue({})
    api.invalidateUserTokens.mockResolvedValue({})
    api.listBackups.mockResolvedValue(emptyPage)
    api.createBackup.mockResolvedValue({})
    api.downloadBackup.mockResolvedValue({ blob: new Blob(['backup']), filename: 'backup.dump' })
    api.deleteBackup.mockResolvedValue(undefined)
    api.listModelConfigurations.mockResolvedValue([])
    api.listEmbeddingRebuilds.mockResolvedValue([])
    api.testModelConfiguration.mockResolvedValue(undefined)
    api.createModelConfiguration.mockResolvedValue({
      id: 3,
      configurationKey: 'embedding-v2',
      kind: 'EMBEDDING',
      provider: 'openai',
      model: 'text-embedding-3-small',
      baseUrl: null,
      temperature: null,
      dimension: 1536,
      status: 'PENDING',
      bootstrap: false,
      secretConfigured: true,
      maskedApiKey: 'sk-***1234',
      available: true,
      restartRequired: true,
      failureReason: null,
      activatedAt: null,
      createdAt: '2026-07-26T08:00:00Z',
      updatedAt: '2026-07-26T08:00:00Z',
    })
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('reviews pending UGC and keeps report controls available to administrators', async () => {
    api.listUgcPosts.mockResolvedValueOnce({
      ...emptyPage,
      items: [{
        id: 8,
        authorId: 4,
        authorName: '同学',
        title: '选课经验',
        content: '提前查看课程容量。',
        tags: [],
        relatedCourse: null,
        relatedLocation: null,
        status: 'PENDING',
        likes: 0,
        reports: 0,
        rejectReason: null,
        createdAt: '2026-07-26T08:00:00Z',
        reviewedAt: null,
      }],
    })
    const wrapper = mount(AdminModerationView)
    await flushPromises()

    await wrapper.get('[data-test="approve-post-8"]').trigger('click')
    await flushPromises()

    expect(api.reviewUgcPost).toHaveBeenCalledWith(8, { decision: 'APPROVE' })
  })

  it('surfaces the backend last-admin conflict instead of silently changing a user role', async () => {
    api.listAdminUsers.mockResolvedValueOnce({
      ...emptyPage,
      items: [{
        id: 7,
        username: 'only-admin',
        displayName: '唯一管理员',
        role: 'ADMIN',
        enabled: true,
        tokenVersion: 1,
        createdAt: '2026-07-26T08:00:00Z',
      }],
    })
    api.updateUserRole.mockRejectedValueOnce(
      new ApiClientError('At least one administrator is required', 40905, 200),
    )
    const wrapper = mount(AdminUsersView)
    await flushPromises()

    await wrapper.get('[data-test="role-user-7"]').setValue('STUDENT')
    await flushPromises()

    expect(api.updateUserRole).toHaveBeenCalledWith(7, 'STUDENT')
    expect(wrapper.get('[role="alert"]').text()).toContain('至少保留一名管理员')
  })

  it('disables backup creation when the server reports unavailable storage and downloads validated archives with authentication', async () => {
    api.getDatabaseOverview.mockResolvedValueOnce({
      ...databaseOverview,
      storageAvailable: false,
    })
    const unavailable = mount(AdminBackupsView)
    await flushPromises()
    expect(unavailable.get('[data-test="create-backup"]').attributes('disabled')).toBeDefined()
    unavailable.unmount()

    api.listBackups.mockResolvedValueOnce({
      ...emptyPage,
      items: [{
        id: 12,
        jobId: 9,
        status: 'VALIDATED',
        format: 'custom',
        sha256: 'hash',
        sizeBytes: 100,
        requestedBy: 1,
        startedAt: null,
        finishedAt: null,
        retentionDays: 7,
        expiresAt: null,
        errorSummary: null,
        createdAt: '2026-07-26T08:00:00Z',
        updatedAt: '2026-07-26T08:00:00Z',
        downloadable: true,
      }],
    })
    const available = mount(AdminBackupsView)
    await flushPromises()
    await available.get('[data-test="download-backup-12"]').trigger('click')
    await flushPromises()
    expect(api.downloadBackup).toHaveBeenCalledWith(12)
  })

  it('never renders a raw model secret and exposes the pending restart state after saving', async () => {
    const rawApiKey = 'sk-live-should-never-render'
    const wrapper = mount(AdminModelsView)
    await flushPromises()

    await wrapper.get('[data-test="model-name"]').setValue('text-embedding-3-small')
    await wrapper.get('[data-test="model-api-key"]').setValue(rawApiKey)
    await wrapper.get('[data-test="save-model"]').trigger('click')
    await flushPromises()

    expect(api.createModelConfiguration).toHaveBeenCalledWith(
      expect.objectContaining({ apiKey: rawApiKey }),
    )
    expect(wrapper.text()).toContain('等待重启')
    expect(wrapper.text()).not.toContain(rawApiKey)
    expect(wrapper.find('[data-test="activate-rebuild-3"]').attributes('disabled')).toBeDefined()
  })
})
