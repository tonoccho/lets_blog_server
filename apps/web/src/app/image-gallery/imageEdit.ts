/**
 * 画像編集(回転・反転・切り抜き、issue #1655)の純粋な計算。UI(`ImageEditDialog`)から切り離してあるのは、
 * プレビューの座標計算を DOM 抜きで確かめるため。操作の意味は media-service の `ImageResizeService#applyEdits` と
 * 同じ: 操作は並べた順に適用し、切り抜きは操作後の画像の座標で指定する。
 */

/** 明るさ・コントラストのスライダーの範囲。0 が変更なし。 */
export const ADJUSTMENT_MIN = -100;
export const ADJUSTMENT_MAX = 100;

export type EditOperation = "ROTATE_CW" | "ROTATE_CCW" | "FLIP_HORIZONTAL" | "FLIP_VERTICAL";

export interface Size {
  width: number;
  height: number;
}

export interface Point {
  x: number;
  y: number;
}

export interface CropRect extends Size, Point {}

/** 切り抜きの比率のプリセット。FREE は自由比率。 */
export type CropPreset = "FREE" | "ORIGINAL" | "SQUARE" | "WIDE";

const CSS_OF: Record<EditOperation, string> = {
  ROTATE_CW: "rotate(90deg)",
  ROTATE_CCW: "rotate(-90deg)",
  FLIP_HORIZONTAL: "scaleX(-1)",
  FLIP_VERTICAL: "scaleY(-1)",
};

/** 操作を適用した後の画像の寸法。90度回転のたびに幅と高さが入れ替わる。 */
export function transformedSize(width: number, height: number, operations: EditOperation[]): Size {
  const rotations = operations.filter((op) => op === "ROTATE_CW" || op === "ROTATE_CCW").length;
  return rotations % 2 === 0 ? { width, height } : { width: height, height: width };
}

/** プレビューの CSS transform。CSS は右の関数から先に適用するので、先に行った操作ほど右に並べる。 */
export function previewTransform(operations: EditOperation[]): string {
  if (operations.length === 0) return "none";
  return [...operations].reverse().map((op) => CSS_OF[op]).join(" ");
}

/** プリセットの縦横比(幅/高さ)。自由は null。 */
export function presetRatio(preset: CropPreset, width: number, height: number): number | null {
  switch (preset) {
    case "ORIGINAL":
      return width / height;
    case "SQUARE":
      return 1;
    case "WIDE":
      return 16 / 9;
    default:
      return null;
  }
}

/** 指定した比率で画像に収まる最大の範囲を、中央に置いたもの。 */
export function presetCropRect(bounds: Size, ratio: number): CropRect {
  const wide = bounds.width / bounds.height > ratio;
  const width = Math.min(bounds.width, Math.round(wide ? bounds.height * ratio : bounds.width));
  const height = Math.min(bounds.height, Math.round(wide ? bounds.height : bounds.width / ratio));
  return {
    x: Math.round((bounds.width - width) / 2),
    y: Math.round((bounds.height - height) / 2),
    width,
    height,
  };
}

function clamp(value: number, max: number): number {
  return Math.min(Math.max(value, 0), max);
}

/**
 * ドラッグの始点と現在位置から切り抜き範囲を作る。画像の外にはみ出した位置は端に収め、`ratio` を
 * 指定すると(幅/高さ)その比率を保ち、はみ出す場合は画像内に収まる大きさへ縮める。
 */
export function clampDragRect(start: Point, current: Point, bounds: Size, ratio: number | null): CropRect {
  const sx = clamp(start.x, bounds.width);
  const sy = clamp(start.y, bounds.height);
  const dx = clamp(current.x, bounds.width) - sx;
  const dy = clamp(current.y, bounds.height) - sy;
  const goesLeft = dx < 0;
  const goesUp = dy < 0;
  let width = Math.abs(dx);
  let height = Math.abs(dy);
  if (ratio !== null) {
    const availableWidth = goesLeft ? sx : bounds.width - sx;
    const availableHeight = goesUp ? sy : bounds.height - sy;
    width = Math.min(width, availableWidth);
    height = width / ratio;
    if (height > availableHeight) {
      height = availableHeight;
      width = height * ratio;
    }
  }
  return { x: goesLeft ? sx - width : sx, y: goesUp ? sy - height : sy, width, height };
}

/** 整数の画素に丸め、画像内に収めた切り抜き範囲。1画素に満たなければ null。 */
export function toPixelCrop(rect: CropRect, bounds: Size): CropRect | null {
  const left = clamp(Math.round(rect.x), bounds.width);
  const top = clamp(Math.round(rect.y), bounds.height);
  const right = clamp(Math.round(rect.x + rect.width), bounds.width);
  const bottom = clamp(Math.round(rect.y + rect.height), bounds.height);
  const width = right - left;
  const height = bottom - top;
  return width < 1 || height < 1 ? null : { x: left, y: top, width, height };
}

/** 明るさ・コントラストのどちらかが変更なし(0)でなければ true。 */
export function isAdjusted(brightness: number, contrast: number): boolean {
  return brightness !== 0 || contrast !== 0;
}

/**
 * プレビューの CSS filter。media-service の `ImageAdjustment` と同じ式: 係数は `1 + 値/100`、明るさ→コントラストの順。
 * 変更なし(0・0)は `none`。式を変えるときは `ImageAdjustment` も合わせる。
 */
export function adjustmentFilter(brightness: number, contrast: number): string {
  if (!isAdjusted(brightness, contrast)) return "none";
  return `brightness(${1 + brightness / 100}) contrast(${1 + contrast / 100})`;
}
