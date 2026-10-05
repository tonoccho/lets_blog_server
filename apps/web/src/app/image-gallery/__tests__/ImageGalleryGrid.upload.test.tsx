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

const UPLOADED: GeneratedImageSummary = {
  id: 7,
  projectId: 2,
  prompt: null,
  checkpoint: null,
  createdAt: '2026-10-04T00:00:00Z',
  tags: [],
  provider: 'UPLOAD',
  folderId: null,
}

const UPLOADED_DETAIL = {
  ...UPLOADED,
  negativePrompt: null,
  steps: null,
  cfgScale: null,
  samplerName: null,
  scheduler: null,
  seed: null,
  width: 1920,
  height: 1080,
  batchSize: null,
  batchIndex: null,
  loraName: null,
  loraWeight: null,
} as unknown as GeneratedImageDetail

describe('ImageGalleryGrid アップロード画像の表示(issue #1599)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.getGeneratedImageAction as jest.Mock).mockResolvedValue(UPLOADED_DETAIL)
  })

  it('promptが無いUPLOADの画像は「アップロード画像」と識別できる表示になる', () => {
    render(<ImageGalleryGrid images={[UPLOADED]} timezone={null} />)

    expect(screen.getByAltText('アップロード画像')).toBeInTheDocument()
    expect(screen.getByText('アップロード画像')).toBeInTheDocument()
    expect(screen.getByLabelText('アップロード画像を選択')).toBeInTheDocument()
  })

  it('詳細では画像生成AIの欄が「アップロード」になる', async () => {
    render(<ImageGalleryGrid images={[UPLOADED]} timezone={null} />)

    fireEvent.click(screen.getByRole('button', { name: 'アップロード画像の詳細を表示' }))

    await waitFor(() => {
      expect(screen.getByText('アップロード', { selector: 'dd' })).toBeInTheDocument()
    })
  })

  it('promptがある生成画像はこれまでどおりpromptを表示する', () => {
    render(<ImageGalleryGrid images={[{ ...UPLOADED, id: 8, prompt: 'a cat', provider: 'COMFYUI' }]} timezone={null} />)

    expect(screen.getByAltText('a cat')).toBeInTheDocument()
    expect(screen.queryByText('アップロード画像')).not.toBeInTheDocument()
  })
})
