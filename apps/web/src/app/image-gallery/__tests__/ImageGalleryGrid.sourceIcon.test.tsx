import { fireEvent, render, screen, within } from '@testing-library/react'
import { ImageGalleryGrid } from '../ImageGalleryGrid'
import * as actions from '../actions'
import type { GeneratedImageSummary } from '@/lib/apiClient'

/** issue #1646: カードのタイトル横に、アップロード / AI生成を示す種別アイコンを表示する。 */
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
const getDetail = actions.getGeneratedImageAction as jest.Mock

function image(id: number, provider: string, prompt: string | null = `prompt-${id}`, tags: string[] = []): GeneratedImageSummary {
  return {
    id,
    projectId: 1,
    prompt,
    checkpoint: 'm.safetensors',
    createdAt: '2026-08-01T00:00:00Z',
    tags,
    provider,
    folderId: null,
  }
}

function renderGrid(images: GeneratedImageSummary[]) {
  return render(<ImageGalleryGrid images={images} folders={[]} timezone="Asia/Tokyo" />)
}

/** タイトル(<p>)と同じ行に置かれた種別アイコン。 */
function iconOf(title: string) {
  const row = screen.getByText(title).parentElement as HTMLElement
  return within(row).queryAllByRole('img')
}

beforeEach(() => {
  jest.clearAllMocks()
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

describe('カードの種別アイコン', () => {
  it('UPLOAD はタイトルの横に「アップロード」のアイコンを表示し、title も同じ', () => {
    renderGrid([image(1, 'UPLOAD', null)])
    const icons = iconOf('アップロード画像')
    expect(icons).toHaveLength(1)
    expect(icons[0]).toHaveAccessibleName('アップロード')
    expect(icons[0]).toHaveAttribute('title', 'アップロード')
  })

  it('COMFYUI は「AI生成(ComfyUI)」、CHATGPT は「AI生成(ChatGPT)」で、アップロードのアイコンは出ない', () => {
    renderGrid([image(2, 'COMFYUI'), image(3, 'CHATGPT')])
    const comfy = iconOf('prompt-2')
    const chat = iconOf('prompt-3')
    expect(comfy).toHaveLength(1)
    expect(comfy[0]).toHaveAccessibleName('AI生成(ComfyUI)')
    expect(comfy[0]).toHaveAttribute('title', 'AI生成(ComfyUI)')
    expect(chat).toHaveLength(1)
    expect(chat[0]).toHaveAccessibleName('AI生成(ChatGPT)')
    expect(chat[0]).toHaveAttribute('title', 'AI生成(ChatGPT)')
    expect(screen.queryByRole('img', { name: 'アップロード' })).toBeNull()
  })

  it('未知の provider は UPLOAD 以外なので「AI生成」のアイコンになる', () => {
    renderGrid([image(4, 'FUTURE')])
    const icons = iconOf('prompt-4')
    expect(icons).toHaveLength(1)
    expect(icons[0]).toHaveAccessibleName('AI生成')
  })

  it('アイコンを足してもタイトルは line-clamp-2 のまま', () => {
    renderGrid([image(5, 'COMFYUI')])
    expect(screen.getByText('prompt-5')).toHaveClass('line-clamp-2')
  })
})

describe('種別アイコンを足しても既存の操作は変わらない', () => {
  it('カード本体のクリックで選択が切り替わり、アイコンのクリックも同じ', () => {
    renderGrid([image(6, 'UPLOAD', null)])
    const checkbox = screen.getByRole('checkbox', { name: 'アップロード画像を選択' })
    fireEvent.click(screen.getByAltText('アップロード画像'))
    expect(checkbox).toBeChecked()
    fireEvent.click(iconOf('アップロード画像')[0])
    expect(checkbox).not.toBeChecked()
  })

  it('虫眼鏡ボタンで詳細を開く', () => {
    getDetail.mockResolvedValue(null)
    renderGrid([image(7, 'COMFYUI')])
    fireEvent.click(screen.getByRole('button', { name: 'prompt-7の詳細を表示' }))
    expect(getDetail).toHaveBeenCalledWith(7)
  })

  it('タグボタンはカード選択を変えずに絞り込みを実行する', () => {
    fetchPage.mockResolvedValue([])
    renderGrid([image(8, 'COMFYUI', 'prompt-8', ['猫'])])
    fireEvent.click(screen.getByRole('button', { name: 'タグ「猫」で絞り込む' }))
    expect(screen.getByRole('checkbox', { name: 'prompt-8を選択' })).not.toBeChecked()
    expect(fetchPage).toHaveBeenCalled()
  })
})
