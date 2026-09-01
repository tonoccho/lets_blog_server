"use client";

import { useActionState } from "react";
import type { Project } from "@/lib/apiClient";
import { updateMasterEnvironmentAction, UpdateMasterEnvironmentState } from "./actions";

const initialState: UpdateMasterEnvironmentState = {};

export function MasterEnvironmentSelector({ projectId, project }: { projectId: number; project: Project }) {
  const action = (prevState: UpdateMasterEnvironmentState, formData: FormData) =>
    updateMasterEnvironmentAction(projectId, prevState, formData);
  const [state, formAction, pending] = useActionState(action, initialState);

  return (
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4">
      <h3 className="mb-1 font-medium text-neutral-700 dark:text-neutral-300">マスター環境</h3>
      <p className="mb-3 text-sm text-neutral-500 dark:text-neutral-400">
        カテゴリ・タグ・プラグイン・テーマの比較テーブルで「正」として扱う環境を選択します(ローカルは指定できません)。
      </p>
      <form action={formAction} className="flex flex-wrap items-end gap-2 text-sm">
        <label className="flex flex-col gap-1">
          <span className="text-neutral-600 dark:text-neutral-400">マスター環境</span>
          <select
            name="masterEnvironment"
            defaultValue={project.masterEnvironment}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          >
            <option value="test">テスト{project.testSite ? "" : "(未紐付け)"}</option>
            <option value="production">本番{project.productionSite ? "" : "(未紐付け)"}</option>
          </select>
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
