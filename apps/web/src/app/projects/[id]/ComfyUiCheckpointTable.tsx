"use client";

import { useState } from "react";
import type { ComfyUiCheckpointListResponse } from "@/lib/apiClient";
import { fetchComfyUiCheckpointsAction, selectComfyUiCheckpointAction } from "./actions";

/**
 * このプロジェクトが使うComfyUIのチェックポイントの一覧と選択。ComfyUIはGPU PCなど外部のサーバーへ
 * URLで接続する構成なので、チェックポイントの導入・削除は ComfyUI を動かしている PC 側で行う
 * (この画面には導入・削除の操作が無い。issue #1746)。
 */
export function ComfyUiCheckpointTable({
  projectId,
  initialData,
}: {
  projectId: number;
  initialData: ComfyUiCheckpointListResponse;
}) {
  const [data, setData] = useState(initialData);
  const [message, setMessage] = useState<{ type: "error" | "success"; text: string } | null>(null);
  const [pendingName, setPendingName] = useState<string | null>(null);

  async function refresh() {
    const result = await fetchComfyUiCheckpointsAction(projectId);
    if (result.data) {
      setData(result.data);
    } else {
      setMessage({ type: "error", text: `一覧の再取得に失敗しました: ${result.error}` });
    }
  }

  async function handleSelect(checkpointName: string) {
    setPendingName(checkpointName);
    const result = await selectComfyUiCheckpointAction(projectId, checkpointName);
    setPendingName(null);
    if (result.error) {
      setMessage({ type: "error", text: result.error });
    } else {
      setMessage({ type: "success", text: "切り替えました。" });
      await refresh();
    }
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-neutral-500 dark:text-neutral-400">
        選択中のチェックポイント: <span className="font-medium text-neutral-700 dark:text-neutral-300">{data.selected}</span>
      </p>

      {message && (
        <p className={`text-sm ${message.type === "error" ? "text-red-600" : "text-green-600"}`}>{message.text}</p>
      )}

      {data.checkpoints.length === 0 ? (
        <p className="text-sm text-neutral-500 dark:text-neutral-400">
          選択できるチェックポイントがありません。ComfyUI を動かしている PC の models/checkpoints/ に置いてから、
          開き直してください。
        </p>
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
                const isSelecting = pendingName === checkpoint;
                return (
                  <tr key={checkpoint} className="border-t border-neutral-100 dark:border-neutral-800">
                    <td className="px-2 py-1.5 font-medium text-neutral-700 dark:text-neutral-300">
                      {checkpoint}
                      {isSelected && (
                        <span className="ml-2 rounded bg-neutral-900 px-1.5 py-0.5 text-xs text-white">選択中</span>
                      )}
                    </td>
                    <td className="px-2 py-1.5">
                      <button
                        type="button"
                        onClick={() => handleSelect(checkpoint)}
                        disabled={isSelected || isSelecting}
                        className="rounded bg-neutral-100 dark:bg-neutral-800 px-2 py-1 text-xs text-neutral-700 dark:text-neutral-300 disabled:opacity-50"
                      >
                        {isSelecting ? "切替中…" : "選択"}
                      </button>
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
