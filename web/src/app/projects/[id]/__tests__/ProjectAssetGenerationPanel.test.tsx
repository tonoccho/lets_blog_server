import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { ProjectAssetGenerationPanel } from '../ProjectAssetGenerationPanel'
import * as actions from '../actions'

jest.mock('../actions', () => ({
  fetchGeneratedImagesAction: jest.fn(),
  fetchImageGenerationOptionsAction: jest.fn(),
  generateImagePromptAction: jest.fn(),
  generateProjectImagesAction: jest.fn(),
  uploadProjectAssetImageAction: jest.fn(),
}))

const OPTIONS = {
  checkpoints: ['model.safetensors'],
  selectedCheckpoint: 'model.safetensors',
  samplers: ['euler'],
  schedulers: ['normal'],
  loras: [],
}

async function openPanel() {
  render(<ProjectAssetGenerationPanel projectId={1} />)
  fireEvent.click(screen.getByText('アセット画像生成'))
  await waitFor(() => {
    expect(screen.getByText('チャットでプロンプトを作成')).toBeInTheDocument()
  })
}

/** チャットセクションとギャラリーセクションが同じ「開く」ラベルを使うため、見出しでスコープして開閉する。 */
function openChatSection() {
  const header = screen.getByText('チャットでプロンプトを作成').closest('div') as HTMLElement
  fireEvent.click(within(header).getByText('開く'))
}

describe('ProjectAssetGenerationPanel チャットでプロンプトを作成', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue(OPTIONS)
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue([])
  })

  it('チャットセクションは既定で閉じており、開くボタンで開閉できる', async () => {
    await openPanel()

    expect(screen.queryByPlaceholderText('例: 夕焼けの海辺を歩く猫')).not.toBeInTheDocument()

    openChatSection()
    expect(screen.getByPlaceholderText('例: 夕焼けの海辺を歩く猫')).toBeInTheDocument()
  })

  it('メッセージ送信でgenerateImagePromptActionをprojectIdと空履歴付きで呼び出す', async () => {
    ;(actions.generateImagePromptAction as jest.Mock).mockResolvedValue({
      prompt: 'a cute cat, high quality',
    })
    await openPanel()
    openChatSection()

    const input = screen.getByPlaceholderText('例: 夕焼けの海辺を歩く猫')
    fireEvent.change(input, { target: { value: '夕焼けの海辺を歩く猫' } })
    fireEvent.click(screen.getByText('プロンプト生成'))

    await waitFor(() => {
      expect(actions.generateImagePromptAction).toHaveBeenCalledWith(1, {
        history: [],
        message: '夕焼けの海辺を歩く猫',
      })
    })
  })

  it('生成成功時にprompt欄へ反映しチャット履歴に表示する', async () => {
    ;(actions.generateImagePromptAction as jest.Mock).mockResolvedValue({
      prompt: 'a cute cat, high quality',
    })
    await openPanel()
    openChatSection()

    const input = screen.getByPlaceholderText('例: 夕焼けの海辺を歩く猫')
    fireEvent.change(input, { target: { value: '夕焼けの海辺を歩く猫' } })
    fireEvent.click(screen.getByText('プロンプト生成'))

    await waitFor(() => {
      expect(screen.getAllByText('a cute cat, high quality')).toHaveLength(2)
    })
    const promptTextarea = screen.getByPlaceholderText('生成したい画像の説明') as HTMLTextAreaElement
    expect(promptTextarea.value).toBe('a cute cat, high quality')
  })

  it('生成失敗時にエラーメッセージを表示する', async () => {
    ;(actions.generateImagePromptAction as jest.Mock).mockResolvedValue({
      error: 'AIサービスへの接続に失敗しました',
    })
    await openPanel()
    openChatSection()

    const input = screen.getByPlaceholderText('例: 夕焼けの海辺を歩く猫')
    fireEvent.change(input, { target: { value: '夕焼けの海辺を歩く猫' } })
    fireEvent.click(screen.getByText('プロンプト生成'))

    await waitFor(() => {
      expect(screen.getByText('AIサービスへの接続に失敗しました')).toBeInTheDocument()
    })
  })

  it('2回目のメッセージ送信では直前の往復を履歴として送る', async () => {
    ;(actions.generateImagePromptAction as jest.Mock)
      .mockResolvedValueOnce({ prompt: 'a cute cat' })
      .mockResolvedValueOnce({ prompt: 'a cuter cat, pastel colors' })
    await openPanel()
    openChatSection()

    const input = screen.getByPlaceholderText('例: 夕焼けの海辺を歩く猫')
    fireEvent.change(input, { target: { value: '猫の画像がほしい' } })
    fireEvent.click(screen.getByText('プロンプト生成'))
    await waitFor(() => {
      expect(screen.getAllByText('a cute cat')).toHaveLength(2)
    })

    fireEvent.change(input, { target: { value: 'もっと可愛くして' } })
    fireEvent.click(screen.getByText('プロンプト生成'))

    await waitFor(() => {
      expect(actions.generateImagePromptAction).toHaveBeenLastCalledWith(1, {
        history: [
          { role: 'user', content: '猫の画像がほしい' },
          { role: 'assistant', content: 'a cute cat' },
        ],
        message: 'もっと可愛くして',
      })
    })
  })
})

describe('ProjectAssetGenerationPanel 生成画像ギャラリーから選択してアップロード (issue #436)', () => {
  const GALLERY_IMAGES = [
    { id: 10, projectId: null, prompt: 'a cute cat', checkpoint: 'model.safetensors', createdAt: '2026-08-01T00:00:00Z', tags: [] },
    { id: 11, projectId: 2, prompt: 'a mountain landscape', checkpoint: 'model.safetensors', createdAt: '2026-08-02T00:00:00Z', tags: [] },
  ]

  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue(OPTIONS)
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue(GALLERY_IMAGES)
  })

  function openGallerySection() {
    const header = screen.getByText('生成画像ギャラリーから選択してアップロード').closest('div') as HTMLElement
    fireEvent.click(within(header).getByText('開く'))
  }

  it('ギャラリーセクションは既定で閉じており、開くとギャラリー画像を取得して表示する', async () => {
    await openPanel()

    expect(actions.fetchGeneratedImagesAction).not.toHaveBeenCalled()

    openGallerySection()

    await waitFor(() => {
      expect(actions.fetchGeneratedImagesAction).toHaveBeenCalledTimes(1)
      expect(screen.getByAltText('a cute cat')).toBeInTheDocument()
      expect(screen.getByAltText('a mountain landscape')).toBeInTheDocument()
    })
  })

  it('画像を選択してアップロードするとuploadProjectAssetImageActionをprojectIdと選択IDで呼び出す', async () => {
    ;(actions.uploadProjectAssetImageAction as jest.Mock).mockResolvedValue({
      logs: [{ environment: 'local', status: 'SUCCESS' }, { environment: 'test', status: 'SUCCESS' }],
    })
    await openPanel()
    openGallerySection()

    await waitFor(() => expect(screen.getByAltText('a mountain landscape')).toBeInTheDocument())
    fireEvent.click(screen.getByAltText('a mountain landscape'))
    fireEvent.click(screen.getByText('選択した画像をアセットとして追加(全環境へアップロード)'))

    await waitFor(() => {
      expect(actions.uploadProjectAssetImageAction).toHaveBeenCalledWith(1, 11)
      expect(screen.getByText('全2環境へアップロードしました。')).toBeInTheDocument()
    })
  })

  it('アップロード失敗時に失敗した環境名を含むエラーメッセージを表示する', async () => {
    ;(actions.uploadProjectAssetImageAction as jest.Mock).mockResolvedValue({
      logs: [{ environment: 'production', status: 'FAILED' }],
    })
    await openPanel()
    openGallerySection()

    await waitFor(() => expect(screen.getByAltText('a cute cat')).toBeInTheDocument())
    fireEvent.click(screen.getByAltText('a cute cat'))
    fireEvent.click(screen.getByText('選択した画像をアセットとして追加(全環境へアップロード)'))

    await waitFor(() => {
      expect(screen.getByText('production環境でアップロードに失敗しました。')).toBeInTheDocument()
    })
  })
})
