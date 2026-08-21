"use client";

import { useEffect, useState } from "react";
import type {
  LlmModelListResponse,
  LlmProviderListResponse,
  ImageProviderListResponse,
  ComfyUiCheckpointListResponse,
} from "@/lib/apiClient";
import {
  fetchLlmModelsAction,
  fetchLlmProviderAction,
  fetchImageProviderAction,
  fetchComfyUiCheckpointsAction,
} from "./actions";
import { LlmModelPanel } from "./LlmModelPanel";
import { LlmProviderPanel } from "./LlmProviderPanel";
import { ImageProviderPanel } from "./ImageProviderPanel";
import { ComfyUiCheckpointTable } from "./ComfyUiCheckpointTable";

type Tab = "LLM" | "COMFYUI";

const TAB_LABEL: Record<Tab, string> = {
  LLM: "LLM",
  COMFYUI: "画像生成",
};

export function ProjectAiModelsPanel({ projectId }: { projectId: number }) {
  const [tab, setTab] = useState<Tab>("LLM");
  // タブを初めて開いたときにクライアント側から取得する(初期表示でLLM/ComfyUI双方への
  // 疎通を待たせないため。一括管理パネルのプラグイン/テーマタブと同じ方針)。
  const [llmData, setLlmData] = useState<LlmModelListResponse | null>(null);
  const [llmProviderData, setLlmProviderData] = useState<LlmProviderListResponse | null>(null);
  const [imageProviderData, setImageProviderData] = useState<ImageProviderListResponse | null>(null);
  const [comfyuiData, setComfyuiData] = useState<ComfyUiCheckpointListResponse | null>(null);
  const [loadingTab, setLoadingTab] = useState<Tab | null>(null);

  async function handleTabChange(nextTab: Tab) {
    setTab(nextTab);
    if (nextTab === "LLM" && llmData === null) {
      setLoadingTab(nextTab);
      setLlmData(await fetchLlmModelsAction(projectId));
      setLlmProviderData(await fetchLlmProviderAction(projectId));
      setLoadingTab(null);
    } else if (nextTab === "COMFYUI" && comfyuiData === null) {
      setLoadingTab(nextTab);
      setImageProviderData(await fetchImageProviderAction(projectId));
      setComfyuiData(await fetchComfyUiCheckpointsAction(projectId));
      setLoadingTab(null);
    }
  }

  // 初回マウント時に、デフォルト表示のLLMタブ分だけ取得しておく
  useEffect(() => {
    fetchLlmModelsAction(projectId).then(setLlmData);
    fetchLlmProviderAction(projectId).then(setLlmProviderData);
  }, [projectId]);

  return (
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4">
      <h3 className="mb-1 font-medium text-neutral-700 dark:text-neutral-300">AIモデル管理</h3>
      <p className="mb-3 text-sm text-neutral-500 dark:text-neutral-400">
        壁打ちチャット等で使用するLLMモデル、画像生成AI(ComfyUI/ChatGPT)とComfyUIチェックポイントを、
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

      {tab === "LLM" &&
        (llmData ? (
          <div className="space-y-4">
            {llmProviderData && <LlmProviderPanel projectId={projectId} initialData={llmProviderData} />}
            <LlmModelPanel projectId={projectId} initialData={llmData} />
          </div>
        ) : (
          <TabLoading loading={loadingTab === "LLM"} />
        ))}
      {tab === "COMFYUI" &&
        (comfyuiData ? (
          <div className="space-y-4">
            {imageProviderData && <ImageProviderPanel projectId={projectId} initialData={imageProviderData} />}
            <ComfyUiCheckpointTable projectId={projectId} initialData={comfyuiData} />
          </div>
        ) : (
          <TabLoading loading={loadingTab === "COMFYUI"} />
        ))}
    </div>
  );
}

function TabLoading({ loading }: { loading: boolean }) {
  return <p className="text-sm text-neutral-500 dark:text-neutral-400">{loading ? "読み込み中…" : "このタブを開くとデータを取得します。"}</p>;
}
