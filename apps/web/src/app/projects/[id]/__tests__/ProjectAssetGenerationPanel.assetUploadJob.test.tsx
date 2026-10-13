import { render, screen, waitFor } from '@testing-library/react'
import { ProjectAssetGenerationPanel } from '../ProjectAssetGenerationPanel'
import * as actions from '../actions'

jest.mock('../actions', () => ({
  fetchGeneratedImagesAction: jest.fn(),
  fetchImageGenerationOptionsAction: jest.fn(),
  generateImagePromptAction: jest.fn(),
  requestProjectImageJobAction: jest.fn(),
  fetchImageJobResultAction: jest.fn(),
  fetchGenerationJobAction: jest.fn(),
  uploadProjectAssetImageAction: jest.fn(),
  uploadGeneratedImageAction: jest.fn(),
}))

const OPTIONS = {
  checkpoints: ['model.safetensors'],
  selectedCheckpoint: 'model.safetensors',
  samplers: ['euler'],
  schedulers: ['normal'],
  loras: [],
}

const done = (logs: unknown) => ({ id: 31, status: 'done', resultPayload: JSON.stringify({ phase: 'done', logs }) })

/**
 * issue #1764: 処理キューの「結果を見る」(`?assetUploadJob=<ジョブID>`)から来たとき、パネルは
 * `fetchGenerationJobAction` でそのジョブを引き、従来と同じ文言でアセットとしての追加の結果を表示する
 * (前例: #1408 の `imageJob`)。
 */
describe('ProjectAssetGenerationPanel アセット追加ジョブの結果(issue #1764)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue(OPTIONS)
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue([])
  })

  it('assetUploadJobId があればパネルを開き、そのジョブを引いて全環境の成功を示す', async () => {
    ;(actions.fetchGenerationJobAction as jest.Mock).mockResolvedValue(
      done([
        { environment: 'local', status: 'SUCCESS' },
        { environment: 'test', status: 'SUCCESS' },
      ])
    )
    render(<ProjectAssetGenerationPanel projectId={5} assetUploadJobId={31} />)

    await waitFor(() => expect(screen.getByText('全2環境へアップロードしました。')).toBeInTheDocument())
    expect(actions.fetchGenerationJobAction).toHaveBeenCalledWith(31)
    expect(screen.getByText('チャットでプロンプトを作成')).toBeInTheDocument()
  })

  it('失敗した環境があれば、環境名を含む失敗を示す', async () => {
    ;(actions.fetchGenerationJobAction as jest.Mock).mockResolvedValue(
      done([
        { environment: 'local', status: 'SUCCESS' },
        { environment: 'production', status: 'FAILED' },
      ])
    )
    render(<ProjectAssetGenerationPanel projectId={5} assetUploadJobId={31} />)

    await waitFor(() => expect(screen.getByText('production環境でアップロードに失敗しました。')).toBeInTheDocument())
  })

  it('ジョブ全体が失敗していれば、その理由を示す', async () => {
    ;(actions.fetchGenerationJobAction as jest.Mock).mockResolvedValue({
      id: 31,
      status: 'failed',
      resultPayload: '{"error":"アセット画像のアップロードの待ち行列が満杯です","errorType":"queue_full"}',
    })
    render(<ProjectAssetGenerationPanel projectId={5} assetUploadJobId={31} />)

    await waitFor(() => expect(screen.getByText('アセット画像のアップロードの待ち行列が満杯です')).toBeInTheDocument())
  })

  it('まだ実行中なら、完了後にもう一度開くよう案内する', async () => {
    ;(actions.fetchGenerationJobAction as jest.Mock).mockResolvedValue({ id: 31, status: 'running', resultPayload: null })
    render(<ProjectAssetGenerationPanel projectId={5} assetUploadJobId={31} />)

    await waitFor(() => expect(screen.getByText(/まだ実行中です/)).toBeInTheDocument())
  })

  it('ジョブを引けなければ、そのエラーを示す(Errorでなくても)', async () => {
    ;(actions.fetchGenerationJobAction as jest.Mock).mockRejectedValueOnce(new Error('404 not found'))
    const { unmount } = render(<ProjectAssetGenerationPanel projectId={5} assetUploadJobId={31} />)
    await waitFor(() => expect(screen.getByText('404 not found')).toBeInTheDocument())
    unmount()

    ;(actions.fetchGenerationJobAction as jest.Mock).mockRejectedValueOnce('boom')
    render(<ProjectAssetGenerationPanel projectId={5} assetUploadJobId={31} />)
    await waitFor(() => expect(screen.getByText('boom')).toBeInTheDocument())
  })

  it('結果が返る前にアンマウントされても、あとから状態を更新しない', async () => {
    let resolveJob: (value: unknown) => void = () => {}
    ;(actions.fetchGenerationJobAction as jest.Mock).mockReturnValue(new Promise((resolve) => (resolveJob = resolve)))
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
    expect(actions.fetchGenerationJobAction).not.toHaveBeenCalled()
  })
})
