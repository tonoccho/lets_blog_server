import {
  clampDragRect,
  presetCropRect,
  presetRatio,
  previewTransform,
  toPixelCrop,
  transformedSize,
  type EditOperation,
} from '../imageEdit'

/** issue #1655: 画像編集(回転・反転・切り抜き)の純粋な計算。 */
describe('transformedSize', () => {
  it('操作が無ければそのまま', () => {
    expect(transformedSize(400, 200, [])).toEqual({ width: 400, height: 200 })
  })

  it('90度回転のたびに幅と高さが入れ替わる(反転では変わらない)', () => {
    expect(transformedSize(400, 200, ['ROTATE_CW'])).toEqual({ width: 200, height: 400 })
    expect(transformedSize(400, 200, ['ROTATE_CCW'])).toEqual({ width: 200, height: 400 })
    expect(transformedSize(400, 200, ['ROTATE_CW', 'ROTATE_CW'])).toEqual({ width: 400, height: 200 })
    expect(transformedSize(400, 200, ['FLIP_HORIZONTAL', 'FLIP_VERTICAL'])).toEqual({ width: 400, height: 200 })
  })
})

describe('previewTransform', () => {
  it('操作が無ければ none', () => {
    expect(previewTransform([])).toBe('none')
  })

  it('各操作を CSS に直す', () => {
    expect(previewTransform(['ROTATE_CW'])).toBe('rotate(90deg)')
    expect(previewTransform(['ROTATE_CCW'])).toBe('rotate(-90deg)')
    expect(previewTransform(['FLIP_HORIZONTAL'])).toBe('scaleX(-1)')
    expect(previewTransform(['FLIP_VERTICAL'])).toBe('scaleY(-1)')
  })

  it('先に行った操作ほど右に並べる(CSS は右から適用されるため)', () => {
    const ops: EditOperation[] = ['ROTATE_CW', 'FLIP_HORIZONTAL']
    expect(previewTransform(ops)).toBe('scaleX(-1) rotate(90deg)')
  })
})

describe('presetRatio', () => {
  it('プリセットごとの縦横比。自由は null', () => {
    expect(presetRatio('FREE', 400, 200)).toBeNull()
    expect(presetRatio('ORIGINAL', 400, 200)).toBe(2)
    expect(presetRatio('SQUARE', 400, 200)).toBe(1)
    expect(presetRatio('WIDE', 400, 200)).toBeCloseTo(16 / 9)
  })
})

describe('presetCropRect', () => {
  it('横長の画像では、高さいっぱいの中央の範囲になる', () => {
    expect(presetCropRect({ width: 400, height: 200 }, 1)).toEqual({ x: 100, y: 0, width: 200, height: 200 })
    expect(presetCropRect({ width: 400, height: 200 }, 16 / 9)).toEqual({ x: 22, y: 0, width: 356, height: 200 })
  })

  it('縦長の画像では、幅いっぱいの中央の範囲になる', () => {
    expect(presetCropRect({ width: 200, height: 400 }, 1)).toEqual({ x: 0, y: 100, width: 200, height: 200 })
  })

  it('元の比率なら画像全体', () => {
    expect(presetCropRect({ width: 400, height: 200 }, 2)).toEqual({ x: 0, y: 0, width: 400, height: 200 })
  })
})

describe('clampDragRect', () => {
  const bounds = { width: 400, height: 200 }

  it('自由比率では始点と現在位置を結ぶ範囲(右下へ)', () => {
    expect(clampDragRect({ x: 10, y: 10 }, { x: 110, y: 60 }, bounds, null)).toEqual({
      x: 10,
      y: 10,
      width: 100,
      height: 50,
    })
  })

  it('左上へドラッグしても正の幅・高さになる', () => {
    expect(clampDragRect({ x: 110, y: 60 }, { x: 10, y: 10 }, bounds, null)).toEqual({
      x: 10,
      y: 10,
      width: 100,
      height: 50,
    })
  })

  it('画像の外へはみ出した位置は画像の端に収める', () => {
    expect(clampDragRect({ x: 300, y: 150 }, { x: 900, y: 900 }, bounds, null)).toEqual({
      x: 300,
      y: 150,
      width: 100,
      height: 50,
    })
    expect(clampDragRect({ x: 50, y: 50 }, { x: -100, y: -100 }, bounds, null)).toEqual({
      x: 0,
      y: 0,
      width: 50,
      height: 50,
    })
  })

  it('比率を固定すると、その比率の範囲になる(右下・左上)', () => {
    expect(clampDragRect({ x: 10, y: 10 }, { x: 110, y: 20 }, bounds, 1)).toEqual({
      x: 10,
      y: 10,
      width: 100,
      height: 100,
    })
    expect(clampDragRect({ x: 110, y: 110 }, { x: 10, y: 100 }, bounds, 1)).toEqual({
      x: 10,
      y: 10,
      width: 100,
      height: 100,
    })
  })

  it('比率を固定してはみ出す場合は、画像内に収まる大きさへ縮める', () => {
    const rect = clampDragRect({ x: 300, y: 150 }, { x: 400, y: 200 }, bounds, 1)
    expect(rect.width).toBe(50)
    expect(rect.height).toBe(50)
    const wide = clampDragRect({ x: 0, y: 0 }, { x: 400, y: 10 }, { width: 400, height: 100 }, 1)
    expect(wide).toEqual({ x: 0, y: 0, width: 100, height: 100 })
  })
})

describe('toPixelCrop', () => {
  it('整数の画素に丸め、画像内に収める。1画素未満なら null', () => {
    expect(toPixelCrop({ x: 10.4, y: 20.6, width: 99.5, height: 49.2 }, { width: 400, height: 200 })).toEqual({
      x: 10,
      y: 21,
      width: 100,
      height: 49,
    })
    expect(toPixelCrop({ x: 390, y: 0, width: 30, height: 10 }, { width: 400, height: 200 })).toEqual({
      x: 390,
      y: 0,
      width: 10,
      height: 10,
    })
    expect(toPixelCrop({ x: 0, y: 0, width: 0.2, height: 10 }, { width: 400, height: 200 })).toBeNull()
    expect(toPixelCrop({ x: 400, y: 0, width: 10, height: 10 }, { width: 400, height: 200 })).toBeNull()
  })
})
