"use client";

import { useActionState, useState, useTransition } from "react";
import type { XConnectionView } from "@/lib/apiClient";
import {
  disconnectProjectHatenaAction,
  startProjectHatenaConnectionAction,
  testProjectHatenaPostAction,
  type SnsHatenaFormState,
} from "./snsHatenaActions";

const initialState: SnsHatenaFormState = {};

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

/**
 * プロジェクト設定画面の「はてなブックマーク」欄(issue #1582。X の ProjectSnsXSection と同型)。プロジェクトの公式はてなアカウントを
 * OAuth 1.0a で接続すると、アクセストークンとその秘密は本番サイトの WordPress プラグインへ送られる(アプリには保存されない)。
 * ここで入力する consumer key / secret は認可の間だけバックエンドのメモリに置かれ(プラグインには署名のために送られ、暗号化して保存される)、
 * 再表示しない。本番サイトが無い・プラグインが導入済みでないときは接続できず、理由を示す。届かないときは
 * 接続状態と告知履歴を「取得できない」と示し、画面は壊さない。認証が失効した・告知文に URL が無いなどで投稿しなかったときの理由も告知履歴に出る。
 */
export function ProjectSnsHatenaSection({
  projectId,
  view,
  callbackUrl,
  connectedBanner,
  errorBanner,
}: {
  projectId: number;
  view: XConnectionView | null;
  callbackUrl: string;
  connectedBanner?: boolean;
  errorBanner?: string;
}) {
  const [connectState, connectFormAction, connectPending] = useActionState(
    (prevState: SnsHatenaFormState, formData: FormData) =>
      startProjectHatenaConnectionAction(projectId, prevState, formData),
    initialState
  );
  const [testResult, setTestResult] = useState<SnsHatenaFormState>({});
  const [disconnectResult, setDisconnectResult] = useState<SnsHatenaFormState>({});
  const [isTesting, startTestTransition] = useTransition();
  const [isDisconnecting, startDisconnectTransition] = useTransition();

  const connectable = view?.connectable ?? false;
  const state = view?.status?.available === true ? view.status.state : null;
  const connected = state === "CONNECTED";
  const hasConfig = state === "CONNECTED" || state === "RECONNECT";
  const log = view?.log ?? null;

  function handleTest() {
    startTestTransition(async () => {
      setTestResult(await testProjectHatenaPostAction(projectId));
    });
  }

  function handleDisconnect() {
    startDisconnectTransition(async () => {
      setDisconnectResult(await disconnectProjectHatenaAction(projectId));
    });
  }

  return (
    <div
      data-testid="sns-hatena-section"
      className="space-y-4 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5"
    >
      <div>
        <h2 className="font-medium">はてなブックマーク</h2>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          プロジェクトの公式はてなアカウントを接続すると、公開先の WordPress サイトのプラグインが記事の公開を告知します(記事の URL をブックマークし、告知文を100文字以内のコメントにします)。
          アクセストークンはそのプラグインへ送られ、このアプリには保存されません。
          はてなの OAuth 開発者設定で consumer key / secret を取得し(scope は read_public と write_public)、下に入力してください。認可のあと、はてなは次の URL へ戻します。
        </p>
        <p className="mt-1 break-all font-mono text-xs">{callbackUrl}</p>
      </div>

      {connectedBanner && <p className="text-sm text-green-600">はてなブックマーク アカウントを接続しました。</p>}
      {errorBanner && <p className="text-sm text-red-600">接続に失敗しました: {errorBanner}</p>}

      {view?.siteName && (
        <p className="text-sm text-neutral-600 dark:text-neutral-400">本番サイト: {view.siteName}</p>
      )}
      {view && !view.connectable && view.reason && (
        <p data-testid="sns-hatena-reason" className="text-sm text-red-600">
          {view.reason}
        </p>
      )}

      <p className="text-sm">
        はてなブックマーク の接続状態:{" "}
        <span
          data-testid="sns-hatena-status"
          className={connected ? "text-green-600" : "text-neutral-500 dark:text-neutral-400"}
        >
          {statusText(view)}
        </span>
      </p>

      <form action={connectFormAction} className="flex flex-col gap-3">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">はてなブックマーク の Consumer Key</span>
          <input
            name="hatenaConsumerKey"
            autoComplete="off"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">はてなブックマーク の Consumer Secret</span>
          <input
            name="hatenaConsumerSecret"
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
          はてなブックマーク と接続
        </button>
      </form>
      {connectState.error && <p className="text-sm text-red-600">{connectState.error}</p>}

      <div className="flex flex-wrap items-center gap-3 border-t border-neutral-200 dark:border-neutral-800 pt-4">
        <button
          type="button"
          onClick={handleTest}
          disabled={!connected || isTesting}
          className="rounded border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm disabled:text-neutral-400"
        >
          はてなブックマーク 投稿テスト
        </button>
        <button
          type="button"
          onClick={handleDisconnect}
          disabled={!hasConfig || isDisconnecting}
          className="rounded border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm disabled:text-neutral-400"
        >
          はてなブックマーク を切断
        </button>
        {testResult.success && <p className="text-sm text-green-600">はてなブックマーク へ投稿テストを送りました。</p>}
        {testResult.error && <p className="text-sm text-red-600">{testResult.error}</p>}
        {disconnectResult.success && <p className="text-sm text-green-600">はてなブックマーク の接続を切断しました。</p>}
        {disconnectResult.error && <p className="text-sm text-red-600">{disconnectResult.error}</p>}
      </div>

      <div data-testid="sns-hatena-log" className="space-y-2 border-t border-neutral-200 dark:border-neutral-800 pt-4">
        <h3 className="text-sm font-medium">はてなブックマーク の告知履歴</h3>
        {view === null || (log && !log.available) ? (
          <p className="text-sm text-neutral-500 dark:text-neutral-400">取得できない</p>
        ) : !log || log.entries.length === 0 ? (
          <p className="text-sm text-neutral-500 dark:text-neutral-400">告知履歴はまだありません。</p>
        ) : (
          <ul className="space-y-1 text-sm">
            {log.entries.map((entry, index) => (
              <li key={`${entry.at}-${index}`} data-testid="sns-hatena-log-entry">
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
