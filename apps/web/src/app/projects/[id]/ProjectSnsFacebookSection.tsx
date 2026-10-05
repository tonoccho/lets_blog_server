"use client";

import { useActionState, useState, useTransition } from "react";
import type { XConnectionView } from "@/lib/apiClient";
import {
  disconnectProjectFacebookAction,
  selectProjectFacebookPageAction,
  startProjectFacebookConnectionAction,
  testProjectFacebookPostAction,
  type SnsFacebookFormState,
} from "./snsFacebookActions";

const initialState: SnsFacebookFormState = {};

const KIND_LABELS: Record<string, string> = {
  test: "テスト投稿",
  publish: "記事公開",
};

function statusText(view: XConnectionView | null): string {
  if (!view) {
    return "取得できない";
  }
  const status = view.status;
  if (!status) {
    return "未接続";
  }
  if (!status.available) {
    return `取得できない${status.error ? `(${status.error})` : ""}`;
  }
  switch (status.state) {
    case "CONNECTED":
      return status.accountName ? `接続済み(${status.accountName})` : "接続済み";
    case "RECONNECT":
      return "要再接続";
    default:
      return "未接続";
  }
}

export interface FacebookPageSelection {
  state: string;
  pages: { id: string; name: string }[];
}

/**
 * プロジェクト設定画面の「Facebook」欄(issue #1580。X の ProjectSnsXSection と同型)。プロジェクトの公式 Facebook ページを
 * OAuth で接続する。個人アカウントには投稿しないので、認可のあとに管理しているページから投稿先を選ぶ(pageSelection)。
 * 選んだページのトークンだけが本番サイトの WordPress プラグインへ送られる(アプリには保存されない)。
 * ここで入力する OAuth のアプリ情報は認可の間だけバックエンドのメモリに置かれ、再表示しない。
 * 本番サイトが無い・プラグインが導入済みでないときは接続できず、理由を示す。届かないときは
 * 接続状態と告知履歴を「取得できない」と示し、画面は壊さない。トークンを更新できず投稿しなかったときの理由も告知履歴に出る。
 */
export function ProjectSnsFacebookSection({
  projectId,
  view,
  callbackUrl,
  connectedBanner,
  errorBanner,
  pageSelection,
}: {
  projectId: number;
  view: XConnectionView | null;
  callbackUrl: string;
  connectedBanner?: boolean;
  errorBanner?: string;
  pageSelection?: FacebookPageSelection | null;
}) {
  const [connectState, connectFormAction, connectPending] = useActionState(
    (prevState: SnsFacebookFormState, formData: FormData) =>
      startProjectFacebookConnectionAction(projectId, prevState, formData),
    initialState
  );
  const [selectState, selectFormAction, selectPending] = useActionState(
    (prevState: SnsFacebookFormState, formData: FormData) =>
      selectProjectFacebookPageAction(projectId, pageSelection?.state ?? "", prevState, formData),
    initialState
  );
  const [testResult, setTestResult] = useState<SnsFacebookFormState>({});
  const [disconnectResult, setDisconnectResult] = useState<SnsFacebookFormState>({});
  const [isTesting, startTestTransition] = useTransition();
  const [isDisconnecting, startDisconnectTransition] = useTransition();

  const connectable = view?.connectable ?? false;
  const state = view?.status?.available === true ? view.status.state : null;
  const connected = state === "CONNECTED";
  const hasConfig = state === "CONNECTED" || state === "RECONNECT";
  const log = view?.log ?? null;

  function handleTest() {
    startTestTransition(async () => {
      setTestResult(await testProjectFacebookPostAction(projectId));
    });
  }

  function handleDisconnect() {
    startDisconnectTransition(async () => {
      setDisconnectResult(await disconnectProjectFacebookAction(projectId));
    });
  }

  return (
    <div
      data-testid="sns-facebook-section"
      className="space-y-4 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5"
    >
      <div>
        <h2 className="font-medium">Facebook</h2>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          プロジェクトの公式 Facebook ページを接続すると、公開先の WordPress サイトのプラグインが記事の公開をそのページへ告知します
          (個人のアカウントには投稿しません)。ページのトークンはそのプラグインへ送られ、このアプリには保存されません。
          Meta for Developers で、アプリの「有効な OAuth リダイレクト URI」に次の URL を登録してください。
        </p>
        <p className="mt-1 break-all font-mono text-xs">{callbackUrl}</p>
      </div>

      {connectedBanner && <p className="text-sm text-green-600">Facebook ページを接続しました。</p>}
      {errorBanner && <p className="text-sm text-red-600">接続に失敗しました: {errorBanner}</p>}

      {view?.siteName && (
        <p className="text-sm text-neutral-600 dark:text-neutral-400">本番サイト: {view.siteName}</p>
      )}
      {view && !view.connectable && view.reason && (
        <p data-testid="sns-facebook-reason" className="text-sm text-red-600">
          {view.reason}
        </p>
      )}

      <p className="text-sm">
        Facebook の接続状態:{" "}
        <span
          data-testid="sns-facebook-status"
          className={connected ? "text-green-600" : "text-neutral-500 dark:text-neutral-400"}
        >
          {statusText(view)}
        </span>
      </p>

      <form action={connectFormAction} className="flex flex-col gap-3">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">Facebook のアプリID</span>
          <input
            name="facebookAppId"
            autoComplete="off"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">Facebook のアプリシークレット</span>
          <input
            name="facebookAppSecret"
            type="password"
            autoComplete="off"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <button
          type="submit"
          disabled={!connectable || connectPending}
          className="self-start rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          Facebook と接続
        </button>
      </form>
      {connectState.error && <p className="text-sm text-red-600">{connectState.error}</p>}

      {pageSelection && (
        <div data-testid="sns-facebook-page-select" className="space-y-2 rounded border border-neutral-200 dark:border-neutral-800 p-3">
          <h3 className="text-sm font-medium">投稿先のページを選んでください</h3>
          {pageSelection.pages.length === 0 ? (
            <p className="text-sm text-neutral-500 dark:text-neutral-400">管理しているページがありません。</p>
          ) : (
            <form action={selectFormAction} className="flex flex-col gap-2">
              {pageSelection.pages.map((facebookPage) => (
                <label key={facebookPage.id} className="flex items-center gap-2 text-sm">
                  <input type="radio" name="facebookPageId" value={facebookPage.id} />
                  {facebookPage.name}
                </label>
              ))}
              <button
                type="submit"
                disabled={!connectable || selectPending}
                className="self-start rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
              >
                このページを接続
              </button>
            </form>
          )}
          {selectState.error && <p className="text-sm text-red-600">{selectState.error}</p>}
        </div>
      )}

      <div className="flex flex-wrap items-center gap-3 border-t border-neutral-200 dark:border-neutral-800 pt-4">
        <button
          type="button"
          onClick={handleTest}
          disabled={!connected || isTesting}
          className="rounded border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm disabled:text-neutral-400"
        >
          Facebook 投稿テスト
        </button>
        <button
          type="button"
          onClick={handleDisconnect}
          disabled={!hasConfig || isDisconnecting}
          className="rounded border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm disabled:text-neutral-400"
        >
          Facebook を切断
        </button>
        {testResult.success && <p className="text-sm text-green-600">Facebook へ投稿テストを送りました。</p>}
        {testResult.error && <p className="text-sm text-red-600">{testResult.error}</p>}
        {disconnectResult.success && <p className="text-sm text-green-600">Facebook の接続を切断しました。</p>}
        {disconnectResult.error && <p className="text-sm text-red-600">{disconnectResult.error}</p>}
      </div>

      <div data-testid="sns-facebook-log" className="space-y-2 border-t border-neutral-200 dark:border-neutral-800 pt-4">
        <h3 className="text-sm font-medium">Facebook の告知履歴</h3>
        {view === null || (log && !log.available) ? (
          <p className="text-sm text-neutral-500 dark:text-neutral-400">取得できない</p>
        ) : !log || log.entries.length === 0 ? (
          <p className="text-sm text-neutral-500 dark:text-neutral-400">告知履歴はまだありません。</p>
        ) : (
          <ul className="space-y-1 text-sm">
            {log.entries.map((entry, index) => (
              <li key={`${entry.at}-${index}`} data-testid="sns-facebook-log-entry">
                {KIND_LABELS[entry.kind] ?? entry.kind}{" "}
                {entry.success ? (
                  <span className="text-green-600">成功</span>
                ) : (
                  <span className="text-red-600">失敗{entry.error ? `: ${entry.error}` : ""}</span>
                )}{" "}
                <span className="text-xs text-neutral-500">{entry.at}</span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
