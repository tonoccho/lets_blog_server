import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ImageGalleryGrid } from '../ImageGalleryGrid'
import * as actions from '../actions'
import type { GeneratedImageDetail, GeneratedImageSummary } from '@/lib/apiClient'

/** issue #1655: 詳細モーダルの「編集」から編集画面を開き、保存すると一覧を取り直す。 */
jest.mock('../actions', () => ({
  getGeneratedImageAction: jest.fn(),
  deleteGeneratedImageAction: jest.fn(),
  updateGeneratedImageTagsAction: jest.fn(),
  fetchGalleryImagesPageAction: jest.fn(),
  editGeneratedImageAction: jest.fn(),
}))

const SUMMARY: GeneratedImageSummary = {
  id: 1,
  projectId: 2,
  prompt: null,
  checkpoint: null,
  createdAt: '2026-08-01T00:00:00Z',
  tags: [],
  provider: 'UPLOAD',
  folderId: null,
} as unknown as GeneratedImageSummary

const DETAIL = {
  ...SUMMARY,
  negativePrompt: null,
  width: 400,
  height: 200,
  sourceImageId: null,
} as unknown as GeneratedImageDetail

const fetchPage = actions.fetchGalleryImagesPageAction as jest.Mock
const edit = actions.editGeneratedImageAction as jest.Mock

async function openDetail() {
  render(<ImageGalleryGrid images={[SUMMARY]} timezone={null} />)
  fireEvent.click(screen.getByRole('button', { name: 'アップロード画像の詳細を表示' }))
  await screen.findByRole('button', { name: '編集' })
}

beforeEach(() => {
  jest.clearAllMocks()
  ;(actions.getGeneratedImageAction as jest.Mock).mockResolvedValue(DETAIL)
  fetchPage.mockResolvedValue([{ ...SUMMARY, id: 2 }, SUMMARY])
})

describe('ImageGalleryGrid 画像の編集(issue #1655)', () => {
  it('詳細の「編集」で編集画面が開き、キャンセルで何も保存せず閉じる', async () => {
    await openDetail()

    fireEvent.click(screen.getByRole('button', { name: '編集' }))
    expect(screen.getByRole('heading', { name: '画像を編集' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'キャンセル' }))

    expect(screen.queryByRole('heading', { name: '画像を編集' })).not.toBeInTheDocument()
    expect(edit).not.toHaveBeenCalled()
    expect(fetchPage).not.toHaveBeenCalled()
  })

  it('保存すると、新しい画像として保存した旨を示し、一覧を取り直す', async () => {
    edit.mockResolvedValue({ ...DETAIL, id: 2, width: 200, height: 400 })
    await openDetail()

    fireEvent.click(screen.getByRole('button', { name: '編集' }))
    fireEvent.click(screen.getByRole('button', { name: '右に90°回転' }))
    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(fetchPage).toHaveBeenCalledWith(0, null, null, null))
    expect(edit).toHaveBeenCalledWith(1, ['ROTATE_CW'], null)
    expect(await screen.findByText('新しい画像として保存しました(ID 2)')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: '画像を編集' })).not.toBeInTheDocument()
  })

  it('詳細を閉じると、保存した旨の表示も消える', async () => {
    edit.mockResolvedValue({ ...DETAIL, id: 2 })
    await openDetail()
    fireEvent.click(screen.getByRole('button', { name: '編集' }))
    fireEvent.click(screen.getByRole('button', { name: '上下反転' }))
    fireEvent.click(screen.getByRole('button', { name: '保存' }))
    await screen.findByText('新しい画像として保存しました(ID 2)')

    fireEvent.click(screen.getByRole('button', { name: '閉じる' }))

    expect(screen.queryByText('新しい画像として保存しました(ID 2)')).not.toBeInTheDocument()
  })
})
