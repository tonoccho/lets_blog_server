import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { ProjectAssetGenerationPanel } from '../ProjectAssetGenerationPanel'
import { backend, installAssetPanelBackend } from './assetPanelRuntime'
import { calledRequests, failWith } from './projectCoreRuntime'

// 自前の `../actions`・`@/lib/apiClient`・`@/lib/session` は差し替えず、Next.js の実行基盤と外部との通信(`fetch`)だけを代替する(#1837)。
// `backend.*` は gateway の向こうのバックエンドの代役(`./assetPanelRuntime`)。
jest.mock('server-only', () => ({}))
jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule)
jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule)
jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule)
jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule)
jest.mock('next/navigation', () => ({
  redirect: (path: string) => {
    throw new Error(`NEXT_REDIRECT:${path}`)
  },
  useRouter: () => ({ refresh: jest.fn(), push: jest.fn() }),
}))
jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }))

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

let fetchSpy: jest.Mock

describe('ProjectAssetGenerationPanel 画像のアップロード(issue #1599)', () => {
  beforeEach(() => {
    fetchSpy = installAssetPanelBackend()
    ;backend.imageOptions.mockResolvedValue(OPTIONS)
    ;backend.gallery.mockResolvedValue([])
  })

  it('JPEG/PNGだけを選べるファイル入力があり、ファイルを選ぶまで登録ボタンは押せない', async () => {
    await openPanel()

    const input = screen.getByLabelText('アップロードする画像ファイル') as HTMLInputElement
    expect(input.accept).toBe('image/jpeg,image/png')
    expect(screen.getByRole('button', { name: 'ギャラリーへ登録' })).toBeDisabled()
  })

  it('JPEGを選んで登録すると、projectIdとファイルを渡して成功を表示する', async () => {
    ;backend.imageUpload.mockResolvedValue({ id: 31 })
    await openPanel()

    chooseFile(new File([new Uint8Array([1, 2])], 'photo.jpg', { type: 'image/jpeg' }))
    fireEvent.click(screen.getByRole('button', { name: 'ギャラリーへ登録' }))

    await waitFor(() => {
      expect(screen.getByText(/画像ギャラリーに登録しました/)).toBeInTheDocument()
    })
    const success = screen.getByText(/画像ギャラリーに登録しました/)
    expect(success.textContent).toContain('元の解像度のまま')
    expect(success.textContent).not.toContain('1920x1080')
    expect(calledRequests(fetchSpy)).toContain('POST /api/generated-images/upload?projectId=5')
    const sent = backend.imageUpload.mock.calls[0][0]
    expect(sent).toBeInstanceOf(File)
    expect((sent as File).name).toBe('photo.jpg')
  })

  it('アップロード欄の説明に1920x1080の文言は無く、元の解像度のまま登録される旨が示される', async () => {
    await openPanel()

    const description = screen.getByText(/JPEG\/PNG\(20MBまで\)を1枚選べます/)
    expect(description.textContent).toContain('元の解像度のまま')
    expect(description.textContent).not.toContain('1920x1080')
    expect(description.textContent).not.toContain('中央で切り抜')
  })

  it('PNGも受け付ける', async () => {
    ;backend.imageUpload.mockResolvedValue({ id: 32 })
    await openPanel()

    chooseFile(new File([new Uint8Array([1])], 'a.png', { type: 'image/png' }))
    fireEvent.click(screen.getByRole('button', { name: 'ギャラリーへ登録' }))

    await waitFor(() => expect(backend.imageUpload).toHaveBeenCalledTimes(1))
  })

  it('対応形式外(GIF)を選ぶとエラーを表示し、サーバーへは送らない', async () => {
    await openPanel()

    chooseFile(new File([new Uint8Array([1])], 'a.gif', { type: 'image/gif' }))

    expect(screen.getByText('対応していない画像形式です。JPEGまたはPNGを選択してください。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'ギャラリーへ登録' })).toBeDisabled()
    expect(backend.imageUpload).not.toHaveBeenCalled()
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
    ;backend.imageUpload.mockResolvedValue(failWith(403, '権限がありません')())
    await openPanel()

    chooseFile(new File([new Uint8Array([1])], 'a.png', { type: 'image/png' }))
    fireEvent.click(screen.getByRole('button', { name: 'ギャラリーへ登録' }))

    await waitFor(() => {
      expect(screen.getByText('APIエラー (403): 権限がありません')).toBeInTheDocument()
    })
  })

  it('登録に成功したら、開いているギャラリー一覧を取得し直し、UPLOADの画像に「アップロード画像」と表示する', async () => {
    ;backend.imageUpload.mockResolvedValue({ id: 31 })
    ;backend.gallery
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        { id: 31, projectId: 5, prompt: null, checkpoint: null, createdAt: '2026-10-04T00:00:00Z', tags: [], provider: 'UPLOAD', folderId: null },
      ])
    await openPanel()
    fireEvent.click(screen.getAllByText('開く')[0])
    await waitFor(() => expect(backend.gallery).toHaveBeenCalledTimes(1))

    chooseFile(new File([new Uint8Array([1])], 'a.png', { type: 'image/png' }))
    fireEvent.click(screen.getByRole('button', { name: 'ギャラリーへ登録' }))

    await waitFor(() => expect(backend.gallery).toHaveBeenCalledTimes(2))
    expect(await screen.findByAltText('アップロード画像')).toBeInTheDocument()
  })

  it.each([
    ['Error', new Error('一覧を取得できません'), 'APIエラー (500): 一覧を取得できません'],
    ['Errorでない値', '一覧が使えません', '一覧が使えません'],
  ])('登録には成功したが、開いているギャラリー一覧の取得し直しが%sで失敗したら、その理由を表示する', async (_name, rejection, text) => {
    backend.imageUpload.mockResolvedValue({ id: 31 })
    backend.gallery.mockReset().mockResolvedValueOnce([]).mockRejectedValueOnce(rejection)
    await openPanel()
    fireEvent.click(screen.getAllByText('開く')[0])
    await waitFor(() => expect(backend.gallery).toHaveBeenCalledTimes(1))

    chooseFile(new File([new Uint8Array([1])], 'a.png', { type: 'image/png' }))
    fireEvent.click(screen.getByRole('button', { name: 'ギャラリーへ登録' }))

    expect(await screen.findByText(text)).toBeInTheDocument()
    expect(backend.gallery).toHaveBeenCalledTimes(2)
  })
})
