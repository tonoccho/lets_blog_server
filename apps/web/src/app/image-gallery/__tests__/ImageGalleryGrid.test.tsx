import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { ImageGalleryGrid } from '../ImageGalleryGrid'
import * as actions from '../actions'
import type { GeneratedImageDetail, GeneratedImageSummary } from '@/lib/apiClient'

jest.mock('../actions', () => ({
  getGeneratedImageAction: jest.fn(),
  deleteGeneratedImageAction: jest.fn(),
  updateGeneratedImageTagsAction: jest.fn(),
  fetchGalleryImagesPageAction: jest.fn(),
}))

const SUMMARY: GeneratedImageSummary = {
  id: 1,
  projectId: 2,
  prompt: 'a cute cat',
  checkpoint: 'model.safetensors',
  createdAt: '2026-08-01T00:00:00Z',
  tags: [],
  provider: 'COMFYUI',
  folderId: null,
}

const DETAIL: GeneratedImageDetail = {
  ...SUMMARY,
  negativePrompt: 'blurry, low quality',
  steps: 20,
  cfgScale: 7,
  samplerName: 'euler',
  scheduler: 'normal',
  seed: 12345,
  width: 1920,
  height: 1080,
  batchSize: 4,
  // issue #1101: 生成に実際に使われた seed とバッチ内位置。
  batchIndex: 0,
  loraName: 'anime-style',
  loraWeight: 0.8,
}

describe('ImageGalleryGrid この画像の設定をコピー (issue #437)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.getGeneratedImageAction as jest.Mock).mockResolvedValue(DETAIL)
    Object.assign(navigator, { clipboard: { writeText: jest.fn().mockResolvedValue(undefined) } })
  })

  async function openDetail() {
    render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />)
    fireEvent.click(screen.getByAltText('a cute cat'))
    await waitFor(() => {
      expect(screen.getByText('この画像の設定をコピー')).toBeInTheDocument()
    })
  }

  it('クリックすると生成パラメータのみをJSON形式でクリップボードにコピーする', async () => {
    await openDetail()

    fireEvent.click(screen.getByText('この画像の設定をコピー'))

    await waitFor(() => {
      expect(navigator.clipboard.writeText).toHaveBeenCalledTimes(1)
    })
    const copied = JSON.parse((navigator.clipboard.writeText as jest.Mock).mock.calls[0][0])
    expect(copied).toEqual({
      prompt: 'a cute cat',
      negativePrompt: 'blurry, low quality',
      steps: 20,
      cfgScale: 7,
      samplerName: 'euler',
      scheduler: 'normal',
      seed: 12345,
      width: 1920,
      height: 1080,
      batchSize: 4,
      checkpoint: 'model.safetensors',
      loraName: 'anime-style',
      loraWeight: 0.8,
    })
    expect(copied.id).toBeUndefined()
    expect(copied.tags).toBeUndefined()
  })

  it('コピー後に一時的に「コピーしました」と表示する', async () => {
    await openDetail()

    fireEvent.click(screen.getByText('この画像の設定をコピー'))

    await waitFor(() => {
      expect(screen.getByText('コピーしました')).toBeInTheDocument()
    })
  })
})

/**
 * issue #1101: 生成に実際に使われた seed とバッチ内位置を詳細に出す。
 *
 * seed が無い画像(CHATGPT プロバイダ。`ChatGptImageClient` は seed を扱わない)は
 * 再現できないので、seed の値を出さず「再現不可」と分かる表示にする。
 */
describe('ImageGalleryGrid seedとバッチ内位置の表示 (issue #1101)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    Object.assign(navigator, { clipboard: { writeText: jest.fn().mockResolvedValue(undefined) } })
  })

  async function openWith(detail: GeneratedImageDetail, summary: GeneratedImageSummary = SUMMARY) {
    ;(actions.getGeneratedImageAction as jest.Mock).mockResolvedValue(detail)
    render(<ImageGalleryGrid images={[summary]} timezone={null} />)
    fireEvent.click(screen.getByAltText(summary.prompt as string))
    await waitFor(() => {
      expect(screen.getByText('この画像の設定をコピー')).toBeInTheDocument()
    })
  }

  it('COMFYUIで生成した画像はseedの実値を表示する', async () => {
    await openWith({ ...DETAIL, seed: 864213579, batchIndex: 0 })

    expect(screen.getByText('864213579')).toBeInTheDocument()
    expect(screen.queryByText(/再現不可/)).not.toBeInTheDocument()
  })

  it('バッチ内位置を表示する', async () => {
    await openWith({ ...DETAIL, seed: 864213579, batchIndex: 3 })

    expect(screen.getByText('batch index')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
  })

  it('CHATGPTで生成した画像はseedを表示せず再現不可と示す', async () => {
    await openWith({
      ...DETAIL,
      provider: 'CHATGPT',
      seed: null,
      batchIndex: null,
    })

    expect(screen.getByText(/再現不可/)).toBeInTheDocument()
    expect(screen.getByText('ChatGPT')).toBeInTheDocument()
  })

  it('seedが無い画像でも設定コピーは seed を null として書き出す', async () => {
    await openWith({ ...DETAIL, provider: 'CHATGPT', seed: null, batchIndex: null })

    fireEvent.click(screen.getByText('この画像の設定をコピー'))

    await waitFor(() => {
      expect(navigator.clipboard.writeText).toHaveBeenCalledTimes(1)
    })
    const copied = JSON.parse((navigator.clipboard.writeText as jest.Mock).mock.calls[0][0])
    expect(copied.seed).toBeNull()
  })

  it('バッチ内位置が無ければ - を表示する', async () => {
    await openWith({ ...DETAIL, seed: 1, batchIndex: null })

    expect(screen.getByText('batch index')).toBeInTheDocument()
  })
})

describe('ImageGalleryGrid 一覧と詳細の既存挙動 (issue #281 / #437 の回帰)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.getGeneratedImageAction as jest.Mock).mockResolvedValue(DETAIL)
    Object.assign(navigator, { clipboard: { writeText: jest.fn().mockResolvedValue(undefined) } })
  })

  it('画像が1件も無ければその旨を表示する', () => {
    render(<ImageGalleryGrid images={[]} timezone={null} />)

    expect(screen.getByText('該当する画像がありません。')).toBeInTheDocument()
  })

  it('タグを持たない画像だけならタグ絞り込みは出ない', () => {
    render(<ImageGalleryGrid images={[{ ...SUMMARY, tags: undefined as unknown as string[] }]} timezone={null} />)

    expect(screen.queryByText('タグで絞り込み:')).not.toBeInTheDocument()
  })

  it('タグで絞り込むとサーバ側の結果に置き換わり、すべてで取り直して戻る(issue #1472: クライアント側の絞り込みはしない)', async () => {
    const tagged = { ...SUMMARY, id: 1, tags: ['猫'] }
    const other = { ...SUMMARY, id: 2, prompt: 'a dog', tags: ['犬'] }
    const fetchPage = actions.fetchGalleryImagesPageAction as jest.Mock
    fetchPage.mockResolvedValueOnce([tagged])
    fetchPage.mockResolvedValueOnce([tagged, other])
    render(<ImageGalleryGrid images={[tagged, other]} timezone={null} />)

    fireEvent.click(screen.getByRole('button', { name: '猫' }))
    await waitFor(() => expect(screen.queryByAltText('a dog')).not.toBeInTheDocument())
    expect(fetchPage).toHaveBeenLastCalledWith(0, '猫', null)

    fireEvent.click(screen.getByRole('button', { name: 'すべて' }))
    expect(await screen.findByAltText('a dog')).toBeInTheDocument()
    expect(fetchPage).toHaveBeenLastCalledWith(0, null, null)
  })

  it('詳細の取得に失敗したらエラーを表示する', async () => {
    ;(actions.getGeneratedImageAction as jest.Mock).mockRejectedValue(new Error('取得できません'))
    render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />)

    fireEvent.click(screen.getByAltText('a cute cat'))

    await waitFor(() => {
      expect(screen.getByText('取得できません')).toBeInTheDocument()
    })
  })

  it('未知のプロバイダはコード値をそのまま表示し、negative promptとLoRAが無ければ - を出す', async () => {
    ;(actions.getGeneratedImageAction as jest.Mock).mockResolvedValue({
      ...DETAIL,
      provider: 'UNKNOWN_AI',
      negativePrompt: '',
      loraName: null,
      loraWeight: null,
      tags: [],
    })
    render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />)
    fireEvent.click(screen.getByAltText('a cute cat'))

    await waitFor(() => {
      expect(screen.getByText('UNKNOWN_AI')).toBeInTheDocument()
    })
    expect(screen.getAllByText('-').length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText('タグはありません。')).toBeInTheDocument()
  })

  it('クリップボードへの書き込みに失敗したらエラーを表示する', async () => {
    Object.assign(navigator, {
      clipboard: { writeText: jest.fn().mockRejectedValue(new Error('クリップボードが使えません')) },
    })
    render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />)
    fireEvent.click(screen.getByAltText('a cute cat'))
    await waitFor(() => expect(screen.getByText('この画像の設定をコピー')).toBeInTheDocument())

    fireEvent.click(screen.getByText('この画像の設定をコピー'))

    await waitFor(() => {
      expect(screen.getByText('クリップボードが使えません')).toBeInTheDocument()
    })
  })

  it('タグを追加すると保存し、重複タグは追加しない', async () => {
    ;(actions.updateGeneratedImageTagsAction as jest.Mock).mockResolvedValue({ ...DETAIL, tags: ['猫'] })
    render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />)
    fireEvent.click(screen.getByAltText('a cute cat'))
    await waitFor(() => expect(screen.getByPlaceholderText('タグを追加')).toBeInTheDocument())

    const input = screen.getByPlaceholderText('タグを追加')
    fireEvent.change(input, { target: { value: '猫' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() => {
      expect(actions.updateGeneratedImageTagsAction).toHaveBeenCalledWith(1, ['猫'])
    })

    fireEvent.change(input, { target: { value: '猫' } })
    fireEvent.click(screen.getByText('追加'))
    await waitFor(() => {
      expect(actions.updateGeneratedImageTagsAction).toHaveBeenCalledTimes(1)
    })
  })

  it('空白だけのタグは追加しない', async () => {
    render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />)
    fireEvent.click(screen.getByAltText('a cute cat'))
    await waitFor(() => expect(screen.getByPlaceholderText('タグを追加')).toBeInTheDocument())

    const input = screen.getByPlaceholderText('タグを追加')
    fireEvent.change(input, { target: { value: '   ' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    fireEvent.keyDown(input, { key: 'a' })

    expect(actions.updateGeneratedImageTagsAction).not.toHaveBeenCalled()
  })

  it('タグを削除すると残りのタグで保存する', async () => {
    ;(actions.getGeneratedImageAction as jest.Mock).mockResolvedValue({ ...DETAIL, tags: ['猫', '動物'] })
    ;(actions.updateGeneratedImageTagsAction as jest.Mock).mockResolvedValue({ ...DETAIL, tags: ['動物'] })
    render(<ImageGalleryGrid images={[{ ...SUMMARY, tags: ['猫', '動物'] }]} timezone={null} />)
    fireEvent.click(screen.getByAltText('a cute cat'))
    await waitFor(() => expect(screen.getByLabelText('タグ「猫」を削除')).toBeInTheDocument())

    fireEvent.click(screen.getByLabelText('タグ「猫」を削除'))

    await waitFor(() => {
      expect(actions.updateGeneratedImageTagsAction).toHaveBeenCalledWith(1, ['動物'])
    })
  })

  it('タグ保存に失敗したらエラーを表示する', async () => {
    ;(actions.updateGeneratedImageTagsAction as jest.Mock).mockRejectedValue(new Error('保存できません'))
    render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />)
    fireEvent.click(screen.getByAltText('a cute cat'))
    await waitFor(() => expect(screen.getByPlaceholderText('タグを追加')).toBeInTheDocument())

    fireEvent.change(screen.getByPlaceholderText('タグを追加'), { target: { value: '新タグ' } })
    fireEvent.click(screen.getByText('追加'))

    await waitFor(() => {
      expect(screen.getByText('保存できません')).toBeInTheDocument()
    })
  })

  it('削除を確認すると詳細を閉じ、キャンセルすると何もしない', async () => {
    ;(actions.deleteGeneratedImageAction as jest.Mock).mockResolvedValue(undefined)
    const confirmSpy = jest.spyOn(window, 'confirm').mockReturnValue(false)
    render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />)
    fireEvent.click(screen.getByAltText('a cute cat'))
    await waitFor(() => expect(screen.getByText('削除')).toBeInTheDocument())

    fireEvent.click(screen.getByText('削除'))
    expect(actions.deleteGeneratedImageAction).not.toHaveBeenCalled()

    confirmSpy.mockReturnValue(true)
    fireEvent.click(screen.getByText('削除'))
    await waitFor(() => {
      expect(screen.queryByText('生成画像の詳細')).not.toBeInTheDocument()
    })
    confirmSpy.mockRestore()
  })

  it('削除に失敗したらエラーを表示する', async () => {
    ;(actions.deleteGeneratedImageAction as jest.Mock).mockRejectedValue(new Error('削除できません'))
    const confirmSpy = jest.spyOn(window, 'confirm').mockReturnValue(true)
    render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />)
    fireEvent.click(screen.getByAltText('a cute cat'))
    await waitFor(() => expect(screen.getByText('削除')).toBeInTheDocument())

    fireEvent.click(screen.getByText('削除'))

    await waitFor(() => {
      expect(screen.getByText('削除できません')).toBeInTheDocument()
    })
    confirmSpy.mockRestore()
  })

  it('背景をクリックすると詳細を閉じ、中身のクリックでは閉じない', async () => {
    render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />)
    fireEvent.click(screen.getByAltText('a cute cat'))
    await waitFor(() => expect(screen.getByText('生成画像の詳細')).toBeInTheDocument())

    fireEvent.click(screen.getByText('生成画像の詳細'))
    expect(screen.getByText('生成画像の詳細')).toBeInTheDocument()

    fireEvent.click(screen.getByText('閉じる'))
    expect(screen.queryByText('生成画像の詳細')).not.toBeInTheDocument()
  })
})

/**
 * 例外は Error とは限らない(Server Action の境界やクリップボードAPIは文字列や
 * DOMException を投げうる)。どの経路でもメッセージ化して画面に出す。
 */
describe('ImageGalleryGrid Error以外の例外の扱い', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.getGeneratedImageAction as jest.Mock).mockResolvedValue(DETAIL)
    Object.assign(navigator, { clipboard: { writeText: jest.fn().mockResolvedValue(undefined) } })
  })

  it('詳細取得がError以外で落ちてもメッセージを表示する', async () => {
    ;(actions.getGeneratedImageAction as jest.Mock).mockRejectedValue('詳細を取得できません')
    render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />)

    fireEvent.click(screen.getByAltText('a cute cat'))

    await waitFor(() => {
      expect(screen.getByText('詳細を取得できません')).toBeInTheDocument()
    })
  })

  it('削除がError以外で落ちてもメッセージを表示する', async () => {
    ;(actions.deleteGeneratedImageAction as jest.Mock).mockRejectedValue('削除できません')
    const confirmSpy = jest.spyOn(window, 'confirm').mockReturnValue(true)
    render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />)
    fireEvent.click(screen.getByAltText('a cute cat'))
    await waitFor(() => expect(screen.getByText('削除')).toBeInTheDocument())

    fireEvent.click(screen.getByText('削除'))

    await waitFor(() => {
      expect(screen.getByText('削除できません')).toBeInTheDocument()
    })
    confirmSpy.mockRestore()
  })

  it('タグ保存がError以外で落ちてもメッセージを表示する', async () => {
    ;(actions.updateGeneratedImageTagsAction as jest.Mock).mockRejectedValue('保存できません')
    render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />)
    fireEvent.click(screen.getByAltText('a cute cat'))
    await waitFor(() => expect(screen.getByPlaceholderText('タグを追加')).toBeInTheDocument())

    fireEvent.change(screen.getByPlaceholderText('タグを追加'), { target: { value: '新タグ' } })
    fireEvent.click(screen.getByText('追加'))

    await waitFor(() => {
      expect(screen.getByText('保存できません')).toBeInTheDocument()
    })
  })

  it('クリップボードがError以外で落ちてもメッセージを表示する', async () => {
    Object.assign(navigator, { clipboard: { writeText: jest.fn().mockRejectedValue('コピーできません') } })
    render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />)
    fireEvent.click(screen.getByAltText('a cute cat'))
    await waitFor(() => expect(screen.getByText('この画像の設定をコピー')).toBeInTheDocument())

    fireEvent.click(screen.getByText('この画像の設定をコピー'))

    await waitFor(() => {
      expect(screen.getByText('コピーできません')).toBeInTheDocument()
    })
  })
})
