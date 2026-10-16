import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { ImageGalleryGrid } from '../ImageGalleryGrid'
import { backend, installGallery } from './galleryBackend'
import type { GeneratedImageFolder, GeneratedImageSummary } from '@/lib/apiClient'

/** issue #1616: カード上のタグを押すと、上部チップと同じサーバ側絞り込みになる。 */
/**
 * 自前の Server Action(../actions)・@/lib/apiClient・@/lib/session は差し替えない。差し替えるのは Next.js の実行基盤と
 * 外部との通信(fetch。media-service の代役は ./galleryBackend の backend)だけ(#1834)。
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

const fetchPage = backend.listImages
const getDetail = backend.getImage

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

const FOLDERS: GeneratedImageFolder[] = [{ id: 1, name: '風景', parentId: null }]

function renderGrid(images: GeneratedImageSummary[]) {
  return render(<ImageGalleryGrid images={images} folders={FOLDERS} timezone="Asia/Tokyo" />)
}

function cardTag(tag: string) {
  return screen.getAllByRole('button', { name: `タグ「${tag}」で絞り込む` })[0]
}

beforeEach(() => {
  installGallery()
  fetchPage.mockReset()
  ;(global as unknown as { IntersectionObserver: unknown }).IntersectionObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
    takeRecords() {
      return []
    }
  }
})

describe('カード上のタグで絞り込む', () => {
  it('タグを押すとサーバ側の結果(初回に無い画像を含む)に置き換わり、上部チップが選択表示になる', async () => {
    fetchPage.mockResolvedValueOnce([image(2, ['猫']), image(99, ['猫'])])
    renderGrid([image(1, ['猫']), image(3, ['犬'])])

    fireEvent.click(cardTag('猫'))

    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, '猫', null, null))
    expect(await screen.findByAltText('prompt-99')).toBeInTheDocument()
    expect(screen.queryByAltText('prompt-3')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '猫' })).toHaveClass('bg-neutral-900')
  })

  it('フォルダで絞り込んだ状態でタグを押すと、フォルダとタグの両方で取り直す', async () => {
    fetchPage.mockResolvedValue([image(1, ['猫'], 1)])
    renderGrid([image(1, ['猫'], 1)])

    fireEvent.click(within(screen.getByRole('treeitem', { name: '風景' })).getAllByRole('button', { name: '風景' })[0])
    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, null, 1, null))
    await waitFor(() => expect(screen.getByRole('treeitem', { name: '風景' })).toHaveAttribute('aria-selected', 'true'))

    fireEvent.click(cardTag('猫'))

    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, '猫', 1, null))
  })

  it('タグを押しても詳細モーダルは開かず、選択件数も変わらない', async () => {
    fetchPage.mockResolvedValue([image(1, ['猫'])])
    renderGrid([image(1, ['猫'])])

    fireEvent.click(cardTag('猫'))
    await waitFor(() => expect(fetchPage).toHaveBeenCalled())

    expect(getDetail).not.toHaveBeenCalled()
    expect(screen.queryByText('生成画像の詳細')).not.toBeInTheDocument()
    expect(screen.getByLabelText('prompt-1を選択')).not.toBeChecked()
    expect(screen.getByText('0件選択中')).toBeInTheDocument()
  })

  it('すでに絞り込み中のタグを押しても絞り込みは維持される(解除しない)', async () => {
    fetchPage.mockResolvedValue([image(1, ['猫'])])
    renderGrid([image(1, ['猫'])])

    fireEvent.click(cardTag('猫'))
    await waitFor(() => expect(screen.getByRole('button', { name: '猫' })).toHaveClass('bg-neutral-900'))

    fireEvent.click(cardTag('猫'))

    await waitFor(() => expect(fetchPage).toHaveBeenCalledTimes(2))
    expect(fetchPage).toHaveBeenLastCalledWith(0, '猫', null, null)
    expect(screen.getByRole('button', { name: '猫' })).toHaveClass('bg-neutral-900')
  })
})
