export type IsoDateTimeString = string

export interface ApiResponse<T> {
  code: number
  message: string
  data: T | null
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
  pages: number
}

export type UserRole = 'STUDENT' | 'ADMIN'
export type TokenType = 'Bearer'

export interface RegisterRequest {
  username: string
  password: string
  displayName?: string | null
}

export interface LoginRequest {
  username: string
  password: string
}

export interface UserSummaryDto {
  id: number
  username: string
  displayName: string | null
  role: UserRole
}

export interface AuthTokenResponse {
  tokenType: TokenType
  accessToken: string
  expiresIn: number
  user: UserSummaryDto
}

export type RegisterResponse = ApiResponse<AuthTokenResponse>
export type LoginResponse = ApiResponse<AuthTokenResponse>

export type Degree =
  | 'UNDERGRADUATE'
  | 'POSTGRADUATE'
  | 'DOCTORAL'
  | 'GENERAL'

export type LifecycleStage =
  | 'NEW_STUDENT'
  | 'ENROLLED'
  | 'GRADUATING'
  | 'ALUMNI'
  | 'UNKNOWN'

export interface CourseRecordDto {
  courseCode: string | null
  courseName: string | null
  term: string | null
}

export interface CourseRecordInput {
  courseCode?: string | null
  courseName?: string | null
  term?: string | null
}

export interface UserProfileDto {
  userId: number
  major: string | null
  grade: number | null
  campus: string | null
  degree: Degree | null
  college: string | null
  lifecycleStage: LifecycleStage | null
  courseRecords: CourseRecordDto[]
  updatedAt: IsoDateTimeString | null
}

export interface UpdateProfileRequest {
  major?: string | null
  grade?: number | null
  campus?: string | null
  degree?: Degree | null
  college?: string | null
  lifecycleStage?: LifecycleStage | null
  courseRecords?: CourseRecordInput[] | null
}

export type UgcStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'REMOVED'

export interface CreateUgcPostRequest {
  title: string
  content: string
  tags?: string[] | null
  relatedCourse?: string | null
  relatedLocation?: string | null
}

export interface ReportUgcRequest {
  reason: string
}

export interface UgcPostDto {
  id: number
  authorId: number
  authorName: string | null
  title: string
  content: string
  tags: string[]
  relatedCourse: string | null
  relatedLocation: string | null
  status: UgcStatus
  likes: number
  reports: number
  rejectReason: string | null
  createdAt: IsoDateTimeString
  reviewedAt: IsoDateTimeString | null
}

export interface UgcListParams {
  page?: number
  size?: number
  mine?: boolean
  status?: UgcStatus
  tag?: string
}

export type IntentType = 'OFFICIAL' | 'EXPERIENTIAL' | 'MIXED' | 'UNKNOWN'
export type SourceType = 'OFFICIAL' | 'UGC'
export type AnswerStatus =
  | 'ANSWERED'
  | 'NO_EVIDENCE'
  | 'NEED_PROFILE'
  | 'FAILED'
export type ChatMessageRole = 'USER' | 'ASSISTANT'

export interface ChatStreamRequest {
  sessionId?: number | null
  question: string
  useMemory?: boolean | null
  maxSources?: number | null
}

export interface IntentDto {
  type: IntentType
  confidence: number
}

export interface ChatStreamMetaDto {
  sessionId: number
  assistantMessageId: number
  intent: IntentDto
}

export interface ChatDeltaDto {
  content: string
}

export type CitationReferenceType = 'EXTERNAL' | 'INTERNAL' | 'UGC'

interface SourceCitationBase {
  chunkId: number
  sourceOrg: string | null
  publishedAt: IsoDateTimeString | null
  relevanceScore: number | null
  quote: string | null
}

export type SourceCitationDto =
  | (SourceCitationBase & {
      sourceType: 'OFFICIAL'
      referenceType: 'EXTERNAL'
      title: string
      sourceUrl: string
      documentId: null
      externalSourceUrl: null
    })
  | (SourceCitationBase & {
      sourceType: 'OFFICIAL'
      referenceType: 'INTERNAL'
      title: string
      sourceUrl: null
      documentId: number
      externalSourceUrl: string | null
    })
  | (SourceCitationBase & {
      sourceType: 'UGC'
      referenceType: 'UGC'
      title: string | null
      sourceUrl: string | null
      documentId: null
      externalSourceUrl: null
    })

export interface ChatDoneDto {
  sessionId: number
  assistantMessageId: number
  answerStatus: AnswerStatus
  answer: string
  sources: SourceCitationDto[]
}

export type ChatErrorDto = ApiResponse<never>

export interface ChatSessionSummaryDto {
  id: number
  title: string | null
  createdAt: IsoDateTimeString
  updatedAt: IsoDateTimeString
  lastMessagePreview: string | null
}

export interface ChatMessageDto {
  id: number
  role: ChatMessageRole
  content: string
  sources: SourceCitationDto[]
  createdAt: IsoDateTimeString
}

export interface ChatSessionDetailDto {
  id: number
  title: string | null
  messages: ChatMessageDto[]
  hasMore: boolean
  createdAt: IsoDateTimeString
  updatedAt: IsoDateTimeString
}

export interface ChatSessionListParams {
  page?: number
  size?: number
}

export interface ChatSseEventMap {
  meta: ChatStreamMetaDto
  delta: ChatDeltaDto
  source: SourceCitationDto
  done: ChatDoneDto
  error: ChatErrorDto
}

export type ChatSseEventName = keyof ChatSseEventMap
