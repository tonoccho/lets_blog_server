"use client";

import { useActionState } from "react";
import { updateProjectNameAction, UpdateProjectNameState } from "./actions";

const initialState: UpdateProjectNameState = {};

export function ProjectNameForm({ projectId, name }: { projectId: number; name: string }) {
  const action = (prevState: UpdateProjectNameState, formData: FormData) =>
    updateProjectNameAction(projectId, prevState, formData);
  const [state, formAction, pending] = useActionState(action, initialState);

  return (
    <form action={formAction} className="flex flex-wrap items-end gap-2 text-sm">
      <label className="flex flex-col gap-1">
        <span className="text-neutral-600 dark:text-neutral-400">プロジェクト名</span>
        <input
          name="name"
          defaultValue={name}
          required
          className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
        />
      </label>
      <button
        type="submit"
        disabled={pending}
        className="rounded bg-neutral-900 px-3 py-2 text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pending ? "保存中…" : "名前を保存"}
      </button>
      {state.error && <p className="text-red-600">{state.error}</p>}
      {state.success && <p className="text-green-600">保存しました。</p>}
    </form>
  );
}
