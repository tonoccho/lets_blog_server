"use client";

import { useActionState, useTransition } from "react";
import type { GoogleAnalyticsPropertyOption } from "@/lib/apiClient";
import {
  setProjectGoogleAnalyticsClientAction,
  selectProjectGoogleAnalyticsPropertyAction,
  clearProjectGoogleAnalyticsCredentialsAction,
  type ProjectApiKeyFormState,
} from "./actions";

const initialState: ProjectApiKeyFormState = {};

/**
 * プロジェクト単位のGoogle Analytics(GA4)連携設定(issue #386、issue #1231でOAuthへ移行)。
 * AdSense(ProjectAdSenseSettingsForm)と同じ「OAuthクライアント保存 → Googleアカウント連携 →
 * (GA固有)プロパティ選択」の導線。サービスアカウントJSONの入力欄は無い。
 * OAuthクライアントはGoogle Cloud Consoleでプロジェクトごとに発行し、承認済みのリダイレクトURIに
 * このアプリの `/connect/google-analytics/callback` を登録する。保存済みのクライアントシークレット/
 * リフレッシュトークンは再表示しない(シークレットは保存済みかどうかだけをplaceholderで示す)。
 */
export function ProjectGoogleAnalyticsSettingsForm({
  projectId,
  connected,
  propertyId,
  clientId,
  hasClientSecret,
  properties,
  propertiesError,
  connectedBanner,
  errorBanner,
}: {
  projectId: number;
  connected: boolean;
  propertyId: string | null;
  clientId: string | null;
  hasClientSecret: boolean;
  properties: GoogleAnalyticsPropertyOption[];
  propertiesError?: string;
  connectedBanner?: boolean;
  errorBanner?: string;
}) {
  const [clientState, clientFormAction, clientPending] = useActionState(
    (prevState: ProjectApiKeyFormState, formData: FormData) =>
      setProjectGoogleAnalyticsClientAction(projectId, prevState, formData),
    initialState
  );
  const [propertyState, propertyFormAction, propertyPending] = useActionState(
    (prevState: ProjectApiKeyFormState, formData: FormData) =>
      selectProjectGoogleAnalyticsPropertyAction(projectId, prevState, formData),
    initialState
  );
  const [isClearing, startClearTransition] = useTransition();
  const clientConfigured = Boolean(clientId) && hasClientSecret;

  function handleClear() {
    if (!window.confirm("Google Analyticsの連携を解除しますか?保存済みのリフレッシュトークンと選択したプロパティを破棄し、ダッシュボードのウィジェットが再び未設定状態になります。")) {
      return;
    }
    startClearTransition(() => clearProjectGoogleAnalyticsCredentialsAction(projectId));
  }

  const statusText = !connected
    ? "未設定"
    : propertyId
      ? `連携済み(プロパティID: ${propertyId})`
      : "連携済み(プロパティ未選択)";

  return (
    <div className="space-y-4 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <div>
        <h2 className="font-medium">Google Analytics</h2>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          本番サイトのアクセス状況をダッシュボードに表示するため、Google OAuthクライアント(Google Cloud Consoleで
          このプロジェクト用に発行したもの)を保存したうえで、対象のGA4プロパティを閲覧できるGoogleアカウントと
          連携し、一覧からプロパティを選択してください。読み取り専用(analytics.readonly)の権限だけを要求します。
        </p>
      </div>

      {connectedBanner && <p className="text-sm text-green-600">Googleアカウントとの連携が完了しました。</p>}
      {errorBanner && <p className="text-sm text-red-600">連携に失敗しました: {errorBanner}</p>}

      <p className="text-sm">
        現在の状態:{" "}
        <span className={connected ? "text-green-600" : "text-neutral-500 dark:text-neutral-400"}>{statusText}</span>
      </p>

      <form action={clientFormAction} className="flex flex-col gap-3">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">Google OAuthクライアントID</span>
          <input
            name="clientId"
            defaultValue={clientId ?? ""}
            placeholder="xxxxxxxxxx.apps.googleusercontent.com"
            autoComplete="off"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">Google OAuthクライアントシークレット</span>
          <input
            name="clientSecret"
            type="password"
            placeholder={hasClientSecret ? "設定済み(変更する場合のみ入力)" : "未設定"}
            autoComplete="off"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <button
          type="submit"
          disabled={clientPending}
          className="self-start rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {clientPending ? "保存中…" : "クライアントを保存"}
        </button>
      </form>
      {clientState.error && <p className="text-sm text-red-600">{clientState.error}</p>}
      {clientState.success && <p className="text-sm text-green-600">保存しました。</p>}

      <div className="flex flex-wrap items-center gap-2 border-t border-neutral-200 dark:border-neutral-800 pt-4">
        {clientConfigured ? (
          <a
            href={`/connect/google-analytics/start?projectId=${projectId}`}
            className="rounded bg-neutral-900 px-4 py-2 text-sm text-white hover:bg-neutral-800"
          >
            Googleアカウントと連携
          </a>
        ) : (
          <p className="text-sm text-neutral-500 dark:text-neutral-400">
            Google連携を行うには、先にOAuthクライアントID/シークレットを保存してください。
          </p>
        )}
        {(connected || clientConfigured) && (
          <button
            type="button"
            onClick={handleClear}
            disabled={isClearing}
            className="rounded border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm text-red-600 hover:bg-neutral-50 dark:hover:bg-neutral-800 disabled:text-neutral-400"
          >
            {isClearing ? "解除中…" : "連携を解除"}
          </button>
        )}
      </div>

      {connected && (
        <div className="space-y-3 border-t border-neutral-200 dark:border-neutral-800 pt-4">
          <h3 className="text-sm font-medium">GA4プロパティの選択</h3>
          {propertiesError ? (
            <p className="text-sm text-red-600">プロパティ一覧を取得できませんでした: {propertiesError}</p>
          ) : properties.length === 0 ? (
            <p className="text-sm text-neutral-500 dark:text-neutral-400">
              連携したGoogleアカウントがアクセスできるGA4プロパティがありません。
            </p>
          ) : (
            <form action={propertyFormAction} className="flex flex-col gap-3">
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-neutral-600 dark:text-neutral-400">ダッシュボードに表示するプロパティ</span>
                <select
                  name="propertyId"
                  defaultValue={propertyId ?? ""}
                  className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm sm:max-w-md"
                >
                  <option value="" disabled>
                    プロパティを選択してください
                  </option>
                  {properties.map((property) => (
                    <option key={property.propertyId} value={property.propertyId}>
                      {property.displayName ?? "(名称なし)"} ({property.propertyId}
                      {property.accountDisplayName ? ` / ${property.accountDisplayName}` : ""})
                    </option>
                  ))}
                </select>
              </label>
              <button
                type="submit"
                disabled={propertyPending}
                className="self-start rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
              >
                {propertyPending ? "保存中…" : "プロパティを保存"}
              </button>
            </form>
          )}
          {propertyState.error && <p className="text-sm text-red-600">{propertyState.error}</p>}
          {propertyState.success && <p className="text-sm text-green-600">プロパティを保存しました。</p>}
        </div>
      )}
    </div>
  );
}
