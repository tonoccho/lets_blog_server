import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { ImageGalleryGrid } from '../ImageGalleryGrid'
import * as actions from '../actions'
import { GALLERY_PAGE_SIZE } from '../pageSize'
import type { GeneratedImageDetail, GeneratedImageFolder, GeneratedImageSummary } from '@/lib/apiClient'

/**
 * issue #1493: 生成画像ギャラリーの入れ子フォルダ。ツリー表示・フォルダでの絞り込み(タグ・ページングと併用)・
 * フォルダの作成・詳細モーダルからの所属変更。
 */
jest.mock('../actions', () => ({
  getGeneratedImageAction: jest.fn(),
  deleteGeneratedImageAction: jest.fn(),
  updateGeneratedImageTagsAction: jest.fn(),
  fetchGalleryImagesPageAction: jest.fn(),
  bulkDeleteGeneratedImagesAction: jest.fn(),
  createGeneratedImageFolderAction: jest.fn(),
  setGeneratedImageFolderAction: jest.fn(),
  renameGeneratedImageFolderAction: jest.fn(),
  getGeneratedImageFolderDeleteImpactAction: jest.fn(),
  deleteGeneratedImageFolderAction: jest.fn(),
}))

const fetchPage = actions.fetchGalleryImagesPageAction as jest.Mock
const getDetail = actions.getGeneratedImageAction as jest.Mock
const createFolder = actions.createGeneratedImageFolderAction as jest.Mock
const setFolder = actions.setGeneratedImageFolderAction as jest.Mock
const renameFolder = actions.renameGeneratedImageFolderAction as jest.Mock
const deleteImpact = actions.getGeneratedImageFolderDeleteImpactAction as jest.Mock
const deleteFolder = actions.deleteGeneratedImageFolderAction as jest.Mock

function image(id: number, folderId: number | null = null, tags: string[] = []): GeneratedImageSummary {
  return {
    id,
    projectId: 1,
    prompt: `prompt-${id}`,
    checkpoint: 'm.safetensors',
    createdAt: '2026-08-01T00:00:00Z',
    tags,
    provider: 'COMFYUI',
    folderId,
  }
}

function detailOf(summary: GeneratedImageSummary): GeneratedImageDetail {
  return {
    ...summary,
    negativePrompt: '',
    steps: 20,
    cfgScale: 7,
    samplerName: 'euler',
    scheduler: 'normal',
    seed: 1,
    width: 512,
    height: 512,
    batchSize: 1,
    batchIndex: null,
    loraName: null,
    loraWeight: null,
  }
}

const FOLDERS: GeneratedImageFolder[] = [
  { id: 1, name: '風景', parentId: null },
  { id: 2, name: '山', parentId: 1 },
  { id: 3, name: '人物', parentId: null },
]

class FakeObserver {
  static instances: FakeObserver[] = []
  disconnected = false
  constructor(public callback: IntersectionObserverCallback) {
    FakeObserver.instances.push(this)
  }
  observe() {}
  unobserve() {}
  disconnect() {
    this.disconnected = true
  }
  takeRecords() {
    return []
  }
}

async function scrollToEnd() {
  const live = FakeObserver.instances.filter((o) => !o.disconnected)
  const observer = live[live.length - 1]
  expect(observer).toBeDefined()
  await act(async () => {
    observer.callback([{ isIntersecting: true } as IntersectionObserverEntry], observer as unknown as IntersectionObserver)
  })
}

function renderGrid(images: GeneratedImageSummary[] = [image(1)], folders = FOLDERS) {
  return render(<ImageGalleryGrid images={images} folders={folders} timezone="Asia/Tokyo" />)
}

function treeItem(name: string) {
  return screen.getByRole('treeitem', { name })
}

function chooseFolder(name: string) {
  fireEvent.click(within(treeItem(name)).getAllByRole('button', { name })[0])
}

beforeEach(() => {
  jest.clearAllMocks()
  fetchPage.mockReset()
  FakeObserver.instances = []
  ;(global as unknown as { IntersectionObserver: unknown }).IntersectionObserver = FakeObserver
})

describe('フォルダツリーの表示', () => {
  it('フォルダを階層のまま表示し、「すべて」「未分類」も選べる', () => {
    renderGrid()

    expect(treeItem('風景')).toHaveAttribute('aria-level', '1')
    expect(within(treeItem('風景')).getByRole('treeitem', { name: '山' })).toHaveAttribute('aria-level', '2')
    expect(treeItem('人物')).toHaveAttribute('aria-level', '1')
    expect(treeItem('すべて')).toHaveAttribute('aria-selected', 'true')
    expect(treeItem('未分類')).toHaveAttribute('aria-selected', 'false')
  })

  it('フォルダが1つも無ければツリー(すべて/未分類)は出さず、作成フォームだけ出す', () => {
    renderGrid([image(1)], [])

    expect(screen.queryByRole('tree')).not.toBeInTheDocument()
    expect(screen.getByLabelText('新しいフォルダの名前')).toBeInTheDocument()
  })

  it('親フォルダを折りたたむと子が隠れ、展開すると戻る', () => {
    renderGrid()

    fireEvent.click(screen.getByRole('button', { name: '「風景」を折りたたむ' }))
    expect(screen.queryByRole('treeitem', { name: '山' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '「風景」を展開' }))
    expect(screen.getByRole('treeitem', { name: '山' })).toBeInTheDocument()
  })

  it('子の無いフォルダには展開ボタンが無い', () => {
    renderGrid()

    expect(within(treeItem('人物')).queryByRole('button', { name: /展開|折りたたむ/ })).not.toBeInTheDocument()
  })
})

describe('フォルダでの絞り込み', () => {
  it('フォルダを選ぶとサーバ側の結果に置き換わり、選択状態になる', async () => {
    fetchPage.mockResolvedValueOnce([image(2, 2)])
    renderGrid([image(1), image(2, 2)])

    chooseFolder('風景')

    await waitFor(() => expect(screen.queryByAltText('prompt-1')).not.toBeInTheDocument())
    expect(fetchPage).toHaveBeenLastCalledWith(0, null, 1, null)
    expect(screen.getByAltText('prompt-2')).toBeInTheDocument()
    expect(treeItem('風景')).toHaveAttribute('aria-selected', 'true')
    expect(treeItem('すべて')).toHaveAttribute('aria-selected', 'false')
  })

  it('「未分類」を選ぶと unfiled で、「すべて」を選ぶと絞り込みなしで取り直す', async () => {
    fetchPage.mockResolvedValue([image(1)])
    renderGrid()

    chooseFolder('未分類')
    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, null, 'unfiled', null))
    await waitFor(() => expect(treeItem('未分類')).toHaveAttribute('aria-selected', 'true'))

    chooseFolder('すべて')
    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, null, null, null))
    await waitFor(() => expect(treeItem('すべて')).toHaveAttribute('aria-selected', 'true'))
  })

  it('タグ絞り込みと併用できる(フォルダを選んでもタグは保たれ、タグを選んでもフォルダは保たれる)', async () => {
    fetchPage.mockResolvedValue([image(1, 1, ['猫'])])
    renderGrid([image(1, 1, ['猫'])])

    fireEvent.click(screen.getByRole('button', { name: '猫' }))
    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, '猫', null, null))

    chooseFolder('風景')
    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, '猫', 1, null))
    await waitFor(() => expect(treeItem('風景')).toHaveAttribute('aria-selected', 'true'))
  })

  it('選んだフォルダのまま続きを読み込む(ページングと併用)', async () => {
    const first = Array.from({ length: GALLERY_PAGE_SIZE }, (_, i) => image(100 - i, 1))
    fetchPage.mockResolvedValueOnce(first)
    fetchPage.mockResolvedValueOnce([image(5, 1)])
    renderGrid([image(500)])

    chooseFolder('風景')
    await waitFor(() => expect(treeItem('風景')).toHaveAttribute('aria-selected', 'true'))
    await scrollToEnd()

    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(GALLERY_PAGE_SIZE, null, 1, null))
  })

  it('絞り込みの取得に失敗したら、読み込み済みの一覧と選択を残して通知する', async () => {
    fetchPage.mockRejectedValueOnce(new Error('落ちた'))
    renderGrid([image(1)])

    chooseFolder('風景')

    expect(await screen.findByText(/絞り込みを読み込めませんでした: 落ちた/)).toBeInTheDocument()
    expect(screen.getByAltText('prompt-1')).toBeInTheDocument()
    expect(treeItem('すべて')).toHaveAttribute('aria-selected', 'true')
  })
})

describe('フォルダの作成', () => {
  function fillAndSubmit(name: string, parentOption?: string) {
    fireEvent.change(screen.getByLabelText('新しいフォルダの名前'), { target: { value: name } })
    if (parentOption !== undefined) {
      fireEvent.change(screen.getByLabelText('親フォルダ'), { target: { value: parentOption } })
    }
    fireEvent.click(screen.getByRole('button', { name: 'フォルダを作成' }))
  }

  it('親を指定して作成すると、ツリーの親の下に現れ、入力は空に戻る', async () => {
    createFolder.mockResolvedValue({ id: 9, name: '海', parentId: 3 })
    renderGrid()

    fillAndSubmit('海', '3')

    await waitFor(() => expect(within(treeItem('人物')).getByRole('treeitem', { name: '海' })).toBeInTheDocument())
    expect(createFolder).toHaveBeenCalledWith('海', 3)
    expect(screen.getByLabelText('新しいフォルダの名前')).toHaveValue('')
  })

  it('親を指定しなければ最上位に作る。最初のフォルダを作るとツリーが現れる', async () => {
    createFolder.mockResolvedValue({ id: 9, name: '最初', parentId: null })
    renderGrid([image(1)], [])

    fillAndSubmit('最初')

    await waitFor(() => expect(treeItem('最初')).toHaveAttribute('aria-level', '1'))
    expect(createFolder).toHaveBeenCalledWith('最初', null)
  })

  it('名前が空白だけなら作成ボタンは押せず、前後の空白は取り除いて送る', async () => {
    createFolder.mockResolvedValue({ id: 9, name: '海', parentId: null })
    renderGrid()
    expect(screen.getByRole('button', { name: 'フォルダを作成' })).toBeDisabled()

    fireEvent.change(screen.getByLabelText('新しいフォルダの名前'), { target: { value: '   ' } })
    expect(screen.getByRole('button', { name: 'フォルダを作成' })).toBeDisabled()

    fillAndSubmit('  海  ')
    await waitFor(() => expect(createFolder).toHaveBeenCalledWith('海', null))
  })

  it('作成に失敗したら理由を表示し、入力は残す', async () => {
    createFolder.mockRejectedValue(new Error('この操作にはadmin権限が必要です'))
    renderGrid()

    fillAndSubmit('海')

    expect(await screen.findByText('この操作にはadmin権限が必要です')).toBeInTheDocument()
    expect(screen.getByLabelText('新しいフォルダの名前')).toHaveValue('海')
  })

  it('Error以外の失敗も文字列にして表示する', async () => {
    createFolder.mockRejectedValue('だめ')
    renderGrid()

    fillAndSubmit('海')

    expect(await screen.findByText('だめ')).toBeInTheDocument()
  })
})

describe('詳細モーダルからの所属変更', () => {
  async function openDetail(summary: GeneratedImageSummary, folders = FOLDERS) {
    getDetail.mockResolvedValue(detailOf(summary))
    renderGrid([summary], folders)
    fireEvent.click(screen.getByRole('button', { name: `${summary.prompt as string}の詳細を表示` }))
    return screen.findByLabelText('所属フォルダ')
  }

  it('現在の所属フォルダが選ばれ、未分類の画像は「未分類」が選ばれる', async () => {
    const inFolder = await openDetail(image(1, 2))
    expect(inFolder).toHaveValue('2')
    expect(within(inFolder).getByRole('option', { name: /山/ })).toBeInTheDocument()
  })

  it('未分類の画像は未分類が選ばれている', async () => {
    const select = await openDetail(image(1, null))
    expect(select).toHaveValue('')
    expect(within(select).getByRole('option', { name: '未分類' })).toBeInTheDocument()
  })

  it('フォルダを選ぶと所属を保存し、詳細と一覧の画像に反映する', async () => {
    const select = await openDetail(image(1, null))
    setFolder.mockResolvedValue(detailOf(image(1, 3)))

    fireEvent.change(select, { target: { value: '3' } })

    await waitFor(() => expect(setFolder).toHaveBeenCalledWith(1, 3))
    await waitFor(() => expect(screen.getByLabelText('所属フォルダ')).toHaveValue('3'))
    // 絞り込み中でなければ一覧は取り直さない。
    expect(fetchPage).not.toHaveBeenCalled()
  })

  it('未分類を選ぶと null で保存する', async () => {
    const select = await openDetail(image(1, 3))
    setFolder.mockResolvedValue(detailOf(image(1, null)))

    fireEvent.change(select, { target: { value: '' } })

    await waitFor(() => expect(setFolder).toHaveBeenCalledWith(1, null))
    await waitFor(() => expect(screen.getByLabelText('所属フォルダ')).toHaveValue(''))
  })

  it('フォルダで絞り込み中に所属を変えると、一覧を同じ絞り込みで取り直す', async () => {
    fetchPage.mockResolvedValueOnce([image(1, 1)])
    getDetail.mockResolvedValue(detailOf(image(1, 1)))
    renderGrid([image(1, 1)])
    chooseFolder('風景')
    await waitFor(() => expect(treeItem('風景')).toHaveAttribute('aria-selected', 'true'))
    fireEvent.click(screen.getByRole('button', { name: 'prompt-1の詳細を表示' }))
    const select = await screen.findByLabelText('所属フォルダ')
    setFolder.mockResolvedValue(detailOf(image(1, 3)))
    fetchPage.mockResolvedValueOnce([])

    fireEvent.change(select, { target: { value: '3' } })

    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, null, 1, null))
    await waitFor(() => expect(screen.queryByAltText('prompt-1')).not.toBeInTheDocument())
  })

  it('保存に失敗したら理由を表示し、選択は元に戻る', async () => {
    const select = await openDetail(image(1, null))
    setFolder.mockRejectedValue(new Error('この操作にはadmin権限が必要です'))

    fireEvent.change(select, { target: { value: '3' } })

    expect(await screen.findByText('この操作にはadmin権限が必要です')).toBeInTheDocument()
    expect(screen.getByLabelText('所属フォルダ')).toHaveValue('')
  })

  it('作成したフォルダも所属先の選択肢に加わる', async () => {
    createFolder.mockResolvedValue({ id: 9, name: '海', parentId: null })
    getDetail.mockResolvedValue(detailOf(image(1)))
    renderGrid([image(1)])
    fireEvent.change(screen.getByLabelText('新しいフォルダの名前'), { target: { value: '海' } })
    fireEvent.click(screen.getByRole('button', { name: 'フォルダを作成' }))
    await waitFor(() => expect(createFolder).toHaveBeenCalled())

    fireEvent.click(screen.getByRole('button', { name: 'prompt-1の詳細を表示' }))
    const select = await screen.findByLabelText('所属フォルダ')

    expect(within(select).getByRole('option', { name: '海' })).toBeInTheDocument()
  })
})

describe('フォルダの改名(issue #1494)', () => {
  function startRename(name: string) {
    fireEvent.click(within(treeItem(name)).getAllByRole('button', { name: `「${name}」を改名` })[0])
  }

  it('改名を始めると現在の名前が入った入力が出て、保存するとツリーに新しい名前で現れる(階層と選択は保つ)', async () => {
    renameFolder.mockResolvedValue({ id: 2, name: '高山', parentId: 1 })
    renderGrid()

    startRename('山')
    const input = screen.getByLabelText('「山」の新しい名前')
    expect(input).toHaveValue('山')
    fireEvent.change(input, { target: { value: ' 高山 ' } })
    fireEvent.click(screen.getByRole('button', { name: '改名を保存' }))

    await waitFor(() => expect(within(treeItem('風景')).getByRole('treeitem', { name: '高山' })).toHaveAttribute('aria-level', '2'))
    expect(renameFolder).toHaveBeenCalledWith(2, '高山')
    expect(screen.queryByRole('treeitem', { name: '山' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('「山」の新しい名前')).not.toBeInTheDocument()
  })

  it('空白だけの名前は保存できず、やめると何も送らず元に戻る', () => {
    renderGrid()

    startRename('山')
    fireEvent.change(screen.getByLabelText('「山」の新しい名前'), { target: { value: '   ' } })
    expect(screen.getByRole('button', { name: '改名を保存' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '改名をやめる' }))

    expect(renameFolder).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('「山」の新しい名前')).not.toBeInTheDocument()
    expect(treeItem('山')).toBeInTheDocument()
  })

  it('改名に失敗したら理由を表示し、入力と元の名前を残す', async () => {
    renameFolder.mockRejectedValue(new Error('この操作にはadmin権限が必要です'))
    renderGrid()

    startRename('人物')
    fireEvent.change(screen.getByLabelText('「人物」の新しい名前'), { target: { value: '動物' } })
    fireEvent.click(screen.getByRole('button', { name: '改名を保存' }))

    expect(await screen.findByText('この操作にはadmin権限が必要です')).toBeInTheDocument()
    expect(screen.getByLabelText('「人物」の新しい名前')).toHaveValue('動物')
    expect(treeItem('人物')).toBeInTheDocument()
  })

  it('Error以外の失敗も文字列にして表示する', async () => {
    renameFolder.mockRejectedValue('だめ')
    renderGrid()

    startRename('人物')
    fireEvent.click(screen.getByRole('button', { name: '改名を保存' }))

    expect(await screen.findByText('だめ')).toBeInTheDocument()
  })
})

describe('フォルダの削除(issue #1494)', () => {
  let confirmSpy: jest.SpyInstance

  beforeEach(() => {
    confirmSpy = jest.spyOn(window, 'confirm')
  })

  afterEach(() => {
    confirmSpy.mockRestore()
  })

  function clickDelete(name: string) {
    fireEvent.click(within(treeItem(name)).getAllByRole('button', { name: `「${name}」を削除` })[0])
  }

  it('確認ダイアログに子孫フォルダ数・未分類に戻る画像の枚数・取り消せないことを出す', async () => {
    deleteImpact.mockResolvedValue({ descendantFolderCount: 1, imageCount: 5 })
    confirmSpy.mockReturnValue(false)
    renderGrid()

    clickDelete('風景')

    await waitFor(() => expect(confirmSpy).toHaveBeenCalledTimes(1))
    const message = confirmSpy.mock.calls[0][0] as string
    expect(deleteImpact).toHaveBeenCalledWith(1)
    expect(message).toContain('「風景」')
    expect(message).toContain('子孫フォルダ1個')
    expect(message).toContain('5枚')
    expect(message).toContain('元に戻せません')
  })

  it('キャンセルすると削除せず、フォルダも一覧も変わらない', async () => {
    deleteImpact.mockResolvedValue({ descendantFolderCount: 0, imageCount: 0 })
    confirmSpy.mockReturnValue(false)
    renderGrid([image(1, 3)])

    clickDelete('人物')
    await waitFor(() => expect(confirmSpy).toHaveBeenCalled())

    expect(deleteFolder).not.toHaveBeenCalled()
    expect(fetchPage).not.toHaveBeenCalled()
    expect(treeItem('人物')).toBeInTheDocument()
    expect(screen.getByAltText('prompt-1')).toBeInTheDocument()
  })

  it('承諾すると削除し、子孫もツリーから消える。絞り込み中でなければ一覧は取り直すが選択は変えない', async () => {
    deleteImpact.mockResolvedValue({ descendantFolderCount: 1, imageCount: 2 })
    deleteFolder.mockResolvedValue(undefined)
    confirmSpy.mockReturnValue(true)
    fetchPage.mockResolvedValueOnce([image(1)])
    renderGrid([image(1, 2)])

    clickDelete('風景')

    await waitFor(() => expect(deleteFolder).toHaveBeenCalledWith(1))
    await waitFor(() => expect(screen.queryByRole('treeitem', { name: '風景' })).not.toBeInTheDocument())
    expect(screen.queryByRole('treeitem', { name: '山' })).not.toBeInTheDocument()
    expect(treeItem('人物')).toBeInTheDocument()
    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, null, null, null))
    expect(treeItem('すべて')).toHaveAttribute('aria-selected', 'true')
  })

  it('選んでいたフォルダ(の子孫)を削除すると「すべて」へ戻り、タグ絞り込みは保つ', async () => {
    fetchPage.mockResolvedValueOnce([image(2, 2, ['猫'])])
    renderGrid([image(1)])
    fireEvent.click(within(treeItem('山')).getAllByRole('button', { name: '山' })[0])
    await waitFor(() => expect(treeItem('山')).toHaveAttribute('aria-selected', 'true'))
    deleteImpact.mockResolvedValue({ descendantFolderCount: 1, imageCount: 1 })
    deleteFolder.mockResolvedValue(undefined)
    confirmSpy.mockReturnValue(true)
    fetchPage.mockResolvedValueOnce([image(2, null, ['猫'])])

    clickDelete('風景')

    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, null, null, null))
    await waitFor(() => expect(treeItem('すべて')).toHaveAttribute('aria-selected', 'true'))
  })

  it('別のフォルダを選んでいる間に削除しても、その選択は保つ', async () => {
    fetchPage.mockResolvedValueOnce([image(3, 3)])
    renderGrid([image(1)])
    chooseFolder('人物')
    await waitFor(() => expect(treeItem('人物')).toHaveAttribute('aria-selected', 'true'))
    deleteImpact.mockResolvedValue({ descendantFolderCount: 1, imageCount: 0 })
    deleteFolder.mockResolvedValue(undefined)
    confirmSpy.mockReturnValue(true)
    fetchPage.mockResolvedValueOnce([image(3, 3)])

    clickDelete('風景')

    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith(0, null, 3, null))
    expect(treeItem('人物')).toHaveAttribute('aria-selected', 'true')
  })

  it('影響範囲を取得できなければ確認を出さず、理由を表示する', async () => {
    deleteImpact.mockRejectedValue(new Error('この操作にはadmin権限が必要です'))
    renderGrid()

    clickDelete('人物')

    expect(await screen.findByText('この操作にはadmin権限が必要です')).toBeInTheDocument()
    expect(confirmSpy).not.toHaveBeenCalled()
    expect(deleteFolder).not.toHaveBeenCalled()
  })

  it('削除に失敗したら理由を表示し、ツリーは変えない', async () => {
    deleteImpact.mockResolvedValue({ descendantFolderCount: 0, imageCount: 0 })
    deleteFolder.mockRejectedValue('だめ')
    confirmSpy.mockReturnValue(true)
    renderGrid()

    clickDelete('人物')

    expect(await screen.findByText('だめ')).toBeInTheDocument()
    expect(treeItem('人物')).toBeInTheDocument()
    expect(fetchPage).not.toHaveBeenCalled()
  })
})
