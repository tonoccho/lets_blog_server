"use client";

import { useEffect, useState } from "react";
import { useCustomTagValidation } from "@/lib/useCustomTagValidation";
import { upsertProjectCustomTagAction } from "@/app/projects/[id]/custom-tags/actions";
import type { CustomTagJobResult } from "@/lib/llmJobResults";
import { ValidationPanel } from "./ValidationPanel";

/**
 * 処理キューの「結果を見る」の遷移先(issue #1409)。カスタムタグのAI生成ジョブの結果(HTML/CSS)を、
 * **未保存のまま**確認する。生成結果は `generation_jobs.result_payload` にだけあり、「保存」を押したときに
 * 初めて既存の保存先(`POST /api/custom-tags`)へ登録する。表示時に検証(`validateCustomTagAction`)も走らせる。
 */
export function CustomTagGenerationResult({
  projectId,
  result,
  effectivePrefix,
}: {
  projectId: number;
  result: CustomTagJobResult & { jobId: number };
  /** 統合CSS生成時に実際に適用されるCSSセレクタのプリフィックス(未設定時はプロジェクトのslug)。 */
  effectivePrefix?: string | null;
}) {
  const { isLoading, error, result: validationResult, validate } = useCustomTagValidation();
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  useEffect(() => {
    // 検証の要求自体が失敗しても、結果の表示は妨げない(失敗はフックの error に残り、パネルが示す)。
    validate(result.htmlTemplate, result.cssContent).catch(() => {});
    // 結果(ジョブ)が変わったときだけ検証し直す。validate は毎レンダーで作り直される。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [result.jobId]);

  async function handleSave() {
    setSaving(true);
    setSaveError(null);
    const formData = new FormData();
    formData.set("projectId", String(projectId));
    formData.set("tagName", result.tagName);
    formData.set("description", result.description ?? "");
    formData.set("htmlTemplate", result.htmlTemplate);
    formData.set("cssContent", result.cssContent);
    const state = await upsertProjectCustomTagAction({}, formData);
    setSaving(false);
    if (state.error) {
      setSaveError(state.error);
      return;
    }
    setSaved(true);
  }

  return (
    <div
      data-testid="custom-tag-generation-result"
      data-job-id={result.jobId}
      className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5"
    >
      <h2 className="font-medium">AIで生成したカスタムタグ(ジョブ #{result.jobId})</h2>
      <div className="rounded-lg bg-yellow-50 p-3 text-sm text-yellow-800">
        <p className="font-medium">生成完了。まだ保存されていません。</p>
        <p className="mt-1">内容を確認して「保存」を押すと、カスタムタグとして登録されます。</p>
      </div>
      <div className="space-y-2">
        <div>
          <span className="text-sm font-medium text-neutral-700 dark:text-neutral-300">タグ名:</span>
          <p className="font-mono text-sm text-neutral-600 dark:text-neutral-400">[{result.tagName}]</p>
        </div>
        <div>
          <span className="text-sm font-medium text-neutral-700 dark:text-neutral-300">説明:</span>
          <p className="text-sm text-neutral-600 dark:text-neutral-400">{result.description || "(なし)"}</p>
        </div>
        <div>
          <span className="text-sm font-medium text-neutral-700 dark:text-neutral-300">HTMLテンプレート:</span>
          <pre className="overflow-x-auto rounded bg-neutral-50 dark:bg-neutral-800 p-2 font-mono text-xs text-neutral-600 dark:text-neutral-400">
            {result.htmlTemplate}
          </pre>
        </div>
        {result.cssContent && (
          <div>
            <span className="text-sm font-medium text-neutral-700 dark:text-neutral-300">CSS:</span>
            <pre className="overflow-x-auto rounded bg-neutral-50 dark:bg-neutral-800 p-2 font-mono text-xs text-neutral-600 dark:text-neutral-400">
              {result.cssContent}
            </pre>
            {effectivePrefix && (
              <p className="mt-1 text-xs text-neutral-500 dark:text-neutral-400">
                上記はそのまま保存される内容です。実際に配信される統合CSSでは、各セレクタの先頭に自動でプリフィックス「
                <code>.{effectivePrefix}</code>」が付与されます(例: 先頭のセレクタは
                <code> .{effectivePrefix} {result.cssContent.trim().split(/[\s{]/)[0] || "..."}</code>
                のようになります)。
              </p>
            )}
          </div>
        )}
      </div>
      <ValidationPanel result={validationResult} isLoading={isLoading} error={error} />
      {saveError && <p className="text-sm text-red-600">{saveError}</p>}
      {saved ? (
        <p className="text-sm text-green-600">保存しました。</p>
      ) : (
        <button
          type="button"
          onClick={handleSave}
          disabled={saving}
          className="rounded bg-green-600 px-4 py-2 text-sm text-white hover:bg-green-700 disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {saving ? "保存中…" : "保存"}
        </button>
      )}
    </div>
  );
}
