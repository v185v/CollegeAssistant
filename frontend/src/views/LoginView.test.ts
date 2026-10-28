// @vitest-environment happy-dom

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import LoginView from './LoginView.vue'

const mocks = vi.hoisted(() => ({
  login: vi.fn(),
  register: vi.fn(),
  setStoredToken: vi.fn(),
  establish: vi.fn(),
  routerReplace: vi.fn(),
  routerResolve: vi.fn(),
  routeQuery: {} as Record<string, unknown>,
}))

vi.mock('../api/endpoints', () => ({
  login: mocks.login,
  register: mocks.register,
}))

vi.mock('../api/client', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/client')>()
  return {
    ...original,
    setStoredToken: mocks.setStoredToken,
  }
})

vi.mock('../auth/session', () => ({
  session: {
    establish: mocks.establish,
    notice: { value: '' },
  },
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: mocks.routeQuery }),
  useRouter: () => ({
    replace: mocks.routerReplace,
    resolve: mocks.routerResolve,
  }),
}))

const authResponse = {
  tokenType: 'Bearer' as const,
  accessToken: 'signed-access-token',
  expiresIn: 3600,
  user: {
    id: 1,
    username: 'lin',
    displayName: '林同学',
    role: 'STUDENT' as const,
  },
}

const seventyTwoByteChinesePassword = '中'.repeat(24)
const seventyFiveByteChinesePassword = '中'.repeat(25)

describe('LoginView', () => {
  beforeEach(() => {
    mocks.login.mockReset()
    mocks.register.mockReset()
    mocks.setStoredToken.mockReset()
    mocks.establish.mockReset()
    mocks.routerReplace.mockReset()
    mocks.routerResolve.mockReset()
    mocks.routerResolve.mockReturnValue({ matched: [], name: undefined })
    for (const key of Object.keys(mocks.routeQuery)) {
      delete mocks.routeQuery[key]
    }
    mocks.setStoredToken.mockReturnValue(true)
  })

  it('switches to registration and submits displayName', async () => {
    mocks.register.mockResolvedValue(authResponse)
    const wrapper = mount(LoginView)

    expect(wrapper.find('input[name="displayName"]').exists()).toBe(false)
    await wrapper.get(
      'button[name="auth-mode"][value="register"]',
    ).trigger('click')
    expect(wrapper.find('input[name="displayName"]').exists()).toBe(true)

    await wrapper.get('input[name="username"]').setValue('zhou')
    await wrapper.get('input[name="password"]').setValue('secret123')
    await wrapper.get('input[name="displayName"]').setValue('  周同学  ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(mocks.register).toHaveBeenCalledWith({
      username: 'zhou',
      password: 'secret123',
      displayName: '周同学',
    })
    expect(mocks.login).not.toHaveBeenCalled()
    expect(mocks.setStoredToken).toHaveBeenCalledWith('signed-access-token')
    expect(mocks.establish).toHaveBeenCalledWith(authResponse.user)
    expect(mocks.routerReplace).toHaveBeenCalledWith('/chat')
  })

  it('applies strict field constraints only after switching to registration', async () => {
    const wrapper = mount(LoginView)
    const username = wrapper.get('input[name="username"]')
    const password = wrapper.get('input[name="password"]')

    expect(username.attributes('minlength')).toBeUndefined()
    expect(username.attributes('maxlength')).toBeUndefined()
    expect(username.attributes('pattern')).toBeUndefined()
    expect(password.attributes('minlength')).toBeUndefined()
    expect(password.attributes('maxlength')).toBeUndefined()

    await wrapper.get(
      'button[name="auth-mode"][value="register"]',
    ).trigger('click')
    expect(username.attributes('minlength')).toBe('3')
    expect(username.attributes('maxlength')).toBe('32')
    expect(username.attributes('pattern')).toBe('[A-Za-z0-9_-]+')
    expect(password.attributes('minlength')).toBe('8')
    expect(password.attributes('maxlength')).toBe('72')
    const displayName = wrapper.get('input[name="displayName"]')
    expect(displayName.attributes('minlength')).toBe('1')
    expect(displayName.attributes('maxlength')).toBe('32')
  })

  it('allows non-blank legacy credentials in login mode', async () => {
    mocks.login.mockResolvedValue(authResponse)
    const wrapper = mount(LoginView)

    await wrapper.get('input[name="username"]').setValue('legacy.user@example')
    await wrapper.get('input[name="password"]').setValue('old')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(mocks.login).toHaveBeenCalledWith({
      username: 'legacy.user@example',
      password: 'old',
    })
    expect(mocks.establish).toHaveBeenCalledWith(authResponse.user)
  })

  it('accepts a 72-byte Chinese password in login mode', async () => {
    mocks.login.mockResolvedValue(authResponse)
    const wrapper = mount(LoginView)

    await wrapper.get('input[name="username"]').setValue('lin')
    await wrapper.get('input[name="password"]').setValue(seventyTwoByteChinesePassword)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(mocks.login).toHaveBeenCalledWith({
      username: 'lin',
      password: seventyTwoByteChinesePassword,
    })
  })

  it('rejects a 75-byte Chinese password in login mode', async () => {
    const wrapper = mount(LoginView)

    await wrapper.get('input[name="username"]').setValue('lin')
    await wrapper.get('input[name="password"]').setValue(seventyFiveByteChinesePassword)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('密码不能超过 72 个 UTF-8 字节')
    expect(mocks.login).not.toHaveBeenCalled()
  })

  it('accepts a 72-byte Chinese password in registration mode', async () => {
    mocks.register.mockResolvedValue(authResponse)
    const wrapper = mount(LoginView)

    await wrapper.get(
      'button[name="auth-mode"][value="register"]',
    ).trigger('click')
    await wrapper.get('input[name="username"]').setValue('lin')
    await wrapper.get('input[name="password"]').setValue(seventyTwoByteChinesePassword)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(mocks.register).toHaveBeenCalledWith({
      username: 'lin',
      password: seventyTwoByteChinesePassword,
      displayName: null,
    })
  })

  it('rejects a 75-byte Chinese password in registration mode', async () => {
    const wrapper = mount(LoginView)

    await wrapper.get(
      'button[name="auth-mode"][value="register"]',
    ).trigger('click')
    await wrapper.get('input[name="username"]').setValue('lin')
    await wrapper.get('input[name="password"]').setValue(seventyFiveByteChinesePassword)
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('密码不能超过 72 个 UTF-8 字节')
    expect(mocks.register).not.toHaveBeenCalled()
  })

  it('does not send blank login credentials', async () => {
    const wrapper = mount(LoginView)
    await wrapper.get('input[name="username"]').setValue('   ')
    await wrapper.get('input[name="password"]').setValue('   ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('用户名和密码不能为空')
    expect(mocks.login).not.toHaveBeenCalled()
  })

  it('does not send registration credentials that violate the strict contract', async () => {
    const wrapper = mount(LoginView)
    await wrapper.get(
      'button[name="auth-mode"][value="register"]',
    ).trigger('click')
    await wrapper.get('input[name="username"]').setValue('ab!')
    await wrapper.get('input[name="password"]').setValue('secret123')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('用户名需为 3–32 位')
    expect(mocks.register).not.toHaveBeenCalled()
  })

  it('logs in and stores the returned access token before entering the app', async () => {
    mocks.login.mockResolvedValue(authResponse)
    const wrapper = mount(LoginView)

    await wrapper.get('input[name="username"]').setValue('lin')
    await wrapper.get('input[name="password"]').setValue('secret123')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(mocks.login).toHaveBeenCalledWith({
      username: 'lin',
      password: 'secret123',
    })
    expect(mocks.setStoredToken).toHaveBeenCalledWith('signed-access-token')
    expect(mocks.establish).toHaveBeenCalledWith(authResponse.user)
    expect(mocks.routerReplace).toHaveBeenCalledWith('/chat')
  })

  it('enters the admin route directly after an administrator logs in', async () => {
    const adminResponse = {
      ...authResponse,
      user: {
        ...authResponse.user,
        role: 'ADMIN' as const,
      },
    }
    mocks.login.mockResolvedValue(adminResponse)
    const wrapper = mount(LoginView)

    await wrapper.get('input[name="username"]').setValue('admin')
    await wrapper.get('input[name="password"]').setValue('secret123')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(mocks.establish).toHaveBeenCalledWith(adminResponse.user)
    expect(mocks.routerReplace).toHaveBeenCalledWith('/admin')
  })

  it('does not enter the app when a successful envelope has null data', async () => {
    mocks.login.mockResolvedValue(null)
    const wrapper = mount(LoginView)

    await wrapper.get('input[name="username"]').setValue('lin')
    await wrapper.get('input[name="password"]').setValue('secret123')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('登录响应缺少凭证，请重试')
    expect(mocks.setStoredToken).not.toHaveBeenCalled()
    expect(mocks.establish).not.toHaveBeenCalled()
    expect(mocks.routerReplace).not.toHaveBeenCalled()
  })

  it('stays signed out when browser storage rejects the token', async () => {
    mocks.login.mockResolvedValue(authResponse)
    mocks.setStoredToken.mockReturnValue(false)
    const wrapper = mount(LoginView)

    await wrapper.get('input[name="username"]').setValue('lin')
    await wrapper.get('input[name="password"]').setValue('secret123')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('浏览器无法保存登录状态')
    expect(mocks.establish).not.toHaveBeenCalled()
    expect(mocks.routerReplace).not.toHaveBeenCalled()
  })
})
