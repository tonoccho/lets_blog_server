"use client";

import { useState, useTransition } from "react";
import type { DiagramSummary } from "@/lib/apiClient";
import { formatDateTime } from "@/lib/formatDate";
import { deleteDiagramAction } from "./actions";

export function DiagramGalleryGrid({
  diagrams,
  timezone,
}: {
  diagrams: DiagramSummary[];
  timezone: string | null;
}) {
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isDeleting, startDeleteTransition] = useTransition();

  const selected = diagrams.find((diagram) => diagram.id === selectedId) ?? null;

  function openDetail(id: number) {
    setSelectedId(id);
    setError(null);
  }

  function closeDetail() {
    setSelectedId(null);
    setError(null);
  }

  function handleDelete(id: number) {
    if (!window.confirm("このダイアグラムを削除しますか?この操作は取り消せません。")) {
      return;
    }
    startDeleteTransition(async () => {
      try {
        await deleteDiagramAction(id);
        closeDetail();
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err));
      }
    });
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
        {diagrams.map((diagram) => (
          <button
            key={diagram.id}
            type="button"
            onClick={() => openDetail(diagram.id)}
            className="group overflow-hidden rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 text-left"
          >
            <img
              src={`/diagram-gallery/${diagram.id}/svg`}
              alt={diagram.name}
              className="aspect-square w-full bg-white object-contain group-hover:opacity-80 p-2"
            />
            <div className="space-y-1 p-2 text-xs">
              <p className="line-clamp-2 text-neutral-700 dark:text-neutral-300">{diagram.name}</p>
              <p className="text-neutral-400">{formatDateTime(diagram.updatedAt, timezone)}</p>
            </div>
          </button>
        ))}
      </div>

      {selected && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
          onClick={closeDetail}
        >
          <div
            className="max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-lg bg-white dark:bg-neutral-900 p-6"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-4 flex items-start justify-between gap-4">
              <h2 className="text-lg font-semibold">{selected.name}</h2>
              <div className="flex items-center gap-3">
                <button
                  type="button"
                  onClick={() => handleDelete(selected.id)}
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
              src={`/diagram-gallery/${selected.id}/svg`}
              alt={selected.name}
              className="mb-4 w-full rounded border border-neutral-200 bg-white dark:border-neutral-800"
            />

            {error && <p className="text-red-600">{error}</p>}

            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
              <dt className="font-semibold">ID</dt>
              <dd className="text-neutral-600 dark:text-neutral-400">{selected.id}</dd>
              <dt className="font-semibold">更新日時</dt>
              <dd className="text-neutral-600 dark:text-neutral-400">{formatDateTime(selected.updatedAt, timezone)}</dd>
            </dl>
          </div>
        </div>
      )}
    </div>
  );
}
