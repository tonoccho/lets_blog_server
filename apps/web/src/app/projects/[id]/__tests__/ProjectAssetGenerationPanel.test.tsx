import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react'
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

describe('ProjectAssetGenerationPanel クリップボードから作成 (issue #437)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue(OPTIONS)
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue([])
  })

  function mockClipboardReadText(text: string | (() => Promise<string>)) {
    Object.assign(navigator, {
      clipboard: {
        readText:
          typeof text === 'function' ? jest.fn(text) : jest.fn().mockResolvedValue(text),
      },
    })
  }

  it('クリップボードのJSONをフォーム項目に反映する', async () => {
    mockClipboardReadText(
      JSON.stringify({
        prompt: 'a mountain landscape',
        negativePrompt: 'blurry',
        steps: 30,
        cfgScale: 8.5,
        samplerName: 'dpmpp_2m',
        scheduler: 'karras',
        seed: 999,
        width: 1024,
        height: 768,
        batchSize: 2,
        checkpoint: 'other-model.safetensors',
        loraName: 'watercolor',
        loraWeight: 0.6,
      })
    )
    await openPanel()

    fireEvent.click(screen.getByText('クリップボードから作成'))

    await waitFor(() => {
      expect((screen.getByPlaceholderText('生成したい画像の説明') as HTMLTextAreaElement).value).toBe(
        'a mountain landscape'
      )
    })
    expect(
      (screen.getByPlaceholderText('low quality, blurry, watermark, text') as HTMLTextAreaElement).value
    ).toBe('blurry')
    expect(screen.getByText('クリップボードの設定をフォームに反映しました。')).toBeInTheDocument()
  })

  it('JSONとして解析できない内容の場合はエラーを表示しフォームを変更しない', async () => {
    mockClipboardReadText('not json')
    await openPanel()

    fireEvent.click(screen.getByText('クリップボードから作成'))

    await waitFor(() => {
      expect(screen.getByText('クリップボードの内容が正しいJSON形式ではありません。')).toBeInTheDocument()
    })
    expect((screen.getByPlaceholderText('生成したい画像の説明') as HTMLTextAreaElement).value).toBe('')
  })

  it('promptを含まないJSONの場合はエラーを表示する', async () => {
    mockClipboardReadText(JSON.stringify({ steps: 10 }))
    await openPanel()

    fireEvent.click(screen.getByText('クリップボードから作成'))

    await waitFor(() => {
      expect(screen.getByText('クリップボードの内容から生成設定を読み取れませんでした。')).toBeInTheDocument()
    })
  })

  it('クリップボードの読み取りに失敗した場合はエラーメッセージを表示する', async () => {
    mockClipboardReadText(() => Promise.reject(new Error('クリップボードへのアクセスが拒否されました')))
    await openPanel()

    fireEvent.click(screen.getByText('クリップボードから作成'))

    await waitFor(() => {
      expect(screen.getByText('クリップボードへのアクセスが拒否されました')).toBeInTheDocument()
    })
  })
})

describe('ProjectAssetGenerationPanel batch size / batch count (issue #1103)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue(OPTIONS)
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue([])
  })

  // 1920×1080のPNGはbase64で1MBを超えることも珍しくない。4バイトのダミーではReactも
  // DOMも文字列長の影響を一切受けず、「256枚でも破綻しない」という受入基準を何も検証しない
  // ことになる(issue #1103 のレビュー指摘)。jsdomで 256×1MB は現実的でないため、1枚あたり
  // base64 40,960文字(≒40KB、元データ約30KB)に抑えた上で、256枚ぶんのdata URIが同時に
  // React stateとDOMへ載る状態を作る。
  const DUMMY_BASE64_LENGTH = 40 * 1024
  const DUMMY_BASE64_BODY = 'A'.repeat(DUMMY_BASE64_LENGTH - 4)

  function generatedImage(id: number) {
    // 末尾4文字を画像ごとに変え、同一文字列の共有で長さの影響が消えないようにする。
    return {
      id,
      fileName: `image-${id}.png`,
      dataBase64: `${DUMMY_BASE64_BODY}${id.toString(36).padStart(4, 'A')}`,
      mimeType: 'image/png',
    }
  }

  function fillPrompt(value = 'a cute cat') {
    fireEvent.change(screen.getByPlaceholderText('生成したい画像の説明'), { target: { value } })
  }

  function batchSizeInput() {
    return screen.getByLabelText('batch size(最大16)') as HTMLInputElement
  }

  function batchCountInput() {
    return screen.getByLabelText('batch count(最大16)') as HTMLInputElement
  }

  it('batch sizeとbatch countの入力が両方表示され、上限がどちらも16である', async () => {
    await openPanel()

    expect(batchSizeInput()).toHaveAttribute('min', '1')
    expect(batchSizeInput()).toHaveAttribute('max', '16')
    expect(batchCountInput()).toHaveAttribute('min', '1')
    expect(batchCountInput()).toHaveAttribute('max', '16')
    expect(batchCountInput().value).toBe('1')
  })

  it('batch countの説明にリピートごとにseedが変わることが書かれている', async () => {
    await openPanel()

    const help = screen.getByText(/リピートのたびにseedが変わ/)
    expect(help).toBeInTheDocument()
    expect(batchCountInput()).toHaveAttribute('aria-describedby', help.id)
  })

  it('生成前から合計枚数の目安と、枚数によっては長時間かかる旨を表示する', async () => {
    await openPanel()

    expect(
      screen.getByText(/この設定で合計4枚\(batch size 4 × batch count 1\)を生成します/)
    ).toBeInTheDocument()

    fireEvent.change(batchSizeInput(), { target: { value: '2' } })
    fireEvent.change(batchCountInput(), { target: { value: '3' } })

    expect(
      screen.getByText(/この設定で合計6枚\(batch size 2 × batch count 3\)を生成します/)
    ).toBeInTheDocument()
  })

  it('batch size 2・batch count 3で生成するとリクエストにbatchSize 2とbatchCount 3が含まれる', async () => {
    ;(actions.generateProjectImagesAction as jest.Mock).mockResolvedValue({
      images: [1, 2, 3, 4, 5, 6].map(generatedImage),
    })
    await openPanel()
    fillPrompt()
    fireEvent.change(batchSizeInput(), { target: { value: '2' } })
    fireEvent.change(batchCountInput(), { target: { value: '3' } })

    fireEvent.click(screen.getByText('生成'))

    await waitFor(() => {
      expect(actions.generateProjectImagesAction).toHaveBeenCalledWith(
        1,
        expect.objectContaining({ batchSize: 2, batchCount: 3 })
      )
    })
    await waitFor(() => {
      expect(screen.getAllByRole('img')).toHaveLength(6)
    })
  })

  it('生成した6枚から1枚を選んでアセットとして追加できる', async () => {
    ;(actions.generateProjectImagesAction as jest.Mock).mockResolvedValue({
      images: [1, 2, 3, 4, 5, 6].map(generatedImage),
    })
    ;(actions.uploadProjectAssetImageAction as jest.Mock).mockResolvedValue({
      logs: [{ environment: 'local', status: 'SUCCESS' }],
    })
    await openPanel()
    fillPrompt()
    fireEvent.change(batchSizeInput(), { target: { value: '2' } })
    fireEvent.change(batchCountInput(), { target: { value: '3' } })
    fireEvent.click(screen.getByText('生成'))

    await waitFor(() => expect(screen.getByAltText('image-4.png')).toBeInTheDocument())
    fireEvent.click(screen.getByAltText('image-4.png'))
    fireEvent.click(screen.getByText('アセットとして追加(全環境へアップロード)'))

    await waitFor(() => {
      expect(actions.uploadProjectAssetImageAction).toHaveBeenCalledWith(1, 4)
      expect(screen.getByText('全1環境へアップロードしました。')).toBeInTheDocument()
    })
  })

  it('batch countを1のままにすると従来どおりbatch size枚だけが表示される', async () => {
    ;(actions.generateProjectImagesAction as jest.Mock).mockResolvedValue({
      images: [1, 2].map(generatedImage),
    })
    await openPanel()
    fillPrompt()
    fireEvent.change(batchSizeInput(), { target: { value: '2' } })

    fireEvent.click(screen.getByText('生成'))

    await waitFor(() => {
      expect(actions.generateProjectImagesAction).toHaveBeenCalledWith(
        1,
        expect.objectContaining({ batchSize: 2, batchCount: 1 })
      )
    })
    await waitFor(() => expect(screen.getAllByRole('img')).toHaveLength(2))
  })

  it('生成中は要求した総枚数と長時間になりうる旨を表示する', async () => {
    let resolveGeneration!: (value: { images: ReturnType<typeof generatedImage>[] }) => void
    ;(actions.generateProjectImagesAction as jest.Mock).mockReturnValue(
      new Promise((resolve) => {
        resolveGeneration = resolve
      })
    )
    await openPanel()
    fillPrompt()
    fireEvent.change(batchSizeInput(), { target: { value: '2' } })
    fireEvent.change(batchCountInput(), { target: { value: '3' } })

    fireEvent.click(screen.getByText('生成'))

    await waitFor(() => {
      expect(
        screen.getByText(/合計6枚を生成しています。枚数によっては非常に長い時間がかかります/)
      ).toBeInTheDocument()
    })

    // docs/ACCESSIBILITY.md のライブリージョンの例にそろえ、差分ではなく文面全体を読ませる。
    const notice = screen.getByText(/合計6枚を生成しています/)
    expect(notice).toHaveAttribute('aria-live', 'polite')
    expect(notice).toHaveAttribute('aria-atomic', 'true')

    await act(async () => {
      resolveGeneration({ images: [] })
    })
  })

  it('batch size 16 × batch count 16でもフォームにブロックされず生成要求が送られる', async () => {
    ;(actions.generateProjectImagesAction as jest.Mock).mockResolvedValue({ images: [] })
    await openPanel()
    fillPrompt()
    fireEvent.change(batchSizeInput(), { target: { value: '16' } })
    fireEvent.change(batchCountInput(), { target: { value: '16' } })

    const generateButton = screen.getByText('生成') as HTMLButtonElement
    expect(generateButton).not.toBeDisabled()
    fireEvent.click(generateButton)

    await waitFor(() => {
      expect(actions.generateProjectImagesAction).toHaveBeenCalledWith(
        1,
        expect.objectContaining({ batchSize: 16, batchCount: 16 })
      )
    })
  })

  it('256枚が返ってもすべて表示され、スクロールできるグリッドで任意の1枚を選択できる', async () => {
    const images = Array.from({ length: 256 }, (_, index) => generatedImage(index + 1))
    ;(actions.generateProjectImagesAction as jest.Mock).mockResolvedValue({ images })
    await openPanel()
    fillPrompt()
    fireEvent.change(batchSizeInput(), { target: { value: '16' } })
    fireEvent.change(batchCountInput(), { target: { value: '16' } })

    fireEvent.click(screen.getByText('生成'))

    await waitFor(() => expect(screen.getAllByRole('img')).toHaveLength(256))
    const grid = screen.getByTestId('generated-image-grid')
    expect(grid.className).toMatch(/overflow-y-auto/)
    expect(grid.className).toMatch(/max-h-/)

    const rendered = screen.getAllByRole('img')
    // 画面外のサムネイルはブラウザ側でデコードを遅らせる。実ブラウザでの描画コスト緩和。
    expect(rendered.filter((img) => img.getAttribute('loading') === 'lazy')).toHaveLength(256)
    // ダミーが実サイズ相当の長さのままDOMへ載っていることを確かめる(4バイトでは何も測れない)。
    expect(rendered[0].getAttribute('src')?.length ?? 0).toBeGreaterThan(DUMMY_BASE64_LENGTH)

    const target = screen.getByAltText('image-200.png').closest('button') as HTMLElement
    expect(target).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(target)
    expect(target).toHaveAttribute('aria-pressed', 'true')
  })
})

describe('ProjectAssetGenerationPanel 数値入力を空にしたとき (issue #1115)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue({ ...OPTIONS, loras: ['my-lora'] })
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue([])
    ;(actions.generateProjectImagesAction as jest.Mock).mockResolvedValue({ images: [] })
  })

  const FIELDS: Array<[string, string]> = [
    ['steps', 'steps'],
    ['cfg scale', 'cfg scale'],
    ['width', 'width'],
    ['height', 'height'],
    ['batch size(最大16)', 'batch size(最大16)'],
    ['batch count(最大16)', 'batch count(最大16)'],
  ]

  it.each(FIELDS)('%s を全消去しても0に書き換わらず空欄のままである', async (label) => {
    await openPanel()
    const input = screen.getByLabelText(label) as HTMLInputElement
    fireEvent.change(input, { target: { value: '' } })
    expect(input.value).toBe('')
  })

  it('LoRA weight を全消去しても0に書き換わらず空欄のままである', async () => {
    await openPanel()
    fireEvent.change(screen.getByLabelText('LoRA'), { target: { value: 'my-lora' } })
    const input = screen.getByLabelText('LoRA weight') as HTMLInputElement
    fireEvent.change(input, { target: { value: '' } })
    expect(input.value).toBe('')
  })

  it('空欄のまま生成すると各項目の既定値で要求を送る', async () => {
    await openPanel()
    fireEvent.change(screen.getByPlaceholderText('生成したい画像の説明'), { target: { value: 'cat' } })
    for (const [label] of FIELDS) {
      fireEvent.change(screen.getByLabelText(label), { target: { value: '' } })
    }
    fireEvent.click(screen.getByText('生成'))
    await waitFor(() => {
      expect(actions.generateProjectImagesAction).toHaveBeenCalledWith(
        1,
        expect.objectContaining({
          steps: 20,
          cfgScale: 7,
          width: 1920,
          height: 1080,
          batchSize: 4,
          batchCount: 1,
        })
      )
    })
  })

  it('width/heightが空欄のとき、プロジェクトの既定サイズがあればそれで送る', async () => {
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue({
      ...OPTIONS,
      defaultWidth: 640,
      defaultHeight: 480,
    })
    await openPanel()
    fireEvent.change(screen.getByPlaceholderText('生成したい画像の説明'), { target: { value: 'cat' } })
    fireEvent.change(screen.getByLabelText('width'), { target: { value: '' } })
    fireEvent.change(screen.getByLabelText('height'), { target: { value: '' } })
    fireEvent.click(screen.getByText('生成'))
    await waitFor(() => {
      expect(actions.generateProjectImagesAction).toHaveBeenCalledWith(
        1,
        expect.objectContaining({ width: 640, height: 480 })
      )
    })
  })

  it('LoRA weight が空欄のまま生成すると既定値1で送る', async () => {
    await openPanel()
    fireEvent.change(screen.getByPlaceholderText('生成したい画像の説明'), { target: { value: 'cat' } })
    fireEvent.change(screen.getByLabelText('LoRA'), { target: { value: 'my-lora' } })
    fireEvent.change(screen.getByLabelText('LoRA weight'), { target: { value: '' } })
    fireEvent.click(screen.getByText('生成'))
    await waitFor(() => {
      expect(actions.generateProjectImagesAction).toHaveBeenCalledWith(
        1,
        expect.objectContaining({ loraName: 'my-lora', loraWeight: 1 })
      )
    })
  })

  it('空欄のbatch sizeでも合計枚数の目安は既定値で計算する', async () => {
    await openPanel()
    fireEvent.change(screen.getByLabelText('batch size(最大16)'), { target: { value: '' } })
    expect(screen.getByText(/この設定で合計4枚/)).toBeInTheDocument()
  })
})

describe('ProjectAssetGenerationPanel クリップボードのbatchCount (issue #1103)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue(OPTIONS)
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue([])
  })

  function mockClipboardReadText(text: string) {
    Object.assign(navigator, {
      clipboard: { readText: jest.fn().mockResolvedValue(text) },
    })
  }

  it('batchCountを含むJSONを読み込むとbatch count欄へ反映する', async () => {
    mockClipboardReadText(JSON.stringify({ prompt: 'a cat', batchSize: 3, batchCount: 5 }))
    await openPanel()

    fireEvent.click(screen.getByText('クリップボードから作成'))

    await waitFor(() => {
      expect((screen.getByLabelText('batch count(最大16)') as HTMLInputElement).value).toBe('5')
    })
    expect((screen.getByLabelText('batch size(最大16)') as HTMLInputElement).value).toBe('3')
  })

  it('batchCountを含まないJSON(生成画像ギャラリーのコピー)ではbatch countが1に戻る', async () => {
    mockClipboardReadText(JSON.stringify({ prompt: 'a cat', batchSize: 3 }))
    await openPanel()
    fireEvent.change(screen.getByLabelText('batch count(最大16)'), { target: { value: '7' } })

    fireEvent.click(screen.getByText('クリップボードから作成'))

    await waitFor(() => {
      expect(screen.getByText('クリップボードの設定をフォームに反映しました。')).toBeInTheDocument()
    })
    expect((screen.getByLabelText('batch count(最大16)') as HTMLInputElement).value).toBe('1')
  })
})

/**
 * #1103 で変更した ProjectAssetGenerationPanel.tsx の分岐カバレッジ(C1/C2)を基準まで引き上げる
 * ための、既存の振る舞いに対する特性テスト。新しい振る舞いではないので RED から始まらない。
 */
describe('ProjectAssetGenerationPanel パラメータ選択肢の読み込み', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue([])
  })

  it('選択肢の取得に失敗するとエラーを表示し、選択肢が空のままフォームを出す', async () => {
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockRejectedValue('選択肢を取得できません')
    await openPanel()

    await waitFor(() => {
      expect(screen.getByText('選択肢を取得できません')).toBeInTheDocument()
    })
    expect(screen.getByLabelText('batch size(最大16)')).toBeInTheDocument()
  })

  it('選択肢の取得がErrorで失敗した場合はそのメッセージを表示する', async () => {
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockRejectedValue(
      new Error('ComfyUIが起動していません')
    )
    await openPanel()

    await waitFor(() => {
      expect(screen.getByText('ComfyUIが起動していません')).toBeInTheDocument()
    })
  })

  it('パネルを閉じて開き直しても選択肢を取得し直さない', async () => {
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue(OPTIONS)
    await openPanel()

    fireEvent.click(screen.getByText('閉じる'))
    fireEvent.click(screen.getByText('アセット画像生成'))

    await waitFor(() => {
      expect(screen.getByText('チャットでプロンプトを作成')).toBeInTheDocument()
    })
    expect(actions.fetchImageGenerationOptionsAction).toHaveBeenCalledTimes(1)
  })

  it('samplerとschedulerが空でcheckpoint未選択の選択肢では既定値を使う', async () => {
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue({
      checkpoints: [],
      selectedCheckpoint: null,
      samplers: [],
      schedulers: [],
      loras: [],
    })
    await openPanel()
    fireEvent.change(screen.getByPlaceholderText('生成したい画像の説明'), { target: { value: 'a cat' } })
    ;(actions.generateProjectImagesAction as jest.Mock).mockResolvedValue({ images: [] })

    fireEvent.click(screen.getByText('生成'))

    await waitFor(() => {
      expect(actions.generateProjectImagesAction).toHaveBeenCalledWith(
        1,
        expect.objectContaining({ samplerName: 'euler', scheduler: 'normal', checkpoint: undefined })
      )
    })
  })

  it('プロジェクトの既定サイズと既定プロンプトを初期値として反映する', async () => {
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue({
      ...OPTIONS,
      defaultWidth: 512,
      defaultHeight: 768,
      defaultNegativePrompt: 'ugly',
      defaultQualityPrompt: 'masterpiece',
    })
    await openPanel()

    expect(screen.getByText('生成時にpromptへ自動で追加されます: masterpiece')).toBeInTheDocument()
    expect(screen.getByPlaceholderText('ugly')).toBeInTheDocument()
    expect(screen.getByText(/この設定で合計4枚/)).toBeInTheDocument()
    fireEvent.change(screen.getByPlaceholderText('生成したい画像の説明'), { target: { value: 'a cat' } })
    ;(actions.generateProjectImagesAction as jest.Mock).mockResolvedValue({ images: [] })
    fireEvent.click(screen.getByText('生成'))

    await waitFor(() => {
      expect(actions.generateProjectImagesAction).toHaveBeenCalledWith(
        1,
        expect.objectContaining({ width: 512, height: 768 })
      )
    })
  })
})

describe('ProjectAssetGenerationPanel 生成とアップロードの失敗系', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue(OPTIONS)
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue([])
  })

  it('promptが空のまま生成するとエラーを表示し、生成要求を送らない', async () => {
    await openPanel()

    fireEvent.click(screen.getByText('生成'))

    await waitFor(() => {
      expect(screen.getByText('promptを入力してください。')).toBeInTheDocument()
    })
    expect(actions.generateProjectImagesAction).not.toHaveBeenCalled()
  })

  it('生成に失敗するとエラーメッセージを表示する', async () => {
    ;(actions.generateProjectImagesAction as jest.Mock).mockResolvedValue({ error: 'ComfyUIに接続できません' })
    await openPanel()
    fireEvent.change(screen.getByPlaceholderText('生成したい画像の説明'), { target: { value: 'a cat' } })

    fireEvent.click(screen.getByText('生成'))

    await waitFor(() => {
      expect(screen.getByText('ComfyUIに接続できません')).toBeInTheDocument()
    })
    expect(screen.queryByTestId('generated-image-grid')).not.toBeInTheDocument()
  })

  it('生成が画像を返さなかった場合は結果グリッドを表示しない', async () => {
    ;(actions.generateProjectImagesAction as jest.Mock).mockResolvedValue({})
    await openPanel()
    fireEvent.change(screen.getByPlaceholderText('生成したい画像の説明'), { target: { value: 'a cat' } })

    fireEvent.click(screen.getByText('生成'))

    await waitFor(() => {
      expect(
        screen.getByText('生成しました。アセットとして追加する画像を選択してください。')
      ).toBeInTheDocument()
    })
    expect(screen.queryByTestId('generated-image-grid')).not.toBeInTheDocument()
  })

  it('クリップボードで読み込んだseedとLoRAをそのまま生成要求に送る', async () => {
    Object.assign(navigator, {
      clipboard: {
        readText: jest.fn().mockResolvedValue(
          JSON.stringify({ prompt: 'a cat', seed: 123, loraName: 'watercolor', loraWeight: 0.6 })
        ),
      },
    })
    ;(actions.generateProjectImagesAction as jest.Mock).mockResolvedValue({ images: [] })
    await openPanel()

    fireEvent.click(screen.getByText('クリップボードから作成'))
    await waitFor(() => {
      expect(screen.getByText('クリップボードの設定をフォームに反映しました。')).toBeInTheDocument()
    })
    fireEvent.click(screen.getByText('生成'))

    await waitFor(() => {
      expect(actions.generateProjectImagesAction).toHaveBeenCalledWith(1, {
        prompt: 'a cat',
        negativePrompt: undefined,
        steps: 20,
        cfgScale: 7,
        samplerName: undefined,
        scheduler: undefined,
        seed: 123,
        width: 1920,
        height: 1080,
        batchSize: 4,
        batchCount: 1,
        checkpoint: undefined,
        loraName: 'watercolor',
        loraWeight: 0.6,
      })
    })
  })

  it('クリップボードの読み取りがErrorでない値で失敗した場合もエラーを表示する', async () => {
    Object.assign(navigator, {
      clipboard: { readText: jest.fn().mockRejectedValue('クリップボードが使えません') },
    })
    await openPanel()

    fireEvent.click(screen.getByText('クリップボードから作成'))

    await waitFor(() => {
      expect(screen.getByText('クリップボードが使えません')).toBeInTheDocument()
    })
  })

  it('アップロードがエラーを返した場合はそのエラーを表示する', async () => {
    ;(actions.generateProjectImagesAction as jest.Mock).mockResolvedValue({
      images: [{ id: 7, fileName: 'image-7.png', dataBase64: 'AAAA', mimeType: 'image/png' }],
    })
    ;(actions.uploadProjectAssetImageAction as jest.Mock).mockResolvedValue({ error: '認可されていません' })
    await openPanel()
    fireEvent.change(screen.getByPlaceholderText('生成したい画像の説明'), { target: { value: 'a cat' } })
    fireEvent.click(screen.getByText('生成'))

    await waitFor(() => expect(screen.getByAltText('image-7.png')).toBeInTheDocument())
    fireEvent.click(screen.getByAltText('image-7.png'))
    fireEvent.click(screen.getByText('アセットとして追加(全環境へアップロード)'))

    await waitFor(() => {
      expect(screen.getByText('認可されていません')).toBeInTheDocument()
    })
  })

  it('アップロードの結果にログが無ければ全0環境として扱う', async () => {
    ;(actions.generateProjectImagesAction as jest.Mock).mockResolvedValue({
      images: [{ id: 8, fileName: 'image-8.png', dataBase64: 'AAAA', mimeType: 'image/png' }],
    })
    ;(actions.uploadProjectAssetImageAction as jest.Mock).mockResolvedValue({})
    await openPanel()
    fireEvent.change(screen.getByPlaceholderText('生成したい画像の説明'), { target: { value: 'a cat' } })
    fireEvent.click(screen.getByText('生成'))

    await waitFor(() => expect(screen.getByAltText('image-8.png')).toBeInTheDocument())
    fireEvent.click(screen.getByAltText('image-8.png'))
    fireEvent.click(screen.getByText('アセットとして追加(全環境へアップロード)'))

    await waitFor(() => {
      expect(screen.getByText('全0環境へアップロードしました。')).toBeInTheDocument()
    })
  })
})

describe('ProjectAssetGenerationPanel ギャラリーとチャットの残りの分岐', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue(OPTIONS)
  })

  function toggleGallerySection() {
    const header = screen.getByText('生成画像ギャラリーから選択してアップロード').closest('div') as HTMLElement
    fireEvent.click(within(header).getByText(/開く|閉じる/))
  }

  it('ギャラリーを閉じて開き直しても再取得しない', async () => {
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue([])
    await openPanel()

    toggleGallerySection()
    await waitFor(() => {
      expect(screen.getByText('生成画像ギャラリーに画像がありません。')).toBeInTheDocument()
    })
    toggleGallerySection()
    toggleGallerySection()

    await waitFor(() => {
      expect(screen.getByText('生成画像ギャラリーに画像がありません。')).toBeInTheDocument()
    })
    expect(actions.fetchGeneratedImagesAction).toHaveBeenCalledTimes(1)
  })

  it('ギャラリーの取得に失敗するとエラーを表示する', async () => {
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockRejectedValue('ギャラリーを取得できません')
    await openPanel()

    toggleGallerySection()

    await waitFor(() => {
      expect(screen.getByText('ギャラリーを取得できません')).toBeInTheDocument()
    })
  })

  it('ギャラリーの取得がErrorで失敗した場合はそのメッセージを表示する', async () => {
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockRejectedValue(new Error('通信に失敗しました'))
    await openPanel()

    toggleGallerySection()

    await waitFor(() => {
      expect(screen.getByText('通信に失敗しました')).toBeInTheDocument()
    })
  })

  it('Enterキーでプロンプト生成を要求し、空入力や生成中のEnterでは要求しない', async () => {
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue([])
    let resolveChat!: (value: { prompt?: string }) => void
    ;(actions.generateImagePromptAction as jest.Mock).mockReturnValue(
      new Promise((resolve) => {
        resolveChat = resolve
      })
    )
    await openPanel()
    openChatSection()
    const input = screen.getByPlaceholderText('例: 夕焼けの海辺を歩く猫')

    // 空入力のEnterは要求しない。
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(actions.generateImagePromptAction).not.toHaveBeenCalled()

    // Enter以外のキーも要求しない。
    fireEvent.change(input, { target: { value: '猫' } })
    fireEvent.keyDown(input, { key: 'a' })
    expect(actions.generateImagePromptAction).not.toHaveBeenCalled()

    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() => expect(actions.generateImagePromptAction).toHaveBeenCalledTimes(1))

    // 生成中のEnterは重ねて要求しない。
    fireEvent.change(input, { target: { value: 'もう一度' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(actions.generateImagePromptAction).toHaveBeenCalledTimes(1)

    await act(async () => {
      resolveChat({})
    })
    // promptを返さない応答は空文字として扱う。
    expect((screen.getByPlaceholderText('生成したい画像の説明') as HTMLTextAreaElement).value).toBe('')
  })

  it('AIプロバイダーを選ぶとその値をプロンプト生成に渡す', async () => {
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue([])
    ;(actions.generateImagePromptAction as jest.Mock).mockResolvedValue({ prompt: 'a cat' })
    await openPanel()
    openChatSection()

    fireEvent.change(screen.getByRole('combobox', { name: 'AIプロバイダー' }), {
      target: { value: 'CLAUDE' },
    })
    const input = screen.getByPlaceholderText('例: 夕焼けの海辺を歩く猫')
    fireEvent.change(input, { target: { value: '猫' } })
    fireEvent.click(screen.getByText('プロンプト生成'))

    await waitFor(() => {
      expect(actions.generateImagePromptAction).toHaveBeenCalledWith(1, {
        history: [],
        message: '猫',
        provider: 'CLAUDE',
      })
    })
  })
})

/**
 * 配色の規約適合(issue #1107)。
 *
 * このパネルだけが Tailwind の `gray` パレットを使い、背景・文字・ボーダーに `dark:` 対を
 * 持たないため、ダークモードで見出しが背景と同色になって読めなくなっていた。ここでは
 * 「どの色を使うか」ではなく「規約から外れたクラスが DOM に出ていないか」を検査する。
 * 参照実装は兄弟パネル ProjectAiModelsPanel.tsx と、同種チャットの plan/ArticlePlanChat.tsx。
 */
describe('ProjectAssetGenerationPanel 配色 (issue #1107)', () => {
  /**
   * ProjectAiModelsPanel.tsx のカードが持つカラー系クラス。パネル外枠はこれと揃える。
   *
   * 明→暗の対を1行に収めてあるのは体裁ではない。#1107 の受入基準が
   * `grep -rn "bg-white" apps/web/src --include=*.tsx | grep -v "dark:bg"` の一致0件を
   * 求めており、この grep は行単位なので、対を別々の行に置くとこのテストが引っかかる。
   */
  const CARD_COLOR_CLASSES = ['rounded-lg', 'border-neutral-200', 'dark:border-neutral-800', 'bg-white', 'dark:bg-neutral-900']

  const GALLERY_IMAGES = [
    { id: 10, projectId: null, prompt: 'a cute cat', checkpoint: 'model.safetensors', createdAt: '2026-08-01T00:00:00Z', tags: [] },
  ]

  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue(OPTIONS)
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue([])
  })

  function classTokens(el: Element): string[] {
    return (el.getAttribute('class') ?? '').split(/\s+/).filter(Boolean)
  }

  /** 自身を含む、配下の全要素。 */
  function selfAndDescendants(root: Element): Element[] {
    return [root, ...Array.from(root.querySelectorAll('*'))]
  }

  function panelSection(): HTMLElement {
    return document.querySelector('section') as HTMLElement
  }

  /** 見出しを含むサブフォームのコンテナ(見出しを包む flex ヘッダの親)。 */
  function subsectionContainer(headingText: string): HTMLElement {
    const header = screen.getByText(headingText).closest('div') as HTMLElement
    return header.parentElement as HTMLElement
  }

  function openGallerySection() {
    const header = screen.getByText('生成画像ギャラリーから選択してアップロード').closest('div') as HTMLElement
    fireEvent.click(within(header).getByText('開く'))
  }

  async function openPanelWithBothSections() {
    await openPanel()
    openGallerySection()
    // ギャラリーの取得が解決してから次へ進む。待たないと、その解決に伴う状態更新が
    // act() の外で起きて React が警告を出す。
    await waitFor(() => expect(actions.fetchGeneratedImagesAction).toHaveBeenCalled())
    openChatSection()
  }

  it('ギャラリーが空の状態で、パネル配下のどの要素にもgrayパレットのクラスが無い', async () => {
    await openPanelWithBothSections()
    expect(screen.getByText('生成画像ギャラリーに画像がありません。')).toBeInTheDocument()

    const offenders = selfAndDescendants(panelSection())
      .flatMap((el) => classTokens(el))
      .filter((token) => token.includes('gray-'))
    expect(offenders).toEqual([])
  })

  it('ギャラリー画像がある状態でも、パネル配下のどの要素にもgrayパレットのクラスが無い', async () => {
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue(GALLERY_IMAGES)
    await openPanelWithBothSections()
    await waitFor(() => expect(screen.getByAltText('a cute cat')).toBeInTheDocument())

    const offenders = selfAndDescendants(panelSection())
      .flatMap((el) => classTokens(el))
      .filter((token) => token.includes('gray-'))
    expect(offenders).toEqual([])
  })

  it('2つのサブフォームのコンテナのクラス列が互いに一致する', async () => {
    await openPanelWithBothSections()

    const gallery = subsectionContainer('生成画像ギャラリーから選択してアップロード')
    const chat = subsectionContainer('チャットでプロンプトを作成')
    expect(gallery.getAttribute('class')).toBe(chat.getAttribute('class'))
  })

  it('2つのサブフォームのコンテナが、bg-とborder-の色指定にdark:対を持つ', async () => {
    await openPanelWithBothSections()

    for (const heading of ['生成画像ギャラリーから選択してアップロード', 'チャットでプロンプトを作成']) {
      const tokens = classTokens(subsectionContainer(heading))
      expect(tokens.filter((t) => /^bg-/.test(t))).not.toEqual([])
      expect(tokens.filter((t) => /^dark:bg-/.test(t))).not.toEqual([])
      expect(tokens.filter((t) => /^border-[a-z]+-\d{2,3}$/.test(t))).not.toEqual([])
      expect(tokens.filter((t) => /^dark:border-[a-z]+-\d{2,3}$/.test(t))).not.toEqual([])
    }
  })

  it('パネル外枠のsectionが、閉じた状態でも兄弟パネルのカードと同じカラー系クラスを持つ', () => {
    render(<ProjectAssetGenerationPanel projectId={1} />)

    expect(classTokens(panelSection())).toEqual(expect.arrayContaining(CARD_COLOR_CLASSES))
  })

  it('パネル外枠のsectionが、開いた状態で兄弟パネルのカードと同じカラー系クラスを持つ', async () => {
    await openPanel()

    expect(classTokens(panelSection())).toEqual(expect.arrayContaining(CARD_COLOR_CLASSES))
  })

  it('チャット履歴領域がArticlePlanChatと同じカラー系クラスを持つ', async () => {
    await openPanelWithBothSections()

    const history = screen.getByText(/作りたい画像の内容をチャットで伝えてください。/).parentElement as HTMLElement
    expect(classTokens(history)).toEqual(expect.arrayContaining(['bg-neutral-50', 'dark:bg-neutral-800']))
  })

  it('AI側の吹き出しがArticlePlanChatと同じカラー系クラスを持ち、ユーザー側は変えない', async () => {
    ;(actions.generateImagePromptAction as jest.Mock).mockResolvedValue({ prompt: 'a cute cat, high quality' })
    await openPanelWithBothSections()

    fireEvent.change(screen.getByPlaceholderText('例: 夕焼けの海辺を歩く猫'), { target: { value: '猫' } })
    fireEvent.click(screen.getByText('プロンプト生成'))
    await waitFor(() => expect(screen.getByText('生成プロンプト:')).toBeInTheDocument())

    const aiBubble = screen.getByText('生成プロンプト:').closest('div') as HTMLElement
    expect(classTokens(aiBubble)).toEqual(
      expect.arrayContaining([
        'bg-neutral-200',
        'dark:bg-neutral-700',
        'text-neutral-900',
        'dark:text-neutral-50',
      ])
    )

    const userBubble = screen.getByText('あなた:').closest('div') as HTMLElement
    expect(classTokens(userBubble)).toEqual(expect.arrayContaining(['bg-blue-100', 'text-blue-900']))
  })

  it('色指定のない素のborderがパネル配下に残っていない', async () => {
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue(GALLERY_IMAGES)
    await openPanelWithBothSections()
    await waitFor(() => expect(screen.getByAltText('a cute cat')).toBeInTheDocument())

    // Tailwind v4 の素の `border` は既定色が currentColor になり、本文色でボーダーが描かれる。
    // 幅だけを指定するトークンを持つ要素は、色トークンとその dark: 対も持たなければならない。
    const offenders = selfAndDescendants(panelSection())
      .filter((el) => classTokens(el).includes('border'))
      .filter((el) => {
        const tokens = classTokens(el)
        return !(
          tokens.some((t) => /^border-[a-z]+-\d{2,3}$/.test(t)) &&
          tokens.some((t) => /^dark:border-[a-z]+-\d{2,3}$/.test(t))
        )
      })
      .map((el) => el.getAttribute('class'))
    expect(offenders).toEqual([])
  })

  it('bg-whiteを持つ要素はdark:bg-対を持つ', async () => {
    await openPanelWithBothSections()

    // 対を1行にまとめてある理由は CARD_COLOR_CLASSES のコメントを参照。
    const [LIGHT_BACKGROUND, DARK_BACKGROUND_PREFIX] = ['bg-white', 'dark:bg-']
    const offenders = selfAndDescendants(panelSection())
      .filter((el) => classTokens(el).includes(LIGHT_BACKGROUND))
      .filter((el) => !classTokens(el).some((t) => t.startsWith(DARK_BACKGROUND_PREFIX)))
      .map((el) => el.getAttribute('class'))
    expect(offenders).toEqual([])
  })
})

describe('ProjectAssetGenerationPanel ラベルと入力の結合 (issue #1114)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue({
      ...OPTIONS,
      loras: ['style.safetensors'],
    })
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue([])
  })

  it('全ての<label>がhtmlForで対応する入力のidと結びついている', async () => {
    await openPanel()
    openChatSection()
    await waitFor(() => {
      expect(screen.getByLabelText('LoRA')).toBeInTheDocument()
    })
    fireEvent.change(screen.getByLabelText('LoRA'), { target: { value: 'style.safetensors' } })

    const labels = Array.from(document.querySelectorAll('label'))
    expect(labels.length).toBeGreaterThan(10)
    for (const label of labels) {
      expect(label.htmlFor).not.toBe('')
      const target = document.getElementById(label.htmlFor)
      expect(target).not.toBeNull()
      expect(label.control).toBe(target)
    }
  })

  it('全ての入力に付いたidはパネル内で重複しない', async () => {
    await openPanel()
    openChatSection()
    const ids = Array.from(document.querySelectorAll('[id]')).map((e) => e.id)
    expect(new Set(ids).size).toBe(ids.length)
  })

  it('ラベル文言でprompt・seed・checkpointの入力を取得できる', async () => {
    await openPanel()
    expect(screen.getByLabelText('prompt')).toBeInstanceOf(HTMLTextAreaElement)
    expect(screen.getByLabelText('seed(空欄でランダム)')).toBeInstanceOf(HTMLInputElement)
    expect(screen.getByLabelText('checkpoint')).toBeInstanceOf(HTMLSelectElement)
  })
})
