"use client";

import { useEffect, useRef, useState } from "react";
import type {
  LlmModelListResponse,
  LlmProviderListResponse,
  ReviewStepSettingsResponse,
  ImageProviderListResponse,
  ComfyUiCheckpointListResponse,
} from "@/lib/apiClient";
import {
  fetchLlmModelsAction,
  fetchLlmProviderAction,
  fetchReviewStepSettingsAction,
  fetchImageProviderAction,
  fetchComfyUiCheckpointsAction,
} from "./actions";
import type { FetchResult } from "./actions";
import { LlmModelPanel } from "./LlmModelPanel";
import { LlmProviderPanel } from "./LlmProviderPanel";
import { ReviewStepSettingsPanel } from "./ReviewStepSettingsPanel";
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
  const [reviewStepData, setReviewStepData] = useState<ReviewStepSettingsResponse | null>(null);
  const [imageProviderData, setImageProviderData] = useState<ImageProviderListResponse | null>(null);
  const [comfyuiData, setComfyuiData] = useState<ComfyUiCheckpointListResponse | null>(null);
  const [loadingTab, setLoadingTab] = useState<Tab | null>(null);
  // 直近に初回取得を行ったprojectId(issue #1310)。開発サーバー(React Strict Mode)では
  // マウント時のuseEffectが2回発火し、fetchLlmModelsAction等が同じprojectIdに対して
  // 並行で2回ずつ(6並行)呼ばれる。実際に落ちた受け入れテストの再現では、この2回の
  // fetchReviewStepSettingsActionがどちらも同じ(誤った)未設定値を返しており、
  // 「後勝ちの順序問題」ではなく、この並行リクエストのバースト自体がサーバー側の
  // 読み取り不整合を誘発していた(直前のPUTで保存した値がGETに反映されない)。
  // 本番(Strict Modeなし)ではuseEffectは1回しか発火しないため、このrefで開発時の
  // 2回目の発火を無視し、本番と同じ「projectIdごとに1回だけ取得する」挙動に揃える。
  const fetchedProjectIdRef = useRef<number | null>(null);

  // 取得に失敗した理由(issue #1715)。成功した部分は表示し、失敗した部分だけ理由を出す。
  // タブを押し直すと、まだ取得できていない部分だけ再取得する。
  const [llmErrors, setLlmErrors] = useState<string[]>([]);
  const [imageErrors, setImageErrors] = useState<string[]>([]);

  async function loadLlm() {
    const [models, provider, reviewStep] = await Promise.all([
      llmData === null ? fetchLlmModelsAction(projectId) : null,
      llmProviderData === null ? fetchLlmProviderAction(projectId) : null,
      reviewStepData === null ? fetchReviewStepSettingsAction(projectId) : null,
    ]);
    if (models?.data) setLlmData(models.data);
    if (provider?.data) setLlmProviderData(provider.data);
    if (reviewStep?.data) setReviewStepData(reviewStep.data);
    setLlmErrors(errorsOf(models, provider, reviewStep));
  }

  async function loadImage() {
    const [provider, checkpoints] = await Promise.all([
      imageProviderData === null ? fetchImageProviderAction(projectId) : null,
      comfyuiData === null ? fetchComfyUiCheckpointsAction(projectId) : null,
    ]);
    if (provider?.data) setImageProviderData(provider.data);
    if (checkpoints?.data) setComfyuiData(checkpoints.data);
    setImageErrors(errorsOf(provider, checkpoints));
  }

  async function handleTabChange(nextTab: Tab) {
    setTab(nextTab);
    if (nextTab === "LLM" && (llmData === null || llmProviderData === null || reviewStepData === null)) {
      setLoadingTab(nextTab);
      await loadLlm();
      setLoadingTab(null);
    } else if (nextTab === "COMFYUI" && (imageProviderData === null || comfyuiData === null)) {
      setLoadingTab(nextTab);
      await loadImage();
      setLoadingTab(null);
    }
  }

  // LlmModelPanelは初期値をstateへ取り込むため、プロバイダー切り替えで取得し直したデータへ作り直させる
  // (key を変える)。再読み込みなしで切り替え先のモデルが選択中・入力欄・候補チップに出る(issue #1644)。
  const [llmModelVersion, setLlmModelVersion] = useState(0);

  // モデルの保存回数。プロバイダー切り替え後の再取得の最中に保存されたら、その取得結果は古いので捨てる。
  const modelSaveCountRef = useRef(0);

  async function handleProviderChanged() {
    const savesBefore = modelSaveCountRef.current;
    const fresh = await fetchLlmModelsAction(projectId);
    if (modelSaveCountRef.current !== savesBefore) {
      return;
    }
    if (!fresh.data) {
      setLlmErrors(errorsOf(fresh));
      return;
    }
    setLlmData(fresh.data);
    setLlmModelVersion((v) => v + 1);
  }

  // 初回マウント時に、デフォルト表示のLLMタブ分だけ取得しておく
  useEffect(() => {
    if (fetchedProjectIdRef.current === projectId) {
      return;
    }
    fetchedProjectIdRef.current = projectId;
    void loadLlm();
    // loadLlm は毎回作り直される関数で、projectIdごとに1回だけ呼ぶ(上のref)ため依存に含めない。
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

      {tab === "LLM" && (
        <div className="space-y-4">
          <FetchErrors messages={llmErrors} />
          {llmProviderData && <LlmProviderPanel projectId={projectId} initialData={llmProviderData} onChanged={handleProviderChanged} />}
          {llmData && (
            <LlmModelPanel
              key={llmModelVersion}
              projectId={projectId}
              initialData={llmData}
              onSaved={() => {
                modelSaveCountRef.current += 1;
              }}
            />
          )}
          {reviewStepData && <ReviewStepSettingsPanel projectId={projectId} initialData={reviewStepData} />}
          {!llmData && !llmProviderData && !reviewStepData && llmErrors.length === 0 && (
            <TabLoading loading={loadingTab === "LLM"} />
          )}
        </div>
      )}
      {tab === "COMFYUI" && (
        <div className="space-y-4">
          <FetchErrors messages={imageErrors} />
          {imageProviderData && <ImageProviderPanel projectId={projectId} initialData={imageProviderData} />}
          {comfyuiData && <ComfyUiCheckpointTable projectId={projectId} initialData={comfyuiData} />}
          {!imageProviderData && !comfyuiData && imageErrors.length === 0 && (
            <TabLoading loading={loadingTab === "COMFYUI"} />
          )}
        </div>
      )}
    </div>
  );
}

function errorsOf(...results: (FetchResult<unknown> | null)[]): string[] {
  return results.flatMap((r) => (r?.error ? [r.error] : []));
}

function FetchErrors({ messages }: { messages: string[] }) {
  return (
    <>
      {messages.map((m, i) => (
        <p key={i} role="alert" className="text-sm text-red-600">
          取得に失敗しました: {m}(タブを押し直すと再取得します)
        </p>
      ))}
    </>
  );
}

function TabLoading({ loading }: { loading: boolean }) {
  return <p className="text-sm text-neutral-500 dark:text-neutral-400">{loading ? "読み込み中…" : "このタブを開くとデータを取得します。"}</p>;
}
