import { getStoredToken } from './client'
import type {
  AnswerStatus,
  ChatDeltaDto,
  ChatDoneDto,
  ChatSseEventMap,
  ChatSseEventName,
  ChatStreamMetaDto,
  ChatStreamRequest,
  IntentType,
  SourceCitationDto,
} from './types'

export type ChatStreamEvent = {
  [Name in ChatSseEventName]: {
    event: Name
    data: ChatSseEventMap[Name]
  }
}[ChatSseEventName]

export type ChatStreamErrorKind =
  | 'http'
  | 'content-type'
  | 'protocol'
  | 'server'
  | 'network'

/** 聊天流在 HTTP、协议、服务端或网络阶段产生的结构化错误。 */
export class ChatStreamError extends Error {
  readonly kind: ChatStreamErrorKind
  readonly status: number
  readonly code: number | null

  constructor(
    message: string,
    kind: ChatStreamErrorKind,
    status = 0,
    code: number | null = null,
  ) {
    super(message)
    this.name = 'ChatStreamError'
    this.kind = kind
    this.status = status
    this.code = code
  }
}

/** 由用户或页面生命周期主动取消问答时抛出的错误。 */
export class ChatStreamAbortError extends Error {
  constructor() {
    super('问答请求已取消')
    this.name = 'ChatStreamAbortError'
  }
}

export function isChatStreamAbort(
  error: unknown,
): error is ChatStreamAbortError {
  return error instanceof ChatStreamAbortError
}

/** 可增量接收任意网络分片的 SSE 协议解析器。 */
export interface ChatSseParser {
  readonly completed: boolean
  push(chunk: string): void
  finish(): ChatDoneDto
}

/**
 * 创建聊天 SSE 解析器。
 *
 * <p>解析器支持 CRLF、LF、跨分片字段和多行 data，并在分发前对每类事件执行
 * 运行时结构校验。收到 error 或无 done 结束时会转为明确的流协议错误。</p>
 */
export function createChatSseParser(
  onEvent: (event: ChatStreamEvent) => void,
): ChatSseParser {
  let buffer = ''
  let eventName = ''
  let dataLines: string[] = []
  let donePayload: ChatDoneDto | null = null
  let terminalFailure = false

  function resetEvent(): void {
    eventName = ''
    dataLines = []
  }

  function dispatch(): void {
    const name = eventName || 'message'
    const payloadText = dataLines.join('\n')
    const hadData = dataLines.length > 0
    resetEvent()

    if (donePayload || !hadData || !isKnownEventName(name)) {
      return
    }

    let payload: unknown
    try {
      payload = JSON.parse(payloadText)
    } catch {
      terminalFailure = true
      throw protocolError('问答流包含无法识别的数据')
    }

    const event = parseChatEvent(name, payload)
    if (event.event === 'error') {
      terminalFailure = true
      throw new ChatStreamError(
        safeMessage(event.data.message, '问答服务暂时无法完成请求'),
        'server',
        200,
        event.data.code,
      )
    }
    if (event.event === 'done') {
      donePayload = event.data
    }
    onEvent(event)
  }

  function processLine(line: string): void {
    if (donePayload || line.startsWith(':')) {
      return
    }
    if (line === '') {
      dispatch()
      return
    }

    const colonIndex = line.indexOf(':')
    const field = colonIndex < 0 ? line : line.slice(0, colonIndex)
    let value = colonIndex < 0 ? '' : line.slice(colonIndex + 1)
    if (value.startsWith(' ')) {
      value = value.slice(1)
    }

    if (field === 'event') {
      eventName = value
    } else if (field === 'data') {
      dataLines.push(value)
    }
  }

  function drain(final: boolean): void {
    while (buffer) {
      const ending = findLineEnding(buffer, final)
      if (!ending) {
        if (final) {
          processLine(buffer)
          buffer = ''
        }
        return
      }
      const line = buffer.slice(0, ending.index)
      buffer = buffer.slice(ending.index + ending.length)
      processLine(line)
    }
  }

  return {
    get completed() {
      return donePayload !== null
    },
    push(chunk: string) {
      if (donePayload || terminalFailure || !chunk) {
        return
      }
      buffer += chunk
      drain(false)
    },
    finish() {
      if (terminalFailure) {
        throw protocolError('问答连接提前结束，请重试')
      }
      drain(true)
      if (dataLines.length > 0 || eventName) {
        dispatch()
      }
      if (!donePayload) {
        throw protocolError('问答连接提前结束，请重试')
      }
      return donePayload
    },
  }
}

/** 页面可按需订阅的聊天流事件处理器。 */
export interface ChatStreamHandlers {
  onMeta?: (event: ChatStreamMetaDto) => void
  onDelta?: (event: ChatDeltaDto) => void
  onSource?: (event: SourceCitationDto) => void
  onDone?: (event: ChatDoneDto) => void
}

/** 聊天流请求选项。 */
export interface ChatStreamOptions {
  signal?: AbortSignal
}

/**
 * 发起流式问答，持续解码 SSE 并分发结构化事件。
 *
 * @param request 问答请求
 * @param handlers 事件处理器
 * @param options 取消信号等请求选项
 * @returns 服务端 done 事件中的最终答案
 */
export async function streamChat(
  request: ChatStreamRequest,
  handlers: ChatStreamHandlers,
  options: ChatStreamOptions = {},
): Promise<ChatDoneDto> {
  const headers = new Headers({
    Accept: 'text/event-stream',
    'Content-Type': 'application/json',
  })
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
    response = await fetch('/api/chat/stream', {
      method: 'POST',
      headers,
      body: JSON.stringify(request),
      signal: options.signal,
    })
  } catch (error) {
    if (isAbortLike(error) || options.signal?.aborted) {
      throw new ChatStreamAbortError()
    }
    throw new ChatStreamError('暂时无法连接问答服务', 'network')
  }

  const mediaType = response.headers
    .get('Content-Type')
    ?.split(';', 1)[0]
    ?.trim()
    .toLowerCase()

  if (mediaType === 'application/json' || mediaType?.endsWith('+json')) {
    throw await jsonResponseError(response)
  }
  if (mediaType !== 'text/event-stream') {
    throw new ChatStreamError(
      '服务器未返回问答流，请重试',
      'content-type',
      response.status,
    )
  }
  if (!response.ok) {
    throw new ChatStreamError(
      `问答请求失败（HTTP ${response.status}）`,
      'http',
      response.status,
    )
  }
  if (!response.body) {
    throw protocolError('浏览器无法读取问答流，请重试', response.status)
  }

  const parser = createChatSseParser((event) => {
    switch (event.event) {
      case 'meta':
        handlers.onMeta?.(event.data)
        break
      case 'delta':
        handlers.onDelta?.(event.data)
        break
      case 'source':
        handlers.onSource?.(event.data)
        break
      case 'done':
        handlers.onDone?.(event.data)
        break
      case 'error':
        break
    }
  })
  const reader = response.body.getReader()
  const decoder = new TextDecoder()

  try {
    while (true) {
      const part = await reader.read()
      if (part.done) {
        parser.push(decoder.decode())
        return parser.finish()
      }
      parser.push(decoder.decode(part.value, { stream: true }))
      if (parser.completed) {
        return parser.finish()
      }
    }
  } catch (error) {
    if (error instanceof ChatStreamError) {
      throw error
    }
    if (isAbortLike(error) || options.signal?.aborted) {
      if (parser.completed) {
        return parser.finish()
      }
      throw new ChatStreamAbortError()
    }
    throw new ChatStreamError('问答连接意外中断，请重试', 'network')
  } finally {
    reader.releaseLock()
  }
}

function findLineEnding(
  value: string,
  final: boolean,
): { index: number; length: number } | null {
  for (let index = 0; index < value.length; index += 1) {
    const character = value[index]
    if (character === '\n') {
      return { index, length: 1 }
    }
    if (character === '\r') {
      if (index === value.length - 1 && !final) {
        return null
      }
      return {
        index,
        length: value[index + 1] === '\n' ? 2 : 1,
      }
    }
  }
  return null
}

function isKnownEventName(value: string): value is ChatSseEventName {
  return value === 'meta'
    || value === 'delta'
    || value === 'source'
    || value === 'done'
    || value === 'error'
}

function parseChatEvent(
  name: ChatSseEventName,
  payload: unknown,
): ChatStreamEvent {
  switch (name) {
    case 'meta':
      if (isMeta(payload)) {
        return { event: name, data: payload }
      }
      break
    case 'delta':
      if (isDelta(payload)) {
        return { event: name, data: payload }
      }
      break
    case 'source':
      if (isSource(payload)) {
        return { event: name, data: payload }
      }
      break
    case 'done':
      if (isDone(payload)) {
        return { event: name, data: payload }
      }
      break
    case 'error':
      if (isErrorEnvelope(payload)) {
        return { event: name, data: payload }
      }
      break
  }
  throw protocolError('问答流包含无法识别的数据')
}

function isMeta(value: unknown): value is ChatStreamMetaDto {
  if (!isRecord(value) || !isPositiveNumber(value.sessionId)
      || !isPositiveNumber(value.assistantMessageId)
      || !isRecord(value.intent)) {
    return false
  }
  return isIntentType(value.intent.type)
    && typeof value.intent.confidence === 'number'
    && Number.isFinite(value.intent.confidence)
}

function isDelta(value: unknown): value is ChatDeltaDto {
  return isRecord(value) && typeof value.content === 'string'
}

function isSource(value: unknown): value is SourceCitationDto {
  if (!isRecord(value)
      || !isPositiveNumber(value.chunkId)
      || (value.sourceType !== 'OFFICIAL' && value.sourceType !== 'UGC')
      || !isNullableString(value.sourceOrg)
      || !isNullableString(value.publishedAt)
      || !isNullableNumber(value.relevanceScore)
      || !isNullableString(value.quote)) {
    return false
  }
  if (value.sourceType === 'OFFICIAL') {
    if (typeof value.title !== 'string' || value.title.trim().length === 0) {
      return false
    }
    if (value.referenceType === 'EXTERNAL') {
      return value.documentId === null
        && value.externalSourceUrl === null
        && typeof value.sourceUrl === 'string'
        && value.sourceUrl.trim().length > 0
    }
    return value.referenceType === 'INTERNAL'
      && isPositiveNumber(value.documentId)
      && value.sourceUrl === null
      && isNullableString(value.externalSourceUrl)
  }
  return value.referenceType === 'UGC'
    && value.documentId === null
    && value.externalSourceUrl === null
    && isNullableString(value.title)
    && isNullableString(value.sourceUrl)
}

function isDone(value: unknown): value is ChatDoneDto {
  return isRecord(value)
    && isPositiveNumber(value.sessionId)
    && isPositiveNumber(value.assistantMessageId)
    && isAnswerStatus(value.answerStatus)
    && typeof value.answer === 'string'
    && Array.isArray(value.sources)
    && value.sources.every(isSource)
}

function isErrorEnvelope(
  value: unknown,
): value is ChatSseEventMap['error'] {
  return isRecord(value)
    && typeof value.code === 'number'
    && Number.isInteger(value.code)
    && typeof value.message === 'string'
    && Object.prototype.hasOwnProperty.call(value, 'data')
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

function isPositiveNumber(value: unknown): value is number {
  return typeof value === 'number'
    && Number.isSafeInteger(value)
    && value > 0
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}

function isNullableNumber(value: unknown): value is number | null {
  return value === null
    || (typeof value === 'number' && Number.isFinite(value))
}

function isIntentType(value: unknown): value is IntentType {
  return value === 'OFFICIAL'
    || value === 'EXPERIENTIAL'
    || value === 'MIXED'
    || value === 'UNKNOWN'
}

function isAnswerStatus(value: unknown): value is AnswerStatus {
  return value === 'ANSWERED'
    || value === 'NO_EVIDENCE'
    || value === 'NEED_PROFILE'
    || value === 'FAILED'
}

function isAbortLike(error: unknown): boolean {
  return isRecord(error) && error.name === 'AbortError'
}

async function jsonResponseError(
  response: Response,
): Promise<ChatStreamError> {
  let payload: unknown
  try {
    payload = await response.json()
  } catch {
    return protocolError('服务器返回了无法识别的错误响应', response.status)
  }
  if (!isErrorEnvelope(payload)) {
    return protocolError('服务器返回了无法识别的错误响应', response.status)
  }
  return new ChatStreamError(
    safeMessage(payload.message, `问答请求失败（HTTP ${response.status}）`),
    'server',
    response.status,
    payload.code,
  )
}

function protocolError(message: string, status = 200): ChatStreamError {
  return new ChatStreamError(message, 'protocol', status)
}

function safeMessage(message: string, fallback: string): string {
  const normalized = message
    .replace(/[\u0000-\u001f\u007f]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 160)
  return normalized || fallback
}
