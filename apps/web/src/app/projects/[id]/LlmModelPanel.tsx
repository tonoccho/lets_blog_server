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

  // 取得した一覧に無くても、保存済みのモデルと選択中のモデルは選択肢に残す(保存済みの値を黙って失わせない、issue #1674)。
  const options = Array.from(new Set([...data.availableModels, data.selected, modelName].filter((name) => name !== "")));

  return (
    <div className="space-y-4">
      <p className="text-sm text-neutral-500 dark:text-neutral-400">
        選択中のモデル: <span className="font-medium text-neutral-700 dark:text-neutral-300">{data.selected}</span>
      </p>

      {message && (
        <p className={`text-sm ${message.type === "error" ? "text-red-600" : "text-green-600"}`}>{message.text}</p>
      )}

      {data.fallback && (
        <p role="status" className="text-sm text-amber-700 dark:text-amber-400">
          モデル一覧をプロバイダーから取得できなかったため、システム設定のモデル一覧を表示しています。
        </p>
      )}

      {/* issue #1051: JS無効時のネイティブGETフォールバックでの意図しない送信を防ぐため、
          method="post"を明示する。送信自体はhandleSaveがpreventDefaultして処理する。 */}
      <form onSubmit={handleSave} method="post" className="flex flex-wrap items-end gap-2">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">LLMのモデル</span>
          <select
            aria-label="LLMのモデル"
            value={modelName}
            onChange={(e) => setModelName(e.target.value)}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          >
            {options.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        </label>
        <button
          type="submit"
          disabled={saving || !mounted}
          className="rounded bg-neutral-900 px-3 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {saving ? "保存中…" : "保存"}
        </button>
      </form>
    </div>
  );
}
