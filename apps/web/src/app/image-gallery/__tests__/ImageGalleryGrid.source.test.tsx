import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { ImageGalleryGrid } from '../ImageGalleryGrid'
import * as actions from '../actions'
import { GALLERY_PAGE_SIZE } from '../pageSize'
import type { GeneratedImageFolder, GeneratedImageSummary } from '@/lib/apiClient'

/** issue #1647: 種別(アップロード / AI生成)の絞り込みチップ。サーバ側で絞り、続きのページにも引き継ぐ。 */
jest.mock('../actions', () => ({
  getGeneratedImageAction: jest.fn(),
  deleteGeneratedImageAction: jest.fn(),
  updateGeneratedImageTagsAction: jest.fn(),
  fetchGalleryImagesPageAction: jest.fn(),
  bulkDeleteGeneratedImagesAction: jest.fn(),
  createGeneratedImageFolderAction: jest.fn(),
  setGeneratedImageFolderAction: jest.fn(),
}))

const fetchPage = actions.fetchGalleryImagesPageAction as jest.Mock

function image(id: number, tags: string[] = [], provider = 'COMFYUI'): GeneratedImageSummary {
  return {
    id,
    projectId: 1,
    prompt: `prompt-${id}`,
    checkpoint: 'm.safetensors',
    createdAt: '2026-08-01T00:00:00Z',
    tags,
    provider,
    folderId: null,
  }
}

function page(from: number, count: number, tags: string[] = []): GeneratedImageSummary[] {
  return Array.from({ length: count }, (_, i) => image(from - i, tags))
}

const FOLDERS: GeneratedImageFolder[] = [{ id: 1, name: '風景', parentId: null }]

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

function sourceChip(name: 'すべて' | 'アップロード' | 'AI生成') {
  return within(screen.getByRole('group', { name: '種別で絞り込み' })).getByRole('button', { name })
}

function renderGrid(images: GeneratedImageSummary[]) {
  return render(<ImageGalleryGrid images={images} folders={FOLDERS} timezone="Asia/Tokyo" />)
}

beforeEach(() => {
  jest.clearAllMocks()
  fetchPage.mockReset()
  FakeObserver.instances = []
  ;(global as unknown as { IntersectionObserver: unknown }).IntersectionObserver = FakeObserver
})

describe('種別の絞り込みチップ', () => {
  it('画像がタグを持たなくても「すべて」「アップロード」「AI生成」が表示され、初期は「すべて」が選択表示', () => {
    renderGrid([image(1)])

    expect(sourceChip('すべて')).toHaveClass('bg-neutral-900')
    expect(sourceChip('アップロード')).not.toHaveClass('bg-neutral-900')
    expect(sourceChip('AI生成')).not.toHaveClass('bg-neutral-900')
    expect(fetchPage).not.toHaveBeenCalled()
  })

  it('「アップロード」を押すとサーバ側の結果に置き換わり、選択表示になる', async () => {
    fetchPage.mockResolvedValueOnce([image(50, [], 'UPLOAD')])
    renderGrid([image(1), image(2, [], 'UPLOAD')])

    fireEvent.click(sourceChip('アップロード'))

    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, null, null, 'UPLOAD'))
    expect(await screen.findByAltText('prompt-50')).toBeInTheDocument()
    expect(screen.queryByAltText('prompt-1')).not.toBeInTheDocument()
    expect(sourceChip('アップロード')).toHaveClass('bg-neutral-900')
    expect(sourceChip('すべて')).not.toHaveClass('bg-neutral-900')
  })

  it('「AI生成」を押すと source=AI で取り直し、「すべて」で絞り込みなしに戻る', async () => {
    fetchPage.mockResolvedValue([image(1)])
    renderGrid([image(1)])

    fireEvent.click(sourceChip('AI生成'))
    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, null, null, 'AI'))
    await waitFor(() => expect(sourceChip('AI生成')).toHaveClass('bg-neutral-900'))

    fireEvent.click(sourceChip('すべて'))
    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, null, null, null))
    await waitFor(() => expect(sourceChip('すべて')).toHaveClass('bg-neutral-900'))
  })

  it('種別で絞り込んでも、タグのチップは消えない(絞り込み前に読み込んだ画像から作る)', async () => {
    fetchPage.mockResolvedValueOnce([image(9, ['別タグ'], 'UPLOAD')])
    renderGrid([image(1, ['猫']), image(2, ['犬'])])

    fireEvent.click(sourceChip('アップロード'))
    await screen.findByAltText('prompt-9')

    expect(screen.getByRole('button', { name: '猫' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '犬' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '別タグ' })).not.toBeInTheDocument()
  })

  it('種別を選んだ状態でタグを選ぶと、種別も付けて取り直す', async () => {
    fetchPage.mockResolvedValue([image(1, ['猫'])])
    renderGrid([image(1, ['猫'])])

    fireEvent.click(sourceChip('AI生成'))
    await waitFor(() => expect(sourceChip('AI生成')).toHaveClass('bg-neutral-900'))
    fireEvent.click(screen.getByRole('button', { name: '猫' }))

    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, '猫', null, 'AI'))
  })

  it('種別を選んだ状態でカード上のタグを押しても、種別を維持する', async () => {
    fetchPage.mockResolvedValue([image(1, ['猫'])])
    renderGrid([image(1, ['猫'])])

    fireEvent.click(sourceChip('アップロード'))
    await waitFor(() => expect(sourceChip('アップロード')).toHaveClass('bg-neutral-900'))
    fireEvent.click(screen.getAllByRole('button', { name: 'タグ「猫」で絞り込む' })[0])

    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, '猫', null, 'UPLOAD'))
  })

  it('タグを選んだ状態で種別を選ぶと、タグも付けて取り直す', async () => {
    fetchPage.mockResolvedValue([image(1, ['猫'])])
    renderGrid([image(1, ['猫'])])

    fireEvent.click(screen.getByRole('button', { name: '猫' }))
    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, '猫', null, null))
    await waitFor(() => expect(screen.getByRole('button', { name: '猫' })).toHaveClass('bg-neutral-900'))
    fireEvent.click(sourceChip('アップロード'))

    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, '猫', null, 'UPLOAD'))
  })

  it('種別を選んだ状態でフォルダを選ぶと、種別も付けて取り直す', async () => {
    fetchPage.mockResolvedValue([image(1)])
    renderGrid([image(1)])

    fireEvent.click(sourceChip('AI生成'))
    await waitFor(() => expect(sourceChip('AI生成')).toHaveClass('bg-neutral-900'))
    fireEvent.click(within(screen.getByRole('treeitem', { name: '風景' })).getAllByRole('button', { name: '風景' })[0])

    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, null, 1, 'AI'))
  })

  it('続きの読み込みにも種別が引き継がれ、続きの画像でタグのチップは増えない', async () => {
    fetchPage.mockResolvedValueOnce(page(500, GALLERY_PAGE_SIZE, ['猫']))
    fetchPage.mockResolvedValueOnce([image(1, ['続きタグ'], 'UPLOAD')])
    renderGrid([image(900, ['猫'])])

    fireEvent.click(sourceChip('アップロード'))
    await waitFor(() => expect(sourceChip('アップロード')).toHaveClass('bg-neutral-900'))
    await waitFor(() => expect(screen.getAllByRole('img').length).toBeGreaterThan(GALLERY_PAGE_SIZE - 1))
    await scrollToEnd()

    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(GALLERY_PAGE_SIZE, null, null, 'UPLOAD'))
    await screen.findByAltText('prompt-1')
    expect(screen.queryByRole('button', { name: '続きタグ' })).not.toBeInTheDocument()
  })

  it('取得に失敗したら、読み込み済みの一覧と現在の種別を残す', async () => {
    fetchPage.mockRejectedValueOnce(new Error('落ちた'))
    renderGrid([image(1)])

    fireEvent.click(sourceChip('アップロード'))

    expect(await screen.findByText(/落ちた/)).toBeInTheDocument()
    expect(screen.getByAltText('prompt-1')).toBeInTheDocument()
    expect(sourceChip('すべて')).toHaveClass('bg-neutral-900')
  })
})
