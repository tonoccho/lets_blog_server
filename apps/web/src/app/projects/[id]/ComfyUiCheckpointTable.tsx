"use client";

import { useState } from "react";
import type { ComfyUiCheckpointListResponse, GenerationJobDetail } from "@/lib/apiClient";
import {
  fetchComfyUiCheckpointsAction,
  selectComfyUiCheckpointAction,
  installComfyUiCheckpointAction,
  deleteComfyUiCheckpointAction,
} from "./actions";
import { pollFailureMessage, useGenerationJobPolling } from "./useGenerationJobPolling";
import { formatJobProgress, parseJobProgress, type JobProgress } from "./jobProgress";

const SAFE_FILE_NAME = /^[A-Za-z0-9_.-]+$/;

export function ComfyUiCheckpointTable({
  projectId,
  initialData,
}: {
  projectId: number;
  initialData: ComfyUiCheckpointListResponse;
}) {
  const [data, setData] = useState(initialData);
  const [showNewForm, setShowNewForm] = useState(false);
  const [downloadUrl, setDownloadUrl] = useState("");
  const [fileName, setFileName] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [message, setMessage] = useState<{ type: "error" | "success"; text: string } | null>(null);
  const [pendingAction, setPendingAction] = useState<{ name: string; type: "select" | "install" | "delete" } | null>(
    null
  );
  const [progress, setProgress] = useState<JobProgress | null>(null);

  async function refresh() {
    setData(await fetchComfyUiCheckpointsAction(projectId));
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
      setDownloadUrl("");
      setFileName("");
    }
    refresh();
  }

  const { startPolling } = useGenerationJobPolling(
    (job: GenerationJobDetail) => handleJobSettled(job),
    (job: GenerationJobDetail) => setProgress(parseJobProgress(job.resultPayload)),
    (error: unknown) => {
      setPendingAction(null);
      setProgress(null);
      setMessage({ type: "error", text: pollFailureMessage(error) });
    }
  );

  async function handleSelect(checkpointName: string) {
    setPendingAction({ name: checkpointName, type: "select" });
    const result = await selectComfyUiCheckpointAction(projectId, checkpointName);
    setPendingAction(null);
    if (result.error) {
      setMessage({ type: "error", text: result.error });
    } else {
      setMessage({ type: "success", text: "切り替えました。" });
      await refresh();
    }
  }

  async function handleDelete(checkpointName: string) {
    if (!window.confirm(`チェックポイント「${checkpointName}」を削除します。よろしいですか?`)) {
      return;
    }
    setPendingAction({ name: checkpointName, type: "delete" });
    setProgress(null);
    const result = await deleteComfyUiCheckpointAction(projectId, checkpointName);
    if (result.error) {
      setPendingAction(null);
      setMessage({ type: "error", text: result.error });
      return;
    }
    startPolling(result.jobId as number);
  }

  async function handleInstallSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setFormError(null);
    const url = downloadUrl.trim();
    const name = fileName.trim();
    if (!url || !name) {
      return;
    }
    if (!SAFE_FILE_NAME.test(name)) {
      setFormError("ファイル名は英数字・アンダースコア・ハイフン・ピリオドのみ使用できます。");
      return;
    }
    setPendingAction({ name, type: "install" });
    setProgress(null);
    const result = await installComfyUiCheckpointAction(projectId, url, name);
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
        <p className="text-sm text-neutral-500 dark:text-neutral-400">
          選択中のチェックポイント: <span className="font-medium text-neutral-700 dark:text-neutral-300">{data.selected}</span>
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
        // issue #1051: JS無効時のネイティブGETフォールバックで入力値がURLへ漏れることを防ぐため、
        // method="post"を明示する。送信自体はhandleInstallSubmitがpreventDefaultして処理する。
        <form
          onSubmit={handleInstallSubmit}
          method="post"
          className="flex flex-wrap items-end gap-2 rounded border border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 p-3 text-sm"
        >
          <label className="flex flex-col gap-1">
            <span className="text-neutral-600 dark:text-neutral-400">ダウンロードURL</span>
            <input
              value={downloadUrl}
              onChange={(e) => setDownloadUrl(e.target.value)}
              placeholder="https://huggingface.co/.../model.safetensors"
              required
              className="w-80 rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
            />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-neutral-600 dark:text-neutral-400">保存ファイル名(.safetensors推奨)</span>
            <input
              value={fileName}
              onChange={(e) => setFileName(e.target.value)}
              placeholder="my-model.safetensors"
              required
              className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
            />
          </label>
          <button
            type="submit"
            disabled={pendingAction?.type === "install"}
            className="rounded bg-neutral-900 px-3 py-2 text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            {pendingAction?.type === "install" ? "インストール中(数分かかる場合があります)…" : "インストール"}
          </button>
          {pendingAction?.type === "install" && (
            <p className="w-full text-neutral-500 dark:text-neutral-400">
              {progress ? formatJobProgress(progress) : "開始しています…"}
            </p>
          )}
          {formError && <p className="w-full text-red-600">{formError}</p>}
        </form>
      )}

      {data.checkpoints.length === 0 ? (
        <p className="text-sm text-neutral-500 dark:text-neutral-400">インストール済みのチェックポイントはまだありません。</p>
      ) : (
        <div className="overflow-x-auto rounded border border-neutral-200 dark:border-neutral-800">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-neutral-200 dark:border-neutral-800 bg-neutral-50 dark:bg-neutral-800 text-neutral-500 dark:text-neutral-400">
              <tr>
                <th className="px-2 py-1.5">チェックポイント</th>
                <th className="px-2 py-1.5">操作</th>
              </tr>
            </thead>
            <tbody>
              {data.checkpoints.map((checkpoint) => {
                const isSelected = checkpoint === data.selected;
                const isSelecting = pendingAction?.name === checkpoint && pendingAction?.type === "select";
                const isDeleting = pendingAction?.name === checkpoint && pendingAction?.type === "delete";
                const rowBusy = isSelecting || isDeleting;
                return (
                  <tr key={checkpoint} className="border-t border-neutral-100 dark:border-neutral-800">
                    <td className="px-2 py-1.5 font-medium text-neutral-700 dark:text-neutral-300">
                      {checkpoint}
                      {isSelected && (
                        <span className="ml-2 rounded bg-neutral-900 px-1.5 py-0.5 text-xs text-white">選択中</span>
                      )}
                    </td>
                    <td className="px-2 py-1.5">
                      <div className="flex flex-wrap gap-2">
                        <button
                          type="button"
                          onClick={() => handleSelect(checkpoint)}
                          disabled={isSelected || rowBusy}
                          className="rounded bg-neutral-100 dark:bg-neutral-800 px-2 py-1 text-xs text-neutral-700 dark:text-neutral-300 disabled:opacity-50"
                        >
                          {isSelecting ? "切替中…" : "選択"}
                        </button>
                        <button
                          type="button"
                          onClick={() => handleDelete(checkpoint)}
                          disabled={isSelected || rowBusy}
                          title={isSelected ? "選択中のチェックポイントは削除できません" : undefined}
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
