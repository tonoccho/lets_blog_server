"use client";

import { useActionState, useState, useTransition } from "react";
import type { XConnectionView } from "@/lib/apiClient";
import { startProjectXConnectionAction, testProjectXPostAction, type SnsXFormState } from "./snsXActions";

const initialState: SnsXFormState = {};

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
      return status.accountName ? `接続済み(@${status.accountName})` : "接続済み";
    case "RECONNECT":
      return "要再接続";
    default:
      return "未接続";
  }
}

/**
 * プロジェクト設定画面の「SNS 告知」欄(X。issue #1574)。プロジェクトの公式アカウントを OAuth で接続すると、
 * トークンは本番サイトの WordPress プラグインへ送られる(アプリには保存されない)。ここで入力する
 * OAuth クライアントの情報は認可の間だけバックエンドのメモリに置かれ、再表示しない。
 * 本番サイトが無い・プラグインが導入済みでないときは接続できず、理由を示す。届かないときは
 * 接続状態と告知履歴を「取得できない」と示し、画面は壊さない。
 */
export function ProjectSnsXSection({
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
    (prevState: SnsXFormState, formData: FormData) => startProjectXConnectionAction(projectId, prevState, formData),
    initialState
  );
  const [testResult, setTestResult] = useState<SnsXFormState>({});
  const [isTesting, startTestTransition] = useTransition();

  const connectable = view?.connectable ?? false;
  const connected = view?.status?.available === true && view.status.state === "CONNECTED";
  const log = view?.log ?? null;

  function handleTest() {
    startTestTransition(async () => {
      setTestResult(await testProjectXPostAction(projectId));
    });
  }

  return (
    <div className="space-y-4 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <div>
        <h2 className="font-medium">SNS 告知</h2>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          プロジェクトの公式 X アカウントを接続すると、公開先の WordPress サイトのプラグインが記事の公開を告知します。
          トークンはそのプラグインへ送られ、このアプリには保存されません。
          X の開発者ポータルで、アプリのコールバック URL に次の URL を登録してください。
        </p>
        <p className="mt-1 break-all font-mono text-xs">{callbackUrl}</p>
      </div>

      {connectedBanner && <p className="text-sm text-green-600">X アカウントを接続しました。</p>}
      {errorBanner && <p className="text-sm text-red-600">接続に失敗しました: {errorBanner}</p>}

      {view?.siteName && (
        <p className="text-sm text-neutral-600 dark:text-neutral-400">本番サイト: {view.siteName}</p>
      )}
      {view && !view.connectable && view.reason && (
        <p data-testid="sns-x-reason" className="text-sm text-red-600">
          {view.reason}
        </p>
      )}

      <p className="text-sm">
        X の接続状態:{" "}
        <span data-testid="sns-x-status" className={connected ? "text-green-600" : "text-neutral-500 dark:text-neutral-400"}>
          {statusText(view)}
        </span>
      </p>

      <form action={connectFormAction} className="flex flex-col gap-3">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">X の OAuth クライアントID</span>
          <input
            name="clientId"
            autoComplete="off"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">X の OAuth クライアントシークレット</span>
          <input
            name="clientSecret"
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
          X と接続
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
          テスト投稿
        </button>
        {testResult.success && <p className="text-sm text-green-600">テスト投稿を送りました。</p>}
        {testResult.error && <p className="text-sm text-red-600">{testResult.error}</p>}
      </div>

      <div data-testid="sns-x-log" className="space-y-2 border-t border-neutral-200 dark:border-neutral-800 pt-4">
        <h3 className="text-sm font-medium">告知履歴</h3>
        {view === null || (log && !log.available) ? (
          <p className="text-sm text-neutral-500 dark:text-neutral-400">取得できない</p>
        ) : !log || log.entries.length === 0 ? (
          <p className="text-sm text-neutral-500 dark:text-neutral-400">告知履歴はまだありません。</p>
        ) : (
          <ul className="space-y-1 text-sm">
            {log.entries.map((entry, index) => (
              <li key={`${entry.at}-${index}`} data-testid="sns-x-log-entry">
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
