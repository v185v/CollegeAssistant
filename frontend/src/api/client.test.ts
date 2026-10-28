import { afterEach, describe, expect, expectTypeOf, it, vi } from 'vitest'

import {
  apiBinaryRequest,
  apiRequest,
  AUTH_TOKEN_STORAGE_KEY,
  clearStoredToken,
  getStoredToken,
  isAuthenticationStatus,
  setStoredToken,
} from './client'

describe('auth token storage', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('reads and trims a safe stored token', () => {
    vi.stubGlobal('localStorage', {
      getItem: vi.fn(() => '  signed-token  '),
    })

    expect(getStoredToken()).toBe('signed-token')
  })

  it('stores only a safe non-blank token', () => {
    const setItem = vi.fn()
    vi.stubGlobal('localStorage', { setItem })

    expect(setStoredToken('  signed-token  ')).toBe(true)
    expect(setItem).toHaveBeenCalledWith(
      AUTH_TOKEN_STORAGE_KEY,
      'signed-token',
    )

    expect(setStoredToken('unsafe\r\ntoken')).toBe(false)
    expect(setStoredToken('   ')).toBe(false)
    expect(setItem).toHaveBeenCalledTimes(1)
  })

  it('clears the token', () => {
    const removeItem = vi.fn()
    vi.stubGlobal('localStorage', { removeItem })

    expect(clearStoredToken()).toBe(true)
    expect(removeItem).toHaveBeenCalledWith(AUTH_TOKEN_STORAGE_KEY)
  })

  it('contains storage security failures for get, set, and clear', () => {
    const storageError = new DOMException('blocked', 'SecurityError')
    vi.stubGlobal('localStorage', {
      getItem: vi.fn(() => {
        throw storageError
      }),
      setItem: vi.fn(() => {
        throw storageError
      }),
      removeItem: vi.fn(() => {
        throw storageError
      }),
    })

    expect(getStoredToken()).toBeNull()
    expect(setStoredToken('signed-token')).toBe(false)
    expect(clearStoredToken()).toBe(false)
  })
})

describe('authentication status', () => {
  it('expires the local session only for HTTP 401, not HTTP 403', () => {
    expect(isAuthenticationStatus(401)).toBe(true)
    expect(isAuthenticationStatus(403)).toBe(false)
  })
})

describe('apiRequest', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('exposes nullable successful data in its public return type', () => {
    type Result = ReturnType<
      typeof apiRequest<{ displayName: string }>
    >

    expectTypeOf<Result>().toEqualTypeOf<
      Promise<{ displayName: string } | null>
    >()
  })

  it('prefixes the API path, adds the stored token, and unwraps successful data', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json({
        code: 0,
        message: 'ok',
        data: { displayName: '林同学' },
      }),
    )
    const storage = {
      getItem: vi.fn((key: string) =>
        key === AUTH_TOKEN_STORAGE_KEY ? 'secret-token' : null,
      ),
    }
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('localStorage', storage)

    const result = await apiRequest<{ displayName: string }>('/profile', {
      headers: { 'X-Request-Source': 'notice-desk' },
    })

    expect(result).toEqual({ displayName: '林同学' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toBe('/api/profile')
    expect(new Headers(init?.headers).get('Authorization')).toBe(
      'Bearer secret-token',
    )
    expect(new Headers(init?.headers).get('X-Request-Source')).toBe(
      'notice-desk',
    )
  })

  it('throws a typed error when the API envelope reports a business failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(
        Response.json(
          { code: 40001, message: '参数校验失败', data: null },
          { status: 200 },
        ),
      ),
    )
    vi.stubGlobal('localStorage', { getItem: () => null })

    await expect(apiRequest('/profile')).rejects.toMatchObject({
      name: 'ApiClientError',
      message: '参数校验失败',
      code: 40001,
      status: 200,
    })
  })

  it('rejects a non-successful HTTP status even when the envelope code is zero', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(
        Response.json(
          { code: 0, message: 'ok', data: { stale: true } },
          { status: 503 },
        ),
      ),
    )
    vi.stubGlobal('localStorage', { getItem: () => null })

    await expect(apiRequest('/profile')).rejects.toMatchObject({
      name: 'ApiClientError',
      message: '请求失败（HTTP 503）',
      code: 0,
      status: 503,
    })
  })

  it('wraps a non-JSON response without exposing its body', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response('<html>upstream-secret</html>', {
          status: 502,
          headers: { 'Content-Type': 'text/html' },
        }),
      ),
    )
    vi.stubGlobal('localStorage', { getItem: () => null })

    const error = await apiRequest('/profile').catch(
      (failure: unknown) => failure,
    )

    expect(error).toMatchObject({
      name: 'ApiClientError',
      message: '服务器返回了无法识别的响应',
      code: null,
      status: 502,
    })
    expect(String(error)).not.toContain('upstream-secret')
  })

  it('returns null for a successful 204 response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response(null, { status: 204 }),
      ),
    )
    vi.stubGlobal('localStorage', { getItem: () => null })

    await expect(apiRequest<void>('/ugc/42/report')).resolves.toBeNull()
  })

  it('wraps network failures in a safe typed error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockRejectedValue(
        new Error('connect ECONNREFUSED internal-host:8080'),
      ),
    )
    vi.stubGlobal('localStorage', { getItem: () => null })

    const error = await apiRequest('/profile').catch(
      (failure: unknown) => failure,
    )

    expect(error).toMatchObject({
      name: 'ApiClientError',
      message: '暂时无法连接服务器',
      code: null,
      status: 0,
    })
    expect(String(error)).not.toContain('internal-host')
  })

  it('does not duplicate the prefix when given an already-prefixed API path', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json({ code: 0, message: 'ok', data: null }),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('localStorage', { getItem: () => null })

    await apiRequest('/api/profile')

    expect(fetchMock).toHaveBeenCalledWith('/api/profile', expect.any(Object))
  })

  it.each([
    '/api/../outside',
    '/api/%2e%2e/outside',
    '/api/%2E%2e/outside',
    '/api/%2e%2E/outside',
    '/api/%2E%2E/outside',
    '/api/a/../../outside',
    '/api\\outside',
    '//outside.example/api/profile',
    'https://outside.example/api/profile',
    'http://localhost/api/profile',
  ])('rejects unsafe API path %s before reading the token or fetching', async (
    unsafePath,
  ) => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json({ code: 0, message: 'ok', data: null }),
    )
    const getItem = vi.fn(() => 'secret-token')
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('localStorage', { getItem })

    await expect(apiRequest(unsafePath)).rejects.toMatchObject({
      name: 'ApiClientError',
      message: 'API 路径无效',
      code: null,
      status: 0,
    })
    expect(getItem).not.toHaveBeenCalled()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('fetches the normalized relative URL when dot segments remain inside /api', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json({ code: 0, message: 'ok', data: null }),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('localStorage', { getItem: () => null })

    await apiRequest('/api/profile/../ugc?mine=true#local-fragment')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/ugc?mine=true',
      expect.any(Object),
    )
  })

  it('rejects absolute URLs before a token can leave the local API boundary', async () => {
    const fetchMock = vi.fn<typeof fetch>()
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('localStorage', { getItem: () => 'secret-token' })

    await expect(apiRequest('https://outside.example/profile')).rejects
      .toMatchObject({
        name: 'ApiClientError',
        message: 'API 路径无效',
        code: null,
        status: 0,
      })
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('rejects JSON that is not an ApiResponse envelope', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(
        Response.json({ payload: 'upstream-secret' }),
      ),
    )
    vi.stubGlobal('localStorage', { getItem: () => null })

    const error = await apiRequest('/profile').catch(
      (failure: unknown) => failure,
    )

    expect(error).toMatchObject({
      name: 'ApiClientError',
      message: '服务器返回了无法识别的响应',
      code: null,
      status: 200,
    })
    expect(String(error)).not.toContain('upstream-secret')
  })

  it('does not send an Authorization header for a blank stored token', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json({ code: 0, message: 'ok', data: null }),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('localStorage', { getItem: () => '   ' })

    await apiRequest('/profile')

    const [, init] = fetchMock.mock.calls[0]!
    expect(new Headers(init?.headers).has('Authorization')).toBe(false)
  })

  it('continues without Authorization when token storage access is blocked', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json({ code: 0, message: 'ok', data: null }),
    )
    const storageError = new Error('storage-secret')
    storageError.name = 'SecurityError'
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('localStorage', {
      getItem: vi.fn(() => {
        throw storageError
      }),
    })

    await expect(apiRequest('/profile')).resolves.toBeNull()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [, init] = fetchMock.mock.calls[0]!
    expect(new Headers(init?.headers).has('Authorization')).toBe(false)
  })

  it('ignores a stored token containing control characters', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json({ code: 0, message: 'ok', data: null }),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('localStorage', {
      getItem: () => 'secret-token\r\nX-Leak: injected',
    })

    await expect(apiRequest('/profile')).resolves.toBeNull()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [, init] = fetchMock.mock.calls[0]!
    expect(new Headers(init?.headers).has('Authorization')).toBe(false)
  })

  it('normalizes control characters in a business error message', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(
        Response.json({
          code: 40001,
          message: '  参数校验失败\r\n请稍后\u0000  ',
          data: null,
        }),
      ),
    )
    vi.stubGlobal('localStorage', { getItem: () => null })

    await expect(apiRequest('/profile')).rejects.toMatchObject({
      message: '参数校验失败 请稍后',
      code: 40001,
      status: 200,
    })
  })
})

describe('apiBinaryRequest', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('returns a typed binary download with the bearer token and UTF-8 filename', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(
      new Response(new Blob(['binary-content']), {
        headers: {
          'Content-Type': 'application/pdf',
          'Content-Disposition': "attachment; filename*=UTF-8''%E8%AF%BE%E7%A8%8B%E8%A1%A8.pdf",
        },
      }),
    )
    vi.stubGlobal('fetch', fetchMock)
    vi.stubGlobal('localStorage', { getItem: () => 'download-token' })

    const result = await apiBinaryRequest('/documents/7/download')

    expect(result.filename).toBe('课程表.pdf')
    expect(result.contentType).toBe('application/pdf')
    expect(await result.blob.text()).toBe('binary-content')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/documents/7/download',
      expect.objectContaining({ headers: expect.any(Headers) }),
    )
    const [, init] = fetchMock.mock.calls[0]!
    expect(new Headers(init?.headers).get('Authorization')).toBe(
      'Bearer download-token',
    )
  })

  it('rejects a JSON business error even when the backend returns HTTP 200', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(
        Response.json(
          { code: 40401, message: 'Document not found', data: null },
          { status: 200 },
        ),
      ),
    )
    vi.stubGlobal('localStorage', { getItem: () => null })

    await expect(apiBinaryRequest('/documents/7/download')).rejects
      .toMatchObject({
        name: 'ApiClientError',
        code: 40401,
        message: 'Document not found',
        status: 200,
      })
  })

  it('rejects a successful JSON envelope because downloads must be binary', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(
        Response.json({ code: 0, message: 'ok', data: { stale: true } }),
      ),
    )
    vi.stubGlobal('localStorage', { getItem: () => null })

    await expect(apiBinaryRequest('/documents/7/download')).rejects
      .toMatchObject({
        name: 'ApiClientError',
        code: null,
        message: '服务器返回了无法识别的下载响应',
        status: 200,
      })
  })

  it('does not expose a non-JSON failed response body', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValue(
        new Response('upstream-download-secret', {
          status: 503,
          headers: { 'Content-Type': 'text/plain' },
        }),
      ),
    )
    vi.stubGlobal('localStorage', { getItem: () => null })

    const error = await apiBinaryRequest('/documents/7/download').catch(
      (failure: unknown) => failure,
    )

    expect(error).toMatchObject({
      name: 'ApiClientError',
      code: null,
      message: '下载失败（HTTP 503）',
      status: 503,
    })
    expect(String(error)).not.toContain('upstream-download-secret')
  })

  it('keeps binary downloads typed in the public client contract', () => {
    expectTypeOf<ReturnType<typeof apiBinaryRequest>>().toEqualTypeOf<
      Promise<{ blob: Blob; filename: string | null; contentType: string }>
    >()
  })
})
