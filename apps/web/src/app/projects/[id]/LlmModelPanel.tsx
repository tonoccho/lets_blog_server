"use client";

import { useState } from "react";
import type { LlmModelListResponse } from "@/lib/apiClient";
import { fetchLlmModelsAction, selectLlmModelAction } from "./actions";

export function LlmModelPanel({
  projectId,
  initialData,
}: {
  projectId: number;
  initialData: LlmModelListResponse;
}) {
  const [data, setData] = useState(initialData);
  const [modelName, setModelName] = useState(initialData.selected);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ type: "error" | "success"; text: string } | null>(null);

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

      <form onSubmit={handleSave} className="flex flex-wrap items-end gap-2">
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
          disabled={saving}
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
