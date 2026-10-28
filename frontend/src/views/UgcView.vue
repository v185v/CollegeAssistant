<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { ApiClientError, isAuthenticationStatus } from '../api/client'
import { createUgcPost, listMyUgc } from '../api/endpoints'
import type { UgcPostDto, UgcStatus } from '../api/types'

const emit = defineEmits<{
  unauthorized: []
}>()

const title = ref('')
const content = ref('')
const tags = ref('')
const relatedCourse = ref('')
const relatedLocation = ref('')
const posts = ref<UgcPostDto[]>([])
const page = ref(1)
const pages = ref(1)
const total = ref(0)
const pageSize = 10
const loading = ref(false)
const submitting = ref(false)
const submissionError = ref('')
const listError = ref('')
const successMessage = ref('')
let listRequestVersion = 0

const statusLabels: Record<UgcStatus, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  REMOVED: '已移除',
}

onMounted(() => {
  void loadSubmissions(1)
})

async function loadSubmissions(
  requestedPage: number,
  preserveSuccess = false,
): Promise<void> {
  const requestVersion = listRequestVersion += 1
  loading.value = true
  listError.value = ''
  if (!preserveSuccess) {
    successMessage.value = ''
  }
  try {
    const result = await listMyUgc(requestedPage, pageSize)
    if (requestVersion !== listRequestVersion) {
      return
    }
    if (!result) {
      listError.value = '我的提交暂时无法读取，请稍后重试'
      return
    }
    posts.value = result.items
    page.value = result.page
    pages.value = Math.max(result.pages, 1)
    total.value = result.total
  } catch (error) {
    if (requestVersion !== listRequestVersion) {
      return
    }
    listError.value = failureMessage(error, '我的提交加载失败，请稍后重试')
  } finally {
    if (requestVersion === listRequestVersion) {
      loading.value = false
    }
  }
}

async function submitPost(): Promise<void> {
  if (submitting.value) {
    return
  }
  const normalizedTitle = title.value.trim()
  const normalizedContent = content.value.trim()
  const normalizedTags = parseTags(tags.value)
  const normalizedCourse = nullableText(relatedCourse.value)
  const normalizedLocation = nullableText(relatedLocation.value)
  const validationMessage = validatePost(
    normalizedTitle,
    normalizedContent,
    normalizedTags,
    normalizedCourse,
    normalizedLocation,
  )
  if (validationMessage) {
    submissionError.value = validationMessage
    successMessage.value = ''
    return
  }
  submitting.value = true
  submissionError.value = ''
  successMessage.value = ''
  try {
    const created = await createUgcPost({
      title: normalizedTitle,
      content: normalizedContent,
      tags: normalizedTags,
      relatedCourse: normalizedCourse,
      relatedLocation: normalizedLocation,
    })
    if (!created) {
      submissionError.value = '提交响应缺少经验帖，请重试'
      return
    }

    title.value = ''
    content.value = ''
    tags.value = ''
    relatedCourse.value = ''
    relatedLocation.value = ''
    successMessage.value = '经验帖已提交审核，通过后可作为同学经验被引用。'
    await loadSubmissions(1, true)
  } catch (error) {
    submissionError.value = failureMessage(error, '经验帖提交失败，请稍后重试')
  } finally {
    submitting.value = false
  }
}

function parseTags(value: string): string[] {
  return value
    .split(/[,，]/)
    .map((tag) => tag.trim())
    .filter((tag) => tag.length > 0)
}

function nullableText(value: string): string | null {
  return value.trim() || null
}

function validatePost(
  normalizedTitle: string,
  normalizedContent: string,
  normalizedTags: string[],
  normalizedCourse: string | null,
  normalizedLocation: string | null,
): string | null {
  if (normalizedTitle.length < 3 || normalizedTitle.length > 80) {
    return '标题需为 3–80 位'
  }
  if (normalizedContent.length < 20 || normalizedContent.length > 5000) {
    return '正文需为 20–5000 位'
  }
  if (normalizedTags.length > 10) {
    return '标签最多填写 10 个'
  }
  if (normalizedTags.some((tag) => tag.length > 20)) {
    return '每个标签不能超过 20 位'
  }
  if (normalizedCourse && normalizedCourse.length > 64) {
    return '相关课程不能超过 64 位'
  }
  if (normalizedLocation && normalizedLocation.length > 64) {
    return '相关地点不能超过 64 位'
  }
  return null
}

function failureMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiClientError
      && isAuthenticationStatus(error.status)) {
    emit('unauthorized')
    return ''
  }
  return error instanceof ApiClientError
    ? error.message
    : fallback
}

function formatDate(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '时间待核'
  }
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date)
}
</script>

<template>
  <section class="ugc-view" aria-labelledby="ugc-title">
    <header class="view-heading">
      <div>
        <p>STUDENT NOTES · 同学来稿</p>
        <h1 id="ugc-title">把走过的弯路，写成下一位同学的便签。</h1>
      </div>
      <div class="pencil-mark" aria-hidden="true">经验</div>
    </header>

    <div class="ugc-layout">
      <section class="submission-sheet" aria-labelledby="submission-title">
        <div class="sheet-heading">
          <div>
            <span>来稿单 · 01</span>
            <h2 id="submission-title">提交校园经验</h2>
          </div>
          <p>来稿会先进入审核</p>
        </div>

        <form :aria-busy="submitting" @submit.prevent="submitPost">
          <fieldset
            class="submission-fields"
            data-submission-fields
            :disabled="submitting"
          >
            <label for="ugc-title-input">标题</label>
          <input
            id="ugc-title-input"
            v-model="title"
            name="title"
            required
            minlength="3"
            maxlength="80"
            placeholder="一句话说清这条经验"
          >

          <label for="ugc-content">正文</label>
          <textarea
            id="ugc-content"
            v-model="content"
            name="content"
            required
            rows="7"
            minlength="20"
            maxlength="5000"
            placeholder="写清适用时间、地点和你亲历的步骤；避免未经核实的断言。"
          />

          <label for="ugc-tags">
            标签 <span>用逗号分隔</span>
          </label>
          <input
            id="ugc-tags"
            v-model="tags"
            name="tags"
            maxlength="209"
            placeholder="选课，迎新，图书馆"
          >

          <div class="related-fields">
            <label for="ugc-course">
              相关课程 <span>选填</span>
              <input
                id="ugc-course"
                v-model="relatedCourse"
                name="relatedCourse"
                maxlength="64"
              >
            </label>
            <label for="ugc-location">
              相关地点 <span>选填</span>
              <input
                id="ugc-location"
                v-model="relatedLocation"
                name="relatedLocation"
                maxlength="64"
              >
            </label>
          </div>

          <p v-if="submissionError" class="form-message form-message--error" role="alert">
            {{ submissionError }}
          </p>
          <p v-if="successMessage" class="form-message form-message--success" role="status">
            {{ successMessage }}
          </p>

            <button class="primary-action" type="submit" :disabled="submitting">
              <span>{{ submitting ? '正在投递…' : '投递审核' }}</span>
              <span aria-hidden="true">→</span>
            </button>
          </fieldset>
        </form>
      </section>

      <section class="my-submissions" aria-labelledby="my-submissions-title">
        <div class="submissions-heading">
          <div>
            <p>MY DESK · {{ total }} 件</p>
            <h2 id="my-submissions-title">我的提交</h2>
          </div>
          <button type="button" :disabled="loading" @click="loadSubmissions(page)">
            刷新
          </button>
        </div>

        <p v-if="loading" class="list-note" role="status">
          正在翻阅来稿记录…
        </p>
        <div v-else-if="listError" class="list-note list-note--error" role="alert">
          <p>{{ listError }}</p>
          <button
            type="button"
            data-action="retry-submissions"
            @click="loadSubmissions(page)"
          >
            重试读取
          </button>
        </div>
        <p v-else-if="posts.length === 0" class="list-note">
          还没有提交记录。第一张便签可以很短，但请写清适用场景。
        </p>
        <ol v-else class="submission-list">
          <li v-for="post in posts" :key="post.id">
            <article>
              <header>
                <span
                  class="review-status"
                  :class="`review-status--${post.status.toLowerCase()}`"
                >
                  {{ statusLabels[post.status] }}
                </span>
                <time :datetime="post.createdAt">{{ formatDate(post.createdAt) }}</time>
              </header>
              <h3>{{ post.title }}</h3>
              <p class="post-content">{{ post.content }}</p>
              <ul v-if="post.tags.length" class="tag-list" aria-label="标签">
                <li v-for="tag in post.tags" :key="tag"># {{ tag }}</li>
              </ul>
              <dl v-if="post.relatedCourse || post.relatedLocation" class="post-context">
                <div v-if="post.relatedCourse">
                  <dt>课程</dt>
                  <dd>{{ post.relatedCourse }}</dd>
                </div>
                <div v-if="post.relatedLocation">
                  <dt>地点</dt>
                  <dd>{{ post.relatedLocation }}</dd>
                </div>
              </dl>
              <aside
                v-if="post.status === 'REJECTED' && post.rejectReason"
                class="reject-note"
                aria-label="驳回原因"
              >
                <strong>编辑意见</strong>
                <p>{{ post.rejectReason }}</p>
              </aside>
            </article>
          </li>
        </ol>

        <nav class="pagination" aria-label="我的提交分页">
          <button
            type="button"
            data-action="previous-page"
            :disabled="loading || page <= 1"
            @click="loadSubmissions(page - 1)"
          >
            ← 上一页
          </button>
          <span>第 {{ page }} / {{ pages }} 页</span>
          <button
            type="button"
            data-action="next-page"
            :disabled="loading || page >= pages"
            @click="loadSubmissions(page + 1)"
          >
            下一页 →
          </button>
        </nav>
      </section>
    </div>
  </section>
</template>

<style scoped>
.ugc-view {
  max-width: 80rem;
  min-height: calc(100vh - 11rem);
  margin: 0 auto;
  padding: clamp(2rem, 5vw, 4.5rem) clamp(1rem, 4vw, 3.5rem) 5rem;
}

.view-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 2rem;
  padding-bottom: 1.5rem;
  border-bottom: 5px double var(--green-800);
}

.view-heading p,
.submissions-heading p {
  margin: 0 0 0.55rem;
  color: var(--vermilion);
  font-family: Georgia, "Times New Roman", serif;
  font-size: 0.68rem;
  font-weight: 700;
  letter-spacing: 0.18em;
}

.view-heading h1 {
  max-width: 51rem;
  margin: 0;
  color: var(--green-950);
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  font-size: clamp(2.1rem, 5vw, 4.4rem);
  font-weight: 600;
  line-height: 1.1;
}

.pencil-mark {
  display: grid;
  flex: 0 0 auto;
  width: 4.2rem;
  height: 4.2rem;
  place-items: center;
  color: #8a602c;
  background: #f6e8bd;
  border: 1px solid rgba(126, 91, 41, 0.55);
  box-shadow: 4px 5px 0 rgba(93, 72, 36, 0.12);
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  transform: rotate(-5deg);
}

.ugc-layout {
  display: grid;
  grid-template-columns: minmax(18rem, 0.72fr) minmax(0, 1fr);
  gap: clamp(1.5rem, 4vw, 3.5rem);
  align-items: start;
  margin-top: 1.6rem;
}

.submission-sheet {
  position: sticky;
  top: 1rem;
  padding: clamp(1.2rem, 3vw, 2rem);
  background: rgba(248, 242, 226, 0.78);
  border: 1px solid var(--green-700);
  box-shadow: 8px 8px 0 var(--paper-deep);
}

.sheet-heading,
.submissions-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1rem;
  padding-bottom: 0.9rem;
  border-bottom: 3px double var(--rule);
}

.sheet-heading span {
  color: var(--vermilion);
  font-size: 0.66rem;
  font-weight: 800;
  letter-spacing: 0.15em;
}

.sheet-heading h2,
.submissions-heading h2 {
  margin: 0.25rem 0 0;
  color: var(--green-900);
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  font-size: 1.55rem;
}

.sheet-heading p {
  margin: 0;
  color: var(--ink-soft);
  font-size: 0.68rem;
}

.submission-sheet form {
  margin-top: 1rem;
}

.submission-fields {
  display: grid;
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  gap: 0.5rem;
}

.submission-sheet label {
  margin-top: 0.45rem;
  color: var(--green-800);
  font-size: 0.75rem;
  font-weight: 700;
  letter-spacing: 0.06em;
}

.submission-sheet label span {
  color: var(--ink-soft);
  font-size: 0.66rem;
  font-weight: 400;
}

.submission-sheet input,
.submission-sheet textarea {
  width: 100%;
  padding: 0.65rem;
  color: var(--ink);
  background: rgba(255, 255, 255, 0.35);
  border: 1px solid rgba(18, 59, 50, 0.38);
  border-radius: 0;
  outline: 0;
  line-height: 1.55;
}

.submission-sheet textarea {
  resize: vertical;
}

.submission-sheet input:focus-visible,
.submission-sheet textarea:focus-visible {
  border-color: var(--vermilion);
  box-shadow: 0 0 0 2px rgba(166, 64, 49, 0.18);
}

.related-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.8rem;
}

.related-fields label {
  display: grid;
  gap: 0.42rem;
}

.form-message {
  margin: 0.5rem 0 0;
  font-size: 0.76rem;
  line-height: 1.55;
}

.form-message--error {
  color: #842f25;
}

.form-message--success {
  color: var(--green-700);
  font-weight: 700;
}

.primary-action {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 0.6rem;
  padding: 0.85rem 1rem;
  color: var(--paper);
  background: var(--green-900);
  border: 1px solid var(--green-900);
  cursor: pointer;
}

.primary-action:disabled {
  cursor: wait;
  opacity: 0.55;
}

.submissions-heading button {
  padding: 0;
  color: var(--green-700);
  background: transparent;
  border: 0;
  border-bottom: 1px dotted currentColor;
  cursor: pointer;
  font-size: 0.72rem;
}

.list-note {
  margin: 1rem 0 0;
  padding: 2rem 1rem;
  color: var(--ink-soft);
  text-align: center;
  border: 1px dashed var(--rule);
  font-size: 0.8rem;
  line-height: 1.7;
}

.list-note--error p {
  margin: 0;
  color: #842f25;
}

.list-note--error button {
  margin-top: 0.75rem;
  color: var(--green-700);
  background: transparent;
  border: 0;
  border-bottom: 1px dotted currentColor;
  cursor: pointer;
}

.submission-list {
  display: grid;
  gap: 1rem;
  margin: 1rem 0 0;
  padding: 0;
  list-style: none;
}

.submission-list article {
  position: relative;
  padding: 1.15rem 1.25rem;
  background: rgba(255, 255, 255, 0.3);
  border: 1px solid var(--rule);
}

.submission-list article > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}

.review-status {
  padding: 0.25rem 0.5rem;
  color: var(--green-800);
  border: 1px solid currentColor;
  font-size: 0.67rem;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.review-status--rejected,
.review-status--removed {
  color: #842f25;
}

.review-status--pending {
  color: #80602f;
}

.submission-list time {
  color: var(--ink-soft);
  font-size: 0.68rem;
}

.submission-list h3 {
  margin: 0.8rem 0 0;
  color: var(--green-900);
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  font-size: 1.3rem;
}

.post-content {
  margin: 0.55rem 0 0;
  color: var(--ink-soft);
  font-size: 0.86rem;
  line-height: 1.75;
  white-space: pre-wrap;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 0.45rem;
  margin: 0.75rem 0 0;
  padding: 0;
  color: var(--vermilion);
  list-style: none;
  font-size: 0.68rem;
}

.post-context {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  margin: 0.75rem 0 0;
  padding-top: 0.6rem;
  border-top: 1px dotted var(--rule);
}

.post-context div {
  display: flex;
  gap: 0.35rem;
}

.post-context dt,
.post-context dd {
  margin: 0;
  font-size: 0.68rem;
}

.post-context dt {
  color: var(--ink-soft);
}

.post-context dd {
  color: var(--green-700);
  font-weight: 700;
}

.reject-note {
  margin-top: 0.85rem;
  padding: 0.75rem;
  color: #762c24;
  background: rgba(166, 64, 49, 0.07);
  border-left: 3px solid var(--vermilion);
}

.reject-note strong {
  font-size: 0.72rem;
  letter-spacing: 0.1em;
}

.reject-note p {
  margin: 0.3rem 0 0;
  font-size: 0.76rem;
}

.pagination {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  gap: 0.8rem;
  align-items: center;
  margin-top: 1rem;
  padding-top: 0.9rem;
  border-top: 3px double var(--rule);
}

.pagination button {
  padding: 0;
  color: var(--green-700);
  background: transparent;
  border: 0;
  cursor: pointer;
  font-size: 0.72rem;
}

.pagination button:last-child {
  justify-self: end;
}

.pagination button:disabled {
  cursor: not-allowed;
  opacity: 0.38;
}

.pagination span {
  color: var(--ink-soft);
  font-size: 0.68rem;
}

@media (max-width: 860px) {
  .ugc-layout {
    grid-template-columns: 1fr;
  }

  .submission-sheet {
    position: static;
  }
}

@media (max-width: 520px) {
  .pencil-mark {
    display: none;
  }

  .related-fields {
    grid-template-columns: 1fr;
  }

  .sheet-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
