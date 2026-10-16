import { act, renderHook, waitFor } from '@testing-library/react'
import { useCustomTagGeneration } from '../useCustomTagGeneration'

// 自前の Server Action(@/app/custom-tags/actions)・@/lib/session・@/lib/apiClient は差し替えない。
// 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1832)。
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

const GENERATE = 'POST /api/custom-tags/generate/jobs'

const INPUT = { prompt: 'p', tagName: 't' }

/**
 * issue #1409: カスタムタグのAI生成は非同期ジョブとして要求する。フックが持つのは
 * 「要求中か」「受理されたジョブID」「拒否の理由」であって、生成結果ではない
 * (結果はジョブの結果にだけあり、処理キューの「結果を見る」から画面が読む)。
 */
describe('useCustomTagGeneration (#1409)', () => {
  beforeEach(() => resetServerRuntime({ user: { id: 'a-1', role: 'admin', email: 'a@example.com' } }))

  it('starts with nothing queued', () => {
    const { result } = renderHook(() => useCustomTagGeneration())
    expect(result.current).toMatchObject({ isLoading: false, error: null, queuedJobId: null })
  })

  it('returns and remembers the id of the accepted job', async () => {
    const fetchMock = installFetch({ [GENERATE]: () => jsonResponse({ id: 21, status: 'running' }) })
    const { result } = renderHook(() => useCustomTagGeneration())

    let jobId: number | undefined
    await act(async () => {
      jobId = await result.current.generate(INPUT)
    })

    expect(jobId).toBe(21)
    expect(fetchMock).toHaveBeenCalledWith(
      'https://localhost/api/custom-tags/generate/jobs',
      expect.objectContaining({ method: 'POST', body: JSON.stringify(INPUT) })
    )
    expect(result.current).toMatchObject({ isLoading: false, error: null, queuedJobId: 21 })
  })

  it('is loading while the request is in flight', async () => {
    let resolve: (v: Response) => void = () => {}
    installFetch({ [GENERATE]: () => new Promise<Response>((r) => (resolve = r)) })
    const { result } = renderHook(() => useCustomTagGeneration())

    let pending: Promise<number | undefined> = Promise.resolve(undefined)
    act(() => {
      pending = result.current.generate(INPUT)
    })
    expect(result.current.isLoading).toBe(true)

    await waitFor(() => expect(global.fetch).toHaveBeenCalled())
    await act(async () => {
      resolve(jsonResponse({ id: 1, status: 'running' }))
      await pending
    })
    expect(result.current.isLoading).toBe(false)
  })

  it('reports the reason when the request is rejected, and queues nothing', async () => {
    installFetch({ [GENERATE]: () => textResponse('権限がありません', 403) })
    const { result } = renderHook(() => useCustomTagGeneration())

    let jobId: number | undefined = 99
    await act(async () => {
      jobId = await result.current.generate(INPUT)
    })

    expect(jobId).toBeUndefined()
    expect(result.current).toMatchObject({ error: 'APIエラー (403): 権限がありません', queuedJobId: null })
  })

  it('reports a full queue when the job was created but already failed', async () => {
    installFetch({ [GENERATE]: () => jsonResponse({ id: 22, status: 'failed' }) })
    const { result } = renderHook(() => useCustomTagGeneration())

    let jobId: number | undefined = 99
    await act(async () => {
      jobId = await result.current.generate(INPUT)
    })

    expect(jobId).toBeUndefined()
    expect(result.current.error).toContain('待ち行列が満杯')
    expect(result.current.queuedJobId).toBeNull()
  })

  it('reports an unexpected exception from the action instead of crashing', async () => {
    getServerSessionMock.mockRejectedValueOnce(new Error('network down'))
    const { result } = renderHook(() => useCustomTagGeneration())
    await act(async () => {
      await result.current.generate(INPUT)
    })
    expect(result.current.error).toBe('network down')

    getServerSessionMock.mockRejectedValueOnce('boom')
    await act(async () => {
      await result.current.generate(INPUT)
    })
    expect(result.current.error).toBe('不明なエラーが発生しました')
  })

  it('reports an unknown error when the accepted job carries no id', async () => {
    installFetch({ [GENERATE]: () => jsonResponse({}) })
    const { result } = renderHook(() => useCustomTagGeneration())

    let jobId: number | undefined = 99
    await act(async () => {
      jobId = await result.current.generate(INPUT)
    })

    expect(jobId).toBeUndefined()
    expect(result.current).toMatchObject({ error: '不明なエラーが発生しました', queuedJobId: null })
  })

  it('reset forgets the queued job and the error', async () => {
    installFetch({ [GENERATE]: () => jsonResponse({ id: 21, status: 'running' }) })
    const { result } = renderHook(() => useCustomTagGeneration())
    await act(async () => {
      await result.current.generate(INPUT)
    })

    act(() => result.current.reset())

    expect(result.current).toMatchObject({ isLoading: false, error: null, queuedJobId: null })
  })
})
