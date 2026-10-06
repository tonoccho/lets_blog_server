import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { StaticContentPanel } from '../StaticContentPanel'
import type { StaticContent } from '@/lib/apiClient'

const mockGenerate = jest.fn()
const mockSave = jest.fn()
jest.mock('../../../actions', () => ({
  generateStaticContentAction: (...args: unknown[]) => mockGenerate(...args),
  saveStaticContentAction: (...args: unknown[]) => mockSave(...args),
}))

function content(overrides: Partial<StaticContent> = {}): StaticContent {
  return {
    id: 1,
    siteId: 3,
    contentType: 'PRIVACY_POLICY',
    body: '保存済みの本文',
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    ...overrides,
  }
}

function item(label: string) {
  return screen.getByText(label).closest('div.space-y-2') as HTMLElement
}

beforeEach(() => {
  jest.clearAllMocks()
})

/**
 * issue #1409: 静的コンテンツの生成は非同期ジョブとして要求し、生成と同時には保存しない。
 * 処理キューの「結果を見る」でこの画面へ戻り、生成結果を確認して「保存」を押したときに初めて
 * 既存の保存先(static_content)へ書く。
 */
describe('StaticContentPanel: 生成の要求(#1409)', () => {
  it('shows the saved contents with a copy button and a generate / regenerate button per type', () => {
    render(<StaticContentPanel siteId={3} initialContents={[content()]} />)

    expect(within(item('プライバシーポリシー')).getByText('保存済みの本文')).toBeInTheDocument()
    expect(within(item('プライバシーポリシー')).getByRole('button', { name: '再生成' })).toBeInTheDocument()
    expect(within(item('運営者情報')).getByRole('button', { name: '生成' })).toBeInTheDocument()
    expect(within(item('利用規約')).getByRole('button', { name: '生成' })).toBeInTheDocument()
  })

  it('requests a generation and says it was added to the queue, not that it was saved', async () => {
    mockGenerate.mockResolvedValue({ jobId: 22, status: 'running' })
    render(<StaticContentPanel siteId={3} initialContents={[]} />)

    await act(async () => {
      fireEvent.click(within(item('運営者情報')).getByRole('button', { name: '生成' }))
    })

    expect(mockGenerate).toHaveBeenCalledWith(3, 'OPERATOR_INFO')
    const notice = within(item('運営者情報')).getByTestId('static-content-queued')
    expect(notice).toHaveAttribute('data-job-id', '22')
    expect(notice).toHaveTextContent('処理キューに追加されました')
    expect(notice).toHaveTextContent('結果を見る')
    expect(notice).toHaveTextContent('保存')
    // まだ何も保存されていないので、コピーする本文もない。
    expect(within(item('運営者情報')).queryByRole('button', { name: 'コピー' })).not.toBeInTheDocument()
    expect(within(item('プライバシーポリシー')).queryByTestId('static-content-queued')).not.toBeInTheDocument()
  })

  it('shows the reason when the request is refused', async () => {
    mockGenerate.mockResolvedValue({ error: 'APIエラー (404): サイトがありません' })
    render(<StaticContentPanel siteId={3} initialContents={[]} />)

    await act(async () => {
      fireEvent.click(within(item('利用規約')).getByRole('button', { name: '生成' }))
    })

    expect(screen.getByText('APIエラー (404): サイトがありません')).toBeInTheDocument()
    expect(screen.queryByTestId('static-content-queued')).not.toBeInTheDocument()
  })

  it('says the queue is full when the job was created but already failed', async () => {
    mockGenerate.mockResolvedValue({ jobId: 23, status: 'failed' })
    render(<StaticContentPanel siteId={3} initialContents={[]} />)

    await act(async () => {
      fireEvent.click(within(item('利用規約')).getByRole('button', { name: '生成' }))
    })

    expect(screen.getByText(/待ち行列が満杯/)).toBeInTheDocument()
    expect(screen.queryByTestId('static-content-queued')).not.toBeInTheDocument()
  })

  it('clears an earlier error or notice when the request is made again', async () => {
    mockGenerate.mockResolvedValueOnce({ error: 'x' }).mockResolvedValueOnce({ jobId: 24, status: 'running' })
    render(<StaticContentPanel siteId={3} initialContents={[]} />)
    const button = within(item('利用規約')).getByRole('button', { name: '生成' })

    await act(async () => {
      fireEvent.click(button)
    })
    expect(screen.getByText('x')).toBeInTheDocument()
    await act(async () => {
      fireEvent.click(button)
    })

    expect(screen.queryByText('x')).not.toBeInTheDocument()
    expect(screen.getByTestId('static-content-queued')).toHaveAttribute('data-job-id', '24')
  })
})

describe('StaticContentPanel: 結果の確認と「保存」(#1409)', () => {
  const generated = { jobId: 22, siteId: 3, contentType: 'OPERATOR_INFO' as const, body: '生成された運営者情報' }

  it('shows the generated body as not yet saved only under its own content type', () => {
    render(<StaticContentPanel siteId={3} initialContents={[content()]} generatedResult={generated} />)

    const block = within(item('運営者情報')).getByTestId('static-content-generated')
    expect(block).toHaveAttribute('data-job-id', '22')
    expect(block).toHaveTextContent('保存されていません')
    expect(block).toHaveTextContent('生成された運営者情報')
    expect(screen.getAllByTestId('static-content-generated')).toHaveLength(1)
    // 保存先にはまだ現れない
    expect(mockSave).not.toHaveBeenCalled()
  })

  it('"保存" writes the generated body through the save action, then shows it as the saved content', async () => {
    mockSave.mockResolvedValue({ content: content({ id: 5, contentType: 'OPERATOR_INFO', body: '生成された運営者情報' }) })
    render(<StaticContentPanel siteId={3} initialContents={[]} generatedResult={generated} />)

    await act(async () => {
      fireEvent.click(within(item('運営者情報')).getByRole('button', { name: '保存' }))
    })

    expect(mockSave).toHaveBeenCalledWith(3, 'OPERATOR_INFO', '生成された運営者情報')
    expect(screen.queryByTestId('static-content-generated')).not.toBeInTheDocument()
    expect(within(item('運営者情報')).getByText('保存しました。')).toBeInTheDocument()
    expect(within(item('運営者情報')).getByRole('button', { name: 'コピー' })).toBeInTheDocument()
    expect(within(item('運営者情報')).getAllByText('生成された運営者情報').length).toBeGreaterThan(0)
  })

  it('shows the reason when the save is refused and keeps the generated body to retry', async () => {
    mockSave.mockResolvedValue({ error: 'APIエラー (404): サイトがありません' })
    render(<StaticContentPanel siteId={3} initialContents={[]} generatedResult={generated} />)

    await act(async () => {
      fireEvent.click(within(item('運営者情報')).getByRole('button', { name: '保存' }))
    })

    expect(screen.getByText('APIエラー (404): サイトがありません')).toBeInTheDocument()
    expect(screen.getByTestId('static-content-generated')).toBeInTheDocument()
    expect(within(item('運営者情報')).getByRole('button', { name: '保存' })).toBeEnabled()
  })

  it('shows no generated block when there is no result', () => {
    render(<StaticContentPanel siteId={3} initialContents={[]} />)

    expect(screen.queryByTestId('static-content-generated')).not.toBeInTheDocument()
  })
})

describe('StaticContentPanel: コピー', () => {
  it('copies the saved body and says so for a moment', async () => {
    jest.useFakeTimers()
    const writeText = jest.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    render(<StaticContentPanel siteId={3} initialContents={[content()]} />)

    await act(async () => {
      fireEvent.click(within(item('プライバシーポリシー')).getByRole('button', { name: 'コピー' }))
    })

    expect(writeText).toHaveBeenCalledWith('保存済みの本文')
    expect(screen.getByRole('button', { name: 'コピーしました' })).toBeInTheDocument()
    act(() => {
      jest.advanceTimersByTime(2000)
    })
    expect(screen.getByRole('button', { name: 'コピー' })).toBeInTheDocument()
    jest.useRealTimers()
  })
})
