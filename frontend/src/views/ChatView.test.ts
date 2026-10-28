// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, nextTick, ref } from 'vue'

import { ApiClientError } from '../api/client'
import type {
  ChatDoneDto,
  ChatSessionDetailDto,
  ChatStreamRequest,
  PageResult,
  SourceCitationDto,
} from '../api/types'
import {
  ChatStreamError,
  type ChatStreamHandlers,
  type ChatStreamOptions,
} from '../api/chatStream'
import ChatView from './ChatView.vue'

const mocks = vi.hoisted(() => ({
  downloadDocument: vi.fn(),
  listChatSessions: vi.fn(),
  getChatSession: vi.fn(),
  streamChat: vi.fn(),
}))

vi.mock('../api/endpoints', () => ({
  listChatSessions: mocks.listChatSessions,
  getChatSession: mocks.getChatSession,
}))

vi.mock('../api/adminEndpoints', () => ({
  downloadDocument: mocks.downloadDocument,
}))

vi.mock('../api/chatStream', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/chatStream')>()
  return {
    ...original,
    streamChat: mocks.streamChat,
  }
})

const officialSource: SourceCitationDto = {
  chunkId: 11,
  sourceType: 'OFFICIAL',
  referenceType: 'EXTERNAL',
  documentId: null,
  externalSourceUrl: null,
  title: '2026 级新生报到通知',
  sourceUrl: 'https://school.example/notices/11',
  sourceOrg: '学生处',
  publishedAt: '2026-07-01T08:00:00+08:00',
  relevanceScore: 0.98,
  quote: '新生于九月一日报到。',
}

const ugcSource: SourceCitationDto = {
  chunkId: 12,
  sourceType: 'UGC',
  referenceType: 'UGC',
  documentId: null,
  externalSourceUrl: null,
  title: '报到当天的小提示',
  sourceUrl: null,
  sourceOrg: null,
  publishedAt: '2026-06-20T11:00:00+08:00',
  relevanceScore: 0.76,
  quote: '东门排队通常更短。',
}

const sessionPage: PageResult<{
  id: number
  title: string | null
  createdAt: string
  updatedAt: string
  lastMessagePreview: string | null
}> = {
  items: [{
    id: 42,
    title: '迎新办理',
    createdAt: '2026-07-01T08:00:00+08:00',
    updatedAt: '2026-07-02T08:00:00+08:00',
    lastMessagePreview: '报到需要什么材料？',
  }],
  total: 1,
  page: 1,
  size: 20,
  pages: 1,
}

type SessionPage = typeof sessionPage

const sessionDetail: ChatSessionDetailDto = {
  id: 42,
  title: '迎新办理',
  hasMore: false,
  createdAt: '2026-07-01T08:00:00+08:00',
  updatedAt: '2026-07-02T08:00:00+08:00',
  messages: [
    {
      id: 1,
      role: 'USER',
      content: '报到需要什么材料？',
      sources: [],
      createdAt: '2026-07-01T08:00:00+08:00',
    },
    {
      id: 2,
      role: 'ASSISTANT',
      content: '请带录取通知书和身份证。',
      sources: [officialSource],
      createdAt: '2026-07-01T08:01:00+08:00',
    },
  ],
}

function answeredDone(): ChatDoneDto {
  return {
    sessionId: 73,
    assistantMessageId: 91,
    answerStatus: 'ANSWERED',
    answer: '最终公报答案：九月一日报到。',
    sources: [officialSource, ugcSource],
  }
}

function deferred<T>(): {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason: unknown) => void
} {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

describe('ChatView', () => {
  beforeEach(() => {
    mocks.downloadDocument.mockReset()
    mocks.listChatSessions.mockReset()
    mocks.getChatSession.mockReset()
    mocks.streamChat.mockReset()
    mocks.listChatSessions.mockResolvedValue(sessionPage)
    mocks.getChatSession.mockResolvedValue(sessionDetail)
  })

  it('downloads an internal document source through the authenticated endpoint', async () => {
    const internalSource = {
      chunkId: 19,
      sourceType: 'OFFICIAL',
      referenceType: 'INTERNAL',
      documentId: 319,
      sourceUrl: null,
      externalSourceUrl: null,
      title: 'Internal schedule',
      sourceOrg: 'Registrar',
      publishedAt: null,
      relevanceScore: 0.91,
      quote: 'Internal schedule excerpt',
    } as SourceCitationDto
    mocks.getChatSession.mockResolvedValue({
      ...sessionDetail,
      messages: [{
        ...sessionDetail.messages[1]!,
        sources: [internalSource],
      }],
    })
    mocks.downloadDocument.mockReturnValue(new Promise(() => undefined))

    const wrapper = mount(ChatView)
    await flushPromises()
    await wrapper.get('[data-session-id="42"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-action="download-document"]').trigger('click')

    expect(mocks.downloadDocument).toHaveBeenCalledWith(319)
  })

  it('loads the session index and opens a selected session detail', async () => {
    const wrapper = mount(ChatView)
    await flushPromises()

    expect(mocks.listChatSessions).toHaveBeenCalledWith(1, 20)
    expect(wrapper.text()).toContain('迎新办理')
    await wrapper.get('[data-session-id="42"]').trigger('click')
    await flushPromises()

    expect(mocks.getChatSession).toHaveBeenCalledWith(42)
    expect(wrapper.text()).toContain('报到需要什么材料？')
    expect(wrapper.text()).toContain('请带录取通知书和身份证。')
    expect(wrapper.text()).toContain('官方文件')
    expect(wrapper.find('.history-limit-note').exists()).toBe(false)
  })

  it('shows and resets the bounded-history notice from session detail metadata', async () => {
    mocks.getChatSession.mockResolvedValue({ ...sessionDetail, hasMore: true })
    const wrapper = mount(ChatView)
    await flushPromises()

    await wrapper.get('[data-session-id="42"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('.history-limit-note').text())
      .toBe('当前仅显示最近 200 条消息。')

    await wrapper.get('[data-action="new-chat"]').trigger('click')
    expect(wrapper.find('.history-limit-note').exists()).toBe(false)
  })

  it('keeps the bounded-history notice when opening another session fails', async () => {
    mocks.listChatSessions.mockResolvedValue({
      ...sessionPage,
      items: [
        sessionPage.items[0]!,
        { ...sessionPage.items[0]!, id: 43, title: 'Other session' },
      ],
    })
    mocks.getChatSession
      .mockResolvedValueOnce({ ...sessionDetail, hasMore: true })
      .mockRejectedValueOnce(new Error('detail unavailable'))
    const wrapper = mount(ChatView)
    await flushPromises()

    await wrapper.get('[data-session-id="42"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-session-id="43"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain(sessionDetail.messages[0]!.content)
    expect(wrapper.find('.history-limit-note').exists()).toBe(true)
  })

  it('keeps only the latest session index response when requests finish in reverse order', async () => {
    const firstRequest = deferred<SessionPage | null>()
    const latestRequest = deferred<SessionPage | null>()
    mocks.listChatSessions
      .mockReturnValueOnce(firstRequest.promise)
      .mockReturnValueOnce(latestRequest.promise)
    mocks.streamChat.mockImplementation(async (
      _request: ChatStreamRequest,
      handlers: ChatStreamHandlers,
    ) => {
      const final = answeredDone()
      handlers.onMeta?.({
        sessionId: final.sessionId,
        assistantMessageId: final.assistantMessageId,
        intent: { type: 'OFFICIAL', confidence: 0.9 },
      })
      handlers.onDone?.(final)
      return final
    })
    const wrapper = mount(ChatView)
    await nextTick()

    await wrapper.get('textarea[name="question"]').setValue('触发最新目录刷新')
    await wrapper.get('form[data-chat-composer]').trigger('submit')
    await flushPromises()
    expect(mocks.listChatSessions).toHaveBeenCalledTimes(2)

    latestRequest.resolve({
      ...sessionPage,
      items: [{ ...sessionPage.items[0]!, id: 52, title: '最新会话目录' }],
    })
    await flushPromises()
    expect(wrapper.text()).toContain('最新会话目录')
    expect(wrapper.text()).not.toContain('正在翻阅会话档案')

    firstRequest.resolve({
      ...sessionPage,
      items: [{ ...sessionPage.items[0]!, id: 51, title: '过期会话目录' }],
    })
    await flushPromises()
    expect(wrapper.text()).toContain('最新会话目录')
    expect(wrapper.text()).not.toContain('过期会话目录')
  })

  it('ignores a stale session index failure after a newer request succeeds', async () => {
    const firstRequest = deferred<SessionPage | null>()
    const latestRequest = deferred<SessionPage | null>()
    mocks.listChatSessions
      .mockReturnValueOnce(firstRequest.promise)
      .mockReturnValueOnce(latestRequest.promise)
    mocks.streamChat.mockImplementation(async (
      _request: ChatStreamRequest,
      handlers: ChatStreamHandlers,
    ) => {
      const final = answeredDone()
      handlers.onMeta?.({
        sessionId: final.sessionId,
        assistantMessageId: final.assistantMessageId,
        intent: { type: 'OFFICIAL', confidence: 0.9 },
      })
      handlers.onDone?.(final)
      return final
    })
    const wrapper = mount(ChatView)
    await nextTick()
    await wrapper.get('textarea[name="question"]').setValue('触发第二次目录请求')
    await wrapper.get('form[data-chat-composer]').trigger('submit')
    await flushPromises()

    latestRequest.resolve({
      ...sessionPage,
      items: [{ ...sessionPage.items[0]!, id: 62, title: '已确认的新目录' }],
    })
    await flushPromises()
    firstRequest.reject(new ApiClientError('过期目录错误', 50000, 500))
    await flushPromises()

    expect(wrapper.text()).toContain('已确认的新目录')
    expect(wrapper.text()).not.toContain('过期目录错误')
    expect(wrapper.text()).not.toContain('正在翻阅会话档案')
  })

  it('aligns the question field limit with the 1000-code-point backend contract', async () => {
    const wrapper = mount(ChatView)
    await flushPromises()

    expect(wrapper.get('textarea[name="question"]').attributes('maxlength'))
      .toBe('1000')
  })

  it('uses done as the authoritative answer and replaces duplicate streamed sources', async () => {
    const final = answeredDone()
    mocks.streamChat.mockImplementation(async (
      request: ChatStreamRequest,
      handlers: ChatStreamHandlers,
    ) => {
      expect(request.sessionId).toBeNull()
      handlers.onMeta?.({
        sessionId: 73,
        assistantMessageId: 91,
        intent: { type: 'MIXED', confidence: 0.9 },
      })
      handlers.onDelta?.({ content: '尚未校准的增量' })
      handlers.onSource?.(officialSource)
      handlers.onDone?.(final)
      return final
    })
    const wrapper = mount(ChatView)
    await flushPromises()

    await wrapper.get('textarea[name="question"]').setValue('新生哪天报到？')
    await wrapper.get('form[data-chat-composer]').trigger('submit')
    await flushPromises()

    expect(mocks.streamChat).toHaveBeenCalledWith(
      expect.objectContaining({
        sessionId: null,
        question: '新生哪天报到？',
      }),
      expect.any(Object),
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    )
    expect(wrapper.text()).toContain('新生哪天报到？')
    expect(wrapper.text()).toContain('最终公报答案：九月一日报到。')
    expect(wrapper.text()).not.toContain('尚未校准的增量')
    expect(wrapper.text()).toContain('官方依据')
    expect(wrapper.text()).toContain('同学经验')
    expect(wrapper.findAll('.source-card--official')).toHaveLength(1)
    expect(wrapper.findAll('.source-card--ugc')).toHaveLength(1)
    expect(mocks.listChatSessions).toHaveBeenCalledTimes(2)
  })

  it('shows an explicit NO_EVIDENCE notice instead of implying an answer', async () => {
    const final: ChatDoneDto = {
      sessionId: 74,
      assistantMessageId: 92,
      answerStatus: 'NO_EVIDENCE',
      answer: '后端建议：请联系教务处核实最新通知。',
      sources: [],
    }
    mocks.streamChat.mockImplementation(async (
      _request: ChatStreamRequest,
      handlers: ChatStreamHandlers,
    ) => {
      handlers.onMeta?.({
        sessionId: 74,
        assistantMessageId: 92,
        intent: { type: 'UNKNOWN', confidence: 0.2 },
      })
      handlers.onDone?.(final)
      return final
    })
    const wrapper = mount(ChatView)
    await flushPromises()

    await wrapper.get('textarea[name="question"]').setValue('食堂有隐藏菜单吗？')
    await wrapper.get('form[data-chat-composer]').trigger('submit')
    await flushPromises()

    const notice = wrapper.get('[data-answer-status="NO_EVIDENCE"]')
    expect(notice.text()).toContain('暂无足够依据，不生成结论')
    expect(notice.text()).toContain('后端建议：请联系教务处核实最新通知。')
    expect(wrapper.find('.source-groups').exists()).toBe(false)
  })

  it('blocks a second new-session question until meta supplies the session id', async () => {
    const pendingStreams: Array<{
      request: ChatStreamRequest
      handlers: ChatStreamHandlers
      options: ChatStreamOptions
      result: ReturnType<typeof deferred<ChatDoneDto>>
    }> = []
    mocks.streamChat.mockImplementation((
      request: ChatStreamRequest,
      handlers: ChatStreamHandlers,
      options: ChatStreamOptions,
    ) => {
      const result = deferred<ChatDoneDto>()
      pendingStreams.push({ request, handlers, options, result })
      return result.promise
    })
    const wrapper = mount(ChatView)
    await flushPromises()

    await wrapper.get('textarea[name="question"]').setValue('新会话第一问')
    await wrapper.get('form[data-chat-composer]').trigger('submit')
    await wrapper.get('textarea[name="question"]').setValue('meta 前第二问')

    const submitButton = wrapper.get('form[data-chat-composer] button[type="submit"]')
    expect(submitButton.attributes('disabled')).toBeDefined()
    await wrapper.get('form[data-chat-composer]').trigger('submit')
    expect(mocks.streamChat).toHaveBeenCalledTimes(1)
    expect(pendingStreams[0]?.options.signal?.aborted).toBe(false)

    pendingStreams[0]?.handlers.onMeta?.({
      sessionId: 73,
      assistantMessageId: 91,
      intent: { type: 'OFFICIAL', confidence: 0.9 },
    })
    await nextTick()
    expect(submitButton.attributes('disabled')).toBeUndefined()

    await wrapper.get('form[data-chat-composer]').trigger('submit')
    expect(mocks.streamChat).toHaveBeenCalledTimes(2)
    expect(pendingStreams[0]?.options.signal?.aborted).toBe(true)
    expect(pendingStreams[1]?.request.sessionId).toBe(73)

    pendingStreams[0]?.result.resolve(answeredDone())
    pendingStreams[1]?.result.resolve(answeredDone())
    await flushPromises()
  })

  it('blocks question submission while a different session detail is loading', async () => {
    mocks.listChatSessions.mockResolvedValue({
      ...sessionPage,
      total: 2,
      items: [
        ...sessionPage.items,
        {
          id: 43,
          title: '选课会话',
          createdAt: '2026-07-03T08:00:00+08:00',
          updatedAt: '2026-07-03T08:00:00+08:00',
          lastMessagePreview: '选课何时开始？',
        },
      ],
    })
    let resolveDetail: ((value: ChatSessionDetailDto) => void) | undefined
    mocks.getChatSession
      .mockResolvedValueOnce(sessionDetail)
      .mockImplementationOnce(() => new Promise<ChatSessionDetailDto>((resolve) => {
        resolveDetail = resolve
      }))
    mocks.streamChat.mockResolvedValue(answeredDone())
    const wrapper = mount(ChatView)
    await flushPromises()

    await wrapper.get('[data-session-id="42"]').trigger('click')
    await flushPromises()
    await wrapper.get('textarea[name="question"]').setValue('不应发到旧会话的问题')
    await wrapper.get('[data-session-id="43"]').trigger('click')

    expect(wrapper.get('textarea[name="question"]').attributes('disabled'))
      .toBeDefined()
    await wrapper.get('form[data-chat-composer]').trigger('submit')
    expect(mocks.streamChat).not.toHaveBeenCalled()

    resolveDetail?.({ ...sessionDetail, id: 43, title: '选课会话' })
    await flushPromises()
  })

  it('forces the message ledger to the bottom after history load and local append', async () => {
    const originalScrollHeight = Object.getOwnPropertyDescriptor(
      HTMLElement.prototype,
      'scrollHeight',
    )
    Object.defineProperty(HTMLElement.prototype, 'scrollHeight', {
      configurable: true,
      get: () => 900,
    })
    const pending = deferred<ChatDoneDto>()
    mocks.streamChat.mockReturnValue(pending.promise)

    try {
      const wrapper = mount(ChatView)
      await flushPromises()
      await wrapper.get('[data-session-id="42"]').trigger('click')
      await flushPromises()
      expect((wrapper.get('.message-ledger').element as HTMLElement).scrollTop)
        .toBe(900)

      await wrapper.get('[data-action="new-chat"]').trigger('click')
      await wrapper.get('textarea[name="question"]').setValue('追加的新问题')
      await wrapper.get('form[data-chat-composer]').trigger('submit')
      await nextTick()
      expect((wrapper.get('.message-ledger').element as HTMLElement).scrollTop)
        .toBe(900)

      pending.resolve(answeredDone())
      await flushPromises()
      wrapper.unmount()
    } finally {
      if (originalScrollHeight) {
        Object.defineProperty(
          HTMLElement.prototype,
          'scrollHeight',
          originalScrollHeight,
        )
      } else {
        Reflect.deleteProperty(HTMLElement.prototype, 'scrollHeight')
      }
    }
  })

  it('follows stream updates near the bottom without stealing an upward reader scroll', async () => {
    const pending = deferred<ChatDoneDto>()
    let streamHandlers: ChatStreamHandlers | undefined
    mocks.streamChat.mockImplementation((
      _request: ChatStreamRequest,
      handlers: ChatStreamHandlers,
    ) => {
      streamHandlers = handlers
      return pending.promise
    })
    const wrapper = mount(ChatView)
    await flushPromises()
    await wrapper.get('textarea[name="question"]').setValue('需要流式滚动的问题')
    await wrapper.get('form[data-chat-composer]').trigger('submit')
    await nextTick()

    const ledger = wrapper.get('.message-ledger')
    const ledgerElement = ledger.element as HTMLElement
    const metrics = { scrollHeight: 1000, clientHeight: 200 }
    Object.defineProperties(ledgerElement, {
      scrollHeight: {
        configurable: true,
        get: () => metrics.scrollHeight,
      },
      clientHeight: {
        configurable: true,
        get: () => metrics.clientHeight,
      },
    })
    ledgerElement.scrollTop = 800
    await ledger.trigger('scroll')

    streamHandlers?.onDelta?.({ content: '第一段' })
    metrics.scrollHeight = 1200
    await flushPromises()
    expect(ledgerElement.scrollTop).toBe(1200)

    ledgerElement.scrollTop = 200
    await ledger.trigger('scroll')
    streamHandlers?.onDone?.(answeredDone())
    metrics.scrollHeight = 1500
    await flushPromises()
    expect(ledgerElement.scrollTop).toBe(200)

    pending.resolve(answeredDone())
    await flushPromises()
  })

  it('marks a partial streamed draft as failed when the stream terminates with an error', async () => {
    mocks.streamChat.mockImplementation(async (
      _request: ChatStreamRequest,
      handlers: ChatStreamHandlers,
    ) => {
      handlers.onMeta?.({
        sessionId: 75,
        assistantMessageId: 93,
        intent: { type: 'OFFICIAL', confidence: 0.8 },
      })
      handlers.onDelta?.({ content: '尚未完成的局部草稿' })
      throw new ChatStreamError('检索暂时失败', 'server', 200, 50000)
    })
    const wrapper = mount(ChatView)
    await flushPromises()

    await wrapper.get('textarea[name="question"]').setValue('查询一条会失败的问题')
    await wrapper.get('form[data-chat-composer]').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[data-answer-status="FAILED"]').text())
      .toContain('本次回答未完成')
    expect(wrapper.text()).not.toContain('尚未完成的局部草稿')
    expect(wrapper.text()).toContain('检索暂时失败')
  })

  it('shows the user message immediately and isolates a cancelled old stream', async () => {
    let settle: ((value: ChatDoneDto) => void) | undefined
    mocks.streamChat.mockImplementation((
      _request: ChatStreamRequest,
      _handlers: ChatStreamHandlers,
      _options: ChatStreamOptions,
    ) => new Promise<ChatDoneDto>((resolve) => {
      settle = resolve
    }))
    const wrapper = mount(ChatView)
    await flushPromises()

    await wrapper.get('textarea[name="question"]').setValue('还在查询的问题')
    await wrapper.get('form[data-chat-composer]').trigger('submit')
    expect(wrapper.text()).toContain('还在查询的问题')
    const firstSignal = mocks.streamChat.mock.calls[0]?.[2]?.signal
    expect(firstSignal?.aborted).toBe(false)

    await wrapper.get('[data-action="new-chat"]').trigger('click')
    expect(firstSignal?.aborted).toBe(true)
    expect(wrapper.text()).not.toContain('还在查询的问题')

    settle?.(answeredDone())
    await flushPromises()
    expect(wrapper.text()).not.toContain('最终公报答案')
  })

  it('clears uncalibrated sources when a streamed answer is cancelled', async () => {
    const streams: Array<{
      handlers: ChatStreamHandlers
      result: ReturnType<typeof deferred<ChatDoneDto>>
    }> = []
    mocks.streamChat.mockImplementation((
      _request: ChatStreamRequest,
      handlers: ChatStreamHandlers,
    ) => {
      const result = deferred<ChatDoneDto>()
      streams.push({ handlers, result })
      return result.promise
    })
    const wrapper = mount(ChatView)
    await flushPromises()

    await wrapper.get('textarea[name="question"]').setValue('第一个问题')
    await wrapper.get('form[data-chat-composer]').trigger('submit')
    streams[0]?.handlers.onMeta?.({
      sessionId: 73,
      assistantMessageId: 91,
      intent: { type: 'MIXED', confidence: 0.9 },
    })
    streams[0]?.handlers.onSource?.(officialSource)
    await nextTick()
    expect(wrapper.findAll('.source-card--official')).toHaveLength(1)

    await wrapper.get('textarea[name="question"]').setValue('第二个问题')
    await wrapper.get('form[data-chat-composer]').trigger('submit')

    expect(mocks.streamChat).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('本次查阅已取消。')
    expect(wrapper.findAll('.source-card--official')).toHaveLength(0)

    streams[0]?.result.resolve(answeredDone())
    streams[1]?.result.resolve(answeredDone())
    await flushPromises()
  })

  it('emits accurate streaming changes from start through completion', async () => {
    const pending = deferred<ChatDoneDto>()
    mocks.streamChat.mockReturnValue(pending.promise)
    const wrapper = mount(ChatView)
    await flushPromises()

    expect(wrapper.emitted('streaming-change')?.map((args) => args[0]))
      .toEqual([false])
    await wrapper.get('textarea[name="question"]').setValue('观察流状态')
    await wrapper.get('form[data-chat-composer]').trigger('submit')
    await nextTick()
    expect(wrapper.emitted('streaming-change')?.map((args) => args[0]))
      .toEqual([false, true])

    pending.resolve(answeredDone())
    await flushPromises()
    expect(wrapper.emitted('streaming-change')?.map((args) => args[0]))
      .toEqual([false, true, false])
  })

  it('cancels an active stream when deactivated inside KeepAlive', async () => {
    const pending = deferred<ChatDoneDto>()
    mocks.streamChat.mockReturnValue(pending.promise)
    const visible = ref(true)
    const Host = defineComponent({
      components: { ChatView },
      setup: () => ({ visible }),
      template: '<KeepAlive><ChatView v-if="visible" /></KeepAlive>',
    })
    const wrapper = mount(Host)
    await flushPromises()
    const chat = wrapper.getComponent(ChatView)

    await chat.get('textarea[name="question"]').setValue('隐藏时应取消')
    await chat.get('form[data-chat-composer]').trigger('submit')
    const signal = mocks.streamChat.mock.calls[0]?.[2]?.signal
    expect(signal?.aborted).toBe(false)

    visible.value = false
    await nextTick()
    expect(signal?.aborted).toBe(true)

    pending.resolve(answeredDone())
    await flushPromises()
    wrapper.unmount()
  })

  it('clears a stale detail load when reactivated inside KeepAlive', async () => {
    const pendingDetail = deferred<ChatSessionDetailDto | null>()
    mocks.getChatSession.mockReturnValue(pendingDetail.promise)
    const visible = ref(true)
    const Host = defineComponent({
      components: { ChatView },
      setup: () => ({ visible }),
      template: '<KeepAlive><ChatView v-if="visible" /></KeepAlive>',
    })
    const wrapper = mount(Host)
    await flushPromises()
    const chat = wrapper.getComponent(ChatView)

    await chat.get('textarea[name="question"]').setValue('返回后继续提问')
    await chat.get('[data-session-id="42"]').trigger('click')
    expect(chat.find('.desk-loading').exists()).toBe(true)
    expect(chat.get('textarea[name="question"]').attributes('disabled')).toBeDefined()

    visible.value = false
    await nextTick()
    visible.value = true
    await nextTick()

    const reactivatedChat = wrapper.getComponent(ChatView)
    expect(reactivatedChat.find('.desk-loading').exists()).toBe(false)
    expect(reactivatedChat.get('textarea[name="question"]').attributes('disabled'))
      .toBeUndefined()
    expect(reactivatedChat.get('form[data-chat-composer] button[type="submit"]')
      .attributes('disabled')).toBeUndefined()

    pendingDetail.resolve(sessionDetail)
    await flushPromises()

    expect(reactivatedChat.text()).not.toContain('请带录取通知书和身份证。')
    expect(reactivatedChat.find('.desk-loading').exists()).toBe(false)
    expect(reactivatedChat.get('textarea[name="question"]').attributes('disabled'))
      .toBeUndefined()
    wrapper.unmount()
  })

  it('emits unauthorized when the session request is rejected with 401', async () => {
    mocks.listChatSessions.mockRejectedValue(
      new ApiClientError('请先登录', 40101, 401),
    )

    const wrapper = mount(ChatView)
    await flushPromises()

    expect(wrapper.emitted('unauthorized')).toHaveLength(1)
  })

  it('keeps the session and displays an HTTP 403 error', async () => {
    mocks.listChatSessions.mockRejectedValue(
      new ApiClientError('当前账号无权查看会话', 40300, 403),
    )

    const wrapper = mount(ChatView)
    await flushPromises()

    expect(wrapper.emitted('unauthorized')).toBeUndefined()
    expect(wrapper.text()).toContain('当前账号无权查看会话')
  })
})
