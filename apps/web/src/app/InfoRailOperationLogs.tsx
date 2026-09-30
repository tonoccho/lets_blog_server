"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { formatOperationLogDateTime, TIMEZONE_PENDING_PLACEHOLDER } from "@/lib/formatDate";
import type { UnifiedLogEntry } from "@/lib/apiClient";
import { useI18n } from "./I18nProvider";
import { fetchRecentOperationLogsAction } from "./infoRailActions";
import { useViewerTimeZone } from "./operation-logs/useViewerTimeZone";

type LogsState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; entries: UnifiedLogEntry[]; timeZone: string | null };

/** 情報表示レールの「操作ログ」タブ。開いた時点と、ページ遷移のたびに取得し直す(自動更新はしない)。 */
export function InfoRailOperationLogs() {
  const { t } = useI18n();
  const pathname = usePathname();
  const [state, setState] = useState<LogsState>({ status: "loading" });
  const displayTimeZone = useViewerTimeZone(state.status === "ready" ? state.timeZone : null);

  useEffect(() => {
    let cancelled = false;
    fetchRecentOperationLogsAction().then(
      (result) => {
        if (!cancelled) setState({ status: "ready", ...result });
      },
      () => {
        if (!cancelled) setState({ status: "error" });
      },
    );
    return () => {
      cancelled = true;
    };
  }, [pathname]);

  return (
    <div data-testid="info-rail-logs-panel" className="space-y-3 text-sm">
      {state.status === "loading" && (
        <p className="text-neutral-500 dark:text-neutral-400">{t("infoRail", "loading")}</p>
      )}
      {state.status === "error" && (
        <p className="text-red-600 dark:text-red-400">{t("infoRail", "loadFailed")}</p>
      )}
      {state.status === "ready" && state.entries.length === 0 && (
        <p className="text-neutral-500 dark:text-neutral-400">{t("infoRail", "empty")}</p>
      )}
      {state.status === "ready" && state.entries.length > 0 && (
        <ul className="divide-y divide-neutral-100 dark:divide-neutral-800">
          {state.entries.map((entry) => (
            <li
              key={`${entry.sourceType}-${entry.id}`}
              data-testid="info-rail-log-item"
              className="min-w-0 space-y-0.5 py-2"
            >
              <time
                dateTime={entry.createdAt}
                className="block text-xs text-neutral-500 dark:text-neutral-400"
              >
                {displayTimeZone
                  ? formatOperationLogDateTime(entry.createdAt, displayTimeZone)
                  : TIMEZONE_PENDING_PLACEHOLDER}
              </time>
              <p className="break-all font-mono text-xs">{entry.title}</p>
              {entry.status && (
                <p className="text-xs text-neutral-600 dark:text-neutral-400">{entry.status}</p>
              )}
            </li>
          ))}
        </ul>
      )}
      <Link
        href="/operation-logs"
        className="block text-xs font-medium text-blue-700 underline dark:text-blue-400"
      >
        {t("infoRail", "viewAll")}
      </Link>
    </div>
  );
}
