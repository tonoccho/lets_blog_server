import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ImageGalleryGrid } from '../ImageGalleryGrid'
import * as actions from '../actions'
import type { GeneratedImageSummary } from '@/lib/apiClient'

/**
 * issue #1492: ギャラリーでの複数選択と一括削除。
 * 選択は現在表示中の画像が対象。確認ダイアログに件数を出し、結果(成功/失敗件数)を表示する。
 */
jest.mock('../actions', () => ({
  getGeneratedImageAction: jest.fn(),
  deleteGeneratedImageAction: jest.fn(),
  updateGeneratedImageTagsAction: jest.fn(),
  fetchGalleryImagesPageAction: jest.fn(),
  bulkDeleteGeneratedImagesAction: jest.fn(),
}))

const bulkDelete = actions.bulkDeleteGeneratedImagesAction as jest.Mock
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
    folderId: null,
  }
}

const IMAGES = [image(3), image(2), image(1)]

function renderGrid(images = IMAGES) {
  return render(<ImageGalleryGrid images={images} timezone="Asia/Tokyo" />)
}

function check(id: number) {
  fireEvent.click(screen.getByLabelText(`prompt-${id}を選択`))
}

describe('ImageGalleryGrid 複数選択と一括削除 (issue #1492)', () => {
  let confirmSpy: jest.SpyInstance

  beforeEach(() => {
    jest.clearAllMocks()
    confirmSpy = jest.spyOn(window, 'confirm').mockReturnValue(true)
  })

  afterEach(() => confirmSpy.mockRestore())

  it('各サムネイルにチェックボックスがあり、チェックすると選択件数が表示される', () => {
    renderGrid()
    expect(screen.getByText('0件選択中')).toBeInTheDocument()

    check(1)
    check(2)

    expect(screen.getByText('2件選択中')).toBeInTheDocument()
    expect(screen.getByLabelText('prompt-1を選択')).toBeChecked()
    expect(screen.getByLabelText('prompt-3を選択')).not.toBeChecked()
  })

  it('チェックをもう一度押すと選択が外れる', () => {
    renderGrid()
    check(1)
    check(1)
    expect(screen.getByText('0件選択中')).toBeInTheDocument()
  })

  it('チェックボックスを押しても詳細モーダルは開かない', () => {
    renderGrid()
    check(1)
    expect(screen.queryByText('生成画像の詳細')).not.toBeInTheDocument()
  })

  it('全選択で表示中の全画像を選び、全選択解除で全て外す', () => {
    renderGrid()

    fireEvent.click(screen.getByRole('button', { name: '全選択' }))
    expect(screen.getByText('3件選択中')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '全選択解除' }))
    expect(screen.getByText('0件選択中')).toBeInTheDocument()
  })

  it('一部だけ選択しているときの全選択は残りも選ぶ', () => {
    renderGrid()
    check(1)
    fireEvent.click(screen.getByRole('button', { name: '全選択' }))
    expect(screen.getByText('3件選択中')).toBeInTheDocument()
  })

  it('0件選択のときは削除ボタンが押せず、確認も出ない', () => {
    renderGrid()
    const button = screen.getByRole('button', { name: '選択した0件を削除' })
    expect(button).toBeDisabled()
    fireEvent.click(button)
    expect(confirmSpy).not.toHaveBeenCalled()
  })

  it('画像が1枚も無いときは選択UIを出さない', () => {
    renderGrid([])
    expect(screen.queryByRole('button', { name: '全選択' })).not.toBeInTheDocument()
  })

  it('確認ダイアログに件数を出し、キャンセルすると削除しない', () => {
    confirmSpy.mockReturnValue(false)
    renderGrid()
    check(1)
    check(2)

    fireEvent.click(screen.getByRole('button', { name: '選択した2件を削除' }))

    expect(confirmSpy).toHaveBeenCalledWith(
      '2件の生成画像を削除します。この操作は元に戻せません。よろしいですか?',
    )
    expect(bulkDelete).not.toHaveBeenCalled()
    expect(screen.getByAltText('prompt-1')).toBeInTheDocument()
  })

  it('承諾すると選択した画像だけを削除して一覧から消し、成功件数を表示する', async () => {
    bulkDelete.mockResolvedValue({ deletedCount: 2, failedCount: 0, deletedIds: [1, 2], failures: {} })
    renderGrid()
    check(1)
    check(2)

    fireEvent.click(screen.getByRole('button', { name: '選択した2件を削除' }))

    await waitFor(() => expect(screen.queryByAltText('prompt-1')).not.toBeInTheDocument())
    expect(bulkDelete).toHaveBeenCalledWith([1, 2])
    expect(screen.queryByAltText('prompt-2')).not.toBeInTheDocument()
    expect(screen.getByAltText('prompt-3')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('2件を削除しました')
    expect(screen.getByText('0件選択中')).toBeInTheDocument()
  })

  it('一部の削除に失敗したときは失敗件数も示し、失敗した画像は一覧と選択に残る', async () => {
    bulkDelete.mockResolvedValue({
      deletedCount: 1,
      failedCount: 1,
      deletedIds: [2],
      failures: { '1': 'disk error' },
    })
    renderGrid()
    check(1)
    check(2)

    fireEvent.click(screen.getByRole('button', { name: '選択した2件を削除' }))

    await waitFor(() => expect(screen.queryByAltText('prompt-2')).not.toBeInTheDocument())
    expect(screen.getByRole('status')).toHaveTextContent('1件を削除しました。1件の削除に失敗しました')
    expect(screen.getByAltText('prompt-1')).toBeInTheDocument()
    expect(screen.getByText('1件選択中')).toBeInTheDocument()
  })

  it('削除要求が失敗したらエラーを表示し、一覧は変えない', async () => {
    bulkDelete.mockRejectedValue(new Error('403 Forbidden'))
    renderGrid()
    check(1)

    fireEvent.click(screen.getByRole('button', { name: '選択した1件を削除' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('403 Forbidden'))
    expect(screen.getByAltText('prompt-1')).toBeInTheDocument()
    expect(screen.getByText('1件選択中')).toBeInTheDocument()
  })

  it('Error以外が投げられても文字列として表示する', async () => {
    bulkDelete.mockRejectedValue('boom')
    renderGrid()
    check(1)

    fireEvent.click(screen.getByRole('button', { name: '選択した1件を削除' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('boom'))
  })

  it('タグで絞り込み直すと、前の選択は引き継がない', async () => {
    fetchPage.mockResolvedValue([image(5, ['猫'])])
    renderGrid([image(5, ['猫']), image(4)])
    check(4)
    expect(screen.getByText('1件選択中')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '猫' }))

    await waitFor(() => expect(screen.getByText('0件選択中')).toBeInTheDocument())
  })

  it('削除中は削除ボタンを無効にして二重送信を防ぐ', async () => {
    let resolve!: (value: unknown) => void
    bulkDelete.mockReturnValue(new Promise((r) => (resolve = r)))
    renderGrid()
    check(1)

    fireEvent.click(screen.getByRole('button', { name: '選択した1件を削除' }))

    await waitFor(() => expect(screen.getByRole('button', { name: '削除中…' })).toBeDisabled())
    resolve({ deletedCount: 1, failedCount: 0, deletedIds: [1], failures: {} })
    await waitFor(() => expect(screen.queryByAltText('prompt-1')).not.toBeInTheDocument())
  })

  it('選択済みの画像を詳細モーダルから単体削除すると、選択からも外れる', async () => {
    ;(actions.deleteGeneratedImageAction as jest.Mock).mockResolvedValue(undefined)
    ;(actions.getGeneratedImageAction as jest.Mock).mockResolvedValue({
      ...image(1),
      negativePrompt: null,
    })
    renderGrid()
    check(1)
    expect(screen.getByText('1件選択中')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'prompt-1の詳細を表示' }))
    await waitFor(() => expect(screen.getByText('削除')).toBeInTheDocument())
    fireEvent.click(screen.getByText('削除'))

    await waitFor(() => expect(screen.queryByAltText('prompt-1')).not.toBeInTheDocument())
    expect(screen.getByText('0件選択中')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '選択した0件を削除' })).toBeDisabled()

    // 以降の一括削除に、既に消えた id を混ぜない。
    bulkDelete.mockResolvedValue({ deletedCount: 1, failedCount: 0, deletedIds: [2], failures: {} })
    check(2)
    fireEvent.click(screen.getByRole('button', { name: '選択した1件を削除' }))
    await waitFor(() => expect(bulkDelete).toHaveBeenCalledWith([2]))
  })
})
