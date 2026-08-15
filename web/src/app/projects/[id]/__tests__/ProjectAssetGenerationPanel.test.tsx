import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { ProjectAssetGenerationPanel } from '../ProjectAssetGenerationPanel'
import * as actions from '../actions'

jest.mock('../actions', () => ({
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

describe('ProjectAssetGenerationPanel チャットでプロンプトを作成', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue(OPTIONS)
  })

  it('チャットセクションは既定で閉じており、開くボタンで開閉できる', async () => {
    await openPanel()

    expect(screen.queryByPlaceholderText('例: 夕焼けの海辺を歩く猫')).not.toBeInTheDocument()

    fireEvent.click(screen.getByText('開く'))
    expect(screen.getByPlaceholderText('例: 夕焼けの海辺を歩く猫')).toBeInTheDocument()
  })

  it('メッセージ送信でgenerateImagePromptActionをprojectIdと空履歴付きで呼び出す', async () => {
    ;(actions.generateImagePromptAction as jest.Mock).mockResolvedValue({
      prompt: 'a cute cat, high quality',
    })
    await openPanel()
    fireEvent.click(screen.getByText('開く'))

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
    fireEvent.click(screen.getByText('開く'))

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
    fireEvent.click(screen.getByText('開く'))

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
    fireEvent.click(screen.getByText('開く'))

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
