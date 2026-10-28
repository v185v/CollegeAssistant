import {
  ApiClientError,
  apiBinaryRequest,
  apiRequest,
} from './client'
import type {
  AdminAuditListParams,
  AdminAuditLogDto,
  AdminJobDto,
  AdminUserDto,
  CreateBackupRequest,
  DatabaseBackupDto,
  DatabaseOverviewDto,
  DocumentDto,
  DocumentListParams,
  EmbeddingRebuildRequest,
  ImportMetadata,
  IngestRunDetailDto,
  IngestRunDto,
  IngestRunListParams,
  ModelConfigurationDto,
  ModelConfigurationRequest,
  ModelTestRequest,
  PaginationParams,
  ReviewUgcRequest,
  SourceDto,
  SourcePreviewItem,
  SourceRequest,
  UgcPostDto,
  UgcReportDto,
  UgcReportStatus,
} from './adminTypes'
import type { BinaryDownload } from './client'
import type { PageResult, UgcListParams, UserRole } from './types'

type QueryValue = string | number | boolean | null | undefined

function jsonRequest(method: string, body?: unknown): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  }
}

function requestWithData<T>(path: string, init?: RequestInit): Promise<T> {
  return apiRequest<T>(path, init).then((data) => {
    if (data === null) {
      throw new ApiClientError('服务器返回了空响应', null, 200)
    }
    return data
  })
}

function requestWithoutData(path: string, init: RequestInit): Promise<void> {
  return apiRequest<void>(path, init).then(() => undefined)
}

function withQuery(
  path: string,
  parameters: object,
): string {
  const query = new URLSearchParams()
  for (const [key, rawValue] of Object.entries(parameters)) {
    const value = rawValue as QueryValue
    if (value !== null && value !== undefined && value !== '') {
      query.set(key, String(value))
    }
  }
  const serialized = query.toString()
  return serialized ? `${path}?${serialized}` : path
}

export function listSources(
  params: PaginationParams = {},
): Promise<PageResult<SourceDto>> {
  return requestWithData(withQuery('/admin/sources', params))
}

export function createSource(request: SourceRequest): Promise<SourceDto> {
  return requestWithData('/admin/sources', jsonRequest('POST', request))
}

export function testSource(
  request: SourceRequest,
): Promise<SourcePreviewItem[]> {
  return requestWithData('/admin/sources/test', jsonRequest('POST', request))
}

export function getSource(sourceId: number): Promise<SourceDto> {
  return requestWithData(`/admin/sources/${sourceId}`)
}

export function updateSource(
  sourceId: number,
  request: SourceRequest,
): Promise<SourceDto> {
  return requestWithData(
    `/admin/sources/${sourceId}`,
    jsonRequest('PUT', request),
  )
}

export function setSourceEnabled(
  sourceId: number,
  enabled: boolean,
): Promise<SourceDto> {
  return requestWithData(
    `/admin/sources/${sourceId}/enabled`,
    jsonRequest('PATCH', { enabled }),
  )
}

export function previewSource(sourceId: number): Promise<SourcePreviewItem[]> {
  return requestWithData(`/admin/sources/${sourceId}/preview`, {
    method: 'POST',
  })
}

export function deleteSource(sourceId: number): Promise<AdminJobDto> {
  return requestWithData(`/admin/sources/${sourceId}`, { method: 'DELETE' })
}

export function uploadDocuments(
  metadata: ImportMetadata,
  files: File[],
): Promise<IngestRunDetailDto> {
  const body = new FormData()
  body.append('metadata', JSON.stringify(metadata))
  for (const file of files) {
    body.append('files', file, file.name)
  }
  return requestWithData('/admin/imports', { method: 'POST', body })
}

export function listImportRuns(
  params: PaginationParams = {},
): Promise<PageResult<IngestRunDto>> {
  return requestWithData(withQuery('/admin/imports', params))
}

export function getImportRun(runId: number): Promise<IngestRunDetailDto> {
  return requestWithData(`/admin/imports/${runId}`)
}

export function retryImportRun(runId: number): Promise<IngestRunDetailDto> {
  return requestWithData(`/admin/imports/${runId}/retry`, { method: 'POST' })
}

export function listDocuments(
  params: DocumentListParams = {},
): Promise<PageResult<DocumentDto>> {
  return requestWithData(
    withQuery('/admin/documents', {
      page: params.page,
      size: params.size,
      sourceId: params.sourceId,
      status: params.status,
      title: params.title,
      referenceType: params.referenceType,
      ingestedFrom: params.ingestedFrom,
      ingestedTo: params.ingestedTo,
    }),
  )
}

export function getDocument(documentId: number): Promise<DocumentDto> {
  return requestWithData(`/admin/documents/${documentId}`)
}

export function reindexDocument(documentId: number): Promise<AdminJobDto> {
  return requestWithData(`/admin/documents/${documentId}/reindex`, {
    method: 'POST',
  })
}

export function deleteDocument(documentId: number): Promise<AdminJobDto> {
  return requestWithData(`/admin/documents/${documentId}`, { method: 'DELETE' })
}

export function downloadDocument(documentId: number): Promise<BinaryDownload> {
  return apiBinaryRequest(`/documents/${documentId}/download`)
}

export function runSourceIngest(
  sourceId: number,
  request: { force?: boolean | null; rebuildVectors?: boolean | null; reason?: string | null },
): Promise<IngestRunDto> {
  return requestWithData(
    `/admin/ingest/${sourceId}/run`,
    jsonRequest('POST', request),
  )
}

export function listIngestRuns(
  params: IngestRunListParams = {},
): Promise<PageResult<IngestRunDto>> {
  return requestWithData(
    withQuery('/admin/ingest/runs', {
      sourceId: params.sourceId,
      status: params.status,
      page: params.page,
      size: params.size,
    }),
  )
}

export function getIngestRun(runId: number): Promise<IngestRunDetailDto> {
  return requestWithData(`/admin/ingest/runs/${runId}`)
}

export function retryIngestRun(runId: number): Promise<IngestRunDto> {
  return requestWithData(`/admin/ingest/runs/${runId}/retry`, {
    method: 'POST',
  })
}

export function getAdminJob(jobId: number): Promise<AdminJobDto> {
  return requestWithData(`/admin/jobs/${jobId}`)
}

export function retryAdminJob(jobId: number): Promise<AdminJobDto> {
  return requestWithData(`/admin/jobs/${jobId}/retry`, { method: 'POST' })
}

export function listUgcPosts(
  params: UgcListParams = {},
): Promise<PageResult<UgcPostDto>> {
  return requestWithData(
    withQuery('/ugc', {
      page: params.page,
      size: params.size,
      mine: params.mine,
      status: params.status,
      tag: params.tag,
    }),
  )
}

export function reviewUgcPost(
  postId: number,
  request: ReviewUgcRequest,
): Promise<UgcPostDto> {
  return requestWithData(
    `/admin/ugc/${postId}/review`,
    jsonRequest('POST', request),
  )
}

export function listUgcReports(
  postId: number,
  params: PaginationParams = {},
): Promise<PageResult<UgcReportDto>> {
  return requestWithData(
    withQuery(`/admin/ugc/${postId}/reports`, params),
  )
}

export function removeUgcPost(postId: number): Promise<UgcPostDto> {
  return requestWithData(`/admin/ugc/${postId}/remove`, { method: 'POST' })
}

export function updateUgcReport(
  postId: number,
  reportId: number,
  status: UgcReportStatus,
): Promise<UgcReportDto> {
  return requestWithData(
    `/admin/ugc/${postId}/reports/${reportId}`,
    jsonRequest('PATCH', { status }),
  )
}

export function listAdminUsers(
  params: PaginationParams = {},
): Promise<PageResult<AdminUserDto>> {
  return requestWithData(withQuery('/admin/users', params))
}

export function updateUserRole(
  userId: number,
  role: UserRole,
): Promise<AdminUserDto> {
  return requestWithData(
    `/admin/users/${userId}/role`,
    jsonRequest('PATCH', { role }),
  )
}

export function setUserEnabled(
  userId: number,
  enabled: boolean,
): Promise<AdminUserDto> {
  return requestWithData(
    `/admin/users/${userId}/enabled`,
    jsonRequest('PATCH', { enabled }),
  )
}

export function invalidateUserTokens(userId: number): Promise<AdminUserDto> {
  return requestWithData(`/admin/users/${userId}/invalidate-tokens`, {
    method: 'POST',
  })
}

export function getAdminOverview(): Promise<DatabaseOverviewDto> {
  return requestWithData('/admin/overview')
}

export function getDatabaseOverview(): Promise<DatabaseOverviewDto> {
  return requestWithData('/admin/database/overview')
}

export function startConsistencyScan(): Promise<AdminJobDto> {
  return requestWithData('/admin/database/consistency-scans', { method: 'POST' })
}

export function getConsistencyScan(jobId: number): Promise<AdminJobDto> {
  return requestWithData(`/admin/database/consistency-scans/${jobId}`)
}

export function listBackups(
  params: PaginationParams = {},
): Promise<PageResult<DatabaseBackupDto>> {
  return requestWithData(withQuery('/admin/backups', params))
}

export function createBackup(
  request?: CreateBackupRequest,
): Promise<DatabaseBackupDto> {
  return requestWithData(
    '/admin/backups',
    request === undefined ? { method: 'POST' } : jsonRequest('POST', request),
  )
}

export function validateBackup(backupId: number): Promise<DatabaseBackupDto> {
  return requestWithData(`/admin/backups/${backupId}/validate`, { method: 'POST' })
}

export function downloadBackup(backupId: number): Promise<BinaryDownload> {
  return apiBinaryRequest(`/admin/backups/${backupId}/download`)
}

export function deleteBackup(backupId: number): Promise<void> {
  return requestWithoutData(`/admin/backups/${backupId}`, { method: 'DELETE' })
}

export function listModelConfigurations(): Promise<ModelConfigurationDto[]> {
  return requestWithData('/admin/models')
}

export function testModelConfiguration(
  request: ModelTestRequest,
): Promise<void> {
  return requestWithoutData('/admin/models/test', jsonRequest('POST', request))
}

export function createModelConfiguration(
  request: ModelConfigurationRequest,
): Promise<ModelConfigurationDto> {
  return requestWithData(
    '/admin/models/configurations',
    jsonRequest('POST', request),
  )
}

export function listAuditLogs(
  params: AdminAuditListParams = {},
): Promise<PageResult<AdminAuditLogDto>> {
  return requestWithData(
    withQuery('/admin/audit-logs', {
      page: params.page,
      size: params.size,
      actorId: params.actorId,
      action: params.action,
      targetType: params.targetType,
      targetId: params.targetId,
      result: params.result,
      createdFrom: params.createdFrom,
      createdTo: params.createdTo,
    }),
  )
}

export function listEmbeddingRebuilds(): Promise<AdminJobDto[]> {
  return requestWithData('/admin/indexes/rebuilds')
}

export function startEmbeddingRebuild(
  request: EmbeddingRebuildRequest,
): Promise<AdminJobDto> {
  return requestWithData('/admin/indexes/rebuilds', jsonRequest('POST', request))
}

export function activateEmbeddingRebuild(
  configurationId: number,
): Promise<void> {
  return requestWithoutData(`/admin/indexes/${configurationId}/activate`, {
    method: 'POST',
  })
}

export function discardEmbeddingRebuild(
  configurationId: number,
): Promise<AdminJobDto> {
  return requestWithData(`/admin/indexes/${configurationId}`, {
    method: 'DELETE',
  })
}
