"use client";

import { useActionState, useRef, useEffect } from "react";
import type { AppUser } from "@/lib/apiClient";
import { addProjectUserAction, AddProjectUserState } from "./actions";
import { WP_ROLES } from "./ProjectUserManager";

const initialState: AddProjectUserState = {};

export function AddProjectUserModal({ projectId, candidateUsers }: { projectId: number; candidateUsers: AppUser[] }) {
  const action = (prevState: AddProjectUserState, formData: FormData) =>
    addProjectUserAction(projectId, prevState, formData);
  const [state, formAction, pending] = useActionState(action, initialState);
  const formRef = useRef<HTMLFormElement>(null);

  useEffect(() => {
    if (state.success) {
      formRef.current?.reset();
    }
  }, [state.success]);

  return (
    <form
      ref={formRef}
      action={formAction}
      className="flex flex-wrap items-end gap-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5 text-sm"
    >
      <label className="flex flex-col gap-1">
        <span className="text-neutral-600 dark:text-neutral-400">ユーザーを追加</span>
        <select name="userId" required className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm">
          <option value="">選択…</option>
          {candidateUsers.map((user) => (
            <option key={user.id} value={user.id}>
              {user.email}
            </option>
          ))}
        </select>
      </label>
      <label className="flex flex-col gap-1">
        <span className="text-neutral-600 dark:text-neutral-400">ロール</span>
        <select name="wpRole" defaultValue="contributor" className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm">
          {WP_ROLES.map((role) => (
            <option key={role} value={role}>
              {role}
            </option>
          ))}
        </select>
      </label>
      <button
        type="submit"
        disabled={pending}
        className="rounded bg-neutral-900 px-4 py-2 text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pending ? "追加中…" : "追加"}
      </button>
      {state.error && <p className="w-full text-red-600">{state.error}</p>}
      {state.success && <p className="w-full text-green-600">追加しました。</p>}
    </form>
  );
}
