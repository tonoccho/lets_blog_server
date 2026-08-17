import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { ImageGalleryGrid } from '../ImageGalleryGrid'
import * as actions from '../actions'
import type { GeneratedImageDetail, GeneratedImageSummary } from '@/lib/apiClient'

jest.mock('../actions', () => ({
  getGeneratedImageAction: jest.fn(),
  deleteGeneratedImageAction: jest.fn(),
  updateGeneratedImageTagsAction: jest.fn(),
}))

const SUMMARY: GeneratedImageSummary = {
  id: 1,
  projectId: 2,
  prompt: 'a cute cat',
  checkpoint: 'model.safetensors',
  createdAt: '2026-08-01T00:00:00Z',
  tags: [],
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
