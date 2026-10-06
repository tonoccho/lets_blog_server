import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ImageEditDialog } from '../ImageEditDialog'
import * as actions from '../actions'
import type { GeneratedImageDetail } from '@/lib/apiClient'

jest.mock('../actions', () => ({
  editGeneratedImageAction: jest.fn(),
}))

const edit = actions.editGeneratedImageAction as jest.Mock

const SAVED = { id: 99, width: 100, height: 50 } as unknown as GeneratedImageDetail

/** 400x200 の画像。プレビューは等倍(480x360 の枠に収まる)。 */
function renderDialog(overrides: Partial<React.ComponentProps<typeof ImageEditDialog>> = {}) {
  const onSaved = jest.fn()
  const onCancel = jest.fn()
  render(<ImageEditDialog imageId={5} width={400} height={200} onSaved={onSaved} onCancel={onCancel} {...overrides} />)
  return { onSaved, onCancel }
}

function previewBox() {
  const box = screen.getByTestId('image-edit-preview')
  box.getBoundingClientRect = () =>
    ({ left: 100, top: 50, width: 400, height: 200, right: 500, bottom: 250, x: 100, y: 50, toJSON: () => ({}) }) as DOMRect
  return box
}

function drag(box: HTMLElement, from: [number, number], to: [number, number]) {
  fireEvent.mouseDown(box, { clientX: 100 + from[0], clientY: 50 + from[1] })
  fireEvent.mouseMove(box, { clientX: 100 + to[0], clientY: 50 + to[1] })
  fireEvent.mouseUp(box, { clientX: 100 + to[0], clientY: 50 + to[1] })
}

beforeEach(() => {
  jest.clearAllMocks()
})

describe('ImageEditDialog 回転・反転(issue #1655)', () => {
  it('開いた直後は元のサイズで、保存はできない', () => {
    renderDialog()

    expect(screen.getByRole('heading', { name: '画像を編集' })).toBeInTheDocument()
    expect(screen.getByText('編集後のサイズ: 400 × 200 px')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled()
  })

  it('右に90°回転すると、プレビューが即座に回り、幅と高さが入れ替わる', () => {
    renderDialog()

    fireEvent.click(screen.getByRole('button', { name: '右に90°回転' }))

    expect(screen.getByText('編集後のサイズ: 200 × 400 px')).toBeInTheDocument()
    expect(screen.getByAltText('編集プレビュー').style.transform).toBe('rotate(90deg)')
    expect(screen.getByRole('button', { name: '保存' })).toBeEnabled()
  })

  it('左に90°回転・左右反転・上下反転がプレビューに反映される(適用順を保つ)', () => {
    renderDialog()
    const img = screen.getByAltText('編集プレビュー')

    fireEvent.click(screen.getByRole('button', { name: '左に90°回転' }))
    expect(img.style.transform).toBe('rotate(-90deg)')
    fireEvent.click(screen.getByRole('button', { name: '左右反転' }))
    expect(img.style.transform).toBe('scaleX(-1) rotate(-90deg)')
    fireEvent.click(screen.getByRole('button', { name: '上下反転' }))
    expect(img.style.transform).toBe('scaleY(-1) scaleX(-1) rotate(-90deg)')
    expect(screen.getByText('編集後のサイズ: 200 × 400 px')).toBeInTheDocument()
  })
})

describe('ImageEditDialog 切り抜き(issue #1655)', () => {
  it('プレビュー上のドラッグで切り抜き範囲(自由比率)が決まり、画素数が表示される', () => {
    renderDialog()
    const box = previewBox()

    drag(box, [10, 10], [110, 60])

    expect(screen.getByText('切り抜き範囲: 100 × 50 px')).toBeInTheDocument()
    expect(screen.getByTestId('image-edit-crop')).toBeInTheDocument()
  })

  it('ドラッグ中でなければマウスを動かしても範囲は変わらない', () => {
    renderDialog()
    const box = previewBox()

    fireEvent.mouseMove(box, { clientX: 300, clientY: 150 })
    fireEvent.mouseUp(box, { clientX: 300, clientY: 150 })

    expect(screen.queryByTestId('image-edit-crop')).not.toBeInTheDocument()
  })

  it('1:1 を選ぶと、中央の正方形の範囲になる', () => {
    renderDialog()

    fireEvent.click(screen.getByRole('button', { name: '1:1' }))

    expect(screen.getByText('切り抜き範囲: 200 × 200 px')).toBeInTheDocument()
  })

  it('16:9 を選ぶと16:9の範囲になり、ドラッグでも比率が保たれる', () => {
    renderDialog()
    const box = previewBox()

    fireEvent.click(screen.getByRole('button', { name: '16:9' }))
    expect(screen.getByText('切り抜き範囲: 356 × 200 px')).toBeInTheDocument()

    drag(box, [0, 0], [160, 10])
    expect(screen.getByText('切り抜き範囲: 160 × 90 px')).toBeInTheDocument()
  })

  it('元の比率を選ぶと画像全体の範囲になる', () => {
    renderDialog()

    fireEvent.click(screen.getByRole('button', { name: '元の比率' }))

    expect(screen.getByText('切り抜き範囲: 400 × 200 px')).toBeInTheDocument()
  })

  it('回転すると、回転後の画像に対して比率の範囲を取り直す。範囲を自分で描いていれば解除される', () => {
    renderDialog()
    const box = previewBox()
    drag(box, [10, 10], [110, 60])

    fireEvent.click(screen.getByRole('button', { name: '右に90°回転' }))

    expect(screen.queryByTestId('image-edit-crop')).not.toBeInTheDocument()
  })

  it('比率を選んだあとに回転すると、回転後の画像の比率で範囲を取り直す', () => {
    renderDialog()

    fireEvent.click(screen.getByRole('button', { name: '1:1' }))
    fireEvent.click(screen.getByRole('button', { name: '右に90°回転' }))

    expect(screen.getByText('切り抜き範囲: 200 × 200 px')).toBeInTheDocument()
  })

  it('「自由」に戻して、切り抜きを解除できる', () => {
    renderDialog()
    fireEvent.click(screen.getByRole('button', { name: '1:1' }))

    fireEvent.click(screen.getByRole('button', { name: '切り抜きを解除' }))

    expect(screen.queryByTestId('image-edit-crop')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled()
  })

  it('幅や高さが1画素に満たないドラッグは範囲にならない', () => {
    renderDialog()
    const box = previewBox()

    drag(box, [10, 10], [10, 80])

    expect(screen.queryByTestId('image-edit-crop')).not.toBeInTheDocument()
  })
})

describe('ImageEditDialog 保存とキャンセル(issue #1655)', () => {
  it('保存すると、操作と切り抜き範囲(画素)を送り、保存された画像を返す', async () => {
    edit.mockResolvedValue(SAVED)
    const { onSaved } = renderDialog()
    const box = previewBox()
    fireEvent.click(screen.getByRole('button', { name: '右に90°回転' }))
    // 回転後は 200x400。枠(480x360)に収めるため 0.9 倍で表示される。表示上の (9,18)→(99,108) は元の画素の (10,20)→(110,120)。
    drag(box, [9, 18], [99, 108])

    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(SAVED))
    expect(edit).toHaveBeenCalledWith(5, ['ROTATE_CW'], { x: 10, y: 20, width: 100, height: 100 }, null)
  })

  it('切り抜きなしの保存は crop を null で送る', async () => {
    edit.mockResolvedValue(SAVED)
    renderDialog()

    fireEvent.click(screen.getByRole('button', { name: '上下反転' }))
    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(edit).toHaveBeenCalledWith(5, ['FLIP_VERTICAL'], null, null))
  })

  it('保存中は保存ボタンを押せず、二重に送らない', async () => {
    let resolve: (v: GeneratedImageDetail) => void = () => {}
    edit.mockReturnValue(new Promise<GeneratedImageDetail>((r) => (resolve = r)))
    renderDialog()
    fireEvent.click(screen.getByRole('button', { name: '上下反転' }))

    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(screen.getByRole('button', { name: '保存中…' })).toBeDisabled())
    expect(edit).toHaveBeenCalledTimes(1)
    resolve(SAVED)
  })

  it('保存に失敗したら理由を表示し、閉じずに残す', async () => {
    edit.mockRejectedValue(new Error('画像の画素数が大きすぎます'))
    const { onSaved } = renderDialog()
    fireEvent.click(screen.getByRole('button', { name: '上下反転' }))

    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('画像の画素数が大きすぎます')
    expect(onSaved).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '保存' })).toBeEnabled()
  })

  it('失敗が Error でなくても文字列にして表示する', async () => {
    edit.mockRejectedValue('boom')
    renderDialog()
    fireEvent.click(screen.getByRole('button', { name: '上下反転' }))

    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('boom')
  })

  it('キャンセルは何も保存せずに閉じる', () => {
    const { onCancel } = renderDialog()
    fireEvent.click(screen.getByRole('button', { name: '右に90°回転' }))

    fireEvent.click(screen.getByRole('button', { name: 'キャンセル' }))

    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(edit).not.toHaveBeenCalled()
  })
})

describe('ImageEditDialog プレビューの大きさ', () => {
  it('大きな画像は枠に収まるよう縮小して表示し、範囲の画素数は元の画素で数える', () => {
    renderDialog({ width: 1920, height: 1080 })
    const box = screen.getByTestId('image-edit-preview')
    box.getBoundingClientRect = () =>
      ({ left: 0, top: 0, width: 480, height: 270, right: 480, bottom: 270, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect

    expect(box.style.width).toBe('480px')
    drag(box, [0, 0], [240, 135])

    expect(screen.getByText('切り抜き範囲: 960 × 540 px')).toBeInTheDocument()
  })
})

function slider(name: string) {
  return screen.getByRole('slider', { name })
}

describe('ImageEditDialog 明るさ・コントラスト(issue #1656)', () => {
  it('開いた直後のスライダーは中央(0)で、プレビューにフィルタは掛からない', () => {
    renderDialog()

    expect(slider('明るさ')).toHaveValue('0')
    expect(slider('コントラスト')).toHaveValue('0')
    expect(slider('明るさ')).toHaveAttribute('min', '-100')
    expect(slider('明るさ')).toHaveAttribute('max', '100')
    expect(slider('コントラスト')).toHaveAttribute('min', '-100')
    expect(slider('コントラスト')).toHaveAttribute('max', '100')
    expect(screen.getByAltText('編集プレビュー').style.filter).toBe('none')
  })

  it('スライダーを動かすと、プレビューに即座に反映され、保存できるようになる', () => {
    renderDialog()

    fireEvent.change(slider('明るさ'), { target: { value: '50' } })
    fireEvent.change(slider('コントラスト'), { target: { value: '-50' } })

    expect(screen.getByAltText('編集プレビュー').style.filter).toBe('brightness(1.5) contrast(0.5)')
    expect(screen.getByRole('button', { name: '保存' })).toBeEnabled()
  })

  it('「リセット」で変更なしに戻り、保存はできなくなる', () => {
    renderDialog()
    fireEvent.change(slider('明るさ'), { target: { value: '50' } })

    fireEvent.click(screen.getByRole('button', { name: 'リセット' }))

    expect(slider('明るさ')).toHaveValue('0')
    expect(screen.getByAltText('編集プレビュー').style.filter).toBe('none')
    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled()
  })

  it('回転と組み合わせて保存すると、操作と調整を一度に送る', async () => {
    edit.mockResolvedValue(SAVED)
    renderDialog()
    fireEvent.click(screen.getByRole('button', { name: '右に90°回転' }))
    fireEvent.change(slider('明るさ'), { target: { value: '30' } })

    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(edit).toHaveBeenCalledWith(5, ['ROTATE_CW'], null, { brightness: 30, contrast: 0 }))
  })

  it('調整だけでも保存できる', async () => {
    edit.mockResolvedValue(SAVED)
    renderDialog()
    fireEvent.change(slider('コントラスト'), { target: { value: '20' } })

    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(edit).toHaveBeenCalledWith(5, [], null, { brightness: 0, contrast: 20 }))
  })

  it('リセット後の回転だけの保存は、調整を送らない', async () => {
    edit.mockResolvedValue(SAVED)
    renderDialog()
    fireEvent.change(slider('明るさ'), { target: { value: '40' } })
    fireEvent.click(screen.getByRole('button', { name: 'リセット' }))
    fireEvent.click(screen.getByRole('button', { name: '上下反転' }))

    fireEvent.click(screen.getByRole('button', { name: '保存' }))

    await waitFor(() => expect(edit).toHaveBeenCalledWith(5, ['FLIP_VERTICAL'], null, null))
  })
})
