import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { ImageGalleryGrid } from '../ImageGalleryGrid'
import { backend, installGallery } from './galleryBackend'
import { GALLERY_PAGE_SIZE } from '../pageSize'
import type { GeneratedImageDetail, GeneratedImageFolder, GeneratedImageSummary } from '@/lib/apiClient'

/**
 * #1834: 既存の分岐テストが届かなかった分岐を、実物の Server Action・部品を通して確かめる。
 * - 古い要求の応答(読み込み中に別の絞り込みを始めた後に届いた成功・失敗)は無視される。
 * - 所属フォルダの変更は、変更した画像だけを書き換える。
 * - 改名中のフォルダごと親を削除すると、改名の入力が閉じる。
 * - 親が一覧に無いフォルダ(孤児)だけでもツリーが壊れない。
 *
 * 自前の Server Action(../actions)・@/lib/apiClient・@/lib/session は差し替えない。差し替えるのは Next.js の実行基盤と
 * 外部との通信(fetch。media-service の代役は ./galleryBackend の backend)だけ。
 */
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

function image(id: number, tags: string[] = [], folderId: number | null = null): GeneratedImageSummary {
  return {
    id,
    projectId: 1,
    prompt: `prompt-${id}`,
    checkpoint: 'm.safetensors',
    createdAt: '2026-08-01T00:00:00Z',
    tags,
    provider: 'COMFYUI',
    folderId,
  }
}

function detailOf(summary: GeneratedImageSummary): GeneratedImageDetail {
  return {
    ...summary,
    negativePrompt: '',
    steps: 20,
    cfgScale: 7,
    samplerName: 'euler',
    scheduler: 'normal',
    seed: 1,
    width: 512,
    height: 512,
    batchSize: 1,
    batchIndex: null,
    loraName: null,
    loraWeight: null,
  }
}

class FakeObserver {
  static instances: FakeObserver[] = []
  disconnected = false
  constructor(public callback: IntersectionObserverCallback) {
    FakeObserver.instances.push(this)
  }
  observe() {}
  unobserve() {}
  disconnect() {
    this.disconnected = true
  }
  takeRecords() {
    return []
  }
}

async function scrollToEnd() {
  const live = FakeObserver.instances.filter((o) => !o.disconnected)
  const observer = live[live.length - 1]
  expect(observer).toBeDefined()
  await act(async () => {
    observer.callback([{ isIntersecting: true } as IntersectionObserverEntry], observer as unknown as IntersectionObserver)
  })
}

/** 解決も拒否も、テストの側で好きなときに行える応答。 */
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

const thumbnails = () => screen.queryAllByRole('img').filter((el) => (el.getAttribute('src') ?? '').startsWith('/image-gallery/'))
const chip = (tag: string) => screen.getByRole('button', { name: tag })

beforeEach(() => {
  installGallery()
  FakeObserver.instances = []
  ;(global as unknown as { IntersectionObserver: unknown }).IntersectionObserver = FakeObserver
})

/** 24 枚(ページサイズ)の一覧。続きがあると見なされ、tag「猫」「犬」のチップが並ぶ。 */
function fullPage() {
  return Array.from({ length: GALLERY_PAGE_SIZE }, (_, i) => image(200 - i, i === 0 ? ['猫'] : i === 1 ? ['犬'] : []))
}

describe('古い要求の応答は無視される', () => {
  it('続きの読み込み中に別の絞り込みを始めたら、遅れて届いた続きの成功は一覧に足さない', async () => {
    const late = deferred<GeneratedImageSummary[]>()
    backend.listImages.mockImplementationOnce(() => late.promise)
    render(<ImageGalleryGrid images={fullPage()} timezone={null} />)
    await scrollToEnd()
    await waitFor(() => expect(backend.listImages).toHaveBeenCalledTimes(1))

    backend.listImages.mockResolvedValueOnce([image(7, ['猫'])])
    fireEvent.click(chip('猫'))
    await waitFor(() => expect(thumbnails()).toHaveLength(1))
    await act(async () => {
      late.resolve([image(8)])
      await late.promise
    })

    expect(thumbnails()).toHaveLength(1)
    expect(screen.queryByAltText('prompt-8')).not.toBeInTheDocument()
  })

  it('続きの読み込み中に別の絞り込みを始めたら、遅れて届いた続きの失敗は通知しない', async () => {
    const late = deferred<GeneratedImageSummary[]>()
    backend.listImages.mockImplementationOnce(() => late.promise)
    render(<ImageGalleryGrid images={fullPage()} timezone={null} />)
    await scrollToEnd()
    await waitFor(() => expect(backend.listImages).toHaveBeenCalledTimes(1))

    backend.listImages.mockResolvedValueOnce([image(7, ['猫'])])
    fireEvent.click(chip('猫'))
    await waitFor(() => expect(thumbnails()).toHaveLength(1))
    await act(async () => {
      late.reject(new Error('APIエラー (500): 遅れて失敗'))
      await late.promise.catch(() => undefined)
    })

    expect(screen.queryByText(/続きを読み込めませんでした/)).not.toBeInTheDocument()
    expect(thumbnails()).toHaveLength(1)
  })

  it('絞り込みの読み込み中に別の絞り込みを始めたら、遅れて届いた前の絞り込みの成功は反映しない', async () => {
    const late = deferred<GeneratedImageSummary[]>()
    backend.listImages.mockImplementationOnce(() => late.promise)
    render(<ImageGalleryGrid images={fullPage()} timezone={null} />)
    fireEvent.click(chip('猫'))
    await waitFor(() => expect(backend.listImages).toHaveBeenCalledTimes(1))

    backend.listImages.mockResolvedValueOnce([image(7, ['犬'])])
    fireEvent.click(chip('犬'))
    await waitFor(() => expect(thumbnails()).toHaveLength(1))
    await act(async () => {
      late.resolve([image(8, ['猫']), image(9, ['猫'])])
      await late.promise
    })

    expect(screen.getByAltText('prompt-7')).toBeInTheDocument()
    expect(thumbnails()).toHaveLength(1)
    expect(chip('犬')).toHaveClass('bg-neutral-900')
  })

  it('絞り込みの読み込み中に別の絞り込みを始めたら、遅れて届いた前の絞り込みの失敗は通知しない', async () => {
    const late = deferred<GeneratedImageSummary[]>()
    backend.listImages.mockImplementationOnce(() => late.promise)
    render(<ImageGalleryGrid images={fullPage()} timezone={null} />)
    fireEvent.click(chip('猫'))
    await waitFor(() => expect(backend.listImages).toHaveBeenCalledTimes(1))

    backend.listImages.mockResolvedValueOnce([image(7, ['犬'])])
    fireEvent.click(chip('犬'))
    await waitFor(() => expect(thumbnails()).toHaveLength(1))
    await act(async () => {
      late.reject(new Error('APIエラー (500): 遅れて失敗'))
      await late.promise.catch(() => undefined)
    })

    expect(screen.queryByText(/絞り込みを読み込めませんでした/)).not.toBeInTheDocument()
    expect(thumbnails()).toHaveLength(1)
  })
})

describe('詳細からの所属フォルダの変更は、変更した画像だけを書き換える', () => {
  it('ほかの画像は一覧に残り、変更した画像の詳細は新しい所属を示す', async () => {
    const folders: GeneratedImageFolder[] = [{ id: 3, name: '人物', parentId: null }]
    backend.getImage.mockResolvedValue(detailOf(image(1)))
    backend.setFolder.mockResolvedValue(detailOf(image(1, [], 3)))
    render(<ImageGalleryGrid images={[image(2), image(1)]} folders={folders} timezone={null} />)
    fireEvent.click(screen.getByRole('button', { name: 'prompt-1の詳細を表示' }))
    const select = await screen.findByLabelText('所属フォルダ')

    fireEvent.change(select, { target: { value: '3' } })

    await waitFor(() => expect(backend.setFolder).toHaveBeenCalledWith(1, 3))
    await waitFor(() => expect(screen.getByLabelText('所属フォルダ')).toHaveValue('3'))
    expect(screen.getAllByAltText('prompt-2')).toHaveLength(1)
    expect(screen.getAllByAltText('prompt-1')).toHaveLength(1)
  })
})

describe('フォルダの削除と改名中の入力', () => {
  const FOLDERS: GeneratedImageFolder[] = [
    { id: 1, name: '風景', parentId: null },
    { id: 2, name: '山', parentId: 1 },
    { id: 3, name: '人物', parentId: null },
  ]
  let confirmSpy: jest.SpyInstance
  beforeEach(() => {
    confirmSpy = jest.spyOn(window, 'confirm').mockReturnValue(true)
    backend.folderDeleteImpact.mockResolvedValue({ descendantFolderCount: 1, imageCount: 0 })
    backend.deleteFolder.mockResolvedValue(undefined)
    backend.listImages.mockResolvedValue([image(1)])
  })
  afterEach(() => confirmSpy.mockRestore())

  const treeItem = (name: string) => screen.getByRole('treeitem', { name })
  const startRename = (name: string) => fireEvent.click(within(treeItem(name)).getAllByRole('button', { name: `「${name}」を改名` })[0])
  const clickDelete = (name: string) => fireEvent.click(within(treeItem(name)).getAllByRole('button', { name: `「${name}」を削除` })[0])

  it('改名中のフォルダが親ごと削除されたら、改名の入力を閉じる', async () => {
    render(<ImageGalleryGrid images={[image(1)]} folders={FOLDERS} timezone={null} />)
    startRename('山')
    expect(screen.getByLabelText('「山」の新しい名前')).toBeInTheDocument()

    clickDelete('風景')

    await waitFor(() => expect(backend.deleteFolder).toHaveBeenCalledWith(1))
    await waitFor(() => expect(screen.queryByLabelText('「山」の新しい名前')).not.toBeInTheDocument())
    expect(screen.queryByRole('treeitem', { name: '風景' })).not.toBeInTheDocument()
  })

  it('別のフォルダの改名中に削除しても、その改名の入力は残る', async () => {
    render(<ImageGalleryGrid images={[image(1)]} folders={FOLDERS} timezone={null} />)
    startRename('人物')

    clickDelete('風景')

    await waitFor(() => expect(backend.deleteFolder).toHaveBeenCalledWith(1))
    await waitFor(() => expect(screen.queryByRole('treeitem', { name: '風景' })).not.toBeInTheDocument())
    expect(screen.getByLabelText('「人物」の新しい名前')).toBeInTheDocument()
  })

  it('親が一覧に無いフォルダだけでも、ツリーを描いて選択できる', () => {
    render(<ImageGalleryGrid images={[image(1)]} folders={[{ id: 5, name: '孤児', parentId: 99 }]} timezone={null} />)

    expect(screen.getByRole('treeitem', { name: 'すべて' })).toBeInTheDocument()
    expect(screen.getByRole('treeitem', { name: '未分類' })).toBeInTheDocument()
    expect(screen.queryByRole('treeitem', { name: '孤児' })).not.toBeInTheDocument()
  })
})
