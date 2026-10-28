// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiClientError } from '../api/client'
import type { PageResult, UgcPostDto } from '../api/types'
import UgcView from './UgcView.vue'

const mocks = vi.hoisted(() => ({
  createUgcPost: vi.fn(),
  listMyUgc: vi.fn(),
}))

vi.mock('../api/endpoints', () => ({
  createUgcPost: mocks.createUgcPost,
  listMyUgc: mocks.listMyUgc,
}))

const rejectedPost: UgcPostDto = {
  id: 31,
  authorId: 6,
  authorName: '林同学',
  title: '选课系统避坑',
  content: '建议先收藏培养方案。',
  tags: ['选课', '系统'],
  relatedCourse: null,
  relatedLocation: '教务处',
  status: 'REJECTED',
  likes: 0,
  reports: 0,
  rejectReason: '包含无法核实的具体时间',
  createdAt: '2026-07-10T09:00:00+08:00',
  reviewedAt: '2026-07-11T10:00:00+08:00',
}

const pendingPost: UgcPostDto = {
  ...rejectedPost,
  id: 32,
  title: '图书馆占座经验',
  status: 'PENDING',
  rejectReason: null,
  tags: ['图书馆'],
  reviewedAt: null,
}

const postPage: PageResult<UgcPostDto> = {
  items: [pendingPost, rejectedPost],
  total: 2,
  page: 1,
  size: 10,
  pages: 2,
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, reject, resolve }
}

describe('UgcView', () => {
  beforeEach(() => {
    mocks.createUgcPost.mockReset()
    mocks.listMyUgc.mockReset()
    mocks.listMyUgc.mockResolvedValue(postPage)
    mocks.createUgcPost.mockResolvedValue(pendingPost)
  })

  it('lists the signed-in user submissions with status and rejection reason', async () => {
    const wrapper = mount(UgcView)
    await flushPromises()

    expect(mocks.listMyUgc).toHaveBeenCalledWith(1, 10)
    expect(wrapper.text()).toContain('待审核')
    expect(wrapper.text()).toContain('已驳回')
    expect(wrapper.text()).toContain('包含无法核实的具体时间')
    expect(wrapper.text()).toContain('选课系统避坑')
  })

  it('exposes the backend UGC field constraints', async () => {
    const wrapper = mount(UgcView)
    await flushPromises()

    const title = wrapper.get('input[name="title"]')
    const content = wrapper.get('textarea[name="content"]')
    expect(title.attributes('minlength')).toBe('3')
    expect(title.attributes('maxlength')).toBe('80')
    expect(content.attributes('minlength')).toBe('20')
    expect(content.attributes('maxlength')).toBe('5000')
    expect(wrapper.get('input[name="tags"]').attributes('maxlength'))
      .toBe('209')
    expect(wrapper.get('input[name="relatedCourse"]').attributes('maxlength'))
      .toBe('64')
    expect(wrapper.get('input[name="relatedLocation"]').attributes('maxlength'))
      .toBe('64')
  })

  it('creates a normalized post, clears the form, and refreshes mine=true results', async () => {
    const wrapper = mount(UgcView)
    await flushPromises()

    await wrapper.get('input[name="title"]').setValue('  报到动线经验  ')
    await wrapper.get('textarea[name="content"]').setValue(
      '  报到当天东门排队通常更短，建议提前准备好录取通知书。  ',
    )
    await wrapper.get('input[name="tags"]').setValue(' 迎新， 动线, ,报到 ')
    await wrapper.get('input[name="relatedCourse"]').setValue('   ')
    await wrapper.get('input[name="relatedLocation"]').setValue('  东门  ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(mocks.createUgcPost).toHaveBeenCalledWith({
      title: '报到动线经验',
      content: '报到当天东门排队通常更短，建议提前准备好录取通知书。',
      tags: ['迎新', '动线', '报到'],
      relatedCourse: null,
      relatedLocation: '东门',
    })
    expect(mocks.listMyUgc).toHaveBeenCalledTimes(2)
    expect(mocks.listMyUgc).toHaveBeenLastCalledWith(1, 10)
    expect((wrapper.get('input[name="title"]').element as HTMLInputElement).value)
      .toBe('')
    expect((wrapper.get('textarea[name="content"]').element as HTMLTextAreaElement).value)
      .toBe('')
    expect(wrapper.text()).toContain('经验帖已提交审核')
  })

  it('disables the entire submission fieldset while creation is pending', async () => {
    let resolveCreation!: (value: UgcPostDto) => void
    mocks.createUgcPost.mockImplementation(() => new Promise<UgcPostDto>((resolve) => {
      resolveCreation = resolve
    }))
    const wrapper = mount(UgcView)
    await flushPromises()

    await wrapper.get('input[name="title"]').setValue('一条有效经验')
    await wrapper.get('textarea[name="content"]').setValue(
      '这是一段长度足够的校园经验正文，用来验证提交期间不能继续编辑。',
    )
    await wrapper.get('form').trigger('submit')

    expect(wrapper.find('fieldset[data-submission-fields]').exists()).toBe(true)
    expect(wrapper.get('fieldset[data-submission-fields]').attributes('disabled')).toBeDefined()

    resolveCreation(pendingPost)
    await flushPromises()

    expect(wrapper.get('fieldset[data-submission-fields]').attributes('disabled')).toBeUndefined()
  })

  it('does not send title or content that is known to violate the contract', async () => {
    const wrapper = mount(UgcView)
    await flushPromises()

    await wrapper.get('input[name="title"]').setValue('短')
    await wrapper.get('textarea[name="content"]').setValue('内容也太短')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('标题需为 3–80 位')
    expect(mocks.createUgcPost).not.toHaveBeenCalled()
  })

  it('rejects too many or overlong tags before creating a post', async () => {
    const wrapper = mount(UgcView)
    await flushPromises()
    await wrapper.get('input[name="title"]').setValue('一条有效经验')
    await wrapper.get('textarea[name="content"]').setValue(
      '这是一段长度足够的校园经验正文，用来验证标签约束不会发出无效请求。',
    )

    await wrapper.get('input[name="tags"]').setValue(
      Array.from({ length: 11 }, (_, index) => `标签${index}`).join(','),
    )
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('标签最多填写 10 个')
    expect(mocks.createUgcPost).not.toHaveBeenCalled()

    await wrapper.get('input[name="tags"]').setValue(
      '这是一个超过二十个字符长度限制的标签内容额外文字',
    )
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('每个标签不能超过 20 位')
    expect(mocks.createUgcPost).not.toHaveBeenCalled()
  })

  it('loads the next page of the current user submissions', async () => {
    const wrapper = mount(UgcView)
    await flushPromises()

    await wrapper.get('[data-action="next-page"]').trigger('click')
    await flushPromises()

    expect(mocks.listMyUgc).toHaveBeenLastCalledWith(2, 10)
  })

  it('ignores an older page result while the newer list request remains pending', async () => {
    const staleRequest = deferred<PageResult<UgcPostDto>>()
    const latestRequest = deferred<PageResult<UgcPostDto>>()
    mocks.listMyUgc
      .mockResolvedValueOnce(postPage)
      .mockImplementationOnce(() => staleRequest.promise)
      .mockImplementationOnce(() => latestRequest.promise)
    const wrapper = mount(UgcView)
    await flushPromises()

    await wrapper.get('[data-action="next-page"]').trigger('click')
    await wrapper.get('input[name="title"]').setValue('一条有效经验')
    await wrapper.get('textarea[name="content"]').setValue(
      '这是一段长度足够的校园经验正文，用来触发更新的列表请求。',
    )
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(mocks.listMyUgc).toHaveBeenCalledTimes(3)

    staleRequest.resolve({
      items: [{ ...rejectedPost, id: 41, title: '过期第二页' }],
      total: 1,
      page: 2,
      size: 10,
      pages: 2,
    })
    await flushPromises()

    expect(wrapper.text()).not.toContain('过期第二页')
    expect(wrapper.get('.submissions-heading button').attributes('disabled')).toBeDefined()

    latestRequest.resolve({
      items: [{ ...pendingPost, id: 42, title: '最新第一页' }],
      total: 1,
      page: 1,
      size: 10,
      pages: 1,
    })
    await flushPromises()

    expect(wrapper.text()).toContain('最新第一页')
    expect(wrapper.text()).toContain('第 1 / 1 页')
    expect(wrapper.get('.submissions-heading button').attributes('disabled')).toBeUndefined()
  })

  it('ignores an older list error after a newer result has been applied', async () => {
    const staleRequest = deferred<PageResult<UgcPostDto>>()
    const latestRequest = deferred<PageResult<UgcPostDto>>()
    mocks.listMyUgc
      .mockResolvedValueOnce(postPage)
      .mockImplementationOnce(() => staleRequest.promise)
      .mockImplementationOnce(() => latestRequest.promise)
    const wrapper = mount(UgcView)
    await flushPromises()

    await wrapper.get('[data-action="next-page"]').trigger('click')
    await wrapper.get('input[name="title"]').setValue('一条有效经验')
    await wrapper.get('textarea[name="content"]').setValue(
      '这是一段长度足够的校园经验正文，用来验证过期错误不会覆盖新结果。',
    )
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    latestRequest.resolve({
      items: [{ ...pendingPost, id: 43, title: '已应用的新结果' }],
      total: 1,
      page: 1,
      size: 10,
      pages: 1,
    })
    await flushPromises()
    expect(wrapper.text()).toContain('已应用的新结果')

    staleRequest.reject(
      new ApiClientError('过期列表请求失败', 40300, 403),
    )
    await flushPromises()

    expect(wrapper.get('.my-submissions').find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('已应用的新结果')
  })

  it('emits unauthorized when mine=true loading returns 401', async () => {
    mocks.listMyUgc.mockRejectedValue(
      new ApiClientError('请先登录', 40101, 401),
    )

    const wrapper = mount(UgcView)
    await flushPromises()

    expect(wrapper.emitted('unauthorized')).toHaveLength(1)
  })

  it('shows list failures in the list area without an empty state and supports retry', async () => {
    mocks.listMyUgc
      .mockRejectedValueOnce(
        new ApiClientError('当前账号无权查看来稿', 40300, 403),
      )
      .mockResolvedValueOnce(postPage)

    const wrapper = mount(UgcView)
    await flushPromises()

    expect(wrapper.emitted('unauthorized')).toBeUndefined()
    const submissions = wrapper.get('.my-submissions')
    const listAlert = submissions.find('[role="alert"]')
    expect(listAlert.exists()).toBe(true)
    expect(listAlert.text()).toContain('当前账号无权查看来稿')
    expect(wrapper.get('.submission-sheet').find('[role="alert"]').exists()).toBe(false)
    expect(submissions.text()).not.toContain('还没有提交记录')

    await submissions.get('[data-action="retry-submissions"]').trigger('click')
    await flushPromises()

    expect(mocks.listMyUgc).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('选课系统避坑')
  })
})
