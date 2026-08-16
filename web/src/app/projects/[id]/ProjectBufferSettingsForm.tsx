"use client";

import { useActionState, useTransition } from "react";
import {
  setProjectBufferSettingsAction,
  setProjectBufferAccessTokenAction,
  clearProjectBufferSettingsAction,
  type ProjectApiKeyFormState,
} from "./actions";

const initialState: ProjectApiKeyFormState = {};

/**
 * プロジェクト単位のBuffer連携設定(issue #402)。以前はアプリ全体の環境変数(BUFFER_*)で1つだけ
 * 設定していたが、プロジェクトごとに異なるBufferアカウント/SNSプロファイルへ投稿できるよう
 * プロジェクト単位に変更した。アクセストークンは暗号化して保存され、保存後は再表示されない
 * (ProjectApiKeysFormと同じ方針)ため、有効/無効・プロファイルID等の設定とは別のフォームで扱う。
 */
export function ProjectBufferSettingsForm({
  projectId,
  configured,
  enabled,
  hasAccessToken,
  profileIds,
  delayMinutes,
  messageTemplate,
}: {
  projectId: number;
  configured: boolean;
  enabled: boolean;
  hasAccessToken: boolean;
  profileIds: string | null;
  delayMinutes: number | null;
  messageTemplate: string | null;
}) {
  const [settingsState, settingsFormAction, settingsPending] = useActionState(
    (prevState: ProjectApiKeyFormState, formData: FormData) =>
      setProjectBufferSettingsAction(projectId, prevState, formData),
    initialState
  );
  const [tokenState, tokenFormAction, tokenPending] = useActionState(
    (prevState: ProjectApiKeyFormState, formData: FormData) =>
      setProjectBufferAccessTokenAction(projectId, prevState, formData),
    initialState
  );
  const [isClearing, startClearTransition] = useTransition();

  function handleClear() {
    if (!window.confirm("Buffer連携の設定を削除しますか?記事公開時のSNS予約投稿が行われなくなります。")) {
      return;
    }
    startClearTransition(() => clearProjectBufferSettingsAction(projectId));
  }

  return (
    <div className="space-y-4 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <div>
        <h2 className="font-medium">Buffer連携</h2>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          本番サイトへの記事公開時、指定した時間後にBuffer経由でSNS(Twitter/Facebook/LinkedIn等)へ
          予約投稿します。プロファイルIDはBuffer管理画面で確認できる各SNSアカウントのIDをカンマ区切りで指定してください。
        </p>
      </div>

      <p className="text-sm">
        現在の状態:{" "}
        <span className={configured ? "text-green-600" : "text-neutral-500 dark:text-neutral-400"}>
          {configured ? "設定済み" : "未設定"}
        </span>
      </p>

      <form action={settingsFormAction} className="space-y-3">
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" name="enabled" defaultChecked={enabled} className="h-4 w-4" />
          <span className="text-neutral-600 dark:text-neutral-400">Buffer連携を有効にする</span>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">プロファイルID(カンマ区切り)</span>
          <input
            name="profileIds"
            defaultValue={profileIds ?? ""}
            placeholder="profile-id-1,profile-id-2"
            autoComplete="off"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm sm:max-w-xs">
          <span className="text-neutral-600 dark:text-neutral-400">投稿までの遅延(分)</span>
          <input
            name="delayMinutes"
            type="number"
            min={0}
            defaultValue={delayMinutes ?? 5}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">メッセージテンプレート</span>
          <input
            name="messageTemplate"
            defaultValue={messageTemplate ?? "{title} {url}"}
            placeholder="{title} {url}"
            autoComplete="off"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm font-mono"
          />
        </label>
        <button
          type="submit"
          disabled={settingsPending}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {settingsPending ? "保存中…" : "設定を保存"}
        </button>
        {settingsState.error && <p className="text-sm text-red-600">{settingsState.error}</p>}
        {settingsState.success && <p className="text-sm text-green-600">保存しました。</p>}
      </form>

      <form action={tokenFormAction} className="flex flex-col gap-2 border-t border-neutral-200 dark:border-neutral-800 pt-4 sm:flex-row sm:items-end">
        <label className="flex flex-1 flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">
            アクセストークン{hasAccessToken ? "(設定済み。変更する場合のみ入力)" : ""}
          </span>
          <input
            name="accessToken"
            type="password"
            placeholder={hasAccessToken ? "変更する場合のみ入力" : "Bufferのアクセストークン"}
            autoComplete="off"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <button
          type="submit"
          disabled={tokenPending}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {tokenPending ? "保存中…" : "アクセストークンを保存"}
        </button>
      </form>
      {tokenState.error && <p className="text-sm text-red-600">{tokenState.error}</p>}
      {tokenState.success && <p className="text-sm text-green-600">アクセストークンを保存しました。</p>}

      {configured && (
        <div className="border-t border-neutral-200 dark:border-neutral-800 pt-4">
          <button
            type="button"
            onClick={handleClear}
            disabled={isClearing}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm text-red-600 hover:bg-neutral-50 dark:hover:bg-neutral-800 disabled:text-neutral-400"
          >
            {isClearing ? "削除中…" : "設定を削除"}
          </button>
        </div>
      )}
    </div>
  );
}
