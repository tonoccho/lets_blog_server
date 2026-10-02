"use client";

import { useActionState, useTransition } from "react";
import { ExternalLink, Settings } from "lucide-react";
import type { Site, ProjectEnvironment } from "@/lib/apiClient";
import { resolveSiteAdminUrl } from "@/lib/siteAdminUrl";
import { bindEnvironmentAction, unbindEnvironmentAction, EnvironmentActionState } from "./actions";

const ENVIRONMENT_LABEL: Record<ProjectEnvironment, string> = {
  local: "ローカル",
  test: "テスト",
  production: "本番",
};

const initialState: EnvironmentActionState = {};

export function EnvironmentSlot({
  projectId,
  environment,
  site,
  candidateSites,
  adminPath = "wp-admin",
}: {
  projectId: number;
  environment: ProjectEnvironment;
  site: Site | null;
  candidateSites: Site[];
  adminPath?: string;
}) {
  const action = (prevState: EnvironmentActionState, formData: FormData) =>
    bindEnvironmentAction(projectId, environment, prevState, formData);
  const [state, formAction, pending] = useActionState(action, initialState);
  const [isUnbinding, startUnbind] = useTransition();
  const adminUrl = site ? resolveSiteAdminUrl(site.baseUrl, adminPath) : null;

  return (
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-4">
      <h3 className="mb-2 font-medium">{ENVIRONMENT_LABEL[environment]}環境</h3>
      {site ? (
        <div className="space-y-2 text-sm">
          <p className="font-mono">{site.siteKey}</p>
          <p className="text-neutral-600 dark:text-neutral-400">{site.name}</p>
          <span className="inline-flex items-center gap-3">
            <a
              href={site.baseUrl}
              target="_blank"
              rel="noreferrer"
              aria-label={`${site.name} のサイトを開く`}
              className="text-blue-700 hover:text-blue-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
            >
              <ExternalLink className="h-4 w-4" aria-hidden="true" />
            </a>
            {adminUrl && (
              <a
                href={adminUrl}
                target="_blank"
                rel="noreferrer"
                aria-label={`${site.name} の管理画面を開く`}
                className="text-blue-700 hover:text-blue-900 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
              >
                <Settings className="h-4 w-4" aria-hidden="true" />
              </a>
            )}
          </span>
          <div>
            <button
              type="button"
              disabled={isUnbinding}
              onClick={() => startUnbind(() => unbindEnvironmentAction(projectId, environment))}
              className="text-sm text-red-600 hover:underline disabled:text-neutral-400"
            >
              {isUnbinding ? "切離し中…" : "切離し"}
            </button>
          </div>
        </div>
      ) : (
        <form action={formAction} className="space-y-2 text-sm">
          <p className="font-medium text-neutral-500 dark:text-neutral-400">未設定</p>
          <select
            name="siteId"
            required
            aria-label={`${ENVIRONMENT_LABEL[environment]}環境に紐付けるサイト`}
            className="w-full rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          >
            <option value="">サイトを選択…</option>
            {candidateSites.map((candidate) => (
              <option key={candidate.id} value={candidate.id}>
                {candidate.name} ({candidate.siteKey})
              </option>
            ))}
          </select>
          {state.error && <p className="text-red-600">{state.error}</p>}
          <button
            type="submit"
            disabled={pending}
            className="rounded bg-neutral-900 px-3 py-1.5 text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            {pending ? "紐付中…" : "紐付ける"}
          </button>
        </form>
      )}
    </div>
  );
}
