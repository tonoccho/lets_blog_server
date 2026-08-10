"use client";

import { useEffect, useState } from "react";
import { CheckCircle2, AlertTriangle, XCircle } from "lucide-react";
import type { ConnectedServiceStatus } from "@/lib/apiClient";

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

export function ConnectedServiceStatusPanel({ initialStatuses }: { initialStatuses: ConnectedServiceStatus[] }) {
  const [statuses, setStatuses] = useState(initialStatuses);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<Date | null>(null);

  useEffect(() => {
    let cancelled = false;

    const refresh = async () => {
      try {
        const res = await fetch("/api/dashboard/service-status", { cache: "no-store" });
        if (!res.ok) return;
        const data = (await res.json()) as ConnectedServiceStatus[];
        if (!cancelled) {
          setStatuses(data);
          setLastUpdatedAt(new Date());
        }
      } catch {
        // ポーリングの失敗は無視し、次回の更新を待つ(直近の表示を維持する)
      }
    };

    const intervalId = setInterval(refresh, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearInterval(intervalId);
    };
  }, []);

  return (
    <section className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5 shadow-sm">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-neutral-700 dark:text-neutral-300">接続サービスの稼働状況</h2>
        {lastUpdatedAt && (
          <span className="text-xs text-neutral-500 dark:text-neutral-400">
            最終更新: {lastUpdatedAt.toLocaleTimeString("ja-JP")}
          </span>
        )}
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
    </section>
  );
}
