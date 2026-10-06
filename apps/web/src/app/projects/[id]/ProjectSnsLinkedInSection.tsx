"use client";

import { useActionState, useState, useTransition } from "react";
import type { XConnectionView } from "@/lib/apiClient";
import {
  disconnectProjectLinkedInAction,
  startProjectLinkedInConnectionAction,
  testProjectLinkedInPostAction,
  type SnsLinkedInFormState,
} from "./snsLinkedInActions";

const initialState: SnsLinkedInFormState = {};

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
 * プロジェクト設定画面の「LinkedIn」欄(issue #1581。X の ProjectSnsXSection と同型)。接続した LinkedIn メンバー本人の
 * プロフィールを OAuth で接続すると、アクセストークンは本番サイトの WordPress プラグインへ送られる(アプリには保存されない。
 * トークンは60日で切れ、更新はできないので、切れたら再接続が要る)。
 * ここで入力する OAuth のアプリ情報は認可の間だけバックエンドのメモリに置かれ、再表示しない。
 * 本番サイトが無い・プラグインが導入済みでないときは接続できず、理由を示す。届かないときは
 * 接続状態と告知履歴を「取得できない」と示し、画面は壊さない。期限切れ・401 で投稿しなかったときの理由も告知履歴に出る。
 */
export function ProjectSnsLinkedInSection({
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
    (prevState: SnsLinkedInFormState, formData: FormData) =>
      startProjectLinkedInConnectionAction(projectId, prevState, formData),
    initialState
  );
  const [testResult, setTestResult] = useState<SnsLinkedInFormState>({});
  const [disconnectResult, setDisconnectResult] = useState<SnsLinkedInFormState>({});
  const [isTesting, startTestTransition] = useTransition();
  const [isDisconnecting, startDisconnectTransition] = useTransition();

  const connectable = view?.connectable ?? false;
  const state = view?.status?.available === true ? view.status.state : null;
  const connected = state === "CONNECTED";
  const hasConfig = state === "CONNECTED" || state === "RECONNECT";
  const log = view?.log ?? null;

  function handleTest() {
    startTestTransition(async () => {
      setTestResult(await testProjectLinkedInPostAction(projectId));
    });
  }

  function handleDisconnect() {
    startDisconnectTransition(async () => {
      setDisconnectResult(await disconnectProjectLinkedInAction(projectId));
    });
  }

  return (
    <div
      data-testid="sns-linkedin-section"
      className="space-y-4 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5"
    >
      <div>
        <h2 className="font-medium">LinkedIn</h2>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          LinkedIn のメンバー本人のプロフィールを接続すると、公開先の WordPress サイトのプラグインが記事の公開を告知します(組織ページには投稿できません)。
          アクセストークンはそのプラグインへ送られ、このアプリには保存されません。トークンは60日で切れ、自動では更新できないため、切れたときは「要再接続」になります。
          LinkedIn Developer Portal で、アプリに「Share on LinkedIn」と「Sign In with LinkedIn using OpenID Connect」を追加し、Auth タブの Authorized redirect URLs に次の URL を登録してください。
        </p>
        <p className="mt-1 break-all font-mono text-xs">{callbackUrl}</p>
      </div>

      {connectedBanner && <p className="text-sm text-green-600">LinkedIn アカウントを接続しました。</p>}
      {errorBanner && <p className="text-sm text-red-600">接続に失敗しました: {errorBanner}</p>}

      {view?.siteName && (
        <p className="text-sm text-neutral-600 dark:text-neutral-400">本番サイト: {view.siteName}</p>
      )}
      {view && !view.connectable && view.reason && (
        <p data-testid="sns-linkedin-reason" className="text-sm text-red-600">
          {view.reason}
        </p>
      )}

      <p className="text-sm">
        LinkedIn の接続状態:{" "}
        <span
          data-testid="sns-linkedin-status"
          className={connected ? "text-green-600" : "text-neutral-500 dark:text-neutral-400"}
        >
          {statusText(view)}
        </span>
      </p>

      <form action={connectFormAction} className="flex flex-col gap-3">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">LinkedIn の Client ID</span>
          <input
            name="linkedinAppId"
            autoComplete="off"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">LinkedIn の Client Secret</span>
          <input
            name="linkedinAppSecret"
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
          LinkedIn と接続
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
          LinkedIn 投稿テスト
        </button>
        <button
          type="button"
          onClick={handleDisconnect}
          disabled={!hasConfig || isDisconnecting}
          className="rounded border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm disabled:text-neutral-400"
        >
          LinkedIn を切断
        </button>
        {testResult.success && <p className="text-sm text-green-600">LinkedIn へ投稿テストを送りました。</p>}
        {testResult.error && <p className="text-sm text-red-600">{testResult.error}</p>}
        {disconnectResult.success && <p className="text-sm text-green-600">LinkedIn の接続を切断しました。</p>}
        {disconnectResult.error && <p className="text-sm text-red-600">{disconnectResult.error}</p>}
      </div>

      <div data-testid="sns-linkedin-log" className="space-y-2 border-t border-neutral-200 dark:border-neutral-800 pt-4">
        <h3 className="text-sm font-medium">LinkedIn の告知履歴</h3>
        {view === null || (log && !log.available) ? (
          <p className="text-sm text-neutral-500 dark:text-neutral-400">取得できない</p>
        ) : !log || log.entries.length === 0 ? (
          <p className="text-sm text-neutral-500 dark:text-neutral-400">告知履歴はまだありません。</p>
        ) : (
          <ul className="space-y-1 text-sm">
            {log.entries.map((entry, index) => (
              <li key={`${entry.at}-${index}`} data-testid="sns-linkedin-log-entry">
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
