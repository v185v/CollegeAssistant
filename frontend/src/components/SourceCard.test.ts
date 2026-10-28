// @vitest-environment happy-dom

import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import SourceCard from './SourceCard.vue'
import type { SourceCitationDto } from '../api/types'

describe('SourceCard', () => {
  it('labels an official source as a document and opens it safely', () => {
    const source: SourceCitationDto = {
      chunkId: 7,
      sourceType: 'OFFICIAL',
      referenceType: 'EXTERNAL',
      documentId: null,
      externalSourceUrl: null,
      title: '本科生学籍管理规定',
      sourceUrl: 'https://school.example/rules/7',
      sourceOrg: '教务处',
      publishedAt: '2026-05-04T08:00:00+08:00',
      relevanceScore: 0.94,
      quote: '新生应在规定日期内完成注册。',
    }

    const wrapper = mount(SourceCard, { props: { source } })

    expect(wrapper.text()).toContain('官方文件')
    expect(wrapper.text()).toContain('教务处')
    expect(wrapper.text()).toContain('新生应在规定日期内完成注册。')
    const link = wrapper.get('a')
    expect(link.text()).toContain('本科生学籍管理规定')
    expect(link.attributes('href')).toBe(source.sourceUrl)
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toContain('noopener')
    expect(link.attributes('rel')).toContain('noreferrer')
  })

  it('labels UGC as peer experience and never links an unsafe URL', () => {
    const source = {
      chunkId: 8,
      sourceType: 'UGC',
      referenceType: 'UGC',
      documentId: null,
      externalSourceUrl: null,
      title: null,
      sourceUrl: 'javascript:alert(1)',
      sourceOrg: null,
      publishedAt: null,
      relevanceScore: null,
      quote: '建议提前十分钟到场。',
    } as SourceCitationDto

    const wrapper = mount(SourceCard, { props: { source } })

    expect(wrapper.text()).toContain('同学经验')
    expect(wrapper.text()).toContain('同学经验摘录')
    expect(wrapper.text()).toContain('建议提前十分钟到场。')
    expect(wrapper.find('a').exists()).toBe(false)
  })

  it('emits an authenticated document download for internal evidence without inventing a URL', async () => {
    const source = {
      chunkId: 9,
      sourceType: 'OFFICIAL',
      referenceType: 'INTERNAL',
      documentId: 209,
      sourceUrl: null,
      externalSourceUrl: null,
      title: 'Internal schedule',
      sourceOrg: 'Registrar',
      publishedAt: null,
      relevanceScore: 0.91,
      quote: 'Internal schedule excerpt',
    } as SourceCitationDto

    const wrapper = mount(SourceCard, { props: { source } })

    expect(wrapper.find('a').exists()).toBe(false)
    await wrapper.get('[data-action="download-document"]').trigger('click')
    expect(wrapper.emitted('download-document')).toEqual([[209]])
  })
})
