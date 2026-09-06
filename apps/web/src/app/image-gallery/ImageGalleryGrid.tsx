"use client";

import { useMemo, useState, useTransition } from "react";
import type { GeneratedImageDetail, GeneratedImageSummary } from "@/lib/apiClient";
import { formatDateTime } from "@/lib/formatDate";
import { deleteGeneratedImageAction, getGeneratedImageAction, updateGeneratedImageTagsAction } from "./actions";

const PROVIDER_LABEL: Record<string, string> = {
  COMFYUI: "ComfyUI",
  CHATGPT: "ChatGPT",
};

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
  const [isSavingTags, startTagsTransition] = useTransition();
  const [newTag, setNewTag] = useState("");
  /** タグ一覧を絞り込むフィルタ(issue #281)。nullは絞り込みなし。 */
  const [activeTag, setActiveTag] = useState<string | null>(null);
  /** 「この画像の設定をコピー」ボタンの一時的なフィードバック表示(issue #437)。 */
  const [settingsCopied, setSettingsCopied] = useState(false);

  const allTags = useMemo(() => {
    const set = new Set<string>();
    images.forEach((image) => (image.tags || []).forEach((tag) => set.add(tag)));
    return Array.from(set).sort();
  }, [images]);

  const visibleImages = activeTag
    ? images.filter((image) => (image.tags || []).includes(activeTag))
    : images;

  function openDetail(id: number) {
    setSelectedId(id);
    setDetail(null);
    setError(null);
    setNewTag("");
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

  function saveTags(tags: string[]) {
    if (selectedId === null) return;
    startTagsTransition(async () => {
      try {
        const result = await updateGeneratedImageTagsAction(selectedId, tags);
        setDetail(result);
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err));
      }
    });
  }

  function handleAddTag() {
    const trimmed = newTag.trim();
    if (!trimmed || !detail) return;
    if (detail.tags.includes(trimmed)) {
      setNewTag("");
      return;
    }
    saveTags([...detail.tags, trimmed]);
    setNewTag("");
  }

  function handleRemoveTag(tag: string) {
    if (!detail) return;
    saveTags(detail.tags.filter((t) => t !== tag));
  }

  /** 生成パラメータをJSON形式でクリップボードにコピーする(issue #437)。アセット画像生成の「クリップボードから作成」で貼り付けられる。 */
  async function handleCopySettings() {
    if (!detail) return;
    const settings = {
      prompt: detail.prompt,
      negativePrompt: detail.negativePrompt,
      steps: detail.steps,
      cfgScale: detail.cfgScale,
      samplerName: detail.samplerName,
      scheduler: detail.scheduler,
      seed: detail.seed,
      width: detail.width,
      height: detail.height,
      batchSize: detail.batchSize,
      checkpoint: detail.checkpoint,
      loraName: detail.loraName,
      loraWeight: detail.loraWeight,
    };
    try {
      await navigator.clipboard.writeText(JSON.stringify(settings));
      setSettingsCopied(true);
      setTimeout(() => setSettingsCopied(false), 2000);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    }
  }

  return (
    <div className="space-y-4">
      {allTags.length > 0 && (
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <span className="text-neutral-500 dark:text-neutral-400">タグで絞り込み:</span>
          <button
            type="button"
            onClick={() => setActiveTag(null)}
            className={`rounded-full px-2.5 py-1 ${
              activeTag === null
                ? "bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900"
                : "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400"
            }`}
          >
            すべて
          </button>
          {allTags.map((tag) => (
            <button
              key={tag}
              type="button"
              onClick={() => setActiveTag(tag === activeTag ? null : tag)}
              className={`rounded-full px-2.5 py-1 ${
                tag === activeTag
                  ? "bg-neutral-900 text-white dark:bg-neutral-100 dark:text-neutral-900"
                  : "bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-400"
              }`}
            >
              {tag}
            </button>
          ))}
        </div>
      )}

      {visibleImages.length === 0 ? (
        <p className="text-neutral-500 dark:text-neutral-400">該当する画像がありません。</p>
      ) : (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
          {visibleImages.map((image) => (
            <button
              key={image.id}
              type="button"
              onClick={() => openDetail(image.id)}
              className="group overflow-hidden rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 text-left"
            >
              <img
                src={`/image-gallery/${image.id}/file`}
                alt={image.prompt}
                className="aspect-square w-full bg-neutral-100 object-contain group-hover:opacity-80 dark:bg-neutral-800"
              />
              <div className="space-y-1 p-2 text-xs">
                <p className="line-clamp-2 text-neutral-700 dark:text-neutral-300">{image.prompt}</p>
                {image.tags && image.tags.length > 0 && (
                  <div className="flex flex-wrap gap-1">
                    {image.tags.map((tag) => (
                      <span
                        key={tag}
                        className="rounded-full bg-neutral-100 dark:bg-neutral-800 px-2 py-0.5 text-neutral-600 dark:text-neutral-400"
                      >
                        {tag}
                      </span>
                    ))}
                  </div>
                )}
                <p className="text-neutral-400">{formatDateTime(image.createdAt, timezone)}</p>
              </div>
            </button>
          ))}
        </div>
      )}

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
                {detail && (
                  <button
                    type="button"
                    onClick={handleCopySettings}
                    className="text-neutral-600 hover:text-neutral-900 dark:text-neutral-300 dark:hover:text-neutral-100"
                  >
                    {settingsCopied ? "コピーしました" : "この画像の設定をコピー"}
                  </button>
                )}
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
              <>
                <div className="mb-4 space-y-2">
                  <p className="text-sm font-semibold">タグ</p>
                  <div className="flex flex-wrap items-center gap-2">
                    {detail.tags.length === 0 && (
                      <span className="text-sm text-neutral-500 dark:text-neutral-400">タグはありません。</span>
                    )}
                    {detail.tags.map((tag) => (
                      <span
                        key={tag}
                        className="flex items-center gap-1 rounded-full bg-neutral-100 dark:bg-neutral-800 px-2.5 py-1 text-xs text-neutral-700 dark:text-neutral-300"
                      >
                        {tag}
                        <button
                          type="button"
                          onClick={() => handleRemoveTag(tag)}
                          disabled={isSavingTags}
                          aria-label={`タグ「${tag}」を削除`}
                          className="text-neutral-400 hover:text-red-600 disabled:opacity-50"
                        >
                          ×
                        </button>
                      </span>
                    ))}
                  </div>
                  <div className="flex gap-2">
                    <input
                      type="text"
                      value={newTag}
                      onChange={(e) => setNewTag(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === "Enter") {
                          e.preventDefault();
                          handleAddTag();
                        }
                      }}
                      placeholder="タグを追加"
                      disabled={isSavingTags}
                      className="flex-1 rounded border border-neutral-300 dark:border-neutral-700 px-2 py-1 text-sm"
                    />
                    <button
                      type="button"
                      onClick={handleAddTag}
                      disabled={isSavingTags || !newTag.trim()}
                      className="rounded bg-neutral-900 px-3 py-1 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
                    >
                      {isSavingTags ? "保存中…" : "追加"}
                    </button>
                  </div>
                </div>

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
                  {/*
                    issue #1101: seedが無い画像は再現できないので、値の代わりにそう分かる表示にする。
                    ChatGPTの画像生成API(gpt-image-1)はseedを受け付けないため常にここへ来る。
                    #1101以前に生成した画像も、実際に使われたseedが残っていないので同じ扱いになる。
                  */}
                  <dd className="text-neutral-600 dark:text-neutral-400">
                    {detail.seed != null ? detail.seed : "再現不可(この画像生成AIはseedに対応していません)"}
                  </dd>
                  <dt className="font-semibold">size</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">
                    {detail.width}x{detail.height}
                  </dd>
                  <dt className="font-semibold">batch size</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">{detail.batchSize}</dd>
                  {/* issue #1101: 同じseed・同じbatch sizeで再実行したときの、この画像の位置。 */}
                  <dt className="font-semibold">batch index</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">
                    {detail.batchIndex != null ? detail.batchIndex : "-"}
                  </dd>
                  <dt className="font-semibold">画像生成AI</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">
                    {PROVIDER_LABEL[detail.provider] ?? detail.provider}
                  </dd>
                  <dt className="font-semibold">checkpoint</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">{detail.checkpoint}</dd>
                  <dt className="font-semibold">LoRA</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">
                    {detail.loraName ? `${detail.loraName} (weight: ${detail.loraWeight})` : "-"}
                  </dd>
                  <dt className="font-semibold">作成日時</dt>
                  <dd className="text-neutral-600 dark:text-neutral-400">{formatDateTime(detail.createdAt, timezone)}</dd>
                </dl>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
