"use client";

import { useActionState, useState, useTransition } from "react";
import type { PvRulesView } from "@/lib/apiClient";
import {
  addProjectPvRuleAction,
  deleteProjectPvRuleAction,
  resendProjectPvRulesAction,
  type PvRuleActionState,
} from "./pvRuleActions";

const initialState: PvRuleActionState = {};

function ruleLabel(rule: PvRulesView["rules"][number]): string {
  return rule.period === "daily" ? `1日で${rule.threshold}PV` : `累計${rule.threshold}PV`;
}

function sendText(send: PvRulesView["send"]): string {
  switch (send.state) {
    case "SENT":
      return "送信済み";
    case "FAILED":
      return `送信失敗${send.error ? `: ${send.error}` : ""}`;
    default:
      return "未送信";
  }
}

/**
 * 「SNS 告知」欄の PV 達成ルール(issue #1578)。1日(その日のうち)か累計の PV が閾値に達した記事を、
 * 本番サイトのプラグインが SNS へ告知する。ルールを保存すると、GA4 の認証情報とルールが本番サイトへ送られる。
 * GA が未連携ならルールを追加できず、理由を示す。送れなかったときは送信失敗として示し、再送できる。
 */
export function ProjectPvRulesSection({ projectId, view }: { projectId: number; view: PvRulesView | null }) {
  const [addState, addFormAction, addPending] = useActionState(
    (prevState: PvRuleActionState, formData: FormData) => addProjectPvRuleAction(projectId, prevState, formData),
    initialState
  );
  const [actionResult, setActionResult] = useState<PvRuleActionState>({});
  const [isPending, startTransition] = useTransition();

  function handleDelete(ruleId: string) {
    startTransition(async () => {
      setActionResult(await deleteProjectPvRuleAction(projectId, ruleId));
    });
  }

  function handleResend() {
    startTransition(async () => {
      setActionResult(await resendProjectPvRulesAction(projectId));
    });
  }

  const addable = view?.addable ?? false;
  const reason = view === null ? "PV 達成ルールを取得できない" : view.reason;

  return (
    <div
      data-testid="pv-rules-section"
      className="space-y-4 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5"
    >
      <div>
        <h3 className="text-sm font-medium">PV 達成ルール</h3>
        <p className="text-sm text-neutral-600 dark:text-neutral-400">
          1日の PV か累計の PV が閾値に達した記事を、接続済みの SNS へ告知します。
        </p>
        <p data-testid="pv-lag-note" className="mt-1 text-xs text-neutral-500 dark:text-neutral-400">
          PV は GA の集計を待つため、達成の告知は GA の集計遅れで遅れることがあります。
        </p>
      </div>

      {view && view.rules.length === 0 ? (
        <p className="text-sm text-neutral-500 dark:text-neutral-400">PV 達成ルールはまだありません。</p>
      ) : (
        <ul className="space-y-1 text-sm">
          {view?.rules.map((rule) => (
            <li key={rule.id} data-testid="pv-rule-item" className="flex items-center gap-3">
              <span>{ruleLabel(rule)}</span>
              <button
                type="button"
                onClick={() => handleDelete(rule.id)}
                disabled={isPending}
                className="rounded border border-neutral-300 dark:border-neutral-700 px-2 py-0.5 text-xs disabled:text-neutral-400"
              >
                削除
              </button>
            </li>
          ))}
        </ul>
      )}

      <form action={addFormAction} className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">期間</span>
          <select
            name="period"
            defaultValue="daily"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          >
            <option value="daily">1日</option>
            <option value="total">累計</option>
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">閾値(PV)</span>
          <input
            name="threshold"
            type="number"
            min={1}
            step={1}
            autoComplete="off"
            className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
          />
        </label>
        <button
          type="submit"
          disabled={!addable || addPending}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          ルールを追加
        </button>
      </form>
      {!addable && reason && (
        <p data-testid="pv-rules-reason" className="text-sm text-red-600">
          {reason}
        </p>
      )}
      {addState.error && <p className="text-sm text-red-600">{addState.error}</p>}
      {actionResult.error && <p className="text-sm text-red-600">{actionResult.error}</p>}

      {view && (
        <div className="flex flex-wrap items-center gap-3 border-t border-neutral-200 dark:border-neutral-800 pt-4">
          <p className="text-sm">
            本番サイトへの送信:{" "}
            <span
              data-testid="pv-send-status"
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
