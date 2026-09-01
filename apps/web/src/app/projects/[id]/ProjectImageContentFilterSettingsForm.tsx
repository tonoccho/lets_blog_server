"use client";

import { useActionState } from "react";
import { updateImageContentFilterSettingsAction, UpdateImageContentFilterSettingsState } from "./actions";

const initialState: UpdateImageContentFilterSettingsState = {};

/** 画像生成の不適切コンテンツフィルタ設定フォーム(issue #532)。各項目はデフォルトで禁止(ON)。 */
export function ProjectImageContentFilterSettingsForm({
  projectId,
  blockSexualContent,
  blockViolentContent,
  blockDiscriminatoryContent,
}: {
  projectId: number;
  blockSexualContent: boolean | null;
  blockViolentContent: boolean | null;
  blockDiscriminatoryContent: boolean | null;
}) {
  const action = (prevState: UpdateImageContentFilterSettingsState, formData: FormData) =>
    updateImageContentFilterSettingsAction(projectId, prevState, formData);
  const [state, formAction, pending] = useActionState(action, initialState);

  return (
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4">
      <h3 className="mb-1 font-medium text-neutral-700 dark:text-neutral-300">不適切コンテンツフィルタ</h3>
      <p className="mb-3 text-sm text-neutral-500 dark:text-neutral-400">
        画像生成プロンプトに含まれるキーワードを判定し、該当する場合は生成をブロックします。各項目はデフォルトで禁止(ON)です。
        プロンプトのキーワードによる簡易フィルタのため、生成される画像の内容そのものまでは判定できません。
        ChatGPT(DALL-E)側は性的コンテンツ等を常に強制フィルタするため、設定を解除してもプロバイダー側でブロックされる場合があります。
      </p>
      <form action={formAction} className="space-y-2 text-sm">
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            name="blockSexualContent"
            defaultChecked={blockSexualContent !== false}
            className="h-4 w-4"
          />
          <span className="text-neutral-600 dark:text-neutral-400">性的な画像の生成を禁止する</span>
        </label>
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            name="blockViolentContent"
            defaultChecked={blockViolentContent !== false}
            className="h-4 w-4"
          />
          <span className="text-neutral-600 dark:text-neutral-400">暴力的な画像の生成を禁止する</span>
        </label>
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            name="blockDiscriminatoryContent"
            defaultChecked={blockDiscriminatoryContent !== false}
            className="h-4 w-4"
          />
          <span className="text-neutral-600 dark:text-neutral-400">差別的な画像の生成を禁止する</span>
        </label>
        <div className="flex items-center gap-2 pt-1">
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
