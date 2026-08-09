"use client";

import { useState, useTransition } from "react";
import type { GeneratedImageDetail, GeneratedImageSummary } from "@/lib/apiClient";
import { formatDateTime } from "@/lib/formatDate";
import { deleteGeneratedImageAction, getGeneratedImageAction } from "./actions";

export function ImageGalleryGrid({
  images,
  timezone,
}: {
  images: GeneratedImageSummary[];
  timezone: string | null;
}) {
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<GeneratedImageDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isPending, startTransition] = useTransition();
  const [isDeleting, startDeleteTransition] = useTransition();

  function openDetail(id: number) {
    setSelectedId(id);
    setDetail(null);
    setError(null);
    startTransition(async () => {
      try {
        const result = await getGeneratedImageAction(id);
        setDetail(result);
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err));
      }
    });
  }

  function closeDetail() {
    setSelectedId(null);
    setDetail(null);
    setError(null);
  }

  function handleDelete(id: number) {
    if (!window.confirm("この生成画像を削除しますか?この操作は取り消せません。")) {
      return;
    }
    startDeleteTransition(async () => {
      try {
        await deleteGeneratedImageAction(id);
        closeDetail();
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err));
      }
    });
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
        {images.map((image) => (
          <button
            key={image.id}
            type="button"
            onClick={() => openDetail(image.id)}
            className="group overflow-hidden rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 text-left"
          >
            <img
              src={`/image-gallery/${image.id}/file`}
              alt={image.prompt}
              className="aspect-square w-full object-cover group-hover:opacity-80"
            />
            <div className="space-y-1 p-2 text-xs">
              <p className="line-clamp-2 text-neutral-700 dark:text-neutral-300">{image.prompt}</p>
              <p className="text-neutral-400">{formatDateTime(image.createdAt, timezone)}</p>
            </div>
          </button>
        ))}
      </div>

      {selectedId !== null && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
          onClick={closeDetail}
        >
          <div
            className="max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-lg bg-white dark:bg-neutral-900 p-6"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-4 flex items-start justify-between gap-4">
              <h2 className="text-lg font-semibold">生成画像の詳細</h2>
              <div className="flex items-center gap-3">
                <button
                  type="button"
                  onClick={() => handleDelete(selectedId)}
                  disabled={isDeleting}
                  className="text-red-600 hover:text-red-800 disabled:opacity-50"
                >
                  {isDeleting ? "削除中…" : "削除"}
                </button>
                <button type="button" onClick={closeDetail} className="text-neutral-400 hover:text-neutral-700 dark:hover:text-neutral-300">
                  閉じる
                </button>
              </div>
            </div>

            <img
              src={`/image-gallery/${selectedId}/file`}
              alt="生成画像"
              className="mb-4 w-full rounded border border-neutral-200 dark:border-neutral-800"
            />

            {isPending && <p className="text-neutral-500 dark:text-neutral-400">読み込み中…</p>}
            {error && <p className="text-red-600">{error}</p>}
            {detail && (
              <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
                <dt className="col-span-2 font-semibold">prompt</dt>
                <dd className="col-span-2 whitespace-pre-wrap text-neutral-600 dark:text-neutral-400">{detail.prompt}</dd>
                <dt className="col-span-2 font-semibold">negative prompt</dt>
                <dd className="col-span-2 whitespace-pre-wrap text-neutral-600 dark:text-neutral-400">{detail.negativePrompt || "-"}</dd>
                <dt className="font-semibold">steps</dt>
                <dd className="text-neutral-600 dark:text-neutral-400">{detail.steps}</dd>
                <dt className="font-semibold">cfg scale</dt>
                <dd className="text-neutral-600 dark:text-neutral-400">{detail.cfgScale}</dd>
                <dt className="font-semibold">sampler</dt>
                <dd className="text-neutral-600 dark:text-neutral-400">{detail.samplerName}</dd>
                <dt className="font-semibold">scheduler</dt>
                <dd className="text-neutral-600 dark:text-neutral-400">{detail.scheduler}</dd>
                <dt className="font-semibold">seed</dt>
                <dd className="text-neutral-600 dark:text-neutral-400">{detail.seed}</dd>
                <dt className="font-semibold">size</dt>
                <dd className="text-neutral-600 dark:text-neutral-400">
                  {detail.width}x{detail.height}
                </dd>
                <dt className="font-semibold">batch size</dt>
                <dd className="text-neutral-600 dark:text-neutral-400">{detail.batchSize}</dd>
                <dt className="font-semibold">checkpoint</dt>
                <dd className="text-neutral-600 dark:text-neutral-400">{detail.checkpoint}</dd>
                <dt className="font-semibold">LoRA</dt>
                <dd className="text-neutral-600 dark:text-neutral-400">
                  {detail.loraName ? `${detail.loraName} (weight: ${detail.loraWeight})` : "-"}
                </dd>
                <dt className="font-semibold">作成日時</dt>
                <dd className="text-neutral-600 dark:text-neutral-400">{formatDateTime(detail.createdAt, timezone)}</dd>
              </dl>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
