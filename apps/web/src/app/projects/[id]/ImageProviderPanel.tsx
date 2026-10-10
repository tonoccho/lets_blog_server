"use client";

import { useState } from "react";
import type { ImageProviderListResponse } from "@/lib/apiClient";
import { fetchImageProviderAction, selectImageProviderAction } from "./actions";

const PROVIDER_LABEL: Record<string, string> = {
  COMFYUI: "ComfyUI",
  CHATGPT: "ChatGPT",
};

/**
 * プロジェクト単位の画像生成AIの切り替え(issue #531)。空選択(ComfyUIを使用)を許容するため、
 * LlmProviderPanelと同様にselectedはプロジェクトの上書き値(未設定ならnull)をそのまま扱う。
 */
export function ImageProviderPanel({
  projectId,
  initialData,
}: {
  projectId: number;
  initialData: ImageProviderListResponse;
}) {
  const [data, setData] = useState(initialData);
  const [provider, setProvider] = useState(initialData.selected ?? "");
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ type: "error" | "success"; text: string } | null>(null);

  async function handleChange(value: string) {
    setProvider(value);
    setSaving(true);
    setMessage(null);
    const result = await selectImageProviderAction(projectId, value);
    setSaving(false);
    if (result.error) {
      setMessage({ type: "error", text: result.error });
      return;
    }
    const refreshed = await fetchImageProviderAction(projectId);
    if (!refreshed.data) {
      setMessage({ type: "error", text: `一覧の再取得に失敗しました: ${refreshed.error}` });
      return;
    }
    setData(refreshed.data);
    setMessage({ type: "success", text: "保存しました。" });
  }

  return (
    <div className="space-y-2">
      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600 dark:text-neutral-400">画像生成AI(このプロジェクトの既定)</span>
        <select
          value={provider}
          disabled={saving}
          onChange={(e) => handleChange(e.target.value)}
          className="rounded border border-neutral-300 dark:border-neutral-700 bg-white dark:bg-neutral-900 px-3 py-2 text-sm disabled:opacity-60"
        >
          <option value="">(ComfyUIを使用)</option>
          {data.availableProviders.map((value) => (
            <option key={value} value={value}>
              {PROVIDER_LABEL[value] ?? value}
            </option>
          ))}
        </select>
      </label>
      {message && (
        <p className={`text-sm ${message.type === "error" ? "text-red-600" : "text-green-600"}`}>{message.text}</p>
      )}
    </div>
  );
}
