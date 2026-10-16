import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { StaticContentPanel } from '../StaticContentPanel'
import type { StaticContent } from '@/lib/apiClient'

// 自前の Server Action(../../../actions)・@/lib/session・@/lib/apiClient は差し替えない。
// 差し替えるのは Next.js の実行基盤と外部との通信(fetch)だけ(#1834)。
jest.mock('server-only', () => ({}))
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule)
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule)
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule)
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule)
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }))
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`)
  },
}))
import { ADMIN_SESSION } from '@/lib/__tests__/testUtils/webAdminRuntime'
import { installFetch, jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime'

/** バックエンド(gateway の向こう)の代役。ジョブ受理と保存。 */
const mockGenerate = jest.fn()
const mockSave = jest.fn()

/** 解決値は 200 の JSON。`{ error }` を持つ値は、その理由を本文にした 404 の応答にする。 */
function respond(body: unknown, status: number) {
  const error = (body as { error?: string } | undefined)?.error
  return error ? textResponse(error.replace(/^APIエラー \(\d+\): /, ''), status) : jsonResponse(body)
}

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
  mockGenerate.mockReset()
  mockSave.mockReset()
  resetServerRuntime(ADMIN_SESSION)
  installFetch({
    'POST /api/sites/:id/static-content/generate/jobs': async (_u, init, [id]) =>
      respond(await mockGenerate(Number(id), JSON.parse(String(init.body)).contentType), 404),
    'PUT /api/sites/:id/static-content/:type': async (_u, init, [id, type]) =>
      respond(await mockSave(Number(id), type, JSON.parse(String(init.body)).body), 404),
  })
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
    mockGenerate.mockResolvedValue({ id: 22, status: 'running' })
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

  it('shows no queued notice when the accepted job carries no id', async () => {
    mockGenerate.mockResolvedValue({ status: 'running' })
    render(<StaticContentPanel siteId={3} initialContents={[]} />)

    await act(async () => {
      fireEvent.click(within(item('運営者情報')).getByRole('button', { name: '生成' }))
    })

    expect(mockGenerate).toHaveBeenCalledWith(3, 'OPERATOR_INFO')
    expect(screen.queryByTestId('static-content-queued')).not.toBeInTheDocument()
  })

  it('shows the reason when the request is refused', async () => {
    mockGenerate.mockResolvedValue({ error: 'サイトがありません' })
    render(<StaticContentPanel siteId={3} initialContents={[]} />)

    await act(async () => {
      fireEvent.click(within(item('利用規約')).getByRole('button', { name: '生成' }))
    })

    expect(screen.getByText('APIエラー (404): サイトがありません')).toBeInTheDocument()
    expect(screen.queryByTestId('static-content-queued')).not.toBeInTheDocument()
  })

  it('says the queue is full when the job was created but already failed', async () => {
    mockGenerate.mockResolvedValue({ id: 23, status: 'failed' })
    render(<StaticContentPanel siteId={3} initialContents={[]} />)

    await act(async () => {
      fireEvent.click(within(item('利用規約')).getByRole('button', { name: '生成' }))
    })

    expect(screen.getByText(/待ち行列が満杯/)).toBeInTheDocument()
    expect(screen.queryByTestId('static-content-queued')).not.toBeInTheDocument()
  })

  it('clears an earlier error or notice when the request is made again', async () => {
    mockGenerate.mockResolvedValueOnce({ error: 'x' }).mockResolvedValueOnce({ id: 24, status: 'running' })
    render(<StaticContentPanel siteId={3} initialContents={[]} />)
    const button = within(item('利用規約')).getByRole('button', { name: '生成' })

    await act(async () => {
      fireEvent.click(button)
    })
    expect(screen.getByText('APIエラー (404): x')).toBeInTheDocument()
    await act(async () => {
      fireEvent.click(button)
    })

    expect(screen.queryByText('APIエラー (404): x')).not.toBeInTheDocument()
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
    mockSave.mockResolvedValue(content({ id: 5, contentType: 'OPERATOR_INFO', body: '生成された運営者情報' }))
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
    mockSave.mockResolvedValue({ error: 'サイトがありません' })
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
