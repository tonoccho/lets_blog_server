"use client";

import { useState } from "react";
import type { ReviewStepSetting, ReviewStepSettingsResponse } from "@/lib/apiClient";
import { updateReviewStepSettingAction } from "./actions";

/**
 * 多段レビュー(issue #1210)の実行ステップの表示名。集合・順序はバックエンドの
 * `ReviewStepKey`(issue #1211)と同じ確定要件のため、ここではAPIが返す順序を
 * そのまま使い、表示名だけをこの対応表で引く。
 */
const STEP_LABEL: Record<string, string> = {
  JAPANESE: "日本語チェック",
  PROOFREADING: "校正チェック",
  FACT_CHECK: "校閲",
  READER_PERSPECTIVE: "読者視点でのチェック",
  STYLE: "文体チェック",
};

/** LlmProviderPanel.tsxの表示名対応表と揃える(issue #530由来)。 */
const PROVIDER_LABEL: Record<string, string> = {
  OLLAMA: "Ollama",
  OPENAI: "OpenAI (ChatGPT)",
  CLAUDE: "Claude (Anthropic)",
};

const UNSET_OPTION_LABEL = "(プロジェクト既定を使用)";

/**
 * ステップ別LLM設定(issue #1211のAPI)を、プロジェクト詳細画面のAIモデル管理カード
 * (LLMタブ)から確認・変更するパネル(issue #1212)。
 *
 * 設定を空へ戻す操作と保存失敗時のエラー表示は対象外(issue #1223へ切り出し)。保存は
 * provider/modelの現在値をまとめて1回のPUTで送る(APIが全体上書きのため、issue #1211の
 * `ReviewStepModelService#selectSetting`参照)。
 */
export function ReviewStepSettingsPanel({
  projectId,
  initialData,
}: {
  projectId: number;
  initialData: ReviewStepSettingsResponse;
}) {
  const [steps, setSteps] = useState(initialData.steps);

  function applySaved(updated: ReviewStepSetting) {
    setSteps((prev) => prev.map((s) => (s.stepKey === updated.stepKey ? updated : s)));
  }

  return (
    <div className="space-y-2">
      <p className="text-sm font-medium text-neutral-700 dark:text-neutral-300">レビューステップ別のAIモデル設定</p>
      <table className="w-full text-sm">
        <thead>
          <tr className="text-left text-neutral-500 dark:text-neutral-400">
            <th className="pb-1 pr-2 font-normal">ステップ</th>
            <th className="pb-1 pr-2 font-normal">プロバイダー</th>
            <th className="pb-1 pr-2 font-normal">モデル</th>
            <th className="pb-1 font-normal" />
          </tr>
        </thead>
        <tbody>
          {steps.map((step) => (
            <ReviewStepRow
              key={step.stepKey}
              projectId={projectId}
              step={step}
              availableProviders={initialData.availableProviders}
              availableModels={initialData.availableModels}
              onSaved={applySaved}
            />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ReviewStepRow({
  projectId,
  step,
  availableProviders,
  availableModels,
  onSaved,
}: {
  projectId: number;
  step: ReviewStepSetting;
  availableProviders: string[];
  availableModels: string[];
  onSaved: (updated: ReviewStepSetting) => void;
}) {
  const [provider, setProvider] = useState(step.provider ?? "");
  const [model, setModel] = useState(step.model ?? "");
  const [saving, setSaving] = useState(false);
  const label = STEP_LABEL[step.stepKey];

  async function handleSave() {
    setSaving(true);
    const result = await updateReviewStepSettingAction(projectId, step.stepKey, provider, model);
    setSaving(false);
    if (!result.error) {
      onSaved({ stepKey: step.stepKey, provider: provider || null, model: model || null });
    }
  }

  return (
    <tr className="border-t border-neutral-200 dark:border-neutral-800">
      <td className="py-1.5 pr-2">{label}</td>
      <td className="py-1.5 pr-2">
        <select
          aria-label={`${label}のプロバイダー`}
          value={provider}
          disabled={saving}
          onChange={(e) => setProvider(e.target.value)}
          className="rounded border border-neutral-300 dark:border-neutral-700 bg-white dark:bg-neutral-900 px-2 py-1 text-sm disabled:opacity-60"
        >
          <option value="">{UNSET_OPTION_LABEL}</option>
          {availableProviders.map((value) => (
            <option key={value} value={value}>
              {PROVIDER_LABEL[value] ?? value}
            </option>
          ))}
        </select>
      </td>
      <td className="py-1.5 pr-2">
        <select
          aria-label={`${label}のモデル`}
          value={model}
          disabled={saving}
          onChange={(e) => setModel(e.target.value)}
          className="rounded border border-neutral-300 dark:border-neutral-700 bg-white dark:bg-neutral-900 px-2 py-1 text-sm disabled:opacity-60"
        >
          <option value="">{UNSET_OPTION_LABEL}</option>
          {availableModels.map((value) => (
            <option key={value} value={value}>
              {value}
            </option>
          ))}
        </select>
      </td>
      <td className="py-1.5">
        <button
          type="button"
          onClick={handleSave}
          disabled={saving}
          className="rounded bg-neutral-900 px-2 py-1 text-xs text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {saving ? "保存中…" : "保存"}
        </button>
      </td>
    </tr>
  );
}
