"use client";

import { useState } from "react";
import { formatDateTime } from "@/lib/formatDate";
import { describeOperationTraceText, type OperationGroup } from "./operationGroups";
import { copyOperationTraceAction } from "./actions";

export function OperationGroupRow({ group, timezone }: { group: OperationGroup; timezone: string | null }) {
  const [expanded, setExpanded] = useState(false);
  const [copied, setCopied] = useState(false);
  const firstEntry = group.entries[0];

  async function handleCopy() {
    // ページ境界をまたいだ欠落が無いよう、operationIdに紐づく全件をサーバーから取り直してコピーする。
    const text = await copyOperationTraceAction(group.operationId).catch(() =>
      describeOperationTraceText(group, timezone)
    );
    await navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  return (
    <div className="border-b border-neutral-100 dark:border-neutral-800 last:border-0">
      <div className="flex items-center justify-between gap-3 px-4 py-3">
        <button
          type="button"
          onClick={() => setExpanded((v) => !v)}
          className="flex flex-1 items-center gap-3 text-left text-sm"
        >
          <span className="text-neutral-600 dark:text-neutral-400">{formatDateTime(group.startedAt, timezone)}</span>
          <span className="font-mono">
            {firstEntry.method} {firstEntry.path}
            {group.entries.length > 1 ? ` ほか${group.entries.length - 1}件` : ""}
          </span>
          <span
            className={
              group.success
                ? "rounded px-2 py-0.5 text-xs text-green-700 bg-green-100 dark:text-green-300 dark:bg-green-900"
                : "rounded px-2 py-0.5 text-xs text-red-700 bg-red-100 dark:text-red-300 dark:bg-red-900"
            }
          >
            {group.success ? "成功" : "エラーあり"}
          </span>
        </button>
        <button
          type="button"
          onClick={handleCopy}
          className="rounded bg-neutral-100 dark:bg-neutral-800 px-2 py-1 text-xs text-neutral-700 dark:text-neutral-300 shrink-0"
          title="このAI/サポート共有用にトレース全体をコピーします"
        >
          {copied ? "コピーしました" : "コピー"}
        </button>
      </div>
      {expanded && (
        <div className="px-4 pb-3">
          <table className="w-full text-left text-xs">
            <thead className="text-neutral-500 dark:text-neutral-400">
              <tr>
                <th className="px-2 py-1">日時</th>
                <th className="px-2 py-1">メソッド</th>
                <th className="px-2 py-1">パス</th>
                <th className="px-2 py-1">ステータス</th>
                <th className="px-2 py-1">所要時間</th>
                <th className="px-2 py-1">エラー</th>
              </tr>
            </thead>
            <tbody>
              {group.entries.map((entry) => (
                <tr key={entry.id} className="border-t border-neutral-100 dark:border-neutral-800">
                  <td className="px-2 py-1 text-neutral-600 dark:text-neutral-400">{formatDateTime(entry.createdAt, timezone)}</td>
                  <td className="px-2 py-1 font-mono">{entry.method}</td>
                  <td className="px-2 py-1 font-mono">{entry.path}</td>
                  <td className="px-2 py-1">{entry.statusCode ?? "-"}</td>
                  <td className="px-2 py-1">{entry.durationMs}ms</td>
                  <td className="px-2 py-1 text-red-700 dark:text-red-300">{entry.errorMessage ?? "-"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
