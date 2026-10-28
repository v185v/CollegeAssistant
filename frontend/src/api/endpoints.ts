import { apiRequest } from './client'
import type {
  AuthTokenResponse,
  ChatSessionDetailDto,
  ChatSessionSummaryDto,
  CreateUgcPostRequest,
  LoginRequest,
  PageResult,
  RegisterRequest,
  UgcPostDto,
  UpdateProfileRequest,
  UserProfileDto,
} from './types'

function jsonRequest(method: 'POST' | 'PUT', body: unknown): RequestInit {
  return {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }
}

/** 使用本地账号登录。 */
export function login(
  request: LoginRequest,
): Promise<AuthTokenResponse | null> {
  return apiRequest<AuthTokenResponse>(
    '/auth/login',
    jsonRequest('POST', request),
  )
}

/** 注册本地学生账号。 */
export function register(
  request: RegisterRequest,
): Promise<AuthTokenResponse | null> {
  return apiRequest<AuthTokenResponse>(
    '/auth/register',
    jsonRequest('POST', request),
  )
}

/** 获取当前用户画像。 */
export function getProfile(): Promise<UserProfileDto | null> {
  return apiRequest<UserProfileDto>('/profile')
}

/** 覆盖当前用户画像。 */
export function updateProfile(
  request: UpdateProfileRequest,
): Promise<UserProfileDto | null> {
  return apiRequest<UserProfileDto>(
    '/profile',
    jsonRequest('PUT', request),
  )
}

/** 创建一条待审核校园经验。 */
export function createUgcPost(
  request: CreateUgcPostRequest,
): Promise<UgcPostDto | null> {
  return apiRequest<UgcPostDto>('/ugc', jsonRequest('POST', request))
}

/** 分页查询当前用户提交的 UGC。 */
export function listMyUgc(
  page = 1,
  size = 20,
): Promise<PageResult<UgcPostDto> | null> {
  const query = new URLSearchParams({
    mine: 'true',
    page: String(page),
    size: String(size),
  })
  return apiRequest<PageResult<UgcPostDto>>(`/ugc?${query.toString()}`)
}

/** 分页查询当前用户的聊天会话。 */
export function listChatSessions(
  page = 1,
  size = 20,
): Promise<PageResult<ChatSessionSummaryDto> | null> {
  const query = new URLSearchParams({
    page: String(page),
    size: String(size),
  })
  return apiRequest<PageResult<ChatSessionSummaryDto>>(
    `/chat/sessions?${query.toString()}`,
  )
}

/** 查询指定聊天会话详情。 */
export function getChatSession(
  id: number,
): Promise<ChatSessionDetailDto | null> {
  return apiRequest<ChatSessionDetailDto>(`/chat/sessions/${id}`)
}
