"use client";

import { useActionState } from "react";
import { updateProjectCssSelectorPrefixAction, type CssSelectorPrefixFormState } from "./actions";

const initialState: CssSelectorPrefixFormState = {};

/**
 * 統合CSSのセレクタ接頭辞の入力フォーム(issue #298 / #1128)。
 * 空欄で保存すると未設定になり、接頭辞はプロジェクトのslugへ戻る。
 */
export function CssSelectorPrefixForm({
  projectId,
  projectSlug,
  cssSelectorPrefix,
}: {
  projectId: number;
  projectSlug: string;
  cssSelectorPrefix: string | null;
}) {
  const [state, formAction, pending] = useActionState(
    updateProjectCssSelectorPrefixAction.bind(null, projectId),
    initialState
  );

  return (
    <form
      action={formAction}
      className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5"
    >
      <h2 className="text-base font-semibold">CSSセレクタ接頭辞</h2>
      <p className="max-w-2xl text-sm text-neutral-600 dark:text-neutral-400">
        統合CSSの各セレクタの先頭に付く接頭辞です。公開先テーマのクラス名とプロジェクトのslugが衝突する場合に変更します。
        空欄で保存すると未設定になり、slugが使われます。現在の接頭辞:{" "}
        <code data-testid="effective-prefix">{cssSelectorPrefix ?? projectSlug}</code>
      </p>
      <label className="flex max-w-sm flex-col gap-1 text-sm">
        <span className="text-neutral-600 dark:text-neutral-400">CSSセレクタ接頭辞</span>
        <input
          type="text"
          name="cssSelectorPrefix"
          defaultValue={cssSelectorPrefix ?? ""}
          placeholder={projectSlug}
          className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
        />
      </label>
      {state.error && <p className="text-sm text-red-600 dark:text-red-400">{state.error}</p>}
      {state.success && (
        <p className="text-sm text-green-700 dark:text-green-400">CSSセレクタ接頭辞を保存しました。</p>
      )}
      <button
        type="submit"
        disabled={pending}
        className="rounded bg-neutral-900 px-4 py-2 text-sm text-white hover:bg-neutral-800 disabled:bg-neutral-300 dark:disabled:bg-neutral-700"
      >
        {pending ? "保存中..." : "接頭辞を保存"}
      </button>
    </form>
  );
}
