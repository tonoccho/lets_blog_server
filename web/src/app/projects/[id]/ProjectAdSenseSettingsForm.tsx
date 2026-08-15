"use client";

import { useActionState, useTransition } from "react";
import {
  setProjectAdSenseAccountIdAction,
  clearProjectAdSenseCredentialsAction,
  type ProjectApiKeyFormState,
} from "./actions";

const initialState: ProjectApiKeyFormState = {};

/**
 * プロジェクト単位のGoogle AdSense連携設定(issue #387)。AdSense Management APIはサービスアカウント
 * 委任に対応していないため、GAとは異なりパブリッシャーIDの入力(このフォーム)とGoogleアカウントとの
 * OAuth連携(/connect/adsense/startへのリンク)の2ステップに分かれる。
 */
export function ProjectAdSenseSettingsForm({
  projectId,
  configured,
  accountId,
  connectedBanner,
  errorBanner,
}: {
  projectId: number;
  configured: boolean;
  accountId: string | null;
  connectedBanner?: boolean;
  errorBanner?: string;
}) {
  const [state, formAction, pending] = useActionState(
    (prevState: ProjectApiKeyFormState, formData: FormData) =>
      setProjectAdSenseAccountIdAction(projectId, prevState, formData),
    initialState
  );
  const [isClearing, startClearTransition] = useTransition();

  function handleClear() {
    if (!window.confirm("Google AdSenseの連携設定を削除しますか?ダッシュボードのウィジェットが再び未設定状態になります。")) {
      return;
    }
    startClearTransition(() => clearProjectAdSenseCredentialsAction(projectId));
  }

  return (
    <div className="space-y-4 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <div>
        <h2 className="font-medium">Google AdSense</h2>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          本番サイトの広告収益レポートをダッシュボードに表示するため、AdSenseパブリッシャーIDを保存したうえで、
          そのAdSenseアカウントにアクセスできるGoogleアカウントと連携してください。
        </p>
      </div>

      {connectedBanner && <p className="text-sm text-green-600">Googleアカウントとの連携が完了しました。</p>}
      {errorBanner && <p className="text-sm text-red-600">連携に失敗しました: {errorBanner}</p>}

      <p className="text-sm">
        現在の状態:{" "}
        <span className={configured ? "text-green-600" : "text-neutral-500 dark:text-neutral-400"}>
          {configured ? `連携済み(パブリッシャーID: ${accountId})` : "未設定"}
        </span>
      </p>

      <form action={formAction} className="flex flex-col gap-2 sm:flex-row sm:items-end">
        <label className="flex flex-1 flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">AdSenseパブリッシャーID</span>
          <input
            name="accountId"
            defaultValue={accountId ?? ""}
            placeholder="pub-1234567890123456"
            autoComplete="off"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <button
          type="submit"
          disabled={pending}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {pending ? "保存中…" : "パブリッシャーIDを保存"}
        </button>
      </form>
      {state.error && <p className="text-sm text-red-600">{state.error}</p>}
      {state.success && <p className="text-sm text-green-600">保存しました。</p>}

      <div className="flex flex-wrap gap-2 border-t border-neutral-200 dark:border-neutral-800 pt-4">
        <a
          href={`/connect/adsense/start?projectId=${projectId}`}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white hover:bg-neutral-800"
        >
          Google AdSenseと連携
        </a>
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
    </div>
  );
}
