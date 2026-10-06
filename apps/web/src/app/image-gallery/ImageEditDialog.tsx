"use client";

import { useState, type MouseEvent } from "react";
import type { GeneratedImageDetail } from "@/lib/apiClient";
import { editGeneratedImageAction } from "./actions";
import {
  clampDragRect,
  presetCropRect,
  presetRatio,
  previewTransform,
  toPixelCrop,
  transformedSize,
  type CropPreset,
  type CropRect,
  type EditOperation,
  type Point,
} from "./imageEdit";

/** プレビューの枠の最大の大きさ(px)。これに収まるよう縮小して表示し、範囲の画素数は元の画素で数える。 */
const MAX_PREVIEW_WIDTH = 480;
const MAX_PREVIEW_HEIGHT = 360;

const OPERATION_BUTTONS: { operation: EditOperation; label: string }[] = [
  { operation: "ROTATE_CCW", label: "左に90°回転" },
  { operation: "ROTATE_CW", label: "右に90°回転" },
  { operation: "FLIP_HORIZONTAL", label: "左右反転" },
  { operation: "FLIP_VERTICAL", label: "上下反転" },
];

const PRESET_BUTTONS: { preset: CropPreset; label: string }[] = [
  { preset: "FREE", label: "自由" },
  { preset: "ORIGINAL", label: "元の比率" },
  { preset: "SQUARE", label: "1:1" },
  { preset: "WIDE", label: "16:9" },
];

/**
 * 画像の編集画面(issue #1655)。回転・反転・切り抜きをプレビューで確かめ、「保存」で新しい画像として
 * 登録する(元の画像は変わらない)。「キャンセル」では何も送らない。編集の本体は media-service が行い、
 * ここのプレビューは見た目の確認だけ。
 */
export function ImageEditDialog({
  imageId,
  width,
  height,
  onSaved,
  onCancel,
}: {
  imageId: number;
  /** 編集元の画素数。 */
  width: number;
  height: number;
  onSaved: (saved: GeneratedImageDetail) => void;
  onCancel: () => void;
}) {
  const [operations, setOperations] = useState<EditOperation[]>([]);
  const [preset, setPreset] = useState<CropPreset>("FREE");
  /** 切り抜き範囲。操作後の画像の画素座標。 */
  const [crop, setCrop] = useState<CropRect | null>(null);
  const [dragStart, setDragStart] = useState<Point | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const size = transformedSize(width, height, operations);
  const scale = Math.min(1, MAX_PREVIEW_WIDTH / size.width, MAX_PREVIEW_HEIGHT / size.height);
  const boxWidth = size.width * scale;
  const boxHeight = size.height * scale;
  const ratio = presetRatio(preset, size.width, size.height);
  const pixelCrop = crop ? toPixelCrop(crop, size) : null;

  function addOperation(operation: EditOperation) {
    const next = [...operations, operation];
    setOperations(next);
    // 回転すると座標が変わる。比率を選んでいれば取り直し、自分で描いた範囲は解除する。
    const nextSize = transformedSize(width, height, next);
    const nextRatio = presetRatio(preset, nextSize.width, nextSize.height);
    setCrop(nextRatio === null ? null : presetCropRect(nextSize, nextRatio));
  }

  function selectPreset(next: CropPreset) {
    setPreset(next);
    const nextRatio = presetRatio(next, size.width, size.height);
    if (nextRatio !== null) setCrop(presetCropRect(size, nextRatio));
  }

  function clearCrop() {
    setPreset("FREE");
    setCrop(null);
  }

  function pointOf(event: MouseEvent<HTMLDivElement>): Point {
    const box = event.currentTarget.getBoundingClientRect();
    return { x: (event.clientX - box.left) / scale, y: (event.clientY - box.top) / scale };
  }

  function handleMouseDown(event: MouseEvent<HTMLDivElement>) {
    event.preventDefault();
    setDragStart(pointOf(event));
    setCrop(null);
  }

  function handleMouseMove(event: MouseEvent<HTMLDivElement>) {
    if (dragStart) setCrop(clampDragRect(dragStart, pointOf(event), size, ratio));
  }

  function handleMouseUp(event: MouseEvent<HTMLDivElement>) {
    if (!dragStart) return;
    const finished = clampDragRect(dragStart, pointOf(event), size, ratio);
    setCrop(toPixelCrop(finished, size) ? finished : null);
    setDragStart(null);
  }

  async function handleSave() {
    setSaving(true);
    setError(null);
    try {
      onSaved(await editGeneratedImageAction(imageId, operations, pixelCrop));
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      setSaving(false);
    }
  }

  const canSave = !saving && (operations.length > 0 || pixelCrop !== null);

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/60 p-4" onClick={onCancel}>
      <div
        role="dialog"
        aria-label="画像を編集"
        className="max-h-[95vh] w-full max-w-xl space-y-4 overflow-y-auto rounded-lg bg-white p-6 dark:bg-neutral-900"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 className="text-lg font-semibold">画像を編集</h3>

        <div className="flex flex-wrap gap-2">
          {OPERATION_BUTTONS.map(({ operation, label }) => (
            <button
              key={operation}
              type="button"
              onClick={() => addOperation(operation)}
              className="rounded border border-neutral-300 px-3 py-1 text-sm hover:bg-neutral-100 dark:border-neutral-700 dark:hover:bg-neutral-800"
            >
              {label}
            </button>
          ))}
        </div>

        <div className="flex flex-wrap items-center gap-2" role="group" aria-label="切り抜きの比率">
          {PRESET_BUTTONS.map((item) => (
            <button
              key={item.preset}
              type="button"
              aria-pressed={preset === item.preset}
              onClick={() => selectPreset(item.preset)}
              className={`rounded px-3 py-1 text-sm ${
                preset === item.preset
                  ? "bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900"
                  : "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400"
              }`}
            >
              {item.label}
            </button>
          ))}
          <button type="button" onClick={clearCrop} className="text-sm text-neutral-600 underline dark:text-neutral-300">
            切り抜きを解除
          </button>
        </div>

        <div className="flex justify-center">
          <div
            data-testid="image-edit-preview"
            onMouseDown={handleMouseDown}
            onMouseMove={handleMouseMove}
            onMouseUp={handleMouseUp}
            onMouseLeave={handleMouseUp}
            className="relative cursor-crosshair select-none overflow-hidden bg-neutral-100 dark:bg-neutral-800"
            style={{ width: `${boxWidth}px`, height: `${boxHeight}px` }}
          >
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={`/image-gallery/${imageId}/file`}
              alt="編集プレビュー"
              draggable={false}
              className="pointer-events-none absolute max-w-none"
              style={{
                width: `${width * scale}px`,
                height: `${height * scale}px`,
                left: `${(boxWidth - width * scale) / 2}px`,
                top: `${(boxHeight - height * scale) / 2}px`,
                transform: previewTransform(operations),
              }}
            />
            {crop && (
              <div
                data-testid="image-edit-crop"
                aria-label="切り抜き範囲"
                className="pointer-events-none absolute border-2 border-white"
                style={{
                  left: `${crop.x * scale}px`,
                  top: `${crop.y * scale}px`,
                  width: `${crop.width * scale}px`,
                  height: `${crop.height * scale}px`,
                  boxShadow: "0 0 0 9999px rgba(0, 0, 0, 0.45)",
                }}
              />
            )}
          </div>
        </div>

        <div className="space-y-1 text-sm text-neutral-600 dark:text-neutral-400">
          <p>{`編集後のサイズ: ${size.width} × ${size.height} px`}</p>
          {/* 行ごと出し入れすると中央寄せのダイアログが動き、ドラッグ中にプレビューがずれる */}
          <p className="min-h-5">{pixelCrop ? `切り抜き範囲: ${pixelCrop.width} × ${pixelCrop.height} px` : ""}</p>
        </div>

        {error && (
          <p role="alert" className="text-sm text-red-600">
            {error}
          </p>
        )}

        <div className="flex justify-end gap-3">
          <button type="button" onClick={onCancel} className="text-neutral-600 hover:text-neutral-900 dark:text-neutral-300">
            キャンセル
          </button>
          <button
            type="button"
            onClick={handleSave}
            disabled={!canSave}
            className="rounded bg-neutral-900 px-4 py-1 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            {saving ? "保存中…" : "保存"}
          </button>
        </div>
      </div>
    </div>
  );
}
