<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  ref,
  watch,
} from 'vue'

import {
  ChatStreamError,
  isChatStreamAbort,
  streamChat,
} from '../api/chatStream'
import { downloadDocument } from '../api/adminEndpoints'
import { ApiClientError, isAuthenticationStatus } from '../api/client'
import { getChatSession, listChatSessions } from '../api/endpoints'
import type {
  AnswerStatus,
  ChatDoneDto,
  ChatMessageRole,
  ChatSessionSummaryDto,
  SourceCitationDto,
} from '../api/types'
import SourceCard from '../components/SourceCard.vue'

interface DisplayMessage {
  key: string
  role: ChatMessageRole
  content: string
  sources: SourceCitationDto[]
  answerStatus?: AnswerStatus
  pending?: boolean
  sourceError?: string
}

const emit = defineEmits<{
  unauthorized: []
  'streaming-change': [value: boolean]
}>()

const sessions = ref<ChatSessionSummaryDto[]>([])
const currentSessionId = ref<number | null>(null)
const messages = ref<DisplayMessage[]>([])
const question = ref('')
const sessionsLoading = ref(false)
const detailLoading = ref(false)
const historyHasMore = ref(false)
const streaming = ref(false)
const errorMessage = ref('')
const messageLedger = ref<HTMLElement | null>(null)
const awaitingNewSessionMeta = computed(
  () => streaming.value && currentSessionId.value === null,
)

let activeController: AbortController | null = null
let requestVersion = 0
let sessionsRequestVersion = 0
let messageSequence = 0
let ledgerNearBottom = true
let ledgerScrollScheduled = false
let ledgerForceScroll = false
const LEDGER_BOTTOM_THRESHOLD = 72
const HISTORY_LIMIT_NOTICE = '\u5f53\u524d\u4ec5\u663e\u793a\u6700\u8fd1 200 \u6761\u6d88\u606f\u3002'

watch(
  streaming,
  (value) => emit('streaming-change', value),
  { immediate: true, flush: 'sync' },
)

onMounted(() => {
  void loadSessions()
})

onBeforeUnmount(() => {
  sessionsRequestVersion += 1
  cancelCurrentStream()
})

onDeactivated(() => {
  cancelCurrentStream()
  detailLoading.value = false
})

async function loadSessions(silent = false): Promise<void> {
  const version = sessionsRequestVersion += 1
  sessionsLoading.value = !silent
  if (!silent) {
    errorMessage.value = ''
  }
  try {
    const page = await listChatSessions(1, 20)
    if (version !== sessionsRequestVersion) {
      return
    }
    if (!page) {
      if (!silent) {
        errorMessage.value = '会话目录暂时无法读取，请重试'
      }
      return
    }
    sessions.value = page.items
  } catch (error) {
    if (version === sessionsRequestVersion) {
      handleFailure(error, '会话目录加载失败，请稍后重试', silent)
    }
  } finally {
    if (version === sessionsRequestVersion) {
      sessionsLoading.value = false
    }
  }
}

async function openSession(id: number): Promise<void> {
  const version = cancelCurrentStream()
  detailLoading.value = true
  errorMessage.value = ''
  try {
    const detail = await getChatSession(id)
    if (version !== requestVersion) {
      return
    }
    if (!detail) {
      errorMessage.value = '会话内容为空，请刷新后重试'
      return
    }
    currentSessionId.value = detail.id
    historyHasMore.value = detail.hasMore
    messages.value = detail.messages.map((message) => ({
      key: `stored-${message.id}`,
      role: message.role,
      content: message.content,
      sources: [...message.sources],
    }))
    scheduleLedgerScroll(true)
  } catch (error) {
    if (version === requestVersion) {
      handleFailure(error, '会话内容加载失败，请稍后重试')
    }
  } finally {
    if (version === requestVersion) {
      detailLoading.value = false
    }
  }
}

function startNewChat(): void {
  cancelCurrentStream()
  currentSessionId.value = null
  messages.value = []
  historyHasMore.value = false
  question.value = ''
  detailLoading.value = false
  errorMessage.value = ''
}

async function submitQuestion(): Promise<void> {
  const normalizedQuestion = question.value.trim()
  if (
    !normalizedQuestion
    || detailLoading.value
    || awaitingNewSessionMeta.value
  ) {
    return
  }

  const version = cancelCurrentStream()
  const controller = new AbortController()
  activeController = controller
  streaming.value = true
  errorMessage.value = ''
  const requestedSessionId = currentSessionId.value
  const userKey = `local-user-${messageSequence += 1}`
  const assistantKey = `local-assistant-${messageSequence += 1}`
  messages.value.push(
    {
      key: userKey,
      role: 'USER',
      content: normalizedQuestion,
      sources: [],
    },
    {
      key: assistantKey,
      role: 'ASSISTANT',
      content: '',
      sources: [],
      pending: true,
    },
  )
  question.value = ''
  scheduleLedgerScroll(true)

  const updateAssistant = (
    update: (message: DisplayMessage) => void,
  ): void => {
    if (version !== requestVersion) {
      return
    }
    const assistant = messages.value.find(
      (message) => message.key === assistantKey,
    )
    if (assistant) {
      update(assistant)
    }
  }

  const applyDone = (event: ChatDoneDto): void => {
    if (version !== requestVersion) {
      return
    }
    const shouldFollow = ledgerWasNearBottom()
    currentSessionId.value = event.sessionId
    updateAssistant((assistant) => {
      assistant.content = event.answer
      assistant.answerStatus = event.answerStatus
      assistant.sources = [...event.sources]
      assistant.pending = false
    })
    if (shouldFollow) {
      scheduleLedgerScroll()
    }
  }

  try {
    const final = await streamChat(
      {
        sessionId: requestedSessionId,
        question: normalizedQuestion,
        useMemory: true,
        maxSources: 6,
      },
      {
        onMeta(event) {
          if (version === requestVersion) {
            currentSessionId.value = event.sessionId
          }
        },
        onDelta(event) {
          const shouldFollow = ledgerWasNearBottom()
          updateAssistant((assistant) => {
            assistant.content += event.content
          })
          if (shouldFollow) {
            scheduleLedgerScroll()
          }
        },
        onSource(event) {
          updateAssistant((assistant) => {
            if (!assistant.sources.some((source) => (
              source.chunkId === event.chunkId
              && source.sourceType === event.sourceType
            ))) {
              assistant.sources.push(event)
            }
          })
        },
        onDone: applyDone,
      },
      { signal: controller.signal },
    )
    applyDone(final)
    if (version === requestVersion) {
      await loadSessions(true)
    }
  } catch (error) {
    if (version !== requestVersion || isChatStreamAbort(error)) {
      return
    }
    updateAssistant((assistant) => {
      assistant.content = ''
      assistant.sources = []
      assistant.answerStatus = 'FAILED'
      assistant.pending = false
    })
    handleFailure(error, '本次问答未完成，请稍后重试')
  } finally {
    if (version === requestVersion) {
      activeController = null
      streaming.value = false
    }
  }
}

function cancelCurrentStream(): number {
  requestVersion += 1
  if (activeController) {
    for (const message of messages.value) {
      if (message.role === 'ASSISTANT' && message.pending) {
        message.content = '本次查阅已取消。'
        message.sources = []
        message.pending = false
      }
    }
  }
  activeController?.abort()
  activeController = null
  streaming.value = false
  return requestVersion
}

function handleFailure(
  error: unknown,
  fallback: string,
  silent = false,
): void {
  if (isUnauthorized(error)) {
    emit('unauthorized')
    return
  }
  if (!silent) {
    errorMessage.value = error instanceof ApiClientError
      || error instanceof ChatStreamError
      ? error.message
      : fallback
  }
}

function isUnauthorized(error: unknown): boolean {
  return (error instanceof ApiClientError || error instanceof ChatStreamError)
    && isAuthenticationStatus(error.status)
}

async function downloadSourceDocument(
  message: DisplayMessage,
  documentId: number,
): Promise<void> {
  message.sourceError = ''
  try {
    const download = await downloadDocument(documentId)
    const objectUrl = URL.createObjectURL(download.blob)
    const anchor = window.document.createElement('a')
    anchor.href = objectUrl
    anchor.download = download.filename ?? `document-${documentId}`
    anchor.hidden = true
    window.document.body.append(anchor)
    anchor.click()
    anchor.remove()
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 0)
  } catch (error) {
    if (isUnauthorized(error)) {
      emit('unauthorized')
      return
    }
    message.sourceError = error instanceof ApiClientError
      ? error.message
      : '\u8d44\u6599\u539f\u4ef6\u4e0b\u8f7d\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5'
  }
}

function officialSources(message: DisplayMessage): SourceCitationDto[] {
  return message.sources.filter((source) => source.sourceType === 'OFFICIAL')
}

function ugcSources(message: DisplayMessage): SourceCitationDto[] {
  return message.sources.filter((source) => source.sourceType === 'UGC')
}

function sessionTitle(session: ChatSessionSummaryDto): string {
  return session.title?.trim() || '未命名会话'
}

function onLedgerScroll(): void {
  ledgerNearBottom = ledgerWasNearBottom()
}

function ledgerWasNearBottom(): boolean {
  const ledger = messageLedger.value
  if (!ledger) {
    return true
  }
  return ledger.scrollHeight - ledger.clientHeight - ledger.scrollTop
    <= LEDGER_BOTTOM_THRESHOLD
}

function scheduleLedgerScroll(force = false): void {
  if (force) {
    ledgerForceScroll = true
    ledgerNearBottom = true
  } else if (!ledgerNearBottom) {
    return
  }
  if (ledgerScrollScheduled) {
    return
  }
  ledgerScrollScheduled = true
  void nextTick(() => {
    ledgerScrollScheduled = false
    const shouldForce = ledgerForceScroll
    ledgerForceScroll = false
    const ledger = messageLedger.value
    if (!ledger || (!shouldForce && !ledgerNearBottom)) {
      return
    }
    ledger.scrollTop = ledger.scrollHeight
    ledgerNearBottom = true
  })
}
</script>

<template>
  <section class="chat-workspace" aria-labelledby="chat-title">
    <aside class="session-index" aria-label="问答会话目录">
      <div class="session-index__heading">
        <div>
          <p>ARCHIVE · 会话档案</p>
          <h2>问答目录</h2>
        </div>
        <button
          class="square-action"
          type="button"
          data-action="new-chat"
          aria-label="新建会话"
          @click="startNewChat"
        >
          ＋
        </button>
      </div>

      <button class="new-chat-action" type="button" @click="startNewChat">
        <span>新会话</span>
        <span aria-hidden="true">空白稿 →</span>
      </button>

      <p v-if="sessionsLoading" class="index-note" role="status">
        正在翻阅会话档案…
      </p>
      <p v-else-if="sessions.length === 0" class="index-note">
        暂无往期会话，从一张空白稿开始吧。
      </p>
      <ol v-else class="session-list">
        <li v-for="session in sessions" :key="session.id">
          <button
            type="button"
            :data-session-id="session.id"
            :aria-current="currentSessionId === session.id ? 'page' : undefined"
            @click="openSession(session.id)"
          >
            <span class="session-list__title">{{ sessionTitle(session) }}</span>
            <span v-if="session.lastMessagePreview" class="session-list__preview">
              {{ session.lastMessagePreview }}
            </span>
          </button>
        </li>
      </ol>

      <button
        class="text-action"
        type="button"
        :disabled="sessionsLoading"
        @click="loadSessions()"
      >
        刷新目录
      </button>
    </aside>

    <section class="answer-desk" aria-labelledby="chat-title">
      <header class="answer-desk__heading">
        <div>
          <p class="section-folio">统一问答台 · LIVE DESK</p>
          <h1 id="chat-title">有出处，再作答。</h1>
        </div>
        <div class="desk-status" :class="{ 'desk-status--live': streaming }">
          <span aria-hidden="true"></span>
          {{ streaming ? '正在查阅' : '编辑台就绪' }}
        </div>
      </header>

      <p v-if="errorMessage" class="form-message form-message--error" role="alert">
        {{ errorMessage }}
      </p>
      <p v-if="detailLoading" class="desk-loading" role="status">
        正在装订会话内容…
      </p>

      <p v-if="historyHasMore" class="history-limit-note" role="status">
        {{ HISTORY_LIMIT_NOTICE }}
      </p>

      <div v-if="messages.length === 0 && !detailLoading" class="empty-desk">
        <span aria-hidden="true">问</span>
        <div>
          <h2>把问题写在这里</h2>
          <p>例如：转专业需要哪些材料？校历上的补考周是哪几天？</p>
        </div>
      </div>

      <ol
        v-else
        ref="messageLedger"
        class="message-ledger"
        aria-label="当前会话消息"
        @scroll="onLedgerScroll"
      >
        <li
          v-for="message in messages"
          :key="message.key"
          class="message-entry"
          :class="`message-entry--${message.role.toLowerCase()}`"
        >
          <article>
            <header>
              <span>{{ message.role === 'USER' ? '你的问题' : '校园智答' }}</span>
              <span aria-hidden="true">
                {{ message.role === 'USER' ? 'Q' : 'A' }}
              </span>
            </header>

            <div
              v-if="message.answerStatus === 'FAILED'"
              class="failed-answer"
              data-answer-status="FAILED"
              role="status"
            >
              <strong>本次回答未完成</strong>
              <p>已停止展示未校准的局部草稿，请稍后重新提问。</p>
            </div>
            <div
              v-else-if="message.answerStatus === 'NO_EVIDENCE'"
              class="no-evidence"
              data-answer-status="NO_EVIDENCE"
              role="status"
            >
              <strong>暂无足够依据，不生成结论</strong>
              <p>
                {{ message.content || '可以补充时间、校区或课程信息后再问一次。' }}
              </p>
            </div>
            <p
              v-else
              class="message-entry__content"
              :aria-live="message.pending ? 'polite' : undefined"
            >
              {{ message.content || (message.pending ? '正在查阅公报与同学经验…' : '') }}
            </p>

            <div
              v-if="message.answerStatus !== 'NO_EVIDENCE'
                && message.answerStatus !== 'FAILED'
                && message.sources.length"
              class="source-groups"
            >
              <section v-if="officialSources(message).length" aria-label="官方依据">
                <h3>官方依据</h3>
                <div class="source-grid">
                  <SourceCard
                    v-for="source in officialSources(message)"
                    :key="`${source.sourceType}-${source.chunkId}`"
                    :source="source"
                    @download-document="(documentId) => downloadSourceDocument(message, documentId)"
                  />
                </div>
              </section>

              <section v-if="ugcSources(message).length" aria-label="同学经验">
                <h3>同学经验 <small>非官方建议</small></h3>
                <div class="source-grid">
                  <SourceCard
                    v-for="source in ugcSources(message)"
                    :key="`${source.sourceType}-${source.chunkId}`"
                    :source="source"
                    @download-document="(documentId) => downloadSourceDocument(message, documentId)"
                  />
                </div>
              </section>

              <p v-if="message.sourceError" class="source-download-error" role="alert">
                {{ message.sourceError }}
              </p>
            </div>
          </article>
        </li>
      </ol>

      <form class="chat-composer" data-chat-composer @submit.prevent="submitQuestion">
        <label for="chat-question">你的问题</label>
        <div class="chat-composer__row">
          <textarea
            id="chat-question"
            v-model="question"
            name="question"
            rows="3"
            maxlength="1000"
            placeholder="写下具体问题；时间、校区、课程等信息越完整越好。"
            required
            :disabled="detailLoading"
          />
          <button
            type="submit"
            :disabled="detailLoading || awaitingNewSessionMeta || !question.trim()"
          >
            <span>
              {{ awaitingNewSessionMeta
                ? '正在建立会话…'
                : (streaming ? '改问此题' : '查阅并回答') }}
            </span>
            <span aria-hidden="true">↗</span>
          </button>
        </div>
        <p>
          {{ streaming ? '提交新问题将取消当前检索。' : '回答会区分官方文件与同学经验。' }}
        </p>
      </form>
    </section>
  </section>
</template>

<style scoped>
.chat-workspace {
  display: grid;
  grid-template-columns: minmax(14rem, 0.38fr) minmax(0, 1fr);
  min-height: calc(100vh - 11rem);
}

.session-index {
  padding: 2rem 1.5rem;
  color: var(--paper);
  background: var(--green-950);
  border-right: 1px solid rgba(242, 234, 214, 0.22);
}

.session-index__heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  padding-bottom: 1.2rem;
  border-bottom: 3px double rgba(242, 234, 214, 0.3);
}

.session-index__heading p,
.section-folio {
  margin: 0 0 0.45rem;
  color: var(--vermilion-soft, #cf8172);
  font-family: Georgia, "Times New Roman", serif;
  font-size: 0.65rem;
  letter-spacing: 0.16em;
}

.session-index h2 {
  margin: 0;
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  font-size: 1.45rem;
  font-weight: 500;
}

.square-action {
  display: grid;
  width: 2.3rem;
  height: 2.3rem;
  place-items: center;
  color: var(--paper);
  background: transparent;
  border: 1px solid rgba(242, 234, 214, 0.5);
  cursor: pointer;
}

.new-chat-action {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  margin: 1.3rem 0;
  padding: 0.75rem 0.85rem;
  color: var(--green-950);
  background: var(--paper);
  border: 0;
  cursor: pointer;
}

.new-chat-action span:last-child {
  color: var(--vermilion);
  font-size: 0.68rem;
}

.session-list {
  display: grid;
  gap: 0.45rem;
  margin: 0;
  padding: 0;
  list-style: none;
}

.session-list button {
  display: grid;
  gap: 0.28rem;
  width: 100%;
  padding: 0.75rem 0.7rem;
  color: #d4ded8;
  text-align: left;
  background: transparent;
  border: 0;
  border-left: 3px solid transparent;
  cursor: pointer;
}

.session-list button:hover,
.session-list button[aria-current="page"] {
  color: var(--paper);
  background: rgba(242, 234, 214, 0.08);
  border-left-color: var(--vermilion-soft, #cf8172);
}

.session-list__title {
  font-size: 0.86rem;
  font-weight: 700;
}

.session-list__preview {
  overflow: hidden;
  color: #9eb0a8;
  font-size: 0.7rem;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.index-note {
  color: #9eb0a8;
  font-size: 0.78rem;
  line-height: 1.7;
}

.text-action {
  margin-top: 1rem;
  padding: 0;
  color: #b8c7bf;
  background: transparent;
  border: 0;
  border-bottom: 1px dotted currentColor;
  cursor: pointer;
  font-size: 0.72rem;
}

.answer-desk {
  min-width: 0;
  padding: clamp(1.6rem, 4vw, 3.4rem);
  background:
    repeating-linear-gradient(0deg, rgba(44, 67, 58, 0.022) 0 1px, transparent 1px 5px),
    var(--paper);
}

.answer-desk__heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1.5rem;
  padding-bottom: 1.25rem;
  border-bottom: 4px double var(--rule);
}

.answer-desk__heading h1 {
  margin: 0;
  color: var(--green-950);
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  font-size: clamp(2rem, 4vw, 3.6rem);
  font-weight: 600;
}

.desk-status {
  display: flex;
  align-items: center;
  gap: 0.45rem;
  padding: 0.45rem 0.7rem;
  color: var(--ink-soft);
  border: 1px solid var(--rule);
  font-size: 0.7rem;
  white-space: nowrap;
}

.desk-status span {
  width: 0.45rem;
  height: 0.45rem;
  background: var(--green-700);
  border-radius: 50%;
}

.desk-status--live span {
  background: var(--vermilion);
  animation: status-pulse 1.2s ease-in-out infinite;
}

.form-message,
.desk-loading {
  margin: 1rem 0;
  font-size: 0.82rem;
}

.form-message--error {
  color: #842f25;
}

.desk-loading {
  color: var(--ink-soft);
}

.history-limit-note {
  margin: 0.75rem 0 0;
  color: var(--ink-soft);
  font-size: 0.76rem;
}

.empty-desk {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 1.25rem;
  align-items: center;
  min-height: 18rem;
  padding: 2rem 8%;
  color: var(--ink-soft);
}

.empty-desk > span {
  display: grid;
  width: 4.5rem;
  height: 4.5rem;
  place-items: center;
  color: var(--vermilion);
  border: 2px solid var(--vermilion);
  border-radius: 50%;
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  font-size: 2rem;
  transform: rotate(-7deg);
}

.empty-desk h2 {
  margin: 0;
  color: var(--green-900);
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  font-size: 1.8rem;
}

.empty-desk p {
  margin: 0.6rem 0 0;
  line-height: 1.7;
}

.message-ledger {
  display: grid;
  gap: 1.25rem;
  max-height: min(58vh, 46rem);
  margin: 0;
  padding: 1.5rem 0;
  overflow-y: auto;
  list-style: none;
}

.message-entry article {
  padding: 1.15rem 1.25rem;
}

.message-entry--user article {
  margin-left: clamp(1.5rem, 8vw, 8rem);
  background: rgba(18, 59, 50, 0.08);
  border-right: 3px solid var(--green-700);
}

.message-entry--assistant article {
  margin-right: clamp(0rem, 3vw, 3rem);
  background: rgba(255, 255, 255, 0.34);
  border: 1px solid rgba(18, 59, 50, 0.16);
  box-shadow: 5px 5px 0 rgba(228, 216, 189, 0.68);
}

.message-entry article > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--vermilion);
  font-size: 0.68rem;
  font-weight: 800;
  letter-spacing: 0.14em;
}

.message-entry article > header span:last-child {
  font-family: Georgia, "Times New Roman", serif;
}

.message-entry__content {
  margin: 0.75rem 0 0;
  color: var(--ink);
  line-height: 1.85;
  white-space: pre-wrap;
}

.no-evidence {
  margin-top: 0.85rem;
  padding: 1rem;
  color: #762c24;
  background: rgba(166, 64, 49, 0.08);
  border: 1px solid rgba(166, 64, 49, 0.38);
}

.failed-answer {
  margin-top: 0.85rem;
  padding: 1rem;
  color: var(--ink-soft);
  background: rgba(18, 59, 50, 0.06);
  border: 1px dashed var(--green-700);
}

.failed-answer strong {
  color: var(--green-900);
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  font-size: 1.12rem;
}

.failed-answer p {
  margin: 0.35rem 0 0;
  font-size: 0.78rem;
}

.no-evidence strong {
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  font-size: 1.12rem;
}

.no-evidence p {
  margin: 0.35rem 0 0;
  font-size: 0.78rem;
}

.source-groups {
  display: grid;
  gap: 1rem;
  margin-top: 1.1rem;
  padding-top: 1rem;
  border-top: 1px dotted var(--rule);
}

.source-groups h3 {
  margin: 0 0 0.55rem;
  color: var(--green-800);
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  font-size: 1rem;
}

.source-groups h3 small {
  color: var(--vermilion);
  font-family: "Noto Serif CJK SC", "Source Han Serif SC", "Songti SC", SimSun, serif;
  font-size: 0.65rem;
  font-weight: 500;
}

.source-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(15rem, 100%), 1fr));
  gap: 0.75rem;
}

.source-download-error {
  margin: 0;
  color: #842f25;
  font-size: 0.78rem;
}

.chat-composer {
  padding-top: 1.1rem;
  border-top: 4px double var(--rule);
}

.chat-composer > label {
  display: block;
  margin-bottom: 0.5rem;
  color: var(--green-800);
  font-size: 0.76rem;
  font-weight: 800;
  letter-spacing: 0.12em;
}

.chat-composer__row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: stretch;
  border: 1px solid var(--green-700);
  background: rgba(255, 255, 255, 0.35);
}

.chat-composer textarea {
  width: 100%;
  min-height: 5.4rem;
  padding: 0.85rem;
  color: var(--ink);
  resize: vertical;
  background: transparent;
  border: 0;
  outline: 0;
  line-height: 1.6;
}

.chat-composer textarea:focus-visible {
  box-shadow: inset 0 0 0 2px var(--vermilion);
}

.chat-composer button {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-width: 8.5rem;
  padding: 0.85rem;
  color: var(--paper);
  text-align: left;
  background: var(--green-900);
  border: 0;
  cursor: pointer;
}

.chat-composer button span:last-child {
  align-self: flex-end;
  color: #d6ded8;
}

.chat-composer button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.chat-composer > p {
  margin: 0.5rem 0 0;
  color: var(--ink-soft);
  font-size: 0.68rem;
}

@keyframes status-pulse {
  50% {
    opacity: 0.35;
    transform: scale(0.78);
  }
}

@media (max-width: 820px) {
  .chat-workspace {
    grid-template-columns: 1fr;
  }

  .session-index {
    border-right: 0;
    border-bottom: 3px solid var(--vermilion);
  }

  .session-list {
    grid-template-columns: repeat(auto-fit, minmax(11rem, 1fr));
  }

  .message-ledger {
    max-height: none;
  }
}

@media (max-width: 520px) {
  .answer-desk {
    padding: 1.4rem 1rem 2rem;
  }

  .answer-desk__heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .empty-desk {
    grid-template-columns: 1fr;
    padding: 2rem 0.5rem;
  }

  .message-entry--user article,
  .message-entry--assistant article {
    margin-right: 0;
    margin-left: 0;
  }

  .chat-composer__row {
    grid-template-columns: 1fr;
  }

  .chat-composer button {
    min-height: 3.6rem;
    min-width: 0;
    flex-direction: row;
  }
}

@media (prefers-reduced-motion: reduce) {
  .desk-status--live span {
    animation: none;
  }
}
</style>
