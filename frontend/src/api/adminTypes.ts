import type {
  IsoDateTimeString,
  UgcListParams,
  UgcPostDto,
  UserRole,
} from './types'

export interface PaginationParams {
  page?: number
  size?: number
}

export type SourceConnectorType =
  | 'LOCAL_UPLOAD'
  | 'SINGLE_URL'
  | 'SITE'
  | 'RSS'
  | 'SITEMAP'

export type SourceScheduleType = 'MANUAL' | 'DAILY' | 'WEEKLY' | 'CRON'

export interface SourceRequest {
  name: string
  type: string
  connectorType: SourceConnectorType
  baseUrl?: string | null
  enabled?: boolean | null
  scheduleType: SourceScheduleType
  scheduleZone?: string | null
  cronExpression?: string | null
  sameHostOnly?: boolean | null
  maxDepth?: number | null
  maxPages?: number | null
  maxConcurrency?: number | null
  includePatterns?: string[] | null
  excludePatterns?: string[] | null
}

export interface SourceDto {
  id: number
  name: string
  type: string
  connectorType: SourceConnectorType
  baseUrl: string | null
  enabled: boolean
  scheduleType: SourceScheduleType
  scheduleZone: string | null
  cronExpression: string | null
  sameHostOnly: boolean
  maxDepth: number
  maxPages: number
  maxConcurrency: number
  includePatterns: string[]
  excludePatterns: string[]
  deletionStatus: string | null
  nextRunAt: IsoDateTimeString | null
  lastRunAt: IsoDateTimeString | null
  latestRunStatus: IngestRunStatus | null
  createdAt: IsoDateTimeString
  updatedAt: IsoDateTimeString
}

export interface SourcePreviewItem {
  sourceUrl: string
  filename: string
  mediaType: string
  byteSize: number
}

export interface ImportFileMetadata {
  clientId: string
  displayTitle: string
  externalSourceUrl: string | null
  replaceDocumentId: number | null
  degree: string | null
  college: string | null
  campus: string | null
  gradeRange: string | null
}

export interface ImportMetadata {
  sourceId?: number | null
  items: ImportFileMetadata[]
}

export type DocumentReferenceType = 'EXTERNAL' | 'INTERNAL'
export type DocumentStatus =
  | 'PROCESSING'
  | 'ACTIVE'
  | 'FAILED'
  | 'DELETING'
  | 'DELETE_FAILED'

export interface DocumentDto {
  id: number
  sourceId: number | null
  title: string
  sourceUrl: string | null
  sourceOrg: string | null
  publishedAt: IsoDateTimeString | null
  contentHash: string
  referenceType: DocumentReferenceType
  externalSourceUrl: string | null
  originalFilename: string | null
  documentStatus: DocumentStatus
  downloadable: boolean
  ingestedAt: IsoDateTimeString | null
  lastSeenAt: IsoDateTimeString | null
  lastCheckedAt: IsoDateTimeString | null
  degree: string | null
  college: string | null
  campus: string | null
  gradeRange: string | null
}

export interface DocumentListParams extends PaginationParams {
  sourceId?: number
  status?: DocumentStatus
  title?: string
  referenceType?: DocumentReferenceType
  ingestedFrom?: IsoDateTimeString
  ingestedTo?: IsoDateTimeString
}

export type IngestRunStatus =
  | 'RUNNING'
  | 'DEFERRED'
  | 'SUCCESS'
  | 'PARTIAL_SUCCESS'
  | 'FAILED'

export type IngestRunItemStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'SKIPPED'
  | 'FAILED'

export interface IngestRunDto {
  id: number
  sourceId: number
  status: IngestRunStatus
  added: number
  updated: number
  skipped: number
  failed: number
  errorDetail: string | null
  startedAt: IsoDateTimeString | null
  finishedAt: IsoDateTimeString | null
}

export interface IngestRunItemDto {
  id: number
  runId: number
  itemKey: string
  itemKind: string
  status: IngestRunItemStatus
  documentId: number | null
  retryCount: number
  nextAttemptAt: IsoDateTimeString | null
  errorCode: string | null
  errorSummary: string | null
  checkpoint: Record<string, unknown>
  checkpointAttempt: number
  createdAt: IsoDateTimeString
  updatedAt: IsoDateTimeString
}

export interface IngestRunDetailDto extends IngestRunDto {
  triggerType: string
  scheduledAt: IsoDateTimeString | null
  discovered: number
  attempt: number
  workerId: string | null
  claimedAt: IsoDateTimeString | null
  heartbeatAt: IsoDateTimeString | null
  errorCode: string | null
  items: IngestRunItemDto[]
}

export interface RunIngestRequest {
  force?: boolean | null
  rebuildVectors?: boolean | null
  reason?: string | null
}

export interface IngestRunListParams extends PaginationParams {
  sourceId?: number
  status?: IngestRunStatus
}

export type AdminJobStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'PARTIAL_SUCCESS'
  | 'FAILED'
  | 'CANCELLED'

export type AdminJobType =
  | 'SOURCE_DELETE'
  | 'DOCUMENT_DELETE'
  | 'DOCUMENT_REINDEX'
  | 'CONSISTENCY_SCAN'
  | 'DATABASE_BACKUP'
  | 'EMBEDDING_REBUILD'
  | 'INDEX_CLEANUP'

export interface AdminJobItemDto {
  id: number
  jobId: number
  itemKey: string
  itemType: string
  targetType: string
  targetId: number | null
  status: AdminJobStatus
  retryCount: number
  errorCode: string | null
  errorSummary: string | null
  checkpoint: Record<string, unknown>
  checkpointAttempt: number
  createdAt: IsoDateTimeString
  updatedAt: IsoDateTimeString
}

export interface AdminJobDto {
  id: number
  jobType: AdminJobType
  status: AdminJobStatus
  targetType: string
  targetId: number | null
  requestedBy: number | null
  attempt: number
  workerId: string | null
  claimedAt: IsoDateTimeString | null
  heartbeatAt: IsoDateTimeString | null
  startedAt: IsoDateTimeString | null
  finishedAt: IsoDateTimeString | null
  errorCode: string | null
  errorSummary: string | null
  requestPayload: Record<string, unknown>
  createdAt: IsoDateTimeString
  updatedAt: IsoDateTimeString
  items: AdminJobItemDto[]
}

export type ReviewDecision = 'APPROVE' | 'REJECT'
export type UgcReportStatus = 'OPEN' | 'RESOLVED' | 'DISMISSED'

export interface ReviewUgcRequest {
  decision: ReviewDecision
  reason?: string | null
}

export interface UgcReportDto {
  id: number
  postId: number
  reporterId: number
  reporterName: string | null
  reason: string
  status: UgcReportStatus
  createdAt: IsoDateTimeString
}

export type ModerationListParams = UgcListParams

export interface AdminUserDto {
  id: number
  username: string
  displayName: string | null
  role: UserRole
  enabled: boolean
  tokenVersion: number
  createdAt: IsoDateTimeString
}

export interface DatabaseOverviewDto {
  databaseVersion: string
  pgvectorVersion: string
  flywayVersion: string
  databaseSizeBytes: number
  indexSizeBytes: number
  tableCounts: Record<string, number>
  activeConnections: number
  storageAvailable: boolean
  modelStatus: {
    provider: string
    model: string
    configured: boolean
    dimension: number
  }
  recentIngestionFailures: Array<{
    id: number
    sourceId: number
    status: string
    errorCode: string | null
    finishedAt: IsoDateTimeString | null
  }>
}

export type DatabaseBackupStatus =
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'VALIDATED'
  | 'FAILED'
  | 'DELETE_FAILED'

export interface DatabaseBackupDto {
  id: number
  jobId: number | null
  status: DatabaseBackupStatus
  format: string
  sha256: string | null
  sizeBytes: number | null
  requestedBy: number | null
  startedAt: IsoDateTimeString | null
  finishedAt: IsoDateTimeString | null
  retentionDays: number
  expiresAt: IsoDateTimeString | null
  errorSummary: string | null
  createdAt: IsoDateTimeString
  updatedAt: IsoDateTimeString
  downloadable: boolean
}

export interface CreateBackupRequest {
  retentionDays?: number | null
}

export type ModelKind = 'CHAT' | 'EMBEDDING'
export type ModelConfigurationStatus = 'ACTIVE' | 'PENDING' | 'FAILED' | 'RETIRED'

export interface ModelConfigurationRequest {
  kind: ModelKind
  provider: string
  model: string
  baseUrl?: string | null
  temperature?: number | null
  dimension?: number | null
  apiKey?: string | null
  keepExistingSecret?: boolean | null
  existingConfigurationId?: number | null
}

export type ModelTestRequest = ModelConfigurationRequest

export interface ModelConfigurationDto {
  id: number
  configurationKey: string
  kind: ModelKind
  provider: string
  model: string
  baseUrl: string | null
  temperature: number | null
  dimension: number | null
  status: ModelConfigurationStatus
  bootstrap: boolean
  secretConfigured: boolean
  maskedApiKey: string | null
  available: boolean
  restartRequired: boolean
  failureReason: string | null
  activatedAt: IsoDateTimeString | null
  createdAt: IsoDateTimeString
  updatedAt: IsoDateTimeString
}

export type AdminAuditResult = 'SUCCEEDED' | 'FAILED'

export interface AdminAuditLogDto {
  id: number
  actorId: number | null
  action: string
  targetType: string
  targetId: number | null
  summary: Record<string, unknown>
  remoteAddress: string | null
  result: AdminAuditResult
  errorCode: number | null
  createdAt: IsoDateTimeString
}

export interface AdminAuditListParams extends PaginationParams {
  actorId?: number
  action?: string
  targetType?: string
  targetId?: number
  result?: AdminAuditResult
  createdFrom?: IsoDateTimeString
  createdTo?: IsoDateTimeString
}

export interface EmbeddingRebuildRequest {
  configurationId: number
}

export type { UgcPostDto }
