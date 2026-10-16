import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ImageGalleryGrid } from '../ImageGalleryGrid'
import { backend, installGallery } from './galleryBackend'
import type { GeneratedImageSummary } from '@/lib/apiClient'

/**
 * issue #1615: Shift+クリックで、起点から表示順に範囲選択する。
 * 受け入れ基準は共有ギャラリー上では並列シナリオの画像が混ざり連続範囲が決まらないため、サービス単体のテストで表す。
 */
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
const bulkDelete = backend.bulkDelete

function image(id: number, tags: string[] = []): GeneratedImageSummary {
  return {
    id,
    projectId: 1,
    prompt: `prompt-${id}`,
    checkpoint: 'm.safetensors',
    createdAt: '2026-08-01T00:00:00Z',
    tags,
    provider: 'COMFYUI',
    folderId: null,
  }
}

// 表示順は 6,5,4,3,2,1
const IMAGES = [6, 5, 4, 3, 2, 1].map((id) => image(id, id === 1 ? ['x'] : []))

function renderGrid(images = IMAGES) {
  return render(<ImageGalleryGrid images={images} timezone="Asia/Tokyo" />)
}
const box = (id: number) => screen.getByLabelText(`prompt-${id}を選択`)
const click = (id: number) => fireEvent.click(box(id))
const shiftClick = (id: number) => fireEvent.click(box(id), { shiftKey: true })
const checkedIds = () => [1, 2, 3, 4, 5, 6].filter((id) => (box(id) as HTMLInputElement).checked)

describe('ImageGalleryGrid Shift+クリックの範囲選択 (issue #1615)', () => {
  beforeEach(() => {
    installGallery()
    jest.spyOn(window, 'confirm').mockReturnValue(true)
  })
  afterEach(() => jest.restoreAllMocks())

  it('画像1を選んだ後に画像4をShift+クリックすると1〜4が選択される', () => {
    renderGrid()
    click(1)
    shiftClick(4)
    expect(checkedIds()).toEqual([1, 2, 3, 4])
    expect(screen.getByText('4件選択中')).toBeInTheDocument()
  })

  it('逆向き(画像4を起点に画像1)でも1〜4が選択される', () => {
    renderGrid()
    click(4)
    shiftClick(1)
    expect(checkedIds()).toEqual([1, 2, 3, 4])
  })

  it('起点が解除状態なら範囲を解除し、範囲外の選択は残る', () => {
    renderGrid()
    click(6)
    click(1)
    shiftClick(4)
    expect(checkedIds()).toEqual([1, 2, 3, 4, 6])
    click(1) // 解除して起点にする
    shiftClick(3)
    expect(checkedIds()).toEqual([4, 6])
  })

  it('起点が無いときのShift+クリックは1枚だけを切り替え、その画像が起点になる', () => {
    renderGrid()
    shiftClick(3)
    expect(checkedIds()).toEqual([3])
    shiftClick(5)
    expect(checkedIds()).toEqual([3, 4, 5])
  })

  it('Shift+クリックの範囲選択後も、起点は最後にShiftなしで切り替えた画像のまま', () => {
    renderGrid()
    click(1)
    shiftClick(3)
    shiftClick(5)
    expect(checkedIds()).toEqual([1, 2, 3, 4, 5])
  })

  it('カード本体のShift+クリックにも適用される', () => {
    renderGrid()
    click(1)
    fireEvent.click(screen.getByAltText('prompt-4'), { shiftKey: true })
    expect(checkedIds()).toEqual([1, 2, 3, 4])
  })

  it('Shift+クリックでは文字選択を起こさないよう mousedown を打ち消す', () => {
    renderGrid()
    const card = screen.getByAltText('prompt-4')
    expect(fireEvent.mouseDown(card, { shiftKey: true })).toBe(false)
    expect(fireEvent.mouseDown(card)).toBe(true)
  })

  it('絞り込みを変えると起点がなくなり、最初のShift+クリックはその1枚だけを切り替える', async () => {
    fetchPage.mockResolvedValue([image(6), image(5), image(4), image(3)])
    renderGrid()
    click(1)
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'x' }))
    })
    await waitFor(() => expect(screen.queryByLabelText('prompt-1を選択')).not.toBeInTheDocument())
    shiftClick(3)
    expect([3, 4, 5, 6].filter((id) => (box(id) as HTMLInputElement).checked)).toEqual([3])
  })

  it('起点の画像が削除で一覧から消えたら起点がなくなる', async () => {
    bulkDelete.mockResolvedValue({ deletedCount: 1, failedCount: 0, deletedIds: [1], failures: {} })
    renderGrid()
    click(1)
    fireEvent.click(screen.getByRole('button', { name: /選択した1件を削除/ }))
    await waitFor(() => expect(screen.queryByLabelText('prompt-1を選択')).not.toBeInTheDocument())
    shiftClick(4)
    expect([2, 3, 4, 5, 6].filter((id) => (box(id) as HTMLInputElement).checked)).toEqual([4])
  })
})
