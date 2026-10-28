import type { ApiResponse } from './types'

export const AUTH_TOKEN_STORAGE_KEY = 'token'
const API_NORMALIZATION_ORIGIN = 'http://college-assistant.local'

/**
 * 统一 API 客户端错误，保留业务错误码与 HTTP 状态，供页面决定展示或跳转登录。
 */
export class ApiClientError extends Error {
  readonly code: number | null
  readonly status: number

  constructor(message: string, code: number | null, status: number) {
    super(message)
    this.name = 'ApiClientError'
    this.code = code
    this.status = status
  }
}

/** 文档和备份下载接口返回的已认证二进制响应。 */
export interface BinaryDownload {
  blob: Blob
  filename: string | null
  contentType: string
}

/** 判断 HTTP 状态是否表示登录态失效。 */
export function isAuthenticationStatus(status: number): boolean {
  return status === 401
}

/**
 * 发送同源 API 请求，自动附加 JWT，并校验后端统一响应包装。
 *
 * @param path 仅允许位于 `/api` 下的相对或绝对路径
 * @param init Fetch 请求配置
 * @returns 成功响应中的业务数据；204 或空业务数据返回 `null`
 */
export async function apiRequest<T>(
  path: string,
  init: RequestInit = {},
): Promise<T | null> {
  const apiPath = normalizeApiPath(path)
  const headers = new Headers(init.headers)
  const token = getStoredToken()
  if (token) {
    try {
      headers.set('Authorization', `Bearer ${token}`)
    } catch {
      // 忽略 Fetch API 无法安全编码的已存储值。
    }
  }
  let response: Response
  try {
    response = await fetch(apiPath, {
      ...init,
      headers,
    })
  } catch {
    throw new ApiClientError('暂时无法连接服务器', null, 0)
  }
  if (response.status === 204) {
    return null
  }
  let payload: unknown
  try {
    payload = await response.json()
  } catch {
    throw new ApiClientError(
      '服务器返回了无法识别的响应',
      null,
      response.status,
    )
  }
  if (!isApiResponse(payload)) {
    throw new ApiClientError(
      '服务器返回了无法识别的响应',
      null,
      response.status,
    )
  }
  const envelope = payload
  if (envelope.code !== 0) {
    throw new ApiClientError(
      safeErrorMessage(envelope.message),
      envelope.code,
      response.status,
    )
  }
  if (!response.ok) {
    throw new ApiClientError(
      `请求失败（HTTP ${response.status}）`,
      envelope.code,
      response.status,
    )
  }
  return envelope.data as T | null
}

/**
 * 发送经过认证的同源二进制下载请求。
 *
 * 成功下载接口不使用 `ApiResponse` 包装；但业务异常仍可能以 JSON 包装和 HTTP 200 返回，
 * 因此必须先根据 Content-Type 识别该异常分支，避免把错误页面保存为文件。
 */
export async function apiBinaryRequest(
  path: string,
  init: RequestInit = {},
): Promise<BinaryDownload> {
  const apiPath = normalizeApiPath(path)
  const headers = new Headers(init.headers)
  const token = getStoredToken()
  if (token) {
    try {
      headers.set('Authorization', `Bearer ${token}`)
    } catch {
      // 忽略 Fetch API 无法安全编码的已存储值。
    }
  }

  let response: Response
  try {
    response = await fetch(apiPath, { ...init, headers })
  } catch {
    throw new ApiClientError('暂时无法连接服务器', null, 0)
  }

  const receivedContentType = response.headers.get('Content-Type') ?? ''
  if (isJsonMediaType(receivedContentType)) {
    return throwBinaryApiEnvelope(response)
  }
  if (!response.ok) {
    throw new ApiClientError(
      `下载失败（HTTP ${response.status}）`,
      null,
      response.status,
    )
  }

  try {
    return {
      blob: await response.blob(),
      filename: parseContentDisposition(
        response.headers.get('Content-Disposition'),
      ),
      contentType: receivedContentType || 'application/octet-stream',
    }
  } catch {
    throw new ApiClientError(
      '服务器返回了无法识别的下载响应',
      null,
      response.status,
    )
  }
}

function normalizeApiPath(path: string): string {
  const requestedPath = path.trim()
  if (
    !requestedPath
    || requestedPath.startsWith('//')
    || requestedPath.includes('\\')
    || /^[a-z][a-z\d+.-]*:/i.test(requestedPath)
  ) {
    throw new ApiClientError('API 路径无效', null, 0)
  }

  const rootedPath = requestedPath.startsWith('/')
    ? requestedPath
    : `/${requestedPath}`
  const alreadyApiPath = rootedPath === '/api'
    || rootedPath.startsWith('/api/')
    || rootedPath.startsWith('/api?')
    || rootedPath.startsWith('/api#')
  const candidatePath = alreadyApiPath ? rootedPath : `/api${rootedPath}`

  let normalizedUrl: URL
  try {
    normalizedUrl = new URL(candidatePath, API_NORMALIZATION_ORIGIN)
  } catch {
    throw new ApiClientError('API 路径无效', null, 0)
  }
  if (
    normalizedUrl.origin !== API_NORMALIZATION_ORIGIN
    || (normalizedUrl.pathname !== '/api'
      && !normalizedUrl.pathname.startsWith('/api/'))
  ) {
    throw new ApiClientError('API 路径无效', null, 0)
  }
  return `${normalizedUrl.pathname}${normalizedUrl.search}`
}

function isApiResponse(
  value: unknown,
): value is ApiResponse<unknown> {
  return typeof value === 'object'
    && value !== null
    && typeof Reflect.get(value, 'code') === 'number'
    && Number.isInteger(Reflect.get(value, 'code'))
    && typeof Reflect.get(value, 'message') === 'string'
    && Object.prototype.hasOwnProperty.call(value, 'data')
}

function isJsonMediaType(contentType: string): boolean {
  const mediaType = contentType.split(';', 1)[0]?.trim().toLowerCase() ?? ''
  return mediaType === 'application/json'
    || mediaType.endsWith('/json')
    || mediaType.endsWith('+json')
}

async function throwBinaryApiEnvelope(response: Response): Promise<never> {
  const payload: unknown = await response.json().catch(() => null)
  if (isApiResponse(payload) && payload.code !== 0) {
    throw new ApiClientError(
      safeErrorMessage(payload.message),
      payload.code,
      response.status,
    )
  }
  throw new ApiClientError(
    '服务器返回了无法识别的下载响应',
    null,
    response.status,
  )
}

function parseContentDisposition(header: string | null): string | null {
  if (!header) {
    return null
  }

  const encodedMatch = /(?:^|;)\s*filename\*\s*=\s*([^;]+)/i.exec(header)
  if (encodedMatch) {
    const rawValue = stripHeaderQuotes(encodedMatch[1]!.trim())
    const rfc8187Match = /^([^']*)'[^']*'(.*)$/.exec(rawValue)
    if (rfc8187Match?.[1].toLowerCase() === 'utf-8') {
      try {
        const filename = sanitizeDownloadFilename(
          decodeURIComponent(rfc8187Match[2]),
        )
        if (filename) {
          return filename
        }
      } catch {
        // 忽略无效百分号编码，继续尝试常规 filename 参数。
      }
    }
  }

  const plainMatch = /(?:^|;)\s*filename\s*=\s*(?:"((?:[^"\\]|\\.)*)"|([^;\s]*))/i.exec(
    header,
  )
  const rawFilename = plainMatch?.[1] ?? plainMatch?.[2]
  return rawFilename
    ? sanitizeDownloadFilename(rawFilename.replace(/\\(.)/g, '$1'))
    : null
}

function stripHeaderQuotes(value: string): string {
  return value.length >= 2 && value.startsWith('"') && value.endsWith('"')
    ? value.slice(1, -1)
    : value
}

function sanitizeDownloadFilename(value: string): string | null {
  const filename = value
    .replace(/[\u0000-\u001f\u007f]/g, '')
    .replace(/[\\/]/g, '_')
    .trim()
    .slice(0, 255)
  return filename || null
}

function safeErrorMessage(message: string): string {
  const normalized = message
    .replace(/[\u0000-\u001f\u007f]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 160)
  return normalized || '请求未能完成'
}

/** 从浏览器存储读取并校验访问令牌。 */
export function getStoredToken(): string | null {
  try {
    const token = localStorage.getItem(AUTH_TOKEN_STORAGE_KEY)?.trim()
    if (!token || /[\u0000-\u001f\u007f]/.test(token)) {
      return null
    }
    return token
  } catch {
    return null
  }
}

/** 保存合法访问令牌；浏览器禁止存储时返回 `false`。 */
export function setStoredToken(token: string): boolean {
  const normalized = token.trim()
  if (!normalized || /[\u0000-\u001f\u007f]/.test(normalized)) {
    return false
  }
  try {
    localStorage.setItem(AUTH_TOKEN_STORAGE_KEY, normalized)
    return true
  } catch {
    return false
  }
}

/** 清除本地访问令牌；浏览器禁止存储时返回 `false`。 */
export function clearStoredToken(): boolean {
  try {
    localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY)
    return true
  } catch {
    return false
  }
}
