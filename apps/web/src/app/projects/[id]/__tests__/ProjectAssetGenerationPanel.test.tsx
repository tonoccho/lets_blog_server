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
