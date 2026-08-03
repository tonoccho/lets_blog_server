"use client";

import { useState } from "react";
import type { OllamaModelListResponse, GenerationJobDetail } from "@/lib/apiClient";
import {
  fetchOllamaModelsAction,
  selectOllamaModelAction,
  installOllamaModelAction,
  deleteOllamaModelAction,
} from "./actions";
import { useGenerationJobPolling } from "./useGenerationJobPolling";
import { formatBytes, formatJobProgress, parseJobProgress, type JobProgress } from "./jobProgress";

export function OllamaModelTable({
  projectId,
  initialData,
}: {
  projectId: number;
  initialData: OllamaModelListResponse;
}) {
  const [data, setData] = useState(initialData);
  const [showNewForm, setShowNewForm] = useState(false);
  const [newModelName, setNewModelName] = useState("");
  const [message, setMessage] = useState<{ type: "error" | "success"; text: string } | null>(null);
  const [pendingAction, setPendingAction] = useState<{ name: string; type: "select" | "install" | "delete" } | null>(
    null
  );
  const [progress, setProgress] = useState<JobProgress | null>(null);

  const { startPolling } = useGenerationJobPolling(
    (job: GenerationJobDetail) => handleJobSettled(job),
    (job: GenerationJobDetail) => setProgress(parseJobProgress(job.resultPayload))
  );

  async function refresh() {
    setData(await fetchOllamaModelsAction(projectId));
  }

  function handleJobSettled(job: GenerationJobDetail) {
    const wasInstall = pendingAction?.type === "install";
    setPendingAction(null);
    setProgress(null);
    if (job.status === "done") {
      setMessage({ type: "success", text: "完了しました。" });
    } else {
      const errorMessage = extractErrorMessage(job.resultPayload);
      setMessage({ type: "error", text: errorMessage ?? "処理に失敗しました。" });
    }
    if (wasInstall) {
      setShowNewForm(false);
      setNewModelName("");
    }
    refresh();
  }

  async function handleSelect(modelName: string) {
    setPendingAction({ name: modelName, type: "select" });
    const result = await selectOllamaModelAction(projectId, modelName);
    setPendingAction(null);
    if (result.error) {
      setMessage({ type: "error", text: result.error });
    } else {
      setMessage({ type: "success", text: "切り替えました。" });
      await refresh();
    }
  }

  async function handleDelete(modelName: string) {
    if (!window.confirm(`モデル「${modelName}」をOllamaから削除します。よろしいですか?`)) {
      return;
    }
    setPendingAction({ name: modelName, type: "delete" });
    setProgress(null);
    const result = await deleteOllamaModelAction(projectId, modelName);
    if (result.error) {
      setPendingAction(null);
      setMessage({ type: "error", text: result.error });
      return;
    }
    startPolling(result.jobId as number);
  }

  async function handleInstallSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const modelName = newModelName.trim();
    if (!modelName) {
      return;
    }
    setPendingAction({ name: modelName, type: "install" });
    setProgress(null);
    const result = await installOllamaModelAction(projectId, modelName);
    if (result.error) {
      setPendingAction(null);
      setMessage({ type: "error", text: result.error });
      return;
    }
    startPolling(result.jobId as number);
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-neutral-500">
          選択中のモデル: <span className="font-medium text-neutral-700">{data.selected}</span>
        </p>
        <button
          type="button"
          onClick={() => setShowNewForm((v) => !v)}
          className="rounded bg-neutral-900 px-3 py-1.5 text-sm text-white"
        >
          + 新規インストール
        </button>
      </div>

      {message && (
        <p className={`text-sm ${message.type === "error" ? "text-red-600" : "text-green-600"}`}>{message.text}</p>
      )}

      {showNewForm && (
        <form
          onSubmit={handleInstallSubmit}
          className="flex flex-wrap items-end gap-2 rounded border border-neutral-200 bg-neutral-50 p-3 text-sm"
        >
          <label className="flex flex-col gap-1">
            <span className="text-neutral-600">Ollamaのモデル名(例: qwen2.5:7b-instruct)</span>
            <input
              value={newModelName}
              onChange={(e) => setNewModelName(e.target.value)}
              placeholder="qwen2.5:7b-instruct"
              required
              className="rounded border border-neutral-300 px-3 py-2 text-sm"
            />
          </label>
          <button
            type="submit"
            disabled={pendingAction?.type === "install"}
            className="rounded bg-neutral-900 px-3 py-2 text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            {pendingAction?.type === "install" ? "インストール中…" : "インストール"}
          </button>
          {pendingAction?.type === "install" && (
            <p className="w-full text-neutral-500">
              {progress ? formatJobProgress(progress) : "開始しています…"}
            </p>
          )}
        </form>
      )}

      {data.models.length === 0 ? (
        <p className="text-sm text-neutral-500">インストール済みのモデルはまだありません。</p>
      ) : (
        <div className="overflow-x-auto rounded border border-neutral-200">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-neutral-200 bg-neutral-50 text-neutral-500">
              <tr>
                <th className="px-2 py-1.5">モデル名</th>
                <th className="px-2 py-1.5">サイズ</th>
                <th className="px-2 py-1.5">更新日時</th>
                <th className="px-2 py-1.5">操作</th>
              </tr>
            </thead>
            <tbody>
              {data.models.map((model) => {
                const isSelected = model.name === data.selected;
                const isSelecting = pendingAction?.name === model.name && pendingAction?.type === "select";
                const isDeleting = pendingAction?.name === model.name && pendingAction?.type === "delete";
                const rowBusy = isSelecting || isDeleting;
                return (
                  <tr key={model.name} className="border-t border-neutral-100">
                    <td className="px-2 py-1.5 font-medium text-neutral-700">
                      {model.name}
                      {isSelected && (
                        <span className="ml-2 rounded bg-neutral-900 px-1.5 py-0.5 text-xs text-white">選択中</span>
                      )}
                    </td>
                    <td className="px-2 py-1.5 text-neutral-500">{formatBytes(model.sizeBytes)}</td>
                    <td className="px-2 py-1.5 text-neutral-500">{model.modifiedAt ?? "-"}</td>
                    <td className="px-2 py-1.5">
                      <div className="flex flex-wrap gap-2">
                        <button
                          type="button"
                          onClick={() => handleSelect(model.name)}
                          disabled={isSelected || rowBusy}
                          className="rounded bg-neutral-100 px-2 py-1 text-xs text-neutral-700 disabled:opacity-50"
                        >
                          {isSelecting ? "切替中…" : "選択"}
                        </button>
                        <button
                          type="button"
                          onClick={() => handleDelete(model.name)}
                          disabled={isSelected || rowBusy}
                          title={isSelected ? "選択中のモデルは削除できません" : undefined}
                          className="rounded bg-red-50 px-2 py-1 text-xs text-red-600 disabled:opacity-50"
                        >
                          {isDeleting ? "削除中…" : "削除"}
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function extractErrorMessage(resultPayload: string | null): string | null {
  if (!resultPayload) {
    return null;
  }
  try {
    const parsed = JSON.parse(resultPayload) as { error?: string };
    return parsed.error ?? null;
  } catch {
    return null;
  }
}
