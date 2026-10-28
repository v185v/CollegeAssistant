import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  ChatStreamAbortError,
  ChatStreamError,
  createChatSseParser,
  isChatStreamAbort,
  streamChat,
  type ChatStreamEvent,
} from './chatStream'

const meta = {
  sessionId: 17,
  assistantMessageId: 29,
  intent: { type: 'MIXED' as const, confidence: 0.91 },
}

const officialSource = {
  chunkId: 3,
  sourceType: 'OFFICIAL' as const,
  title: '本科生学籍管理规定',
  sourceUrl: 'https://school.example/rules/3',
  sourceOrg: '教务处',
  publishedAt: '2026-01-02T03:04:05+08:00',
  relevanceScore: 0.96,
  referenceType: 'EXTERNAL' as const,
  documentId: null,
  externalSourceUrl: null,
  quote: '学生应在规定时间内完成注册。',
}

const done = {
  sessionId: 17,
  assistantMessageId: 29,
  answerStatus: 'ANSWERED' as const,
  answer: '以最终校务公报为准。',
  sources: [officialSource],
}

function encodedStream(chunks: Uint8Array[]): ReadableStream<Uint8Array> {
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(chunk)
      }
      controller.close()
    },
  })
}

function sseResponse(
  chunks: Uint8Array[],
  init: ResponseInit = {},
): Response {
  return new Response(encodedStream(chunks), {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' },
    ...init,
  })
}

describe('incremental chat SSE parser', () => {
  it('parses CRLF across chunks, comments, unknown fields, and multiple data lines', () => {
    const events: ChatStreamEvent[] = []
    const parser = createChatSseParser((event) => events.push(event))

    parser.push(': keep-alive\revent: ignored-until-newline')
    parser.push('\r\n\r\nevent: meta\r')
    parser.push(`\ndata: ${JSON.stringify(meta)}\r\n\r\n`)
    parser.push('event: heartbeat\ndata: not-json\n\n')
    parser.push('event: delta\nid: 8\nretry: 2500\n')
    parser.push('data: {"content":\n')
    parser.push('data: "分行内容"}\n\n')
    parser.push(`event: source\ndata: ${JSON.stringify(officialSource)}\n\n`)
    parser.push(`event: done\ndata: ${JSON.stringify(done)}\n`)

    expect(parser.finish()).toEqual(done)
    expect(events).toEqual([
      { event: 'meta', data: meta },
      { event: 'delta', data: { content: '分行内容' } },
      { event: 'source', data: officialSource },
      { event: 'done', data: done },
    ])
  })

  it('turns a named error event into a sanitized typed error', () => {
    const events: ChatStreamEvent[] = []
    const parser = createChatSseParser((event) => events.push(event))

    parser.push(`event:meta\ndata:${JSON.stringify(meta)}\n\n`)
    parser.push('event:delta\ndata:{"content":"半段回答"}\n\n')

    expect(() => parser.push(
      'event:error\ndata:{"code":50000,"message":"  检索失败\\r\\n请稍后  ","data":null}\n\n',
    )).toThrowError(expect.objectContaining({
      name: 'ChatStreamError',
      kind: 'server',
      code: 50000,
      message: '检索失败 请稍后',
    }))
    expect(events.map((event) => event.event)).toEqual(['meta', 'delta'])
  })

  it('rejects malformed JSON without echoing the frame', () => {
    const parser = createChatSseParser(() => undefined)

    const failure = (() => {
      try {
        parser.push('event:delta\ndata:{private-answer\n\n')
      } catch (error) {
        return error
      }
      return null
    })()

    expect(failure).toMatchObject({
      name: 'ChatStreamError',
      kind: 'protocol',
      message: '问答流包含无法识别的数据',
    })
    expect(String(failure)).not.toContain('private-answer')
  })

  it('rejects an internal source whose document identifier is invalid', () => {
    const parser = createChatSseParser(() => undefined)
    const invalidSource = {
      ...officialSource,
      referenceType: 'INTERNAL',
      sourceUrl: null,
      externalSourceUrl: null,
      documentId: 0,
    }

    expect(() => parser.push(
      `event:source\ndata:${JSON.stringify(invalidSource)}\n\n`,
    )).toThrowError(expect.objectContaining({
      name: 'ChatStreamError',
      kind: 'protocol',
    }))
  })

  it('rejects EOF when no done event was received', () => {
    const parser = createChatSseParser(() => undefined)
    parser.push(`event:meta\ndata:${JSON.stringify(meta)}\n\n`)

    expect(() => parser.finish()).toThrowError(expect.objectContaining({
      name: 'ChatStreamError',
      kind: 'protocol',
      message: '问答连接提前结束，请重试',
    }))
  })
})

describe('streamChat', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('posts to the same-origin stream endpoint and decodes Unicode across byte chunks', async () => {
    const wire = [
      `event:meta\ndata:${JSON.stringify(meta)}\n\n`,
      'event:delta\ndata:{"content":"校🙂园"}\n\n',
      `event:source\ndata:${JSON.stringify(officialSource)}\n\n`,
      `event:done\ndata:${JSON.stringify(done)}\n\n`,
    ].join('')
    const encoder = new TextEncoder()
    const bytes = encoder.encode(wire)
    const unicodeOffset = encoder.encode(wire.slice(0, wire.indexOf('🙂'))).length
    const chunks = Array.from(bytes, (_, index) => bytes.slice(index, index + 1))
    expect(chunks[unicodeOffset]?.byteLength).toBe(1)
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      sseResponse(chunks),
    )
    const onDelta = vi.fn()
    const onSource = vi.fn()
    const onDone = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('localStorage', { getItem: () => ' signed-token ' })

    const result = await streamChat(
      { sessionId: null, question: '如何注册？' },
      { onDelta, onSource, onDone },
    )

    expect(result).toEqual(done)
    expect(onDelta).toHaveBeenCalledWith({ content: '校🙂园' })
    expect(onSource).toHaveBeenCalledWith(officialSource)
    expect(onDone).toHaveBeenCalledWith(done)
    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toBe('/api/chat/stream')
    expect(init?.method).toBe('POST')
    expect(init?.body).toBe(JSON.stringify({
      sessionId: null,
      question: '如何注册？',
    }))
    const headers = new Headers(init?.headers)
    expect(headers.get('Authorization')).toBe('Bearer signed-token')
    expect(headers.get('Accept')).toBe('text/event-stream')
    expect(headers.get('Content-Type')).toBe('application/json')
  })

  it('recognizes an HTTP 200 JSON business error before attempting SSE parsing', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(
      Response.json(
        { code: 40401, message: '会话不存在', data: null },
        { status: 200 },
      ),
    ))
    vi.stubGlobal('localStorage', { getItem: () => null })

    await expect(streamChat(
      { sessionId: 404, question: '继续' },
      {},
    )).rejects.toMatchObject({
      name: 'ChatStreamError',
      kind: 'server',
      status: 200,
      code: 40401,
      message: '会话不存在',
    })
  })

  it.each([
    [400, 40001, '参数校验失败'],
    [401, 40101, '请先登录'],
    [500, 50000, '服务暂不可用'],
  ])('preserves safe JSON error metadata for HTTP %i', async (
    status,
    code,
    message,
  ) => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(
      Response.json({ code, message, data: null }, { status }),
    ))
    vi.stubGlobal('localStorage', { getItem: () => null })

    await expect(streamChat(
      { sessionId: null, question: '问题' },
      {},
    )).rejects.toMatchObject({ status, code, message })
  })

  it('rejects a successful response with an unexpected content type', async () => {
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(
      new Response('private upstream page', {
        status: 200,
        headers: { 'Content-Type': 'text/html' },
      }),
    ))
    vi.stubGlobal('localStorage', { getItem: () => null })

    const failure = await streamChat(
      { sessionId: null, question: '问题' },
      {},
    ).catch((error: unknown) => error)

    expect(failure).toMatchObject({
      name: 'ChatStreamError',
      kind: 'content-type',
      message: '服务器未返回问答流，请重试',
    })
    expect(String(failure)).not.toContain('private upstream page')
  })

  it('rejects an SSE response without a readable body', async () => {
    const response = {
      ok: true,
      status: 200,
      headers: new Headers({ 'Content-Type': 'text/event-stream' }),
      body: null,
    } as Response
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(response))
    vi.stubGlobal('localStorage', { getItem: () => null })

    await expect(streamChat(
      { sessionId: null, question: '问题' },
      {},
    )).rejects.toMatchObject({
      kind: 'protocol',
      message: '浏览器无法读取问答流，请重试',
    })
  })

  it('reports active aborts distinctly from network failures', async () => {
    const controller = new AbortController()
    controller.abort()
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockRejectedValue(
      new DOMException('The operation was aborted.', 'AbortError'),
    ))
    vi.stubGlobal('localStorage', { getItem: () => null })

    const failure = await streamChat(
      { sessionId: null, question: '问题' },
      {},
      { signal: controller.signal },
    ).catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(ChatStreamAbortError)
    expect(isChatStreamAbort(failure)).toBe(true)
    expect(failure).not.toBeInstanceOf(ChatStreamError)
  })

  it('continues without Authorization when a stored token cannot be encoded as a header', async () => {
    const encoder = new TextEncoder()
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      sseResponse([
        encoder.encode(`event:done\ndata:${JSON.stringify(done)}\n\n`),
      ]),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('localStorage', { getItem: () => 'token-🙂' })

    await expect(streamChat(
      { sessionId: null, question: '问题' },
      {},
    )).resolves.toEqual(done)

    const [, init] = fetchMock.mock.calls[0]!
    expect(new Headers(init?.headers).has('Authorization')).toBe(false)
  })

  it('keeps a received done result when the stream aborts immediately afterward', async () => {
    const encoder = new TextEncoder()
    const controller = new AbortController()
    vi.stubGlobal('fetch', vi.fn<typeof fetch>().mockResolvedValue(
      sseResponse([
        encoder.encode(`event:done\ndata:${JSON.stringify(done)}\n\n`),
      ]),
    ))
    vi.stubGlobal('localStorage', { getItem: () => null })

    await expect(streamChat(
      { sessionId: null, question: '问题' },
      { onDone: () => controller.abort() },
      { signal: controller.signal },
    )).resolves.toEqual(done)
  })
})
