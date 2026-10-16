import { act, renderHook } from '@testing-library/react'
import { useCustomTagValidation } from '../useCustomTagValidation'

/**
 * カスタムタグの HTML / CSS の検証を要求するフック。自前の Server Action(@/app/custom-tags/actions)・
 * @/lib/session・@/lib/apiClient は差し替えない。差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1832)。
 */
jest.mock('server-only', () => ({}))
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule)
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule)
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule)
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule)
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`redirect:${path}`)
  },
}))

import {
  getServerSessionMock,
  installFetch,
  jsonResponse,
  resetServerRuntime,
  textResponse,
} from '@/lib/__tests__/testUtils/serverRuntime'

const VALIDATE = 'POST /api/custom-tags/validate'
const RESULT = { valid: true, errors: [] }

describe('useCustomTagValidation', () => {
  beforeEach(() => resetServerRuntime({ user: { id: 'a-1', role: 'admin', email: 'a@example.com' } }))

  it('starts idle', () => {
    const { result } = renderHook(() => useCustomTagValidation())
    expect(result.current).toMatchObject({ isLoading: false, error: null, result: null })
  })

  it('sends the template and css, and keeps and returns the validation result', async () => {
    const fetchMock = installFetch({ [VALIDATE]: () => jsonResponse(RESULT) })
    const { result } = renderHook(() => useCustomTagValidation())

    let returned: unknown
    await act(async () => {
      returned = await result.current.validate('<b/>', '.b{}')
    })

    expect(returned).toEqual(RESULT)
    expect(result.current).toMatchObject({ isLoading: false, error: null, result: RESULT })
    const call = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/api/custom-tags/validate'))!
    expect(JSON.parse(call[1].body)).toEqual({ htmlTemplate: '<b/>', cssContent: '.b{}' })
  })

  it('keeps no result when the response has no body', async () => {
    installFetch({ [VALIDATE]: () => ({ ...jsonResponse(null, 204), status: 204 }) as Response })
    const { result } = renderHook(() => useCustomTagValidation())

    await act(async () => {
      await result.current.validate('<b/>', '.b{}')
    })

    expect(result.current).toMatchObject({ isLoading: false, error: null, result: null })
  })

  it('shows the reason and rethrows when the server rejects the request', async () => {
    installFetch({ [VALIDATE]: () => textResponse('不正なテンプレート', 400) })
    const { result } = renderHook(() => useCustomTagValidation())

    let thrown: unknown
    await act(async () => {
      thrown = await result.current.validate('<b/>', '.b{}').catch((e: unknown) => e)
    })

    expect((thrown as Error).message).toContain('不正なテンプレート')
    expect(result.current).toMatchObject({ isLoading: false, result: null })
    expect(result.current.error).toContain('不正なテンプレート')
  })

  it('reports an unknown error when something other than an Error is thrown', async () => {
    getServerSessionMock.mockRejectedValueOnce('boom')
    const { result } = renderHook(() => useCustomTagValidation())

    let thrown: unknown
    await act(async () => {
      thrown = await result.current.validate('<b/>', '.b{}').catch((e: unknown) => e)
    })

    expect(thrown).toBe('boom')
    expect(result.current.error).toBe('不明なエラーが発生しました')
  })

  it('reset forgets the result and the error', async () => {
    installFetch({ [VALIDATE]: () => jsonResponse(RESULT) })
    const { result } = renderHook(() => useCustomTagValidation())
    await act(async () => {
      await result.current.validate('<b/>', '.b{}')
    })

    act(() => result.current.reset())

    expect(result.current).toMatchObject({ isLoading: false, error: null, result: null })
  })
})
