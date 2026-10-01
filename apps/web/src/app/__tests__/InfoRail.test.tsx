import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import type { ReactNode } from 'react'
import { InfoRail } from '../InfoRail'
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
const mockFetchQueue = jest.fn()
jest.mock('../infoRailActions', () => ({
  fetchRecentOperationLogsAction: (...args: unknown[]) => mockFetch(...args),
  fetchQueueJobsAction: (...args: unknown[]) => mockFetchQueue(...args),
}))

const entries = [
  { sourceType: 'OPERATION', id: 2, createdAt: '2026-09-30T10:00:00', title: 'POST /api/sites', detail: null, status: 'SUCCESS', operationId: 'a', actorKeycloakSub: null },
  { sourceType: 'AUDIT', id: 1, createdAt: '2026-09-30T09:00:00', title: 'PROJECT_CREATED PROJECT #1', detail: null, status: null, operationId: null, actorKeycloakSub: null },
]

function setup(locale: 'ja' | 'en' = 'ja') {
  return render(
    <I18nProvider initialLocale={locale}>
      <InfoRail />
    </I18nProvider>
  )
}

beforeEach(() => {
  mockPathname = '/'
  window.localStorage.clear()
  mockFetch.mockReset()
  mockFetchQueue.mockReset()
  mockFetchQueue.mockResolvedValue({ jobs: [], timeZone: 'UTC' })
  mockFetch.mockResolvedValue({ entries, timeZone: 'UTC' })
})

describe('InfoRail (#1489)', () => {
  it('shows the queue and operation-log tabs, with the queue selected by default', async () => {
    setup()
    const rail = screen.getByTestId('info-rail')
    expect(within(rail).getByRole('button', { name: '処理キュー' })).toHaveAttribute('aria-pressed', 'true')
    expect(within(rail).getByRole('button', { name: '操作ログ' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByTestId('info-rail-queue-panel')).toBeInTheDocument()
    expect(await screen.findByText('まだ処理はありません')).toBeInTheDocument()
    expect(mockFetchQueue).toHaveBeenCalledTimes(1)
    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('shows recent logs in API order (newest first) when the log tab is chosen, with a link to /operation-logs', async () => {
    setup()
    fireEvent.click(screen.getByRole('button', { name: '操作ログ' }))
    const items = await screen.findAllByTestId('info-rail-log-item')
    expect(items.map((i) => i.textContent)).toEqual([
      expect.stringContaining('POST /api/sites'),
      expect.stringContaining('PROJECT_CREATED PROJECT #1'),
    ])
    expect(mockFetch).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('link', { name: '操作ログをすべて見る' })).toHaveAttribute('href', '/operation-logs')
    expect(items[0].querySelector('time')).toHaveAttribute('datetime', '2026-09-30T10:00:00')
  })

  it('renders the log entry status when present', async () => {
    setup()
    fireEvent.click(screen.getByRole('button', { name: '操作ログ' }))
    const items = await screen.findAllByTestId('info-rail-log-item')
    expect(items[0]).toHaveTextContent('SUCCESS')
    expect(items[1]).not.toHaveTextContent('SUCCESS')
  })

  it('shows an empty message when there are no logs', async () => {
    mockFetch.mockResolvedValue({ entries: [], timeZone: null })
    setup()
    fireEvent.click(screen.getByRole('button', { name: '操作ログ' }))
    expect(await screen.findByText('操作ログはありません')).toBeInTheDocument()
  })

  it('shows an error message when fetching fails', async () => {
    mockFetch.mockRejectedValue(new Error('boom'))
    setup()
    fireEvent.click(screen.getByRole('button', { name: '操作ログ' }))
    expect(await screen.findByText('操作ログを取得できませんでした')).toBeInTheDocument()
  })

  it('refetches when the pathname changes and ignores a response that arrives after unmount', async () => {
    const { rerender } = setup()
    fireEvent.click(screen.getByRole('button', { name: '操作ログ' }))
    await screen.findAllByTestId('info-rail-log-item')
    mockPathname = '/sites'
    rerender(
      <I18nProvider initialLocale="ja">
        <InfoRail />
      </I18nProvider>
    )
    await waitFor(() => expect(mockFetch).toHaveBeenCalledTimes(2))
  })

  it('does not update state after unmount while a fetch is pending', async () => {
    let resolve!: (v: unknown) => void
    mockFetch.mockReturnValue(new Promise((r) => (resolve = r)))
    const spy = jest.spyOn(console, 'error').mockImplementation(() => {})
    const { unmount } = setup()
    fireEvent.click(screen.getByRole('button', { name: '操作ログ' }))
    unmount()
    resolve({ entries, timeZone: null })
    await Promise.resolve()
    expect(spy).not.toHaveBeenCalled()
    spy.mockRestore()
  })

  it('does not update state after unmount when a pending fetch rejects', async () => {
    let reject!: (e: unknown) => void
    mockFetch.mockReturnValue(new Promise((_, r) => (reject = r)))
    const { unmount } = setup()
    fireEvent.click(screen.getByRole('button', { name: '操作ログ' }))
    unmount()
    reject(new Error('late'))
    await Promise.resolve()
  })

  it('persists the selected tab and restores it on a fresh mount', async () => {
    const first = setup()
    fireEvent.click(screen.getByRole('button', { name: '操作ログ' }))
    expect(window.localStorage.getItem('infoRailTab')).toBe('logs')
    first.unmount()
    setup()
    expect(screen.getByRole('button', { name: '操作ログ' })).toHaveAttribute('aria-pressed', 'true')
    expect(await screen.findAllByTestId('info-rail-log-item')).toHaveLength(2)
  })

  it('falls back to the queue tab when the stored tab is unknown', () => {
    window.localStorage.setItem('infoRailTab', 'nonsense')
    setup()
    expect(screen.getByRole('button', { name: '処理キュー' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('collapses and expands, persisting the state and hiding the content while collapsed', () => {
    const first = setup()
    const rail = screen.getByTestId('info-rail')
    expect(rail).toHaveAttribute('data-collapsed', 'false')
    fireEvent.click(screen.getByRole('button', { name: '情報表示を折りたたむ' }))
    expect(rail).toHaveAttribute('data-collapsed', 'true')
    expect(window.localStorage.getItem('infoRailCollapsed')).toBe('true')
    expect(screen.queryByRole('button', { name: '処理キュー' })).not.toBeInTheDocument()
    first.unmount()

    setup()
    expect(screen.getByTestId('info-rail')).toHaveAttribute('data-collapsed', 'true')
    fireEvent.click(screen.getByRole('button', { name: '情報表示を展開する' }))
    expect(screen.getByTestId('info-rail')).toHaveAttribute('data-collapsed', 'false')
    expect(window.localStorage.getItem('infoRailCollapsed')).toBe('false')
    expect(screen.getByRole('button', { name: '処理キュー' })).toBeInTheDocument()
  })

  it('still switches state when localStorage is unavailable', () => {
    const getSpy = jest.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })
    const setSpy = jest.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied')
    })
    setup()
    fireEvent.click(screen.getByRole('button', { name: '操作ログ' }))
    expect(screen.getByRole('button', { name: '操作ログ' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: '情報表示を折りたたむ' }))
    expect(screen.getByTestId('info-rail')).toHaveAttribute('data-collapsed', 'true')
    getSpy.mockRestore()
    setSpy.mockRestore()
    // 後続テストへ状態を持ち越さない
    fireEvent.click(screen.getByRole('button', { name: '情報表示を展開する' }))
  })

  it('is closed as a drawer by default; opens as a dialog and closes via button, backdrop and Escape', () => {
    setup()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    const open = screen.getByRole('button', { name: '情報表示を開く' })
    fireEvent.click(open)
    const dialog = screen.getByRole('dialog', { name: '情報表示' })
    expect(within(dialog).getByRole('button', { name: '処理キュー' })).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: '情報表示を閉じる' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '情報表示を開く' }))
    fireEvent.click(screen.getByTestId('info-rail-backdrop'))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '情報表示を開く' }))
    fireEvent.keyDown(document, { key: 'Enter' })
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('shows the drawer content even when the desktop rail is collapsed', () => {
    window.localStorage.setItem('infoRailCollapsed', 'true')
    setup()
    expect(screen.queryByRole('button', { name: '処理キュー' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '情報表示を開く' }))
    expect(screen.getByRole('button', { name: '処理キュー' })).toBeInTheDocument()
  })

  it('uses English labels', () => {
    setup('en')
    expect(screen.getByRole('button', { name: 'Queue' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Operation log' })).toBeInTheDocument()
  })
})
