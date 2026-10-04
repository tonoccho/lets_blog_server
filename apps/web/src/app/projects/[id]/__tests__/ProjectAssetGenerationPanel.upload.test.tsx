import { render, screen, fireEvent, waitFor } from '@testing-library/react'
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

const MAX_BYTES = 20 * 1024 * 1024

async function openPanel() {
  render(<ProjectAssetGenerationPanel projectId={5} />)
  fireEvent.click(screen.getByText('アセット画像生成'))
  await waitFor(() => {
    expect(screen.getByText('画像をアップロードしてギャラリーへ登録')).toBeInTheDocument()
  })
}

function chooseFile(file: File) {
  const input = screen.getByLabelText('アップロードする画像ファイル') as HTMLInputElement
  fireEvent.change(input, { target: { files: [file] } })
}

function fileOfSize(name: string, type: string, size: number): File {
  const file = new File([new Uint8Array([1])], name, { type })
  Object.defineProperty(file, 'size', { value: size })
  return file
}

describe('ProjectAssetGenerationPanel 画像のアップロード(issue #1599)', () => {
  beforeEach(() => {
    jest.clearAllMocks()
    ;(actions.fetchImageGenerationOptionsAction as jest.Mock).mockResolvedValue(OPTIONS)
    ;(actions.fetchGeneratedImagesAction as jest.Mock).mockResolvedValue([])
  })

  it('JPEG/PNGだけを選べるファイル入力があり、ファイルを選ぶまで登録ボタンは押せない', async () => {
    await openPanel()

    const input = screen.getByLabelText('アップロードする画像ファイル') as HTMLInputElement
    expect(input.accept).toBe('image/jpeg,image/png')
    expect(screen.getByRole('button', { name: 'ギャラリーへ登録' })).toBeDisabled()
  })

  it('JPEGを選んで登録すると、projectIdとファイルを渡して成功を表示する', async () => {
    ;(actions.uploadGeneratedImageAction as jest.Mock).mockResolvedValue({ imageId: 31 })
    await openPanel()

    chooseFile(new File([new Uint8Array([1, 2])], 'photo.jpg', { type: 'image/jpeg' }))
    fireEvent.click(screen.getByRole('button', { name: 'ギャラリーへ登録' }))

    await waitFor(() => {
      expect(screen.getByText(/生成画像ギャラリーに登録しました/)).toBeInTheDocument()
    })
    const [projectId, formData] = (actions.uploadGeneratedImageAction as jest.Mock).mock.calls[0]
    expect(projectId).toBe(5)
    expect((formData as FormData).get('file')).toBeInstanceOf(File)
    expect(((formData as FormData).get('file') as File).name).toBe('photo.jpg')
  })

  it('PNGも受け付ける', async () => {
    ;(actions.uploadGeneratedImageAction as jest.Mock).mockResolvedValue({ imageId: 32 })
    await openPanel()

    chooseFile(new File([new Uint8Array([1])], 'a.png', { type: 'image/png' }))
    fireEvent.click(screen.getByRole('button', { name: 'ギャラリーへ登録' }))

    await waitFor(() => expect(actions.uploadGeneratedImageAction).toHaveBeenCalledTimes(1))
  })

  it('対応形式外(GIF)を選ぶとエラーを表示し、サーバーへは送らない', async () => {
    await openPanel()

    chooseFile(new File([new Uint8Array([1])], 'a.gif', { type: 'image/gif' }))

    expect(screen.getByText('対応していない画像形式です。JPEGまたはPNGを選択してください。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'ギャラリーへ登録' })).toBeDisabled()
    expect(actions.uploadGeneratedImageAction).not.toHaveBeenCalled()
  })

  it('20MBを超えるファイルを選ぶとエラーを表示し、サーバーへは送らない', async () => {
    await openPanel()

    chooseFile(fileOfSize('big.png', 'image/png', MAX_BYTES + 1))

    expect(screen.getByText('ファイルサイズが上限(20MB)を超えています。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'ギャラリーへ登録' })).toBeDisabled()
  })

  it('ちょうど20MBは受け付ける', async () => {
    await openPanel()

    chooseFile(fileOfSize('edge.png', 'image/png', MAX_BYTES))

    expect(screen.getByRole('button', { name: 'ギャラリーへ登録' })).toBeEnabled()
  })

  it('ファイル選択を取り消すと、エラーも消えて登録ボタンは押せなくなる', async () => {
    await openPanel()
    chooseFile(new File([new Uint8Array([1])], 'a.gif', { type: 'image/gif' }))

    fireEvent.change(screen.getByLabelText('アップロードする画像ファイル'), { target: { files: [] } })

    expect(screen.queryByText(/対応していない画像形式/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'ギャラリーへ登録' })).toBeDisabled()
  })

  it('サーバーが拒否したら、その理由を表示する', async () => {
    ;(actions.uploadGeneratedImageAction as jest.Mock).mockResolvedValue({ error: 'APIエラー (403): 権限がありません' })
    await openPanel()

    chooseFile(new File([new Uint8Array([1])], 'a.png', { type: 'image/png' }))
    fireEvent.click(screen.getByRole('button', { name: 'ギャラリーへ登録' }))

    await waitFor(() => {
      expect(screen.getByText('APIエラー (403): 権限がありません')).toBeInTheDocument()
    })
  })

  it('登録に成功したら、開いているギャラリー一覧を取得し直し、UPLOADの画像に「アップロード画像」と表示する', async () => {
    ;(actions.uploadGeneratedImageAction as jest.Mock).mockResolvedValue({ imageId: 31 })
    ;(actions.fetchGeneratedImagesAction as jest.Mock)
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        { id: 31, projectId: 5, prompt: null, checkpoint: null, createdAt: '2026-10-04T00:00:00Z', tags: [], provider: 'UPLOAD', folderId: null },
      ])
    await openPanel()
    fireEvent.click(screen.getAllByText('開く')[0])
    await waitFor(() => expect(actions.fetchGeneratedImagesAction).toHaveBeenCalledTimes(1))

    chooseFile(new File([new Uint8Array([1])], 'a.png', { type: 'image/png' }))
    fireEvent.click(screen.getByRole('button', { name: 'ギャラリーへ登録' }))

    await waitFor(() => expect(actions.fetchGeneratedImagesAction).toHaveBeenCalledTimes(2))
    expect(await screen.findByAltText('アップロード画像')).toBeInTheDocument()
  })
})
