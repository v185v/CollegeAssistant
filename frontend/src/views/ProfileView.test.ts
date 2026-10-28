// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiClientError } from '../api/client'
import type { UserProfileDto } from '../api/types'
import ProfileView from './ProfileView.vue'

const mocks = vi.hoisted(() => ({
  getProfile: vi.fn(),
  updateProfile: vi.fn(),
}))

vi.mock('../api/endpoints', () => ({
  getProfile: mocks.getProfile,
  updateProfile: mocks.updateProfile,
}))

const profile: UserProfileDto = {
  userId: 6,
  major: '计算机科学与技术',
  grade: 2026,
  campus: '东校区',
  degree: 'UNDERGRADUATE',
  college: '计算机学院',
  lifecycleStage: 'NEW_STUDENT',
  courseRecords: [{
    courseCode: 'CS101',
    courseName: '程序设计',
    term: '2026 秋',
  }],
  updatedAt: '2026-07-10T09:00:00+08:00',
}

function courseRecords(count: number): UserProfileDto['courseRecords'] {
  return Array.from({ length: count }, (_, index) => ({
    courseCode: `CS${String(index + 1).padStart(3, '0')}`,
    courseName: `课程 ${index + 1}`,
    term: '2026 秋',
  }))
}

describe('ProfileView', () => {
  beforeEach(() => {
    mocks.getProfile.mockReset()
    mocks.updateProfile.mockReset()
    mocks.getProfile.mockResolvedValue(profile)
    mocks.updateProfile.mockResolvedValue(profile)
  })

  it('loads profile fields and existing course records', async () => {
    const wrapper = mount(ProfileView)
    await flushPromises()

    expect(mocks.getProfile).toHaveBeenCalledTimes(1)
    expect((wrapper.get('input[name="major"]').element as HTMLInputElement).value)
      .toBe('计算机科学与技术')
    expect((wrapper.get('input[name="grade"]').element as HTMLInputElement).value)
      .toBe('2026')
    expect(wrapper.findAll('[data-course-record]')).toHaveLength(1)
    expect((wrapper.get('input[name="courseName"]').element as HTMLInputElement).value)
      .toBe('程序设计')
  })

  it('keeps the profile fieldset disabled until a profile is applied', async () => {
    let resolveProfile!: (value: UserProfileDto) => void
    mocks.getProfile.mockImplementation(() => new Promise<UserProfileDto>((resolve) => {
      resolveProfile = resolve
    }))

    const wrapper = mount(ProfileView)

    expect(wrapper.find('fieldset[data-profile-fields]').exists()).toBe(true)
    expect(wrapper.get('fieldset[data-profile-fields]').attributes('disabled')).toBeDefined()

    resolveProfile(profile)
    await flushPromises()

    expect(wrapper.get('fieldset[data-profile-fields]').attributes('disabled')).toBeUndefined()
  })

  it('normalizes nullable fields and supports adding and removing courses', async () => {
    const wrapper = mount(ProfileView)
    await flushPromises()

    await wrapper.get('input[name="major"]').setValue('   ')
    await wrapper.get('input[name="grade"]').setValue('')
    await wrapper.get('input[name="campus"]').setValue('  南校区  ')
    await wrapper.get('select[name="degree"]').setValue('')
    await wrapper.get('input[name="college"]').setValue('')
    await wrapper.get('select[name="lifecycleStage"]').setValue('ENROLLED')

    await wrapper.get('[data-action="add-course"]').trigger('click')
    let records = wrapper.findAll('[data-course-record]')
    expect(records).toHaveLength(2)
    await records[1]!.get('input[name="courseCode"]').setValue('  MA102 ')
    await records[1]!.get('input[name="courseName"]').setValue('  高等数学  ')
    await records[1]!.get('input[name="term"]').setValue('   ')
    await records[0]!.get('[data-remove-course]').trigger('click')
    records = wrapper.findAll('[data-course-record]')
    expect(records).toHaveLength(1)

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(mocks.updateProfile).toHaveBeenCalledWith({
      major: null,
      grade: null,
      campus: '南校区',
      degree: null,
      college: null,
      lifecycleStage: 'ENROLLED',
      courseRecords: [{
        courseCode: 'MA102',
        courseName: '高等数学',
        term: null,
      }],
    })
    expect(wrapper.text()).toContain('画像已保存')
  })

  it('disables the entire profile fieldset while a save is pending', async () => {
    let resolveSave!: (value: UserProfileDto) => void
    mocks.updateProfile.mockImplementation(() => new Promise<UserProfileDto>((resolve) => {
      resolveSave = resolve
    }))
    const wrapper = mount(ProfileView)
    await flushPromises()

    await wrapper.get('form').trigger('submit')

    expect(wrapper.get('fieldset[data-profile-fields]').attributes('disabled')).toBeDefined()

    resolveSave(profile)
    await flushPromises()

    expect(wrapper.get('fieldset[data-profile-fields]').attributes('disabled')).toBeUndefined()
  })

  it('disables adding and explains the limit at 100 course records', async () => {
    mocks.getProfile.mockResolvedValue({
      ...profile,
      courseRecords: courseRecords(100),
    })
    const wrapper = mount(ProfileView)
    await flushPromises()

    const addButton = wrapper.get('[data-action="add-course"]')
    expect(addButton.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('已达到 100 条课程记录上限')
    await addButton.trigger('click')

    expect(wrapper.findAll('[data-course-record]')).toHaveLength(100)
  })

  it('refuses to save more than 100 course records', async () => {
    mocks.getProfile.mockResolvedValue({
      ...profile,
      courseRecords: courseRecords(101),
    })
    const wrapper = mount(ProfileView)
    await flushPromises()

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(mocks.updateProfile).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('课程记录不能超过 100 条')
  })

  it('shows an understandable error when the profile response is null', async () => {
    mocks.getProfile.mockResolvedValue(null)

    const wrapper = mount(ProfileView)
    await flushPromises()

    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('个人画像暂时无法读取')
    expect(mocks.updateProfile).not.toHaveBeenCalled()
  })

  it('retries an unavailable profile and enables editing after recovery', async () => {
    mocks.getProfile
      .mockResolvedValueOnce(null)
      .mockResolvedValueOnce(profile)

    const wrapper = mount(ProfileView)
    await flushPromises()

    expect(wrapper.find('[data-action="retry-profile"]').exists()).toBe(true)
    await wrapper.get('[data-action="retry-profile"]').trigger('click')
    await flushPromises()

    expect(mocks.getProfile).toHaveBeenCalledTimes(2)
    expect(wrapper.get('fieldset[data-profile-fields]').attributes('disabled')).toBeUndefined()
  })

  it('emits unauthorized when loading returns 401', async () => {
    mocks.getProfile.mockRejectedValue(
      new ApiClientError('请先登录', 40101, 401),
    )

    const wrapper = mount(ProfileView)
    await flushPromises()

    expect(wrapper.emitted('unauthorized')).toHaveLength(1)
  })

  it('keeps the session and displays an HTTP 403 error', async () => {
    mocks.getProfile.mockRejectedValue(
      new ApiClientError('当前账号无权查看画像', 40300, 403),
    )

    const wrapper = mount(ProfileView)
    await flushPromises()

    expect(wrapper.emitted('unauthorized')).toBeUndefined()
    expect(wrapper.text()).toContain('当前账号无权查看画像')
  })
})
