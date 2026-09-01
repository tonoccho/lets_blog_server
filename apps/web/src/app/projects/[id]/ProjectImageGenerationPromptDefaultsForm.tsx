"use client";

import { useActionState } from "react";
import { updateImageGenerationPromptDefaultsAction, UpdateImageGenerationPromptDefaultsState } from "./actions";

const initialState: UpdateImageGenerationPromptDefaultsState = {};

/** 画像生成時のnegative prompt/画質プロンプトのデフォルト値を設定するフォーム(issue #293)。 */
export function ProjectImageGenerationPromptDefaultsForm({
  projectId,
  defaultNegativePrompt,
  defaultQualityPrompt,
}: {
  projectId: number;
  defaultNegativePrompt: string | null;
  defaultQualityPrompt: string | null;
}) {
  const action = (prevState: UpdateImageGenerationPromptDefaultsState, formData: FormData) =>
    updateImageGenerationPromptDefaultsAction(projectId, prevState, formData);
  const [state, formAction, pending] = useActionState(action, initialState);

  return (
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4">
      <h3 className="mb-1 font-medium text-neutral-700 dark:text-neutral-300">画像生成のデフォルトプロンプト</h3>
      <p className="mb-3 text-sm text-neutral-500 dark:text-neutral-400">
        画像生成のたびにnegative promptへ自動的に使われる内容と、本文プロンプトの末尾へ自動的に追加される画質プロンプトです。
        空にするとアプリ全体のデフォルトに戻ります。
      </p>
      <form action={formAction} className="space-y-3 text-sm">
        <label className="flex flex-col gap-1">
          <span className="text-neutral-600 dark:text-neutral-400">negative prompt</span>
          <textarea
            name="defaultNegativePrompt"
            rows={2}
            placeholder="low quality, blurry, watermark, text"
            defaultValue={defaultNegativePrompt ?? ""}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 font-mono text-sm"
          />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-neutral-600 dark:text-neutral-400">画質プロンプト(本文プロンプトの末尾へ追加)</span>
          <textarea
            name="defaultQualityPrompt"
            rows={2}
            placeholder="high quality, highly detailed, sharp focus, masterpiece"
            defaultValue={defaultQualityPrompt ?? ""}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 font-mono text-sm"
          />
        </label>
        <div className="flex items-center gap-2">
          <button
            type="submit"
            disabled={pending}
            className="rounded bg-neutral-900 px-3 py-2 text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            {pending ? "保存中…" : "保存"}
          </button>
          {state.error && <p className="text-red-600">{state.error}</p>}
          {state.success && <p className="text-green-600">保存しました。</p>}
        </div>
      </form>
    </div>
  );
}
