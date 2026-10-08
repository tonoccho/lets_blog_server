import { render, screen, act, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { InfoRailQueue } from '../InfoRailQueue'
import { I18nProvider } from '../I18nProvider'

let mockPathname = '/'
jest.mock('next/navigation', () => ({ usePathname: () => mockPathname }))
jest.mock('next/link', () => {
  const Link = ({ children, href, ...rest }: { children: ReactNode; href: string }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  )
  Link.displayName = 'MockLink'
  return Link
})

const mockFetch = jest.fn()
jest.mock('../infoRailActions', () => ({
  fetchQueueJobsAction: (...args: unknown[]) => mockFetch(...args),
}))

type J = { id: number; type: string; status: string; createdAt: string; resultHref: string | null; failureReason?: string | null }
const job = (over: Partial<J> & { id: number }): J => ({
  type: 'image_generation',
  status: 'done',
  createdAt: '2026-09-30T10:00:00',
  resultHref: null,
  ...over,
})

function setup(locale: 'ja' | 'en' = 'ja') {
  return render(
    <I18nProvider initialLocale={locale}>
      <InfoRailQueue />
    </I18nProvider>
  )
}

/** マイクロタスクを流して、保留中の取得結果を反映させる。 */
async function flush() {
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
  })
}

beforeEach(() => {
  jest.useFakeTimers()
  mockPathname = '/'
  mockFetch.mockReset()
  mockFetch.mockResolvedValue({ jobs: [], timeZone: 'UTC' })
})

afterEach(() => {
  jest.useRealTimers()
})

describe('InfoRailQueue (#1407)', () => {
  it('shows a loading message, then the empty state with an always-visible link to the operation logs', async () => {
    setup()
    expect(screen.getByText('読み込み中…')).toBeInTheDocument()
    await flush()
    expect(screen.getByText('まだ処理はありません')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '過去の処理を操作ログで見る' })).toHaveAttribute('href', '/operation-logs')
  })

  it('shows type, status and start time for each job, newest first as returned', async () => {
    mockFetch.mockResolvedValue({
      jobs: [
        job({ id: 2, type: 'comfyui_checkpoint_download', status: 'running', createdAt: '2026-09-30T10:05:00' }),
        job({ id: 1, type: 'media_garbage_collection_delete', status: 'failed' }),
      ],
      timeZone: 'UTC',
    })
    setup()
    await flush()
    const items = screen.getAllByTestId('info-rail-queue-item')
    expect(items).toHaveLength(2)
    expect(items[0]).toHaveAttribute('data-job-id', '2')
    expect(items[0]).toHaveTextContent('チェックポイントのダウンロード')
    expect(items[0]).toHaveTextContent('実行中')
    expect(items[0].querySelector('time')).toHaveAttribute('datetime', '2026-09-30T10:05:00')
    expect(items[0].querySelector('time')?.textContent).toMatch(/10:05/)
    expect(items[1]).toHaveTextContent('メディアの整理')
    expect(items[1]).toHaveTextContent('失敗')
    expect(screen.queryByText('まだ処理はありません')).not.toBeInTheDocument()
  })

  it('labels pending and done, and falls back to the raw type and status when unknown', async () => {
    mockFetch.mockResolvedValue({
      jobs: [
        job({ id: 1, status: 'pending' }),
        job({ id: 2, status: 'done' }),
        job({ id: 3, type: 'mystery', status: 'weird' }),
      ],
      timeZone: 'UTC',
    })
    setup()
    await flush()
    const items = screen.getAllByTestId('info-rail-queue-item')
    expect(items[0]).toHaveTextContent('待機中')
    expect(items[1]).toHaveTextContent('完了')
    expect(items[2]).toHaveTextContent('mystery')
    expect(items[2]).toHaveTextContent('weird')
  })

  it('shows a result link only for done jobs that have a destination', async () => {
    mockFetch.mockResolvedValue({
      jobs: [
        job({ id: 1, resultHref: '/projects/3?tab=garbage-collection' }),
        job({ id: 2, resultHref: null }),
      ],
      timeZone: 'UTC',
    })
    setup()
    await flush()
    const items = screen.getAllByTestId('info-rail-queue-item')
    expect(within(items[0]).getByRole('link', { name: '結果を見る' })).toHaveAttribute(
      'href',
      '/projects/3?tab=garbage-collection'
    )
    expect(within(items[1]).queryByRole('link')).not.toBeInTheDocument()
  })

  it('shows the time placeholder while the time zone is unresolved', async () => {
    const spy = jest.spyOn(Intl, 'DateTimeFormat').mockImplementation(() => {
      throw new Error('no intl')
    })
    mockFetch.mockResolvedValue({ jobs: [job({ id: 1 })], timeZone: null })
    setup()
    await flush()
    spy.mockRestore()
    expect(screen.getByTestId('info-rail-queue-item').querySelector('time')).toBeInTheDocument()
  })

  it('polls every 2 seconds while a job is running or pending, and stops once all have settled', async () => {
    mockFetch
      .mockResolvedValueOnce({ jobs: [job({ id: 1, status: 'running' })], timeZone: 'UTC' })
      .mockResolvedValueOnce({ jobs: [job({ id: 1, status: 'pending' })], timeZone: 'UTC' })
      .mockResolvedValue({ jobs: [job({ id: 1, status: 'done' })], timeZone: 'UTC' })
    setup()
    await flush()
    expect(mockFetch).toHaveBeenCalledTimes(1)
    await act(async () => {
      jest.advanceTimersByTime(1999)
    })
    expect(mockFetch).toHaveBeenCalledTimes(1)
    await act(async () => {
      jest.advanceTimersByTime(1)
    })
    await flush()
    expect(mockFetch).toHaveBeenCalledTimes(2)
    await act(async () => {
      jest.advanceTimersByTime(2000)
    })
    await flush()
    expect(mockFetch).toHaveBeenCalledTimes(3)
    expect(screen.getByTestId('info-rail-queue-item')).toHaveTextContent('完了')
    // 全て終わったら 2 秒間隔をやめ、低頻度(10 秒)の確認に切り替わる。
    await act(async () => {
      jest.advanceTimersByTime(9999)
    })
    expect(mockFetch).toHaveBeenCalledTimes(3)
    await act(async () => {
      jest.advanceTimersByTime(1)
    })
    await flush()
    expect(mockFetch).toHaveBeenCalledTimes(4)
  })

  it('keeps checking at a low frequency when no job is active, not every 2 seconds', async () => {
    mockFetch.mockResolvedValue({ jobs: [job({ id: 1, status: 'failed' })], timeZone: 'UTC' })
    setup()
    await flush()
    await act(async () => {
      jest.advanceTimersByTime(9999)
    })
    expect(mockFetch).toHaveBeenCalledTimes(1)
    await act(async () => {
      jest.advanceTimersByTime(1)
    })
    await flush()
    expect(mockFetch).toHaveBeenCalledTimes(2)
  })

  it('shows a job started on the current page after the idle interval, with no navigation', async () => {
    mockFetch
      .mockResolvedValueOnce({ jobs: [], timeZone: 'UTC' })
      .mockResolvedValue({ jobs: [job({ id: 7, type: 'comfyui_checkpoint_download', status: 'pending' })], timeZone: 'UTC' })
    setup()
    await flush()
    expect(screen.getByText('まだ処理はありません')).toBeInTheDocument()
    await act(async () => {
      jest.advanceTimersByTime(10000)
    })
    await flush()
    expect(screen.getByTestId('info-rail-queue-item')).toHaveAttribute('data-job-id', '7')
    expect(screen.queryByText('まだ処理はありません')).not.toBeInTheDocument()
    // 進行中になったので 2 秒間隔に切り替わる。
    await act(async () => {
      jest.advanceTimersByTime(2000)
    })
    await flush()
    expect(mockFetch).toHaveBeenCalledTimes(3)
  })

  it('stops the idle check on unmount', async () => {
    setup().unmount()
    await flush()
    await act(async () => {
      jest.advanceTimersByTime(30000)
    })
    expect(mockFetch).toHaveBeenCalledTimes(1)

    const idle = setup()
    await flush()
    idle.unmount()
    await act(async () => {
      jest.advanceTimersByTime(30000)
    })
    expect(mockFetch).toHaveBeenCalledTimes(2)
  })

  it('fetches again on a pathname change, restarting polling for the new page', async () => {
    const { rerender } = setup()
    await flush()
    expect(mockFetch).toHaveBeenCalledTimes(1)
    mockPathname = '/sites'
    rerender(
      <I18nProvider initialLocale="ja">
        <InfoRailQueue />
      </I18nProvider>
    )
    await flush()
    expect(mockFetch).toHaveBeenCalledTimes(2)
  })

  it('shows an error, stops polling and raises no unhandled rejection when a fetch fails', async () => {
    mockFetch
      .mockResolvedValueOnce({ jobs: [job({ id: 1, status: 'running' })], timeZone: 'UTC' })
      .mockRejectedValue(new Error('boom'))
    setup()
    await flush()
    await act(async () => {
      jest.advanceTimersByTime(2000)
    })
    await flush()
    expect(screen.getByText('処理キューを取得できませんでした')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '過去の処理を操作ログで見る' })).toBeInTheDocument()
    await act(async () => {
      jest.advanceTimersByTime(10000)
    })
    expect(mockFetch).toHaveBeenCalledTimes(2)
  })

  it('stops polling on unmount and ignores a response that arrives afterwards', async () => {
    const spy = jest.spyOn(console, 'error').mockImplementation(() => {})
    mockFetch.mockResolvedValue({ jobs: [job({ id: 1, status: 'running' })], timeZone: 'UTC' })
    const { unmount } = setup()
    await flush()
    unmount()
    await act(async () => {
      jest.advanceTimersByTime(10000)
    })
    expect(mockFetch).toHaveBeenCalledTimes(1)

    let resolve!: (v: unknown) => void
    mockFetch.mockReturnValue(new Promise((r) => (resolve = r)))
    const second = setup()
    second.unmount()
    resolve({ jobs: [job({ id: 1, status: 'running' })], timeZone: 'UTC' })
    await flush()
    await act(async () => {
      jest.advanceTimersByTime(10000)
    })
    expect(mockFetch).toHaveBeenCalledTimes(2)
    expect(spy).not.toHaveBeenCalled()
    spy.mockRestore()
  })

  it('ignores a rejection that arrives after unmount', async () => {
    let reject!: (e: unknown) => void
    mockFetch.mockReturnValue(new Promise((_, r) => (reject = r)))
    const { unmount } = setup()
    unmount()
    reject(new Error('late'))
    await flush()
  })

  it('renders English labels', async () => {
    mockFetch.mockResolvedValue({ jobs: [job({ id: 1, status: 'running' })], timeZone: 'UTC' })
    setup('en')
    await flush()
    expect(screen.getByTestId('info-rail-queue-item')).toHaveTextContent('Running')
    expect(screen.getByRole('link', { name: 'See past jobs in the operation log' })).toBeInTheDocument()
  })

  it('labels an image generation job in Japanese and English instead of showing the raw type (#1408)', async () => {
    mockFetch.mockResolvedValue({ jobs: [job({ id: 1, type: 'image_generation', status: 'running' })], timeZone: 'UTC' })
    const { unmount } = setup('ja')
    await flush()
    expect(screen.getByTestId('info-rail-queue-item')).toHaveTextContent('画像生成')
    expect(screen.getByTestId('info-rail-queue-item')).not.toHaveTextContent('image_generation')
    unmount()
    setup('en')
    await flush()
    expect(screen.getByTestId('info-rail-queue-item')).toHaveTextContent('Image generation')
  })

  it.each([
    ['custom_tag_generation', 'カスタムタグ生成', 'Custom tag generation'],
    ['static_content_generation', '静的コンテンツ生成', 'Static content generation'],
    ['tag_design_generation', 'タグデザイン生成', 'Tag design generation'],
    ['site_provisioning', 'サイト自動構築', 'Site provisioning'],
  ])('labels a %s job in Japanese and English instead of showing the raw type (#1409)', async (type, ja, en) => {
    mockFetch.mockResolvedValue({ jobs: [job({ id: 1, type, status: 'running' })], timeZone: 'UTC' })
    const { unmount } = setup('ja')
    await flush()
    expect(screen.getByTestId('info-rail-queue-item')).toHaveTextContent(ja)
    expect(screen.getByTestId('info-rail-queue-item')).not.toHaveTextContent(type)
    unmount()
    setup('en')
    await flush()
    expect(screen.getByTestId('info-rail-queue-item')).toHaveTextContent(en)
  })

  it('links a done image generation to its result via the view-result link (#1408)', async () => {
    mockFetch.mockResolvedValue({
      jobs: [job({ id: 4, type: 'image_generation', status: 'done', resultHref: '/projects/7?tab=ai-models&imageJob=4' })],
      timeZone: 'UTC',
    })
    setup()
    await flush()
    expect(screen.getByRole('link', { name: '結果を見る' })).toHaveAttribute(
      'href',
      '/projects/7?tab=ai-models&imageJob=4'
    )
  })
})

describe('InfoRailQueue failure reason (#1571)', () => {
  it('shows the failure reason on a failed job', async () => {
    mockFetch.mockResolvedValue({
      jobs: [job({ id: 1, status: 'failed', failureReason: 'ComfyUI timed out' })],
      timeZone: 'UTC',
    })
    setup()
    await flush()
    const item = screen.getByTestId('info-rail-queue-item')
    expect(item).toHaveTextContent('失敗')
    expect(within(item).getByTestId('info-rail-queue-failure-reason')).toHaveTextContent('ComfyUI timed out')
  })

  it('shows no failure reason when the job has none', async () => {
    mockFetch.mockResolvedValue({
      jobs: [job({ id: 1, status: 'failed' }), job({ id: 2, status: 'done' })],
      timeZone: 'UTC',
    })
    setup()
    await flush()
    expect(screen.queryByTestId('info-rail-queue-failure-reason')).not.toBeInTheDocument()
  })
})
