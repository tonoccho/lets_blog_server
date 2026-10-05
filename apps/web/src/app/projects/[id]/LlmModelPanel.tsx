"use client";

import { useEffect, useState } from "react";
import type { LlmModelListResponse } from "@/lib/apiClient";
import { fetchLlmModelsAction, selectLlmModelAction } from "./actions";

export function LlmModelPanel({
  projectId,
  initialData,
  onSaved,
}: {
  projectId: number;
  initialData: LlmModelListResponse;
  /** モデルの保存に成功したとき(進行中の古い再取得結果で作り直さないため、issue #1644)。 */
  onSaved?: () => void;
}) {
  const [data, setData] = useState(initialData);
  const [modelName, setModelName] = useState(initialData.selected);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ type: "error" | "success"; text: string } | null>(null);
  // issue #1414: ハイドレーション完了(mounted)までは送信ボタンを押せないようにする(#1413と同じ方式)。
  // 完了前はonSubmitが未結線で、クリックがネイティブ送信になり入力値だけが失われる。
  const [mounted, setMounted] = useState(false);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setMounted(true);
  }, []);

  async function refresh() {
    setData(await fetchLlmModelsAction(projectId));
  }

  async function handleSave(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const trimmed = modelName.trim();
    if (!trimmed) {
      return;
    }
    setSaving(true);
    const result = await selectLlmModelAction(projectId, trimmed);
    setSaving(false);
    if (result.error) {
      setMessage({ type: "error", text: result.error });
    } else {
      onSaved?.();
      setMessage({ type: "success", text: "保存しました。" });
      await refresh();
    }
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-neutral-500 dark:text-neutral-400">
        選択中のモデル: <span className="font-medium text-neutral-700 dark:text-neutral-300">{data.selected}</span>
      </p>

      {message && (
        <p className={`text-sm ${message.type === "error" ? "text-red-600" : "text-green-600"}`}>{message.text}</p>
      )}

      {/* issue #1051: JS無効時のネイティブGETフォールバックで入力値がURLへ漏れることを防ぐため、
          method="post"を明示する。送信自体はhandleSaveがpreventDefaultして処理する。 */}
      <form onSubmit={handleSave} method="post" className="flex flex-wrap items-end gap-2">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">モデル名(例: gpt-4o-mini)</span>
          <input
            value={modelName}
            onChange={(e) => setModelName(e.target.value)}
            placeholder="gpt-4o-mini"
            required
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <button
          type="submit"
          disabled={saving || !mounted}
          className="rounded bg-neutral-900 px-3 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {saving ? "保存中…" : "保存"}
        </button>
      </form>

      {data.availableModels.length > 0 && (
        <div className="flex flex-wrap gap-2 text-xs">
          {data.availableModels.map((name) => (
            <button
              key={name}
              type="button"
              onClick={() => setModelName(name)}
              className="rounded bg-neutral-100 dark:bg-neutral-800 px-2 py-1 text-neutral-700 dark:text-neutral-300"
            >
              {name}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
