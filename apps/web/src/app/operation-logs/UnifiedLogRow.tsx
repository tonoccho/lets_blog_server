"use client";

import { useState } from "react";
import { formatOperationLogDateTime, TIMEZONE_PENDING_PLACEHOLDER } from "@/lib/formatDate";
import type { UnifiedLogEntry, UnifiedLogSourceType } from "@/lib/apiClient";
import { copyOperationTraceAction } from "./actions";
import { useViewerTimeZone } from "./useViewerTimeZone";

const SOURCE_LABEL: Record<UnifiedLogSourceType, string> = {
  OPERATION: "操作",
  AI_JOB: "AI",
  AUDIT: "監査",
};

const SOURCE_BADGE_CLASS: Record<UnifiedLogSourceType, string> = {
  OPERATION: "text-neutral-700 bg-neutral-100 dark:text-neutral-300 dark:bg-neutral-800",
  AI_JOB: "text-purple-700 bg-purple-100 dark:text-purple-300 dark:bg-purple-900",
  AUDIT: "text-blue-700 bg-blue-100 dark:text-blue-300 dark:bg-blue-900",
};

function statusBadgeClass(status: string | null): string {
  if (status === "SUCCESS" || status === "done") {
    return "text-green-700 bg-green-100 dark:text-green-300 dark:bg-green-900";
  }
  if (status === "FAILED" || status === "failed") {
    return "text-red-700 bg-red-100 dark:text-red-300 dark:bg-red-900";
  }
  return "text-neutral-600 bg-neutral-100 dark:text-neutral-400 dark:bg-neutral-800";
}

export function UnifiedLogRow({ entry, timezone }: { entry: UnifiedLogEntry; timezone: string | null }) {
  const [copied, setCopied] = useState(false);
  // 個人設定TZ、無ければブラウザTZ。ブラウザTZが未解決の間(SSR・ハイドレーション中)は仮表示(issue #1260)。
  const displayTimeZone = useViewerTimeZone(timezone);

  async function handleCopy() {
    if (!entry.operationId) return;
    const text = await copyOperationTraceAction(entry.operationId);
    await navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-b border-neutral-100 dark:border-neutral-800 px-4 py-3 last:border-0">
      <div className="flex flex-1 flex-wrap items-center gap-3 text-sm">
        <span className="w-36 shrink-0 text-neutral-600 dark:text-neutral-400">
          {displayTimeZone ? formatOperationLogDateTime(entry.createdAt, displayTimeZone) : TIMEZONE_PENDING_PLACEHOLDER}
        </span>
        <span className={`shrink-0 rounded px-2 py-0.5 text-xs font-medium ${SOURCE_BADGE_CLASS[entry.sourceType]}`}>
          {SOURCE_LABEL[entry.sourceType]}
        </span>
        <span className="font-mono">{entry.title}</span>
        {entry.detail && (
          <span className="truncate text-xs text-neutral-500 dark:text-neutral-400">{entry.detail}</span>
        )}
        {entry.actorKeycloakSub && (
          <span
            className="shrink-0 truncate rounded bg-neutral-100 dark:bg-neutral-800 px-2 py-0.5 font-mono text-xs text-neutral-600 dark:text-neutral-400"
            title={`Keycloak Sub: ${entry.actorKeycloakSub}`}
          >
            Keycloak: {entry.actorKeycloakSub}
          </span>
        )}
        {entry.status && (
          <span className={`shrink-0 rounded px-2 py-0.5 text-xs ${statusBadgeClass(entry.status)}`}>
            {entry.status}
          </span>
        )}
      </div>
      {entry.sourceType === "OPERATION" && entry.operationId && (
        <button
          type="button"
          onClick={handleCopy}
          className="shrink-0 rounded bg-neutral-100 dark:bg-neutral-800 px-2 py-1 text-xs text-neutral-700 dark:text-neutral-300"
          title="このAI/サポート共有用にトレース全体をコピーします"
        >
          {copied ? "コピーしました" : "コピー"}
        </button>
      )}
    </div>
  );
}
