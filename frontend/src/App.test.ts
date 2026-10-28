// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, type Router } from 'vue-router'

import App from './App.vue'
import { session } from './auth/session'
import type { UserSummaryDto } from './api/types'
import { createAppRouter } from './router'

const student: UserSummaryDto = {
  id: 7,
  username: 'lin',
  displayName: '林同学',
  role: 'STUDENT',
}

const administrator: UserSummaryDto = {
  id: 1,
  username: 'admin',
  displayName: '值班管理员',
  role: 'ADMIN',
}

const stubs = {
  LoginView: {
    template: '<main id="main-content" tabindex="-1" data-view="login">登录</main>',
  },
  ChatView: {
    emits: ['streaming-change', 'unauthorized'],
    template: '<section data-view="chat"><button data-start-stream @click="$emit(\'streaming-change\', true)">开始生成</button><button data-unauthorized @click="$emit(\'unauthorized\')">失效</button></section>',
  },
  ProfileView: {
    data: () => ({ draft: '' }),
    template: '<section data-view="profile"><input data-profile-draft v-model="draft"></section>',
  },
  UgcView: {
    data: () => ({ draft: '' }),
    template: '<section data-view="ugc"><input data-ugc-draft v-model="draft"></section>',
  },
  AdminOverviewView: {
    template: '<section data-view="admin-overview">管理员总览</section>',
  },
}

async function mountAt(
  path: string,
  user: UserSummaryDto | null,
): Promise<{ router: Router; wrapper: ReturnType<typeof mount> }> {
  session.user.value = user
  session.ready.value = true
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
  await router.isReady()
  const wrapper = mount(App, {
    global: {
      plugins: [router],
      stubs,
    },
  })
  await flushPromises()
  return { router, wrapper }
}

describe('role-aware application shell', () => {
  beforeEach(() => {
    localStorage.clear()
    session.user.value = null
    session.ready.value = true
    session.notice.value = ''
    session.authorizationError.value = ''
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('shows only the login route while signed out', async () => {
    const { wrapper, router } = await mountAt('/chat', null)

    expect(router.currentRoute.value.name).toBe('login')
    expect(wrapper.find('[data-view="login"]').exists()).toBe(true)
    expect(wrapper.find('[data-app-navigation]').exists()).toBe(false)
    expect(wrapper.get('.skip-link').attributes('href')).toBe('#main-content')
  })

  it('renders the student navigation, switches views, and logs out', async () => {
    localStorage.setItem('token', 'signed-token')
    const { wrapper, router } = await mountAt('/chat', student)

    expect(wrapper.get('.brand').text()).toContain('校园智答')
    expect(wrapper.get('[data-view-button="chat"]').attributes('aria-current'))
      .toBe('page')

    await wrapper.get('[data-view-button="profile"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-view="profile"]').exists()).toBe(true)

    await wrapper.get('[data-action="logout"]').trigger('click')
    await flushPromises()
    expect(session.user.value).toBeNull()
    expect(localStorage.getItem('token')).toBeNull()
    expect(router.currentRoute.value.name).toBe('login')
    expect(wrapper.find('[data-app-navigation]').exists()).toBe(false)
  })

  it('keeps profile and UGC drafts when navigating away and back', async () => {
    const { wrapper } = await mountAt('/profile', student)

    await wrapper.get('[data-profile-draft]').setValue('尚未保存的专业')
    await wrapper.get('[data-view-button="ugc"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-ugc-draft]').setValue('尚未投递的经验')

    await wrapper.get('[data-view-button="profile"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('[data-profile-draft]').element as HTMLInputElement).value)
      .toBe('尚未保存的专业')

    await wrapper.get('[data-view-button="ugc"]').trigger('click')
    await flushPromises()
    expect((wrapper.get('[data-ugc-draft]').element as HTMLInputElement).value)
      .toBe('尚未投递的经验')
  })

  it('asks before leaving a streaming chat and stays when declined', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const { wrapper, router } = await mountAt('/chat', student)

    await wrapper.get('[data-start-stream]').trigger('click')
    await wrapper.get('[data-view-button="profile"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(router.currentRoute.value.fullPath).toBe('/chat')

    confirm.mockReturnValue(true)
    await wrapper.get('[data-view-button="profile"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/profile')
  })

  it('clears authentication when a child view reports 401', async () => {
    localStorage.setItem('token', 'signed-token')
    const host = document.createElement('div')
    document.body.appendChild(host)
    const { wrapper, router } = await mountAt('/chat', student)
    host.appendChild(wrapper.element)

    await wrapper.get('[data-unauthorized]').trigger('click')
    await flushPromises()

    expect(session.user.value).toBeNull()
    expect(session.notice.value).toContain('登录状态已失效')
    expect(localStorage.getItem('token')).toBeNull()
    expect(router.currentRoute.value.name).toBe('login')
    expect(document.activeElement).toBe(wrapper.get('main#main-content').element)
  })

  it('renders the admin shell only for administrators', async () => {
    const { wrapper, router } = await mountAt('/admin', administrator)

    expect(router.currentRoute.value.name).toBe('admin-overview')
    expect(wrapper.find('[data-admin-navigation]').exists()).toBe(true)
    expect(wrapper.find('[data-view="admin-overview"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('值班管理员')
  })

  it('shows authorization errors without clearing the established session', async () => {
    session.authorizationError.value = '没有权限读取此资源'
    const { wrapper } = await mountAt('/admin', administrator)

    expect(wrapper.get('[data-authorization-error]').text())
      .toContain('没有权限读取此资源')
    expect(session.user.value).toEqual(administrator)
  })
})
