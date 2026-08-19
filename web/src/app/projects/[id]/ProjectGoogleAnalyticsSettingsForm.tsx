"use client";

import { useActionState, useTransition } from "react";
import {
  setProjectGoogleAnalyticsCredentialsAction,
  clearProjectGoogleAnalyticsCredentialsAction,
  type ProjectApiKeyFormState,
} from "./actions";

const initialState: ProjectApiKeyFormState = {};

/**
 * プロジェクト単位のGoogle Analytics(GA4)連携設定(issue #386)。GA4プロパティIDとサービスアカウントの
 * JSON鍵を入力する。値は暗号化して保存され、保存後は再表示されない(ProjectApiKeysFormと同じ方針)。
 */
export function ProjectGoogleAnalyticsSettingsForm({
  projectId,
  configured,
  propertyId,
}: {
  projectId: number;
  configured: boolean;
  propertyId: string | null;
}) {
  const [state, formAction, pending] = useActionState(
    (prevState: ProjectApiKeyFormState, formData: FormData) =>
      setProjectGoogleAnalyticsCredentialsAction(projectId, prevState, formData),
    initialState
  );
  const [isClearing, startClearTransition] = useTransition();

  function handleClear() {
    if (!window.confirm("Google Analyticsの連携設定を削除しますか?ダッシュボードのウィジェットが再び未設定状態になります。")) {
      return;
    }
    startClearTransition(() => clearProjectGoogleAnalyticsCredentialsAction(projectId));
  }

  return (
    <div className="space-y-4 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <div>
        <h2 className="font-medium">Google Analytics</h2>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          本番サイトのアクセス状況をダッシュボードに表示するため、GA4プロパティIDとサービスアカウントの
          JSON鍵ファイルの内容を設定してください。サービスアカウントには、GA4の「プロパティのアクセス管理」で
          対象プロパティの閲覧者権限を付与しておく必要があります。
        </p>
      </div>

      <p className="text-sm">
        現在の状態:{" "}
        <span className={configured ? "text-green-600" : "text-neutral-500 dark:text-neutral-400"}>
          {configured ? `設定済み(プロパティID: ${propertyId})` : "未設定"}
        </span>
      </p>

      <form action={formAction} className="space-y-3">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">GA4プロパティID</span>
          <input
            name="propertyId"
            placeholder="123456789"
            autoComplete="off"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm sm:max-w-xs"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">サービスアカウントのJSON鍵</span>
          <textarea
            name="serviceAccountJson"
            placeholder='{"type": "service_account", "client_email": "...", "private_key": "...", ...}'
            autoComplete="off"
            rows={6}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm font-mono"
          />
        </label>
        <div className="flex gap-2">
          <button
            type="submit"
            disabled={pending}
            className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
          >
            {pending ? "保存中…" : "保存"}
          </button>
          {configured && (
            <button
              type="button"
              onClick={handleClear}
              disabled={isClearing}
              className="rounded border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm text-red-600 hover:bg-neutral-50 dark:hover:bg-neutral-800 disabled:text-neutral-400"
            >
              {isClearing ? "削除中…" : "設定を削除"}
            </button>
          )}
        </div>
      </form>
      {state.error && <p className="text-sm text-red-600">{state.error}</p>}
      {state.success && <p className="text-sm text-green-600">保存しました。</p>}
    </div>
  );
}
