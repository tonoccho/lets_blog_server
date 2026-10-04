import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { ProjectAssetGenerationPanel } from '../ProjectAssetGenerationPanel'
import * as actions from '../actions'

jest.mock('../actions', () => ({
  fetchGeneratedImagesAction: jest.fn(),
  fetchImageGenerationOptionsAction: jest.fn(),
  generateImagePromptAction: jest.fn(),
  requestProjectImageJobAction: jest.fn(),
  fetchImageJobResultAction: jest.fn(),
  uploadProjectAssetImageAction: jest.fn(),
  uploadGeneratedImageAction: jest.fn(),
}))

const OPTIONS = {
  checkpoints: ['model.safetensors'],
  selectedCheckpoint: 'model.safetensors',
  samplers: ['euler'],
  schedulers: ['normal'],
  loras: [],
}

const summary = (id: number, projectId: number | null) => ({
  id,
  projectId,
  prompt: `image ${id}`,
  checkpoint: 'model.safetensors',
  createdAt: '2026-08-01T00:00:00Z',
  tags: [],
  provider: 'COMFYUI',
  folderId: null,
})

/** projectId=1 のパネルに対し、画像 11・12 はこのプロジェクト、99 は別プロジェクトのもの。 */
const GALLERY = [summary(11, 1), summary(99, 2), summary(12, 1)]

async function openPanel() {
  render(<ProjectAssetGenerationPanel projectId={1} />)
  fireEvent.click(screen.getByText('アセット画像生成'))
  await waitFor(() => {
    expect(screen.getByText('参照画像(img2img)')).toBeInTheDocument()
  })
}

function referenceSection() {
  return screen.getByText('参照画像(img2img)').closest('div[data-testid="reference-image-section"]') as HTMLElement
}

async function openReferencePicker() {
  fireEvent.click(within(referenceSection()).getByRole('button', { name: '参照画像を選ぶ' }))
  await waitFor(() => {
    expect(within(referenceSection()).queryByText('読み込んでいます…')).not.toBeInTheDocument()
  })
}

function fillPrompt() {
  fireEvent.change(screen.getByPlaceholderText('生成したい画像の説明'), { target: { value: 'a cat' } })
}

function generate() {
  fireEvent.click(screen.getByRole('button', { name: '生成' }))
}

function lastRequest(): Record<string, unknown> {
  const calls = (actions.requestProjectImageJobAction as jest.Mock).mock.calls
  return calls[calls.length - 1][1]
}

describe('ProjectAssetGenerationPanel 参照画像(img2img、issue #1601)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue(OPTIONS)
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue(GALLERY)
    ;(actions.requestProjectImageJobAction as jest.Mock).mockResolvedValue({ jobId: 5, status: 'running' })
  })

  it('参照画像を選ばなければ、生成要求に参照画像もdenoiseも含めない(従来のtxt2img)', async () => {
    await openPanel()
    fillPrompt()

    generate()

    await waitFor(() => expect(actions.requestProjectImageJobAction).toHaveBeenCalled())
    const body = lastRequest()
    expect(body.referenceImageId).toBeUndefined()
    expect(body.denoise).toBeUndefined()
  })

  it('参照画像の選択肢は、このプロジェクトのギャラリー画像だけを並べる', async () => {
    await openPanel()
    await openReferencePicker()

    const section = referenceSection()
    expect(within(section).getByAltText('image 11')).toBeInTheDocument()
    expect(within(section).getByAltText('image 12')).toBeInTheDocument()
    expect(within(section).queryByAltText('image 99')).not.toBeInTheDocument()
  })

  it('ギャラリーの画像を1枚選ぶと選択中として示され、生成要求に参照画像IDと既定のdenoise 0.6が載る', async () => {
    await openPanel()
    await openReferencePicker()

    fireEvent.click(within(referenceSection()).getByAltText('image 12'))

    expect(within(referenceSection()).getByText(/選択中の参照画像: ID 12/)).toBeInTheDocument()
    fillPrompt()
    generate()

    await waitFor(() => expect(actions.requestProjectImageJobAction).toHaveBeenCalled())
    expect(actions.requestProjectImageJobAction).toHaveBeenCalledWith(
      1,
      expect.objectContaining({ referenceImageId: 12, denoise: 0.6 })
    )
  })

  it('denoiseを変えると、その値が生成要求に載る', async () => {
    await openPanel()
    await openReferencePicker()
    fireEvent.click(within(referenceSection()).getByAltText('image 11'))

    fireEvent.change(screen.getByLabelText(/denoise/), { target: { value: '0.3' } })
    fillPrompt()
    generate()

    await waitFor(() => expect(actions.requestProjectImageJobAction).toHaveBeenCalled())
    expect(lastRequest().denoise).toBe(0.3)
  })

  it('denoiseを空にすると既定の0.6で送る', async () => {
    await openPanel()
    await openReferencePicker()
    fireEvent.click(within(referenceSection()).getByAltText('image 11'))

    fireEvent.change(screen.getByLabelText(/denoise/), { target: { value: '' } })
    fillPrompt()
    generate()

    await waitFor(() => expect(actions.requestProjectImageJobAction).toHaveBeenCalled())
    expect(lastRequest().denoise).toBe(0.6)
  })

  it('denoiseが0〜1の範囲外なら要求せず、範囲を示すエラーを表示する', async () => {
    await openPanel()
    await openReferencePicker()
    fireEvent.click(within(referenceSection()).getByAltText('image 11'))

    fireEvent.change(screen.getByLabelText(/denoise/), { target: { value: '1.5' } })
    fillPrompt()
    generate()

    await waitFor(() => {
      expect(screen.getByText('denoiseは0〜1の範囲で指定してください。')).toBeInTheDocument()
    })
    expect(actions.requestProjectImageJobAction).not.toHaveBeenCalled()
  })

  it('解除すると選択が消え、以後の生成要求に参照画像は載らない', async () => {
    await openPanel()
    await openReferencePicker()
    fireEvent.click(within(referenceSection()).getByAltText('image 11'))

    fireEvent.click(within(referenceSection()).getByRole('button', { name: '参照画像を解除' }))

    expect(within(referenceSection()).queryByText(/選択中の参照画像/)).not.toBeInTheDocument()
    fillPrompt()
    generate()
    await waitFor(() => expect(actions.requestProjectImageJobAction).toHaveBeenCalled())
    expect(lastRequest().referenceImageId).toBeUndefined()
    expect(lastRequest().denoise).toBeUndefined()
  })

  it('このプロジェクトに参照できる画像が無ければ、その旨を示す', async () => {
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue([summary(99, 2)])
    await openPanel()
    await openReferencePicker()

    expect(within(referenceSection()).getByText('このプロジェクトのギャラリーに参照できる画像がありません。')).toBeInTheDocument()
  })

  it('選択肢を閉じられる', async () => {
    await openPanel()
    await openReferencePicker()

    fireEvent.click(within(referenceSection()).getByRole('button', { name: '閉じる' }))

    expect(within(referenceSection()).queryByAltText('image 11')).not.toBeInTheDocument()
  })

  it('ギャラリーの取得に失敗したらエラーを表示する', async () => {
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockRejectedValue(new Error('取得に失敗しました'))
    await openPanel()
    fireEvent.click(within(referenceSection()).getByRole('button', { name: '参照画像を選ぶ' }))

    await waitFor(() => {
      expect(screen.getByText('取得に失敗しました')).toBeInTheDocument()
    })
  })

  it('ギャラリーが既に読み込み済みなら選択肢を開き直しても再取得しない', async () => {
    await openPanel()
    await openReferencePicker()
    fireEvent.click(within(referenceSection()).getByRole('button', { name: '閉じる' }))
    fireEvent.click(within(referenceSection()).getByRole('button', { name: '参照画像を選ぶ' }))

    expect(actions.fetchGeneratedImagesAction).toHaveBeenCalledTimes(1)
  })

  it('プロンプトを持たないアップロード画像も参照画像に選べる', async () => {
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue([{ ...summary(21, 1), prompt: null }])
    await openPanel()
    await openReferencePicker()

    fireEvent.click(within(referenceSection()).getByAltText('アップロード画像'))

    expect(within(referenceSection()).getByText(/選択中の参照画像: ID 21/)).toBeInTheDocument()
  })
})
