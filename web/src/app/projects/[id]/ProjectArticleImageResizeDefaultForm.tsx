"use client";

import { useActionState } from "react";
import { updateArticleImageResizeDefaultAction, UpdateArticleImageResizeDefaultState } from "./actions";

const initialState: UpdateArticleImageResizeDefaultState = {};

/** 記事投稿時の画像リサイズ(長編px)のデフォルト値を設定するフォーム(issue #291)。空にするとアプリ全体のデフォルト(1300px)に戻る。 */
export function ProjectArticleImageResizeDefaultForm({
  projectId,
  defaultArticleImageLongEdgePx,
}: {
  projectId: number;
  defaultArticleImageLongEdgePx: number | null;
}) {
  const action = (prevState: UpdateArticleImageResizeDefaultState, formData: FormData) =>
    updateArticleImageResizeDefaultAction(projectId, prevState, formData);
  const [state, formAction, pending] = useActionState(action, initialState);

  return (
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4">
      <h3 className="mb-1 font-medium text-neutral-700 dark:text-neutral-300">記事投稿時の画像リサイズ</h3>
      <p className="mb-3 text-sm text-neutral-500 dark:text-neutral-400">
        記事を投稿する際、本文中とアイキャッチの画像をこの長編(px)に収まるよう縮小します(拡大はしません)。
        空にするとアプリ全体のデフォルト(1300px)に戻ります。
      </p>
      <form action={formAction} className="flex flex-wrap items-end gap-2 text-sm">
        <label className="flex flex-col gap-1">
          <span className="text-neutral-600 dark:text-neutral-400">長編(px)</span>
          <input
            type="number"
            name="defaultArticleImageLongEdgePx"
            min={64}
            max={4096}
            placeholder="1300"
            defaultValue={defaultArticleImageLongEdgePx ?? ""}
            className="w-28 rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <button
          type="submit"
          disabled={pending}
          className="rounded bg-neutral-900 px-3 py-2 text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {pending ? "保存中…" : "保存"}
        </button>
        {state.error && <p className="text-red-600">{state.error}</p>}
        {state.success && <p className="text-green-600">保存しました。</p>}
      </form>
    </div>
  );
}
