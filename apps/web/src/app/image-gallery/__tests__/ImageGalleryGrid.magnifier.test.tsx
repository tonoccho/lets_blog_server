import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { ImageGalleryGrid } from '../ImageGalleryGrid'
import * as actions from '../actions'
import type { GeneratedImageDetail, GeneratedImageSummary } from '@/lib/apiClient'

/**
 * issue #1614: 詳細は各カード右上の虫眼鏡ボタンから開き、カード本体のクリックは選択の切り替えにする。
 */
jest.mock('../actions', () => ({
  getGeneratedImageAction: jest.fn(),
  deleteGeneratedImageAction: jest.fn(),
  updateGeneratedImageTagsAction: jest.fn(),
  fetchGalleryImagesPageAction: jest.fn(),
  bulkDeleteGeneratedImagesAction: jest.fn(),
}))

const getDetail = actions.getGeneratedImageAction as jest.Mock

function image(id: number, prompt: string | null = `prompt-${id}`): GeneratedImageSummary {
  return {
    id,
    projectId: 1,
    prompt,
    checkpoint: 'm.safetensors',
    createdAt: '2026-08-01T00:00:00Z',
    tags: [],
    provider: prompt === null ? 'UPLOAD' : 'COMFYUI',
    folderId: null,
  }
}

function detail(id: number, prompt: string | null): GeneratedImageDetail {
  return {
    ...image(id, prompt),
    negativePrompt: '',
    steps: 20,
    cfgScale: 7,
    samplerName: 'euler',
    scheduler: 'normal',
    seed: 1,
    width: 512,
    height: 512,
    batchSize: 1,
    batchIndex: 0,
    loraName: null,
    loraWeight: null,
  }
}

function renderGrid(images = [image(2), image(1)]) {
  return render(<ImageGalleryGrid images={images} timezone="Asia/Tokyo" />)
}

describe('ImageGalleryGrid 虫眼鏡ボタンとカード選択 (issue #1614)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    getDetail.mockImplementation(async (id: number) => detail(id, `prompt-${id}`))
  })

  it('各カードに「<画像名>の詳細を表示」ボタンが常に表示される', () => {
    renderGrid()
    expect(screen.getByRole('button', { name: 'prompt-1の詳細を表示' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'prompt-2の詳細を表示' })).toBeVisible()
  })

  it('プロンプトの無いアップロード画像は「アップロード画像の詳細を表示」になる', () => {
    renderGrid([image(5, null)])
    expect(screen.getByRole('button', { name: 'アップロード画像の詳細を表示' })).toBeVisible()
  })

  it('虫眼鏡ボタンを押すと詳細モーダルが開き、選択件数は変わらない', async () => {
    renderGrid()
    fireEvent.click(screen.getByRole('button', { name: 'prompt-1の詳細を表示' }))
    await waitFor(() => expect(screen.getByText('生成画像の詳細')).toBeInTheDocument())
    expect(getDetail).toHaveBeenCalledWith(1)
    expect(screen.getByText('0件選択中')).toBeInTheDocument()
    expect(screen.getByLabelText('prompt-1を選択')).not.toBeChecked()
  })

  it('選択済みの画像の虫眼鏡ボタンを押しても選択は外れない', async () => {
    renderGrid()
    fireEvent.click(screen.getByLabelText('prompt-1を選択'))
    fireEvent.click(screen.getByRole('button', { name: 'prompt-1の詳細を表示' }))
    await waitFor(() => expect(screen.getByText('生成画像の詳細')).toBeInTheDocument())
    expect(screen.getByText('1件選択中')).toBeInTheDocument()
    expect(screen.getByLabelText('prompt-1を選択')).toBeChecked()
  })

  it('サムネイルをクリックすると選択が切り替わり、詳細モーダルは開かない', () => {
    renderGrid()
    fireEvent.click(screen.getByAltText('prompt-1'))
    expect(screen.getByText('1件選択中')).toBeInTheDocument()
    expect(screen.getByLabelText('prompt-1を選択')).toBeChecked()
    expect(screen.queryByText('生成画像の詳細')).not.toBeInTheDocument()
    expect(getDetail).not.toHaveBeenCalled()

    fireEvent.click(screen.getByAltText('prompt-1'))
    expect(screen.getByText('0件選択中')).toBeInTheDocument()
    expect(screen.getByLabelText('prompt-1を選択')).not.toBeChecked()
  })

  it('promptと日時の部分のクリックでも選択が切り替わる', () => {
    renderGrid()
    const card = screen.getByAltText('prompt-2').closest('li, div[class*="relative"]') as HTMLElement
    fireEvent.click(within(card).getByText('prompt-2'))
    expect(screen.getByText('1件選択中')).toBeInTheDocument()
    fireEvent.click(within(card).getByText(/2026/))
    expect(screen.getByText('0件選択中')).toBeInTheDocument()
  })

  it('チェックボックスのクリックは1回で1件だけ増減する(カードの選択と二重に切り替わらない)', () => {
    renderGrid()
    fireEvent.click(screen.getByLabelText('prompt-1を選択'))
    expect(screen.getByText('1件選択中')).toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('prompt-1を選択'))
    expect(screen.getByText('0件選択中')).toBeInTheDocument()
  })

  it('カードの外側はボタンではなく、ボタンの中にボタンが入らない', () => {
    renderGrid()
    const img = screen.getByAltText('prompt-1')
    expect(img.closest('button')).toBeNull()
  })
})
