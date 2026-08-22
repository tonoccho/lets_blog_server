"use client";

import { useActionState, useRef, useState } from "react";
import type { Project, ProjectEnvironment, Site } from "@/lib/apiClient";
import { syncEnvironmentAction, SyncEnvironmentState } from "./actions";

const ENVIRONMENT_LABEL: Record<ProjectEnvironment, string> = {
  local: "ローカル",
  test: "テスト",
  production: "本番",
};

const initialState: SyncEnvironmentState = {};

/** issue #325: ローカル開発向けに、マスタ環境→ローカルの同期設定をワンクリックで入力できるようにする。 */
export function EnvironmentSyncPanel({ projectId, project }: { projectId: number; project: Project }) {
  const action = (prevState: SyncEnvironmentState, formData: FormData) =>
    syncEnvironmentAction(projectId, prevState, formData);
  const [state, formAction, pending] = useActionState(action, initialState);
  const formRef = useRef<HTMLFormElement>(null);
  const [selectedFrom, setSelectedFrom] = useState<ProjectEnvironment | "">("");

  const sitesByEnvironment: Record<ProjectEnvironment, Site | null> = {
    local: project.localSite,
    test: project.testSite,
    production: project.productionSite,
  };

  // 同期元は自動構築(managed)環境に加え、SSH管理サイトも対象にする(issue #511。DB・メディア・テーマのみ対応)。
  // 同期先はファイルシステム・DBへの直接アクセス手段が要るため、managed環境限定のまま変更しない。
  const syncSourceEnvironments = (["local", "test", "production"] as const)
    .filter((environment) => environment !== "local")
    .filter((environment) => {
      const site = sitesByEnvironment[environment];
      return site?.managedWordpress || site?.sshConfigured;
    })
    .map((environment) => ({ value: environment, label: ENVIRONMENT_LABEL[environment] }));

  const syncDestinationEnvironments = (["local", "test", "production"] as const)
    .filter((environment) => environment !== "production")
    .filter((environment) => sitesByEnvironment[environment]?.managedWordpress)
    .map((environment) => ({ value: environment, label: ENVIRONMENT_LABEL[environment] }));

  const hasAnySyncableEnvironment =
    (["local", "test", "production"] as const).filter((environment) => {
      const site = sitesByEnvironment[environment];
      return site?.managedWordpress || site?.sshConfigured;
    }).length >= 2;

  const selectedFromSite = selectedFrom ? sitesByEnvironment[selectedFrom] : null;
  const fromIsSshOnly = selectedFromSite != null && !selectedFromSite.managedWordpress && selectedFromSite.sshConfigured;

  const masterEnvironment: ProjectEnvironment = project.masterEnvironment;
  const canFillFromMaster =
    sitesByEnvironment.local != null
    && syncSourceEnvironments.some((env) => env.value === masterEnvironment);

  function fillFromMasterToLocal() {
    const form = formRef.current;
    if (!form) return;
    (form.elements.namedItem("from") as HTMLSelectElement).value = masterEnvironment;
    setSelectedFrom(masterEnvironment);
    (form.elements.namedItem("to") as HTMLSelectElement).value = "local";
    const masterIsSshOnly = sitesByEnvironment[masterEnvironment]?.managedWordpress === false
      && sitesByEnvironment[masterEnvironment]?.sshConfigured === true;
    form.querySelectorAll<HTMLInputElement>('input[name="targets"]').forEach((el) => {
      el.checked = !masterIsSshOnly || el.value === "db" || el.value === "media" || el.value === "themes";
    });
  }

  if (!hasAnySyncableEnvironment || syncSourceEnvironments.length === 0 || syncDestinationEnvironments.length === 0) {
    return (
      <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4 text-sm text-neutral-500 dark:text-neutral-400">
        <h3 className="mb-2 font-medium text-neutral-700 dark:text-neutral-300">環境同期</h3>
        自動構築(managed)されたWordPress環境が2つ以上紐付いており、そのうちテスト環境または本番環境が
        1つ以上ある場合に、テーマ・プラグイン・メディア・DBの同期が行えます
        (ローカル環境は同期元に、本番環境は同期先に指定できません)。
        SSH管理の外部サイトはDB・メディア・テーマのみ同期元として指定できます。
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
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h3 className="font-medium text-neutral-700 dark:text-neutral-300">環境同期</h3>
        {canFillFromMaster && (
          <button
            type="button"
            onClick={fillFromMasterToLocal}
            className="text-sm text-blue-600 hover:underline dark:text-blue-400"
          >
            マスタ環境({ENVIRONMENT_LABEL[masterEnvironment]})→ローカルの設定を入力
          </button>
        )}
      </div>
      <form ref={formRef} action={formAction} onSubmit={handleSubmit} className="space-y-3 text-sm">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1">
            <span className="text-neutral-600 dark:text-neutral-400">同期元</span>
            <select
              name="from"
              required
              value={selectedFrom}
              onChange={(e) => {
                const value = e.target.value as ProjectEnvironment | "";
                setSelectedFrom(value);
                const site = value ? sitesByEnvironment[value] : null;
                const sshOnly = site != null && !site.managedWordpress && site.sshConfigured;
                if (sshOnly) {
                  formRef.current?.querySelectorAll<HTMLInputElement>('input[name="targets"]').forEach((el) => {
                    if (el.value !== "db" && el.value !== "media" && el.value !== "themes") {
                      el.checked = false;
                    }
                  });
                }
              }}
              className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
            >
              <option value="">選択してください</option>
              {syncSourceEnvironments.map((env) => (
                <option key={env.value} value={env.value}>
                  {env.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-neutral-600 dark:text-neutral-400">同期先</span>
            <select name="to" required className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm">
              <option value="">選択してください</option>
              {syncDestinationEnvironments.map((env) => (
                <option key={env.value} value={env.value}>
                  {env.label}
                </option>
              ))}
            </select>
          </label>
        </div>

        <fieldset className="flex gap-4">
          <legend className="mb-1 text-neutral-600 dark:text-neutral-400">
            同期対象{fromIsSshOnly && "(SSH管理サイトが同期元のためプラグインは選択できません)"}
          </legend>
          <label className="flex items-center gap-1.5">
            <input type="checkbox" name="targets" value="themes" />
            テーマ
          </label>
          <label className="flex items-center gap-1.5">
            <input type="checkbox" name="targets" value="plugins" disabled={fromIsSshOnly} />
            プラグイン
          </label>
          <label className="flex items-center gap-1.5">
            <input type="checkbox" name="targets" value="media" />
            メディア
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
