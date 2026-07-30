"use client";

import { useActionState } from "react";
import type { Project, ProjectEnvironment } from "@/lib/apiClient";
import { syncEnvironmentAction, SyncEnvironmentState } from "./actions";

const ENVIRONMENT_LABEL: Record<ProjectEnvironment, string> = {
  local: "ローカル",
  test: "テスト",
  production: "本番",
};

const initialState: SyncEnvironmentState = {};

export function EnvironmentSyncPanel({ projectId, project }: { projectId: number; project: Project }) {
  const action = (prevState: SyncEnvironmentState, formData: FormData) =>
    syncEnvironmentAction(projectId, prevState, formData);
  const [state, formAction, pending] = useActionState(action, initialState);

  const syncableEnvironments: { value: ProjectEnvironment; label: string }[] = (
    [
      ["local", project.localSite] as const,
      ["test", project.testSite] as const,
      ["production", project.productionSite] as const,
    ] as const
  )
    .filter(([, site]) => site?.managedWordpress)
    .map(([environment]) => ({ value: environment, label: ENVIRONMENT_LABEL[environment] }));

  if (syncableEnvironments.length < 2) {
    return (
      <div className="rounded-lg border border-neutral-200 bg-white p-4 text-sm text-neutral-500">
        <h3 className="mb-2 font-medium text-neutral-700">環境同期</h3>
        自動構築(managed)されたWordPress環境が2つ以上紐付いている場合のみ、テーマ・プラグイン・DBの同期が行えます。
      </div>
    );
  }

  function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    const formData = new FormData(e.currentTarget);
    const from = formData.get("from");
    const to = formData.get("to");
    const targets = formData.getAll("targets");
    if (
      !window.confirm(
        `${ENVIRONMENT_LABEL[from as ProjectEnvironment] ?? from}環境から${ENVIRONMENT_LABEL[to as ProjectEnvironment] ?? to}環境へ、` +
          `選択した内容(${targets.join(", ")})を同期します。同期先の内容は上書きされます。よろしいですか?`
      )
    ) {
      e.preventDefault();
    }
  }

  return (
    <div className="rounded-lg border border-neutral-200 bg-white p-4">
      <h3 className="mb-3 font-medium text-neutral-700">環境同期</h3>
      <form action={formAction} onSubmit={handleSubmit} className="space-y-3 text-sm">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1">
            <span className="text-neutral-600">同期元</span>
            <select name="from" required className="rounded border border-neutral-300 px-3 py-2 text-sm">
              <option value="">選択してください</option>
              {syncableEnvironments.map((env) => (
                <option key={env.value} value={env.value}>
                  {env.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-neutral-600">同期先</span>
            <select name="to" required className="rounded border border-neutral-300 px-3 py-2 text-sm">
              <option value="">選択してください</option>
              {syncableEnvironments.map((env) => (
                <option key={env.value} value={env.value}>
                  {env.label}
                </option>
              ))}
            </select>
          </label>
        </div>

        <fieldset className="flex gap-4">
          <legend className="mb-1 text-neutral-600">同期対象</legend>
          <label className="flex items-center gap-1.5">
            <input type="checkbox" name="targets" value="themes" />
            テーマ
          </label>
          <label className="flex items-center gap-1.5">
            <input type="checkbox" name="targets" value="plugins" />
            プラグイン
          </label>
          <label className="flex items-center gap-1.5">
            <input type="checkbox" name="targets" value="db" />
            DB
          </label>
        </fieldset>

        {state.error && <p className="text-red-600">{state.error}</p>}
        {state.success && <p className="text-green-600">同期しました。</p>}

        <button
          type="submit"
          disabled={pending}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {pending ? "同期中(数分かかる場合があります)…" : "同期する"}
        </button>
      </form>
    </div>
  );
}
