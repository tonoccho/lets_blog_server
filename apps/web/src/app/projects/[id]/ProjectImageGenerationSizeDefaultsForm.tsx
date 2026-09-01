"use client";

import { useActionState } from "react";
import { updateImageGenerationSizeDefaultsAction, UpdateImageGenerationSizeDefaultsState } from "./actions";

const initialState: UpdateImageGenerationSizeDefaultsState = {};

/** 画像生成時のデフォルトサイズを設定するフォーム(issue #292)。空にするとアプリ全体のデフォルト(1920x1080)に戻る。 */
export function ProjectImageGenerationSizeDefaultsForm({
  projectId,
  defaultGeneratedImageWidth,
  defaultGeneratedImageHeight,
}: {
  projectId: number;
  defaultGeneratedImageWidth: number | null;
  defaultGeneratedImageHeight: number | null;
}) {
  const action = (prevState: UpdateImageGenerationSizeDefaultsState, formData: FormData) =>
    updateImageGenerationSizeDefaultsAction(projectId, prevState, formData);
  const [state, formAction, pending] = useActionState(action, initialState);

  return (
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4">
      <h3 className="mb-1 font-medium text-neutral-700 dark:text-neutral-300">画像生成のデフォルトサイズ</h3>
      <p className="mb-3 text-sm text-neutral-500 dark:text-neutral-400">
        画像生成フォームでサイズを指定しなかった場合に使われる幅・高さです(8の倍数、64〜2048)。
        空にするとアプリ全体のデフォルト(1920x1080)に戻ります。
      </p>
      <form action={formAction} className="flex flex-wrap items-end gap-2 text-sm">
        <label className="flex flex-col gap-1">
          <span className="text-neutral-600 dark:text-neutral-400">width</span>
          <input
            type="number"
            name="defaultGeneratedImageWidth"
            min={64}
            max={2048}
            step={8}
            placeholder="1920"
            defaultValue={defaultGeneratedImageWidth ?? ""}
            className="w-28 rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-neutral-600 dark:text-neutral-400">height</span>
          <input
            type="number"
            name="defaultGeneratedImageHeight"
            min={64}
            max={2048}
            step={8}
            placeholder="1080"
            defaultValue={defaultGeneratedImageHeight ?? ""}
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
