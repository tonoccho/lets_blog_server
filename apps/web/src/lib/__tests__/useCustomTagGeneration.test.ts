import { act, renderHook } from '@testing-library/react'
import { useCustomTagGeneration } from '../useCustomTagGeneration'

const mockAction = jest.fn()
jest.mock('@/app/custom-tags/actions', () => ({
  generateCustomTagAction: (...args: unknown[]) => mockAction(...args),
}))

const INPUT = { prompt: 'p', tagName: 't' }

/**
 * issue #1409: カスタムタグのAI生成は非同期ジョブとして要求する。フックが持つのは
 * 「要求中か」「受理されたジョブID」「拒否の理由」であって、生成結果ではない
 * (結果はジョブの結果にだけあり、処理キューの「結果を見る」から画面が読む)。
 */
describe('useCustomTagGeneration (#1409)', () => {
  beforeEach(() => mockAction.mockReset())

  it('starts with nothing queued', () => {
    const { result } = renderHook(() => useCustomTagGeneration())
    expect(result.current).toMatchObject({ isLoading: false, error: null, queuedJobId: null })
  })

  it('returns and remembers the id of the accepted job', async () => {
    mockAction.mockResolvedValue({ jobId: 21, status: 'running' })
    const { result } = renderHook(() => useCustomTagGeneration())

    let jobId: number | undefined
    await act(async () => {
      jobId = await result.current.generate(INPUT)
    })

    expect(jobId).toBe(21)
    expect(mockAction).toHaveBeenCalledWith(INPUT)
    expect(result.current).toMatchObject({ isLoading: false, error: null, queuedJobId: 21 })
  })

  it('is loading while the request is in flight', async () => {
    let resolve: (v: unknown) => void = () => {}
    mockAction.mockReturnValue(new Promise((r) => (resolve = r)))
    const { result } = renderHook(() => useCustomTagGeneration())

    let pending: Promise<number | undefined> = Promise.resolve(undefined)
    act(() => {
      pending = result.current.generate(INPUT)
    })
    expect(result.current.isLoading).toBe(true)

    await act(async () => {
      resolve({ jobId: 1, status: 'running' })
      await pending
    })
    expect(result.current.isLoading).toBe(false)
  })

  it('reports the reason when the request is rejected, and queues nothing', async () => {
    mockAction.mockResolvedValue({ error: 'APIエラー (403): 権限がありません' })
    const { result } = renderHook(() => useCustomTagGeneration())

    let jobId: number | undefined = 99
    await act(async () => {
      jobId = await result.current.generate(INPUT)
    })

    expect(jobId).toBeUndefined()
    expect(result.current).toMatchObject({ error: 'APIエラー (403): 権限がありません', queuedJobId: null })
  })

  it('reports a full queue when the job was created but already failed', async () => {
    mockAction.mockResolvedValue({ jobId: 22, status: 'failed' })
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
    mockAction.mockRejectedValueOnce(new Error('network down'))
    const { result } = renderHook(() => useCustomTagGeneration())
    await act(async () => {
      await result.current.generate(INPUT)
    })
    expect(result.current.error).toBe('network down')

    mockAction.mockRejectedValueOnce('boom')
    await act(async () => {
      await result.current.generate(INPUT)
    })
    expect(result.current.error).toBe('不明なエラーが発生しました')
  })

  it('reset forgets the queued job and the error', async () => {
    mockAction.mockResolvedValue({ jobId: 21, status: 'running' })
    const { result } = renderHook(() => useCustomTagGeneration())
    await act(async () => {
      await result.current.generate(INPUT)
    })

    act(() => result.current.reset())

    expect(result.current).toMatchObject({ isLoading: false, error: null, queuedJobId: null })
  })
})
