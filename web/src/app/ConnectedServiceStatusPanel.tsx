"use client";

import { useEffect, useState } from "react";
import { CheckCircle2, AlertTriangle, XCircle } from "lucide-react";
import type { ConnectedServiceStatus, ConnectedServiceStatusDetail } from "@/lib/apiClient";

const POLL_INTERVAL_MS = 30000;

const STATUS_LABEL: Record<ConnectedServiceStatus["status"], string> = {
  NORMAL: "正常",
  WARNING: "警告",
  ERROR: "エラー",
};

const STATUS_STYLE: Record<ConnectedServiceStatus["status"], string> = {
  NORMAL: "text-green-700 dark:text-green-400",
  WARNING: "text-amber-700 dark:text-amber-400",
  ERROR: "text-red-700 dark:text-red-400",
};

function StatusIcon({ status }: { status: ConnectedServiceStatus["status"] }) {
  const className = `h-4 w-4 ${STATUS_STYLE[status]}`;
  if (status === "NORMAL") return <CheckCircle2 className={className} aria-hidden="true" />;
  if (status === "WARNING") return <AlertTriangle className={className} aria-hidden="true" />;
  return <XCircle className={className} aria-hidden="true" />;
}

export function ConnectedServiceStatusPanel({
  initialStatuses,
  initialDetail,
}: {
  initialStatuses: ConnectedServiceStatus[];
  /** admin向けの詳細診断情報(issue #199)。非adminまたは取得失敗時はnull(セクション自体を表示しない)。 */
  initialDetail: ConnectedServiceStatusDetail[] | null;
}) {
  const [statuses, setStatuses] = useState(initialStatuses);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<Date | null>(null);
  const [live, setLive] = useState(false);

  useEffect(() => {
    let cancelled = false;
    let pollIntervalId: ReturnType<typeof setInterval> | null = null;

    const applyStatuses = (data: ConnectedServiceStatus[]) => {
      if (cancelled) return;
      setStatuses(data);
      setLastUpdatedAt(new Date());
    };

    const refresh = async () => {
      try {
        const res = await fetch("/api/dashboard/service-status", { cache: "no-store" });
        if (!res.ok) return;
        applyStatuses((await res.json()) as ConnectedServiceStatus[]);
      } catch {
        // ポーリングの失敗は無視し、次回の更新を待つ(直近の表示を維持する)
      }
    };

    const startPolling = () => {
      if (pollIntervalId !== null) return;
      pollIntervalId = setInterval(refresh, POLL_INTERVAL_MS);
    };

    // issue #198: SSEで即時配信を受け取る。接続確立に失敗した場合(非対応ブラウザ・
    // ルート自体に到達できない等)のみ、既存の定期ポーリングにフォールバックする
    // (再接続中の一時的なerrorイベントでは切り替えない。EventSourceは自動再接続するため)。
    if (typeof EventSource === "undefined") {
      startPolling();
      return () => {
        cancelled = true;
        if (pollIntervalId !== null) clearInterval(pollIntervalId);
      };
    }

    const eventSource = new EventSource("/api/dashboard/service-status/stream");
    eventSource.addEventListener("open", () => {
      if (!cancelled) setLive(true);
    });
    eventSource.addEventListener("status", (event: MessageEvent<string>) => {
      try {
        applyStatuses(JSON.parse(event.data) as ConnectedServiceStatus[]);
      } catch {
        // 不正なペイロードは無視する
      }
    });
    eventSource.onerror = () => {
      if (eventSource.readyState === EventSource.CLOSED) {
        if (!cancelled) setLive(false);
        startPolling();
      }
    };

    return () => {
      cancelled = true;
      eventSource.close();
      if (pollIntervalId !== null) clearInterval(pollIntervalId);
    };
  }, []);

  return (
    <section className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5 shadow-sm">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-neutral-700 dark:text-neutral-300">接続サービスの稼働状況</h2>
        <div className="flex items-center gap-2">
          {live && (
            <span className="flex items-center gap-1 text-xs text-green-700 dark:text-green-400">
              <span className="h-1.5 w-1.5 rounded-full bg-green-500" aria-hidden="true" />
              リアルタイム更新中
            </span>
          )}
          {lastUpdatedAt && (
            <span className="text-xs text-neutral-500 dark:text-neutral-400">
              最終更新: {lastUpdatedAt.toLocaleTimeString("ja-JP")}
            </span>
          )}
        </div>
      </div>
      <ul className="mt-4 grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
        {statuses.map((service) => (
          <li
            key={service.id}
            className="flex items-center justify-between gap-2 rounded-md border border-neutral-200 dark:border-neutral-800 px-3 py-2"
          >
            <span className="text-sm text-neutral-700 dark:text-neutral-300">{service.name}</span>
            <span className={`flex items-center gap-1 text-xs font-medium ${STATUS_STYLE[service.status]}`}>
              <StatusIcon status={service.status} />
              {STATUS_LABEL[service.status]}
            </span>
          </li>
        ))}
      </ul>
      {initialDetail && initialDetail.length > 0 && (
        <details className="mt-4 border-t border-neutral-200 dark:border-neutral-800 pt-4">
          <summary className="cursor-pointer text-sm font-semibold text-neutral-700 dark:text-neutral-300">
            管理者向け詳細診断
          </summary>
          <div className="mt-3 overflow-x-auto">
            <table className="w-full min-w-[720px] text-left text-xs">
              <thead>
                <tr className="text-neutral-500 dark:text-neutral-400">
                  <th className="pb-2 pr-4 font-medium">サービス</th>
                  <th className="pb-2 pr-4 font-medium">ステータス</th>
                  <th className="pb-2 pr-4 font-medium">応答時間</th>
                  <th className="pb-2 pr-4 font-medium">HTTPステータス</th>
                  <th className="pb-2 pr-4 font-medium">エラー内容</th>
                  <th className="pb-2 pr-4 font-medium">チェック対象URL</th>
                  <th className="pb-2 font-medium">最終チェック時刻</th>
                </tr>
              </thead>
              <tbody>
                {initialDetail.map((detail) => (
                  <tr key={detail.id} className="border-t border-neutral-100 dark:border-neutral-800">
                    <td className="py-2 pr-4 text-neutral-700 dark:text-neutral-300">{detail.name}</td>
                    <td className={`py-2 pr-4 font-medium ${STATUS_STYLE[detail.status]}`}>
                      {STATUS_LABEL[detail.status]}
                    </td>
                    <td className="py-2 pr-4 text-neutral-700 dark:text-neutral-300">{detail.responseTimeMs}ms</td>
                    <td className="py-2 pr-4 text-neutral-700 dark:text-neutral-300">{detail.httpStatus ?? "-"}</td>
                    <td className="py-2 pr-4 text-neutral-700 dark:text-neutral-300">{detail.errorMessage ?? "-"}</td>
                    <td className="py-2 pr-4 break-all text-neutral-700 dark:text-neutral-300">
                      {detail.targetUrl ?? "-"}
                    </td>
                    <td className="py-2 text-neutral-700 dark:text-neutral-300">
                      {new Date(detail.checkedAt).toLocaleString("ja-JP")}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      )}
    </section>
  );
}
