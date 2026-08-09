"use client";

import { useActionState, useEffect, useRef } from "react";
import { createProjectAction, CreateProjectState } from "./actions";

const initialState: CreateProjectState = {};

function slugify(value: string): string {
  return value
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9-]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

export function ProjectForm() {
  const [state, formAction, pending] = useActionState(createProjectAction, initialState);
  const formRef = useRef<HTMLFormElement>(null);
  const slugInputRef = useRef<HTMLInputElement>(null);
  const slugTouchedRef = useRef(false);

  useEffect(() => {
    if (state.success) {
      formRef.current?.reset();
      slugTouchedRef.current = false;
    }
  }, [state.success]);

  return (
    <form ref={formRef} action={formAction} className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <h2 className="font-medium">プロジェクトを作成</h2>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">プロジェクト名</span>
          <input
            name="name"
            required
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
            onChange={(e) => {
              if (!slugTouchedRef.current && slugInputRef.current) {
                slugInputRef.current.value = slugify(e.target.value);
              }
            }}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">slug(半角英数字・ハイフン)</span>
          <input
            ref={slugInputRef}
            name="slug"
            required
            pattern="[a-z0-9-]+"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
            onChange={() => {
              slugTouchedRef.current = true;
            }}
          />
        </label>
      </div>
      {state.error && <p className="text-sm text-red-600">{state.error}</p>}
      {state.success && <p className="text-sm text-green-600">作成しました。</p>}
      <button
        type="submit"
        disabled={pending}
        className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pending ? "作成中…" : "作成"}
      </button>
    </form>
  );
}
