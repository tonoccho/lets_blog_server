"use client";

import { useState } from "react";
import type { LlmProviderListResponse } from "@/lib/apiClient";
import { fetchLlmProviderAction, selectLlmProviderAction } from "./actions";

const PROVIDER_LABEL: Record<string, string> = {
  OLLAMA: "Ollama",
  OPENAI: "OpenAI (ChatGPT)",
  CLAUDE: "Claude (Anthropic)",
};

/**
 * プロジェクト単位のAIプロバイダー既定値の切り替え(issue #530)。空選択(グローバル既定を使用)を
 * 許容するため、LlmModelPanelと異なりselectedはプロジェクトの上書き値(未設定ならnull)をそのまま扱う。
 */
export function LlmProviderPanel({
  projectId,
  initialData,
}: {
  projectId: number;
  initialData: LlmProviderListResponse;
}) {
  const [data, setData] = useState(initialData);
  const [provider, setProvider] = useState(initialData.selected ?? "");
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ type: "error" | "success"; text: string } | null>(null);

  async function handleChange(value: string) {
    setProvider(value);
    setSaving(true);
    setMessage(null);
    const result = await selectLlmProviderAction(projectId, value);
    setSaving(false);
    if (result.error) {
      setMessage({ type: "error", text: result.error });
      return;
    }
    setData(await fetchLlmProviderAction(projectId));
    setMessage({ type: "success", text: "保存しました。" });
  }

  return (
    <div className="space-y-2">
      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600 dark:text-neutral-400">AIプロバイダー(このプロジェクトの既定)</span>
        <select
          value={provider}
          disabled={saving}
          onChange={(e) => handleChange(e.target.value)}
          className="rounded border border-neutral-300 dark:border-neutral-700 bg-white dark:bg-neutral-900 px-3 py-2 text-sm disabled:opacity-60"
        >
          <option value="">(グローバル既定を使用)</option>
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
