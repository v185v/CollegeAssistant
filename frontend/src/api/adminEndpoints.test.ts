import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  activateEmbeddingRebuild,
  createBackup,
  createModelConfiguration,
  createSource,
  deleteBackup,
  deleteDocument,
  deleteSource,
  discardEmbeddingRebuild,
  downloadBackup,
  downloadDocument,
  getAdminJob,
  getAdminOverview,
  getConsistencyScan,
  getDatabaseOverview,
  getDocument,
  getImportRun,
  getIngestRun,
  getSource,
  invalidateUserTokens,
  listAdminUsers,
  listAuditLogs,
  listBackups,
  listDocuments,
  listEmbeddingRebuilds,
  listImportRuns,
  listIngestRuns,
  listModelConfigurations,
  listSources,
  listUgcPosts,
  listUgcReports,
  previewSource,
  reindexDocument,
  removeUgcPost,
  retryAdminJob,
  retryImportRun,
  retryIngestRun,
  reviewUgcPost,
  runSourceIngest,
  setSourceEnabled,
  setUserEnabled,
  startConsistencyScan,
  startEmbeddingRebuild,
  testModelConfiguration,
  testSource,
  updateSource,
  updateUgcReport,
  updateUserRole,
  uploadDocuments,
  validateBackup,
} from './adminEndpoints'
import type { SourceRequest } from './adminTypes'

const sourceRequest: SourceRequest = {
  name: '教务通知',
  type: 'OFFICIAL',
  connectorType: 'SINGLE_URL',
  baseUrl: 'https://example.edu/notices',
  scheduleType: 'MANUAL',
}

type EndpointCase = {
  name: string
  method: string
  path: string
  invoke: () => Promise<unknown>
}

describe('admin API endpoint wrappers', () => {
  const fetchMock = vi.fn<typeof fetch>()

  beforeEach(() => {
    fetchMock.mockImplementation(async () =>
      Response.json({ code: 0, message: 'ok', data: {} }),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('localStorage', { getItem: () => 'admin-token' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  const endpointCases: EndpointCase[] = [
    {
      name: 'lists sources',
      method: 'GET',
      path: '/api/admin/sources?page=2&size=10',
      invoke: () => listSources({ page: 2, size: 10 }),
    },
    {
      name: 'creates a source',
      method: 'POST',
      path: '/api/admin/sources',
      invoke: () => createSource(sourceRequest),
    },
    {
      name: 'tests a source definition',
      method: 'POST',
      path: '/api/admin/sources/test',
      invoke: () => testSource(sourceRequest),
    },
    {
      name: 'gets a source',
      method: 'GET',
      path: '/api/admin/sources/3',
      invoke: () => getSource(3),
    },
    {
      name: 'updates a source',
      method: 'PUT',
      path: '/api/admin/sources/3',
      invoke: () => updateSource(3, sourceRequest),
    },
    {
      name: 'sets source enabled state',
      method: 'PATCH',
      path: '/api/admin/sources/3/enabled',
      invoke: () => setSourceEnabled(3, true),
    },
    {
      name: 'previews a source',
      method: 'POST',
      path: '/api/admin/sources/3/preview',
      invoke: () => previewSource(3),
    },
    {
      name: 'deletes a source',
      method: 'DELETE',
      path: '/api/admin/sources/3',
      invoke: () => deleteSource(3),
    },
    {
      name: 'lists import runs',
      method: 'GET',
      path: '/api/admin/imports?page=2&size=10',
      invoke: () => listImportRuns({ page: 2, size: 10 }),
    },
    {
      name: 'gets an import run',
      method: 'GET',
      path: '/api/admin/imports/4',
      invoke: () => getImportRun(4),
    },
    {
      name: 'retries an import run',
      method: 'POST',
      path: '/api/admin/imports/4/retry',
      invoke: () => retryImportRun(4),
    },
    {
      name: 'lists documents with filters',
      method: 'GET',
      path: '/api/admin/documents?page=2&size=10&sourceId=3&status=ACTIVE&title=admissions&referenceType=INTERNAL&ingestedFrom=2026-01-01T00%3A00%3A00Z&ingestedTo=2026-01-02T00%3A00%3A00Z',
      invoke: () => listDocuments({
        page: 2,
        size: 10,
        sourceId: 3,
        status: 'ACTIVE',
        title: 'admissions',
        referenceType: 'INTERNAL',
        ingestedFrom: '2026-01-01T00:00:00Z',
        ingestedTo: '2026-01-02T00:00:00Z',
      }),
    },
    {
      name: 'gets a document',
      method: 'GET',
      path: '/api/admin/documents/5',
      invoke: () => getDocument(5),
    },
    {
      name: 'reindexes a document',
      method: 'POST',
      path: '/api/admin/documents/5/reindex',
      invoke: () => reindexDocument(5),
    },
    {
      name: 'deletes a document',
      method: 'DELETE',
      path: '/api/admin/documents/5',
      invoke: () => deleteDocument(5),
    },
    {
      name: 'runs source ingestion',
      method: 'POST',
      path: '/api/admin/ingest/3/run',
      invoke: () => runSourceIngest(3, { force: true, reason: 'manual' }),
    },
    {
      name: 'lists ingest runs',
      method: 'GET',
      path: '/api/admin/ingest/runs?sourceId=3&status=FAILED&page=2&size=10',
      invoke: () => listIngestRuns({
        sourceId: 3,
        status: 'FAILED',
        page: 2,
        size: 10,
      }),
    },
    {
      name: 'gets an ingest run',
      method: 'GET',
      path: '/api/admin/ingest/runs/6',
      invoke: () => getIngestRun(6),
    },
    {
      name: 'retries an ingest run',
      method: 'POST',
      path: '/api/admin/ingest/runs/6/retry',
      invoke: () => retryIngestRun(6),
    },
    {
      name: 'gets an admin job',
      method: 'GET',
      path: '/api/admin/jobs/7',
      invoke: () => getAdminJob(7),
    },
    {
      name: 'retries an admin job',
      method: 'POST',
      path: '/api/admin/jobs/7/retry',
      invoke: () => retryAdminJob(7),
    },
    {
      name: 'lists moderation posts',
      method: 'GET',
      path: '/api/ugc?page=2&size=10&status=PENDING',
      invoke: () => listUgcPosts({ page: 2, size: 10, status: 'PENDING' }),
    },
    {
      name: 'reviews a moderation post',
      method: 'POST',
      path: '/api/admin/ugc/8/review',
      invoke: () => reviewUgcPost(8, { decision: 'APPROVE' }),
    },
    {
      name: 'lists post reports',
      method: 'GET',
      path: '/api/admin/ugc/8/reports?page=2&size=10',
      invoke: () => listUgcReports(8, { page: 2, size: 10 }),
    },
    {
      name: 'removes a moderation post',
      method: 'POST',
      path: '/api/admin/ugc/8/remove',
      invoke: () => removeUgcPost(8),
    },
    {
      name: 'resolves a report',
      method: 'PATCH',
      path: '/api/admin/ugc/8/reports/9',
      invoke: () => updateUgcReport(8, 9, 'RESOLVED'),
    },
    {
      name: 'lists users',
      method: 'GET',
      path: '/api/admin/users?page=2&size=10',
      invoke: () => listAdminUsers({ page: 2, size: 10 }),
    },
    {
      name: 'updates user role',
      method: 'PATCH',
      path: '/api/admin/users/10/role',
      invoke: () => updateUserRole(10, 'ADMIN'),
    },
    {
      name: 'updates user enabled state',
      method: 'PATCH',
      path: '/api/admin/users/10/enabled',
      invoke: () => setUserEnabled(10, false),
    },
    {
      name: 'invalidates user tokens',
      method: 'POST',
      path: '/api/admin/users/10/invalidate-tokens',
      invoke: () => invalidateUserTokens(10),
    },
    {
      name: 'gets admin overview',
      method: 'GET',
      path: '/api/admin/overview',
      invoke: () => getAdminOverview(),
    },
    {
      name: 'gets database overview',
      method: 'GET',
      path: '/api/admin/database/overview',
      invoke: () => getDatabaseOverview(),
    },
    {
      name: 'starts a consistency scan',
      method: 'POST',
      path: '/api/admin/database/consistency-scans',
      invoke: () => startConsistencyScan(),
    },
    {
      name: 'gets a consistency scan',
      method: 'GET',
      path: '/api/admin/database/consistency-scans/11',
      invoke: () => getConsistencyScan(11),
    },
    {
      name: 'lists backups',
      method: 'GET',
      path: '/api/admin/backups?page=2&size=10',
      invoke: () => listBackups({ page: 2, size: 10 }),
    },
    {
      name: 'creates a backup',
      method: 'POST',
      path: '/api/admin/backups',
      invoke: () => createBackup({ retentionDays: 30 }),
    },
    {
      name: 'validates a backup',
      method: 'POST',
      path: '/api/admin/backups/12/validate',
      invoke: () => validateBackup(12),
    },
    {
      name: 'deletes a backup',
      method: 'DELETE',
      path: '/api/admin/backups/12',
      invoke: () => deleteBackup(12),
    },
    {
      name: 'lists model configurations',
      method: 'GET',
      path: '/api/admin/models',
      invoke: () => listModelConfigurations(),
    },
    {
      name: 'tests a model configuration',
      method: 'POST',
      path: '/api/admin/models/test',
      invoke: () => testModelConfiguration({
        kind: 'CHAT',
        provider: 'openai',
        model: 'gpt-4.1-mini',
      }),
    },
    {
      name: 'creates a model configuration',
      method: 'POST',
      path: '/api/admin/models/configurations',
      invoke: () => createModelConfiguration({
        kind: 'EMBEDDING',
        provider: 'openai',
        model: 'text-embedding-3-small',
        dimension: 1536,
      }),
    },
    {
      name: 'lists audit records',
      method: 'GET',
      path: '/api/admin/audit-logs?page=2&size=10&actorId=13&action=MODEL_SAVE&targetType=MODEL&targetId=14&result=SUCCEEDED&createdFrom=2026-01-01T00%3A00%3A00Z&createdTo=2026-01-02T00%3A00%3A00Z',
      invoke: () => listAuditLogs({
        page: 2,
        size: 10,
        actorId: 13,
        action: 'MODEL_SAVE',
        targetType: 'MODEL',
        targetId: 14,
        result: 'SUCCEEDED',
        createdFrom: '2026-01-01T00:00:00Z',
        createdTo: '2026-01-02T00:00:00Z',
      }),
    },
    {
      name: 'lists embedding rebuilds',
      method: 'GET',
      path: '/api/admin/indexes/rebuilds',
      invoke: () => listEmbeddingRebuilds(),
    },
    {
      name: 'starts an embedding rebuild',
      method: 'POST',
      path: '/api/admin/indexes/rebuilds',
      invoke: () => startEmbeddingRebuild({ configurationId: 15 }),
    },
    {
      name: 'activates an embedding rebuild',
      method: 'POST',
      path: '/api/admin/indexes/15/activate',
      invoke: () => activateEmbeddingRebuild(15),
    },
    {
      name: 'discards an embedding rebuild',
      method: 'DELETE',
      path: '/api/admin/indexes/15',
      invoke: () => discardEmbeddingRebuild(15),
    },
  ]

  it.each(endpointCases)('$name uses $method $path', async (endpoint) => {
    await endpoint.invoke()

    const [path, init] = fetchMock.mock.calls.at(-1)!
    expect(path).toBe(endpoint.path)
    expect(init?.method ?? 'GET').toBe(endpoint.method)
    expect(new Headers(init?.headers).get('Authorization')).toBe(
      'Bearer admin-token',
    )
  })

  it('serializes local import metadata as text and preserves every file in FormData', async () => {
    const files = [
      Object.assign(new Blob(['first']), { name: 'first.txt' }) as File,
      Object.assign(new Blob(['second']), { name: 'second.txt' }) as File,
    ]
    const metadata = {
      sourceId: 3,
      items: [
        {
          clientId: 'first',
          displayTitle: 'First file',
          externalSourceUrl: null,
          replaceDocumentId: null,
          degree: null,
          college: null,
          campus: null,
          gradeRange: null,
        },
        {
          clientId: 'second',
          displayTitle: 'Second file',
          externalSourceUrl: null,
          replaceDocumentId: null,
          degree: null,
          college: null,
          campus: null,
          gradeRange: null,
        },
      ],
    }

    await uploadDocuments(metadata, files)

    const [path, init] = fetchMock.mock.calls.at(-1)!
    expect(path).toBe('/api/admin/imports')
    expect(init?.method).toBe('POST')
    expect(init?.body).toBeInstanceOf(FormData)
    const formData = init?.body as FormData
    expect(formData.get('metadata')).toBe(JSON.stringify(metadata))
    expect(formData.getAll('files')).toHaveLength(files.length)
    expect(new Headers(init?.headers).has('Content-Type')).toBe(false)
  })

  it('uses the authenticated binary endpoints for internal documents and backups', async () => {
    fetchMock.mockImplementation(async () =>
      new Response(new Blob(['archive']), {
        headers: {
          'Content-Type': 'application/octet-stream',
          'Content-Disposition': 'attachment; filename="archive.dump"',
        },
      }),
    )

    await downloadDocument(7)
    await downloadBackup(12)

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/documents/7/download')
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/admin/backups/12/download')
  })
})
