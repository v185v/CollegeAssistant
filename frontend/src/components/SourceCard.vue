<script setup lang="ts">
import { computed } from 'vue'

import type { SourceCitationDto } from '../api/types'

const props = defineProps<{
  source: SourceCitationDto
}>()

const emit = defineEmits<{
  'download-document': [documentId: number]
}>()

const sourceLabel = computed(() => (
  props.source.sourceType === 'OFFICIAL' ? '官方文件' : '同学经验'
))
const displayTitle = computed(() => (
  props.source.title?.trim() || '同学经验摘录'
))
function safeHttpUrl(value: string | null): string | null {
  if (!value) {
    return null
  }
  try {
    const url = new URL(value)
    if ((url.protocol === 'http:' || url.protocol === 'https:')
        && !url.username && !url.password) {
      return url.href
    }
  } catch {
    return null
  }
  return null
}

const safeUrl = computed(() => safeHttpUrl(props.source.sourceUrl))
const externalSourceUrl = computed(() => (
  props.source.referenceType === 'INTERNAL'
    ? safeHttpUrl(props.source.externalSourceUrl)
    : null
))
const documentId = computed(() => (
  props.source.referenceType === 'INTERNAL'
    ? props.source.documentId
    : null
))
const publishedLabel = computed(() => {
  if (!props.source.publishedAt) {
    return null
  }
  const date = new Date(props.source.publishedAt)
  if (Number.isNaN(date.getTime())) {
    return null
  }
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  }).format(date)
})
</script>

<template>
  <article
    class="source-card"
    :class="`source-card--${source.sourceType.toLowerCase()}`"
    :aria-label="`${sourceLabel}：${displayTitle}`"
  >
    <header class="source-card__meta">
      <span class="source-card__kind">{{ sourceLabel }}</span>
      <span v-if="source.sourceOrg" class="source-card__org">
        {{ source.sourceOrg }}
      </span>
    </header>

    <h4 class="source-card__title">
      <a
        v-if="safeUrl"
        :href="safeUrl"
        target="_blank"
        rel="noopener noreferrer"
      >
        {{ displayTitle }}
        <span class="source-card__external" aria-hidden="true">↗</span>
      </a>
      <span v-else>{{ displayTitle }}</span>
    </h4>

    <a
      v-if="externalSourceUrl"
      class="source-card__origin"
      :href="externalSourceUrl"
      target="_blank"
      rel="noopener noreferrer"
    >
      {{ '\u5916\u90e8\u539f\u6587' }}
    </a>

    <div v-if="documentId !== null" class="source-card__actions">
      <button
        class="source-card__download"
        type="button"
        data-action="download-document"
        @click="emit('download-document', documentId)"
      >
        {{ '\u4e0b\u8f7d\u539f\u4ef6' }}
      </button>
    </div>

    <blockquote v-if="source.quote" class="source-card__quote">
      {{ source.quote }}
    </blockquote>

    <footer v-if="publishedLabel" class="source-card__footer">
      <time :datetime="source.publishedAt ?? undefined">{{ publishedLabel }}</time>
    </footer>
  </article>
</template>

<style scoped>
.source-card {
  position: relative;
  min-width: 0;
  padding: 1rem 1.05rem;
  color: var(--ink);
}

.source-card--official {
  background: rgba(242, 234, 214, 0.78);
  border: 1px solid var(--green-700);
  border-top: 4px double var(--green-700);
}

.source-card--official::after {
  position: absolute;
  right: 0.7rem;
  bottom: 0.55rem;
  width: 1.4rem;
  height: 1.4rem;
  border: 1px solid var(--vermilion);
  border-radius: 50%;
  opacity: 0.34;
  content: "核";
  color: var(--vermilion);
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  font-size: 0.7rem;
  line-height: 1.4rem;
  text-align: center;
  transform: rotate(-9deg);
}

.source-card--ugc {
  background: #f6e8bd;
  border: 1px solid rgba(126, 91, 41, 0.38);
  box-shadow: 4px 5px 0 rgba(93, 72, 36, 0.1);
  transform: rotate(-0.35deg);
}

.source-card__meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
}

.source-card__kind {
  color: var(--vermilion);
  font-size: 0.72rem;
  font-weight: 800;
  letter-spacing: 0.14em;
}

.source-card--ugc .source-card__kind::before {
  margin-right: 0.35rem;
  content: "便签 ·";
}

.source-card__org,
.source-card__footer {
  color: var(--ink-soft);
  font-size: 0.72rem;
}

.source-card__title {
  margin: 0.65rem 0 0;
  font-family: STKaiti, KaiTi, "FangSong SC", serif;
  font-size: 1.05rem;
  line-height: 1.4;
}

.source-card__title a {
  text-decoration-color: var(--vermilion);
  text-decoration-thickness: 1px;
  text-underline-offset: 0.2em;
}

.source-card__external {
  color: var(--vermilion);
  font-family: Georgia, "Times New Roman", serif;
}

.source-card__origin {
  display: inline-block;
  margin-top: 0.48rem;
  color: var(--green-700);
  font-size: 0.75rem;
  text-decoration-style: dotted;
  text-underline-offset: 0.22em;
}

.source-card__actions {
  display: flex;
  margin-top: 0.7rem;
}

.source-card__download {
  padding: 0.25rem 0;
  color: var(--green-950);
  background: transparent;
  border: 0;
  border-bottom: 1px solid var(--vermilion);
  cursor: pointer;
  font-size: 0.76rem;
  font-weight: 700;
}

.source-card__download:hover {
  color: var(--vermilion);
}

.source-card__quote {
  margin: 0.75rem 0 0;
  padding-left: 0.75rem;
  color: var(--ink-soft);
  border-left: 2px solid var(--paper-deep);
  font-size: 0.84rem;
  line-height: 1.65;
}

.source-card__footer {
  margin-top: 0.8rem;
}
</style>
