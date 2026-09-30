import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ImageGalleryGrid } from '../ImageGalleryGrid'
import * as actions from '../actions'
import { GALLERY_PAGE_SIZE } from '../pageSize'
import type { GeneratedImageDetail, GeneratedImageSummary } from '@/lib/apiClient'

/**
 * issue #1472: ギャラリーの無限スクロール(IntersectionObserver)、id による重複除去、
 * サーバ側タグ絞り込み、失敗時に読み込み済みの一覧を消さないこと。
 */
jest.mock('../actions', () => ({
  getGeneratedImageAction: jest.fn(),
  deleteGeneratedImageAction: jest.fn(),
  updateGeneratedImageTagsAction: jest.fn(),
  fetchGalleryImagesPageAction: jest.fn(),
}))

const fetchPage = actions.fetchGalleryImagesPageAction as jest.Mock

function image(id: number, tags: string[] = []): GeneratedImageSummary {
  return {
    id,
    projectId: 1,
    prompt: `prompt-${id}`,
    checkpoint: 'm.safetensors',
    createdAt: '2026-08-01T00:00:00Z',
    tags,
    provider: 'COMFYUI',
  }
}

/** id が from から始まり降順に並ぶ count 件(新しい順)。 */
function page(from: number, count: number, tags: string[] = []): GeneratedImageSummary[] {
  return Array.from({ length: count }, (_, i) => image(from - i, tags))
}

class FakeObserver {
  static instances: FakeObserver[] = []
  disconnected = false
  observed: Element[] = []
  constructor(public callback: IntersectionObserverCallback) {
    FakeObserver.instances.push(this)
  }
  observe(target: Element) {
    this.observed.push(target)
  }
  unobserve() {}
  disconnect() {
    this.disconnected = true
  }
  takeRecords() {
    return []
  }
}

/** 末尾の番兵が画面に入った、を再現する。 */
async function scrollToEnd() {
  const live = FakeObserver.instances.filter((o) => !o.disconnected)
  const observer = live[live.length - 1]
  expect(observer).toBeDefined()
  await act(async () => {
    observer.callback([{ isIntersecting: true } as IntersectionObserverEntry], observer as unknown as IntersectionObserver)
  })
}

function thumbnails() {
  return screen.queryAllByRole('img').filter((el) => (el.getAttribute('src') ?? '').startsWith('/image-gallery/'))
}

beforeEach(() => {
  jest.clearAllMocks()
  // clearAllMocks は mockResolvedValueOnce の未消費分を消さず、次のテストへ漏れる。
  fetchPage.mockReset()
  FakeObserver.instances = []
  ;(global as unknown as { IntersectionObserver: unknown }).IntersectionObserver = FakeObserver
})

describe('ImageGalleryGrid 無限スクロール(issue #1472)', () => {
  it('ページサイズは24', () => {
    expect(GALLERY_PAGE_SIZE).toBe(24)
  })

  it('末尾が画面に入ると次のページを offset=読み込み済み件数 で取得して末尾に追加する', async () => {
    fetchPage.mockResolvedValue(page(100, 3))
    render(<ImageGalleryGrid images={page(200, GALLERY_PAGE_SIZE)} timezone={null} />)
    expect(thumbnails()).toHaveLength(GALLERY_PAGE_SIZE)

    await scrollToEnd()

    await waitFor(() => expect(thumbnails()).toHaveLength(GALLERY_PAGE_SIZE + 3))
    expect(fetchPage).toHaveBeenCalledWith(GALLERY_PAGE_SIZE, null)
  })

  it('返った件数がページサイズ未満ならそれ以上は要求しない', async () => {
    fetchPage.mockResolvedValue(page(100, 3))
    render(<ImageGalleryGrid images={page(200, GALLERY_PAGE_SIZE)} timezone={null} />)

    await scrollToEnd()
    await waitFor(() => expect(thumbnails()).toHaveLength(GALLERY_PAGE_SIZE + 3))

    expect(FakeObserver.instances.filter((o) => !o.disconnected)).toHaveLength(0)
    expect(fetchPage).toHaveBeenCalledTimes(1)
  })

  it('初回がページサイズ未満なら最初から続きを要求しない(番兵を監視しない)', () => {
    render(<ImageGalleryGrid images={page(10, 3)} timezone={null} />)

    expect(FakeObserver.instances.filter((o) => !o.disconnected)).toHaveLength(0)
    expect(fetchPage).not.toHaveBeenCalled()
  })

  it('ちょうどページサイズで終わった場合、次の要求が空なら終端になる', async () => {
    fetchPage.mockResolvedValue([])
    render(<ImageGalleryGrid images={page(200, GALLERY_PAGE_SIZE)} timezone={null} />)

    await scrollToEnd()
    await waitFor(() => expect(fetchPage).toHaveBeenCalledTimes(1))

    await waitFor(() => expect(FakeObserver.instances.filter((o) => !o.disconnected)).toHaveLength(0))
    expect(thumbnails()).toHaveLength(GALLERY_PAGE_SIZE)
  })

  it('同じidは2度表示せず、次のoffsetは重複を除く前の取得件数から数える', async () => {
    // 新しい画像が作られ、境界の1件(id=177)が次ページにも現れた状況。
    const initial = page(200, GALLERY_PAGE_SIZE)
    const dup = initial[GALLERY_PAGE_SIZE - 1]
    fetchPage.mockResolvedValueOnce([dup, ...page(100, GALLERY_PAGE_SIZE - 1)])
    fetchPage.mockResolvedValueOnce([])
    render(<ImageGalleryGrid images={initial} timezone={null} />)

    await scrollToEnd()
    await waitFor(() => expect(thumbnails()).toHaveLength(GALLERY_PAGE_SIZE * 2 - 1))
    const ids = thumbnails().map((el) => el.getAttribute('src'))
    expect(new Set(ids).size).toBe(ids.length)

    await scrollToEnd()
    await waitFor(() => expect(fetchPage).toHaveBeenCalledTimes(2))
    expect(fetchPage).toHaveBeenLastCalledWith(GALLERY_PAGE_SIZE * 2, null)
  })

  it('読み込みが進むとタグのチップが増える', async () => {
    fetchPage.mockResolvedValue([image(1, ['新タグ'])])
    render(<ImageGalleryGrid images={page(200, GALLERY_PAGE_SIZE, ['猫'])} timezone={null} />)
    expect(screen.queryByRole('button', { name: '新タグ' })).not.toBeInTheDocument()

    await scrollToEnd()

    expect(await screen.findByRole('button', { name: '新タグ' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '猫' })).toBeInTheDocument()
  })

  it('取得に失敗しても読み込み済みの一覧は消さず、失敗を示し、再試行できる', async () => {
    fetchPage.mockRejectedValueOnce(new Error('APIエラー (503)'))
    fetchPage.mockResolvedValueOnce(page(100, 2))
    render(<ImageGalleryGrid images={page(200, GALLERY_PAGE_SIZE)} timezone={null} />)

    await scrollToEnd()

    expect(await screen.findByText(/続きを読み込めませんでした.*APIエラー \(503\)/)).toBeInTheDocument()
    expect(thumbnails()).toHaveLength(GALLERY_PAGE_SIZE)

    fireEvent.click(screen.getByRole('button', { name: '再試行' }))

    await waitFor(() => expect(thumbnails()).toHaveLength(GALLERY_PAGE_SIZE + 2))
    expect(screen.queryByText(/続きを読み込めませんでした/)).not.toBeInTheDocument()
  })

  it('Error以外で失敗しても文字列化して示す', async () => {
    fetchPage.mockRejectedValueOnce('boom')
    render(<ImageGalleryGrid images={page(200, GALLERY_PAGE_SIZE)} timezone={null} />)

    await scrollToEnd()

    expect(await screen.findByText(/boom/)).toBeInTheDocument()
  })

  it('読み込み中に重ねて末尾が検知されても二重には要求しない', async () => {
    let resolve: (v: GeneratedImageSummary[]) => void = () => {}
    fetchPage.mockReturnValue(new Promise<GeneratedImageSummary[]>((r) => (resolve = r)))
    render(<ImageGalleryGrid images={page(200, GALLERY_PAGE_SIZE)} timezone={null} />)
    const first = FakeObserver.instances[FakeObserver.instances.length - 1]

    await act(async () => {
      first.callback([{ isIntersecting: true } as IntersectionObserverEntry], first as unknown as IntersectionObserver)
      first.callback([{ isIntersecting: true } as IntersectionObserverEntry], first as unknown as IntersectionObserver)
    })

    expect(fetchPage).toHaveBeenCalledTimes(1)
    await act(async () => resolve([]))
  })

  it('画面に入っていない通知では要求しない', async () => {
    render(<ImageGalleryGrid images={page(200, GALLERY_PAGE_SIZE)} timezone={null} />)
    const observer = FakeObserver.instances[FakeObserver.instances.length - 1]

    await act(async () => {
      observer.callback([{ isIntersecting: false } as IntersectionObserverEntry], observer as unknown as IntersectionObserver)
    })

    expect(fetchPage).not.toHaveBeenCalled()
  })
})

describe('ImageGalleryGrid サーバ側のタグ絞り込み(issue #1472)', () => {
  const initial = [...page(200, GALLERY_PAGE_SIZE - 1), image(150, ['猫'])]

  it('タグを選ぶと offset=0 でそのタグを指定して取り直し、結果で一覧を置き換える', async () => {
    fetchPage.mockResolvedValue([image(7, ['猫']), image(3, ['猫'])])
    render(<ImageGalleryGrid images={initial} timezone={null} />)

    fireEvent.click(screen.getByRole('button', { name: '猫' }))

    await waitFor(() => expect(thumbnails()).toHaveLength(2))
    expect(fetchPage).toHaveBeenCalledWith(0, '猫')
    // チップは絞り込み前に読み込み済みの画像から作るので、絞り込み後も選択肢は残る。
    expect(screen.getByRole('button', { name: '猫' })).toBeInTheDocument()
  })

  it('絞り込み後の一覧も同じ無限スクロールで続きを、タグ付きで読み込む', async () => {
    fetchPage.mockResolvedValueOnce(page(900, GALLERY_PAGE_SIZE, ['猫']))
    fetchPage.mockResolvedValueOnce([image(1, ['猫'])])
    render(<ImageGalleryGrid images={initial} timezone={null} />)

    fireEvent.click(screen.getByRole('button', { name: '猫' }))
    expect(await screen.findByAltText('prompt-900')).toBeInTheDocument()

    await scrollToEnd()

    await waitFor(() => expect(thumbnails()).toHaveLength(GALLERY_PAGE_SIZE + 1))
    expect(fetchPage).toHaveBeenLastCalledWith(GALLERY_PAGE_SIZE, '猫')
  })

  it('絞り込み中にもう一度同じタグを押すか「すべて」を押すと、絞り込みなしで取り直す', async () => {
    fetchPage.mockResolvedValueOnce([image(7, ['猫'])])
    fetchPage.mockResolvedValueOnce(initial)
    fetchPage.mockResolvedValueOnce([image(7, ['猫'])])
    fetchPage.mockResolvedValueOnce([...page(300, GALLERY_PAGE_SIZE - 1), image(150, ['猫'])])
    render(<ImageGalleryGrid images={initial} timezone={null} />)

    fireEvent.click(screen.getByRole('button', { name: '猫' }))
    await waitFor(() => expect(thumbnails()).toHaveLength(1))
    fireEvent.click(screen.getByRole('button', { name: 'すべて' }))
    await waitFor(() => expect(thumbnails()).toHaveLength(GALLERY_PAGE_SIZE))
    expect(fetchPage).toHaveBeenLastCalledWith(0, null)

    fireEvent.click(screen.getByRole('button', { name: '猫' }))
    await waitFor(() => expect(thumbnails()).toHaveLength(1))
    fireEvent.click(screen.getByRole('button', { name: '猫' }))
    await waitFor(() => expect(thumbnails()).toHaveLength(GALLERY_PAGE_SIZE))
    expect(fetchPage).toHaveBeenLastCalledWith(0, null)
  })

  it('絞り込んだ結果が0件なら該当なしを示す', async () => {
    fetchPage.mockResolvedValue([])
    render(<ImageGalleryGrid images={initial} timezone={null} />)

    fireEvent.click(screen.getByRole('button', { name: '猫' }))

    expect(await screen.findByText('該当する画像がありません。')).toBeInTheDocument()
  })

  it('絞り込みの取得に失敗したら、読み込み済みの一覧を消さずに失敗を示す', async () => {
    fetchPage.mockRejectedValue(new Error('APIエラー (500)'))
    render(<ImageGalleryGrid images={initial} timezone={null} />)

    fireEvent.click(screen.getByRole('button', { name: '猫' }))

    expect(await screen.findByText(/APIエラー \(500\)/)).toBeInTheDocument()
    expect(thumbnails()).toHaveLength(GALLERY_PAGE_SIZE)
  })

  it('絞り込みの取得中に届いた古い続きの応答は捨てる', async () => {
    let resolveMore: (v: GeneratedImageSummary[]) => void = () => {}
    fetchPage.mockReturnValueOnce(new Promise<GeneratedImageSummary[]>((r) => (resolveMore = r)))
    fetchPage.mockResolvedValueOnce([image(7, ['猫'])])
    render(<ImageGalleryGrid images={initial} timezone={null} />)

    await scrollToEnd()
    fireEvent.click(screen.getByRole('button', { name: '猫' }))
    await waitFor(() => expect(thumbnails()).toHaveLength(1))
    await act(async () => resolveMore(page(900, 5)))

    expect(thumbnails()).toHaveLength(1)
  })
})

describe('ImageGalleryGrid ローカルの一覧の更新(issue #1472)', () => {
  const DETAIL = { ...image(5, ['猫']), negativePrompt: '', steps: 1, cfgScale: 1, samplerName: 'e', scheduler: 'n', seed: 1, width: 1, height: 1, batchSize: 1, batchIndex: 0, loraName: null, loraWeight: null } as unknown as GeneratedImageDetail

  it('画像を削除すると一覧から取り除く', async () => {
    ;(actions.getGeneratedImageAction as jest.Mock).mockResolvedValue(DETAIL)
    ;(actions.deleteGeneratedImageAction as jest.Mock).mockResolvedValue(undefined)
    jest.spyOn(window, 'confirm').mockReturnValue(true)
    render(<ImageGalleryGrid images={[image(5, ['猫']), image(4)]} timezone={null} />)

    fireEvent.click(screen.getByAltText('prompt-5'))
    fireEvent.click(await screen.findByRole('button', { name: '削除' }))

    await waitFor(() => expect(screen.queryByAltText('prompt-5')).not.toBeInTheDocument())
    expect(screen.getByAltText('prompt-4')).toBeInTheDocument()
  })

  it('削除した分だけ次のoffsetが戻る', async () => {
    ;(actions.getGeneratedImageAction as jest.Mock).mockResolvedValue({ ...DETAIL, id: 200 })
    ;(actions.deleteGeneratedImageAction as jest.Mock).mockResolvedValue(undefined)
    jest.spyOn(window, 'confirm').mockReturnValue(true)
    fetchPage.mockResolvedValue([])
    render(<ImageGalleryGrid images={page(200, GALLERY_PAGE_SIZE)} timezone={null} />)

    fireEvent.click(screen.getByAltText('prompt-200'))
    fireEvent.click(await screen.findByRole('button', { name: '削除' }))
    await waitFor(() => expect(screen.queryByAltText('prompt-200')).not.toBeInTheDocument())
    await scrollToEnd()

    await waitFor(() => expect(fetchPage).toHaveBeenCalledWith(GALLERY_PAGE_SIZE - 1, null))
  })

  it('タグを保存すると一覧の画像のタグとチップに反映する', async () => {
    ;(actions.getGeneratedImageAction as jest.Mock).mockResolvedValue(DETAIL)
    ;(actions.updateGeneratedImageTagsAction as jest.Mock).mockResolvedValue({ ...DETAIL, tags: ['猫', '新'] })
    render(<ImageGalleryGrid images={[image(5, ['猫']), image(4)]} timezone={null} />)

    fireEvent.click(screen.getByAltText('prompt-5'))
    fireEvent.change(await screen.findByPlaceholderText('タグを追加'), { target: { value: '新' } })
    fireEvent.click(screen.getByRole('button', { name: '追加' }))

    expect(await screen.findByRole('button', { name: '新' })).toBeInTheDocument()
  })
})
