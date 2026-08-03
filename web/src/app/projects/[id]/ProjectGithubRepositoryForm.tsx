"use client";

import { useActionState } from "react";
import { updateProjectGithubRepositoryAction, UpdateProjectGithubRepositoryState } from "./actions";

const initialState: UpdateProjectGithubRepositoryState = {};

export function ProjectGithubRepositoryForm({
  projectId,
  githubRepository,
}: {
  projectId: number;
  githubRepository: string | null;
}) {
  const action = (prevState: UpdateProjectGithubRepositoryState, formData: FormData) =>
    updateProjectGithubRepositoryAction(projectId, prevState, formData);
  const [state, formAction, pending] = useActionState(action, initialState);

  return (
    <div className="rounded-lg border border-neutral-200 bg-white p-4">
      <h3 className="mb-1 font-medium text-neutral-700">GitHub リポジトリ設定</h3>
      <p className="mb-3 text-sm text-neutral-500">
        記事計画で issue を作成する先のリポジトリを指定します。空にすると紐付けを解除します。
      </p>
      <form action={formAction} className="flex flex-wrap items-end gap-2 text-sm">
        <label className="flex flex-col gap-1">
          <span className="text-neutral-600">リポジトリ (owner/repo)</span>
          <input
            type="text"
            name="githubRepository"
            placeholder="anthropics/prompt-library"
            defaultValue={githubRepository ?? ""}
            className="rounded border border-neutral-300 px-3 py-2 text-sm font-mono"
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
