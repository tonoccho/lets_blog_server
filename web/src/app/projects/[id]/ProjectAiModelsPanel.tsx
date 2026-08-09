"use client";

import { useEffect, useState } from "react";
import type { OllamaModelListResponse, ComfyUiCheckpointListResponse } from "@/lib/apiClient";
import { fetchOllamaModelsAction, fetchComfyUiCheckpointsAction } from "./actions";
import { OllamaModelTable } from "./OllamaModelTable";
import { ComfyUiCheckpointTable } from "./ComfyUiCheckpointTable";

type Tab = "OLLAMA" | "COMFYUI";

const TAB_LABEL: Record<Tab, string> = {
  OLLAMA: "Ollama",
  COMFYUI: "ComfyUI",
};

export function ProjectAiModelsPanel({ projectId }: { projectId: number }) {
  const [tab, setTab] = useState<Tab>("OLLAMA");
  // タブを初めて開いたときにクライアント側から取得する(初期表示でOllama/ComfyUI双方への
  // 疎通を待たせないため。一括管理パネルのプラグイン/テーマタブと同じ方針)。
  const [ollamaData, setOllamaData] = useState<OllamaModelListResponse | null>(null);
  const [comfyuiData, setComfyuiData] = useState<ComfyUiCheckpointListResponse | null>(null);
  const [loadingTab, setLoadingTab] = useState<Tab | null>(null);

  async function handleTabChange(nextTab: Tab) {
    setTab(nextTab);
    if (nextTab === "OLLAMA" && ollamaData === null) {
      setLoadingTab(nextTab);
      setOllamaData(await fetchOllamaModelsAction(projectId));
      setLoadingTab(null);
    } else if (nextTab === "COMFYUI" && comfyuiData === null) {
      setLoadingTab(nextTab);
      setComfyuiData(await fetchComfyUiCheckpointsAction(projectId));
      setLoadingTab(null);
    }
  }

  // 初回マウント時に、デフォルト表示のOllamaタブ分だけ取得しておく
  useEffect(() => {
    fetchOllamaModelsAction(projectId).then(setOllamaData);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId]);

  return (
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4">
      <h3 className="mb-1 font-medium text-neutral-700 dark:text-neutral-300">AIモデル管理</h3>
      <p className="mb-3 text-sm text-neutral-500 dark:text-neutral-400">
        壁打ちチャット等で使用するOllamaモデル、画像生成で使用するComfyUIチェックポイントを、
        プロジェクトごとに切り替えられます。
      </p>

      <div className="mb-4 flex gap-2 text-sm">
        {(Object.keys(TAB_LABEL) as Tab[]).map((t) => (
          <button
            key={t}
            type="button"
            onClick={() => handleTabChange(t)}
            className={`rounded px-3 py-1.5 ${tab === t ? "bg-neutral-900 text-white" : "bg-neutral-100 dark:bg-neutral-800 text-neutral-600 dark:text-neutral-400"}`}
          >
            {TAB_LABEL[t]}
          </button>
        ))}
      </div>

      {tab === "OLLAMA" &&
        (ollamaData ? (
          <OllamaModelTable projectId={projectId} initialData={ollamaData} />
        ) : (
          <TabLoading loading={loadingTab === "OLLAMA"} />
        ))}
      {tab === "COMFYUI" &&
        (comfyuiData ? (
          <ComfyUiCheckpointTable projectId={projectId} initialData={comfyuiData} />
        ) : (
          <TabLoading loading={loadingTab === "COMFYUI"} />
        ))}
    </div>
  );
}

function TabLoading({ loading }: { loading: boolean }) {
  return <p className="text-sm text-neutral-500 dark:text-neutral-400">{loading ? "読み込み中…" : "このタブを開くとデータを取得します。"}</p>;
}
