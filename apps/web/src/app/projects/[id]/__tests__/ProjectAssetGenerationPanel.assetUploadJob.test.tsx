import { render, screen, waitFor } from '@testing-library/react'
import { ProjectAssetGenerationPanel } from '../ProjectAssetGenerationPanel'
import { backend, installAssetPanelBackend } from './assetPanelRuntime'

// 自前の `../actions`・`@/lib/apiClient`・`@/lib/session` は差し替えず、Next.js の実行基盤と外部との通信(`fetch`)だけを代替する(#1837)。
// `backend.*` は gateway の向こうのバックエンドの代役(`./assetPanelRuntime`)。
jest.mock('server-only', () => ({}))
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule)
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule)
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule)
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule)
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`)
  },
  useRouter: () => ({ refresh: jest.fn(), push: jest.fn() }),
}))
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }))

const OPTIONS = {
  checkpoints: ['model.safetensors'],
  selectedCheckpoint: 'model.safetensors',
  samplers: ['euler'],
  schedulers: ['normal'],
  loras: [],
}

const done = (logs: unknown) => ({ id: 31, type: 'asset_image_upload', requestPayload: null, createdAt: '', updatedAt: '', status: 'done', resultPayload: JSON.stringify({ phase: 'done', logs }) })

/**
 * issue #1764: 処理キューの「結果を見る」(`?assetUploadJob=<ジョブID>`)から来たとき、パネルは
 * ジョブの取得(`GET /api/generation-jobs/:id`)でそのジョブを引き、従来と同じ文言でアセットとしての追加の結果を表示する
 * (前例: #1408 の `imageJob`)。
 */
let fetchSpy: jest.Mock

describe('ProjectAssetGenerationPanel アセット追加ジョブの結果(issue #1764)', () => {
  beforeEach(() => {
    fetchSpy = installAssetPanelBackend()
    ;backend.imageOptions.mockResolvedValue(OPTIONS)
    ;backend.gallery.mockResolvedValue([])
  })

  it('assetUploadJobId があればパネルを開き、そのジョブを引いて全環境の成功を示す', async () => {
    ;backend.job.mockResolvedValue(
      done([
        { environment: 'local', status: 'SUCCESS' },
        { environment: 'test', status: 'SUCCESS' },
      ])
    )
    render(<ProjectAssetGenerationPanel projectId={5} assetUploadJobId={31} />)

    await waitFor(() => expect(screen.getByText('全2環境へアップロードしました。')).toBeInTheDocument())
    expect(backend.job).toHaveBeenCalledWith(31)
    expect(screen.getByText('チャットでプロンプトを作成')).toBeInTheDocument()
  })

  it('失敗した環境があれば、環境名を含む失敗を示す', async () => {
    ;backend.job.mockResolvedValue(
      done([
        { environment: 'local', status: 'SUCCESS' },
        { environment: 'production', status: 'FAILED' },
      ])
    )
    render(<ProjectAssetGenerationPanel projectId={5} assetUploadJobId={31} />)

    await waitFor(() => expect(screen.getByText('production環境でアップロードに失敗しました。')).toBeInTheDocument())
  })

  it('ジョブ全体が失敗していれば、その理由を示す', async () => {
    ;backend.job.mockResolvedValue({
      id: 31,
      status: 'failed',
      resultPayload: '{"error":"アセット画像のアップロードの待ち行列が満杯です","errorType":"queue_full"}',
    })
    render(<ProjectAssetGenerationPanel projectId={5} assetUploadJobId={31} />)

    await waitFor(() => expect(screen.getByText('アセット画像のアップロードの待ち行列が満杯です')).toBeInTheDocument())
  })

  it('まだ実行中なら、完了後にもう一度開くよう案内する', async () => {
    ;backend.job.mockResolvedValue({ id: 31, status: 'running', resultPayload: null })
    render(<ProjectAssetGenerationPanel projectId={5} assetUploadJobId={31} />)

    await waitFor(() => expect(screen.getByText(/まだ実行中です/)).toBeInTheDocument())
  })

  it('ジョブを引けなければ、そのエラーを示す(Errorでなくても)', async () => {
    ;backend.job.mockRejectedValueOnce(new Error('404 not found'))
    const { unmount } = render(<ProjectAssetGenerationPanel projectId={5} assetUploadJobId={31} />)
    await waitFor(() => expect(screen.getByText('APIエラー (500): 404 not found')).toBeInTheDocument())
    unmount()

    ;backend.job.mockRejectedValueOnce('boom')
    render(<ProjectAssetGenerationPanel projectId={5} assetUploadJobId={31} />)
    await waitFor(() => expect(screen.getByText('boom')).toBeInTheDocument())
  })

  it('結果が返る前にアンマウントされても、あとから状態を更新しない', async () => {
    let resolveJob: (value: unknown) => void = () => {}
    ;backend.job.mockReturnValue(new Promise((resolve) => (resolveJob = resolve)))
    const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => {})
    const { unmount } = render(<ProjectAssetGenerationPanel projectId={5} assetUploadJobId={31} />)
    unmount()
    resolveJob(done([{ environment: 'local', status: 'SUCCESS' }]))
    await Promise.resolve()
    expect(errorSpy).not.toHaveBeenCalled()
    errorSpy.mockRestore()
  })

  it('assetUploadJobId が無ければジョブは引かない', async () => {
    render(<ProjectAssetGenerationPanel projectId={5} />)
    await Promise.resolve()
    expect(backend.job).not.toHaveBeenCalled()
  })
})
