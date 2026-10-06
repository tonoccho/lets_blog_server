"use client";

import { useActionState, useState, useTransition } from "react";
import type { SnsTemplatesView } from "@/lib/apiClient";
import {
  resendProjectSnsTemplatesAction,
  saveProjectSnsTemplatesAction,
  type SnsTemplateActionState,
} from "./snsTemplateActions";

const initialState: SnsTemplateActionState = {};

function sendText(send: SnsTemplatesView["send"]): string {
  switch (send.state) {
    case "SENT":
      return "送信済み";
    case "FAILED":
      return `送信失敗${send.error ? `: ${send.error}` : ""}`;
    default:
      return "未送信";
  }
}

const textareaClass =
  "w-full rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm font-mono";

/**
 * 「SNS 告知」欄の告知文テンプレート(issue #1583)。記事の公開時と PV 達成時の告知文を別々に編集する。
 * 差し込み項目は {title}・{url}、PV 達成時のみ {period}・{threshold}。空なら既定の告知文(タイトルと URL)で投稿し、
 * SNS の文字数の上限を超えるときは URL を残して切り詰める。保存すると本番サイトのプラグインへ送られる。
 * 送れなかったときは送信失敗として示し、再送できる。
 */
export function ProjectSnsTemplatesSection({ projectId, view }: { projectId: number; view: SnsTemplatesView | null }) {
  const [saveState, saveFormAction, savePending] = useActionState(
    (prevState: SnsTemplateActionState, formData: FormData) => saveProjectSnsTemplatesAction(projectId, prevState, formData),
    initialState
  );
  const [resendResult, setResendResult] = useState<SnsTemplateActionState>({});
  const [isPending, startTransition] = useTransition();

  function handleResend() {
    startTransition(async () => {
      setResendResult(await resendProjectSnsTemplatesAction(projectId));
    });
  }

  return (
    <div
      data-testid="sns-templates-section"
      className="space-y-4 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5"
    >
      <div>
        <h3 className="text-sm font-medium">告知文テンプレート</h3>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          接続済みの SNS へ投稿する告知文を、公開時と PV 達成時で別々に編集します。SNS ごとには分けられません。
        </p>
        <p data-testid="sns-templates-empty-note" className="mt-1 text-xs text-neutral-500 dark:text-neutral-400">
          空のときは既定の告知文(タイトルと URL)で投稿します。SNS の文字数の上限を超えるときは、URL を残して切り詰めます。
        </p>
        {view === null && (
          <p data-testid="sns-templates-unavailable" className="mt-1 text-sm text-red-600">
            告知文テンプレートを取得できない
          </p>
        )}
      </div>

      <form action={saveFormAction} className="space-y-4">
        <div className="flex flex-col gap-1 text-sm">
          <label htmlFor="sns-template-publish" className="text-neutral-600 dark:text-neutral-400">
            公開時の告知文
          </label>
          <textarea
            id="sns-template-publish"
            name="publishTemplate"
            rows={3}
            maxLength={1000}
            defaultValue={view?.publishTemplate ?? ""}
            aria-describedby="sns-template-publish-hint"
            className={textareaClass}
          />
          <span
            id="sns-template-publish-hint"
            data-testid="sns-templates-publish-hint"
            className="text-xs text-neutral-500 dark:text-neutral-400"
          >
            差し込み項目: {"{title}"}(記事のタイトル)、{"{url}"}(記事の URL)
          </span>
        </div>
        <div className="flex flex-col gap-1 text-sm">
          <label htmlFor="sns-template-pv" className="text-neutral-600 dark:text-neutral-400">
            PV 達成時の告知文
          </label>
          <textarea
            id="sns-template-pv"
            name="pvTemplate"
            rows={3}
            maxLength={1000}
            defaultValue={view?.pvTemplate ?? ""}
            aria-describedby="sns-template-pv-hint"
            className={textareaClass}
          />
          <span
            id="sns-template-pv-hint"
            data-testid="sns-templates-pv-hint"
            className="text-xs text-neutral-500 dark:text-neutral-400"
          >
            差し込み項目: {"{title}"}(記事のタイトル)、{"{url}"}(記事の URL)、{"{period}"}(1日 か 累計)、
            {"{threshold}"}(達成した PV)
          </span>
        </div>
        <button
          type="submit"
          disabled={savePending}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          テンプレートを保存
        </button>
      </form>
      {saveState.error && <p className="text-sm text-red-600">{saveState.error}</p>}
      {resendResult.error && <p className="text-sm text-red-600">{resendResult.error}</p>}

      {view && (
        <div className="flex flex-wrap items-center gap-3 border-t border-neutral-200 dark:border-neutral-800 pt-4">
          <p className="text-sm">
            本番サイトへの送信:{" "}
            <span
              data-testid="sns-templates-send-status"
              className={
                view.send.state === "FAILED"
                  ? "text-red-600"
                  : view.send.state === "SENT"
                    ? "text-green-600"
                    : "text-neutral-500 dark:text-neutral-400"
              }
            >
              {sendText(view.send)}
            </span>
          </p>
          {view.send.state === "FAILED" && (
            <button
              type="button"
              onClick={handleResend}
              disabled={isPending}
              className="rounded border border-neutral-300 dark:border-neutral-700 px-4 py-2 text-sm disabled:text-neutral-400"
            >
              再送
            </button>
          )}
        </div>
      )}
    </div>
  );
}
