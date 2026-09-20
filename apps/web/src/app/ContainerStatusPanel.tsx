"use client";

import { useEffect, useState } from "react";
import { CheckCircle2, AlertTriangle, XCircle } from "lucide-react";
import type { ContainerStatus } from "@/lib/apiClient";

const POLL_INTERVAL_MS = 30000;

const STATUS_LABEL: Record<ContainerStatus["status"], string> = {
  NORMAL: "正常",
  WARNING: "警告",
  ERROR: "エラー",
};

const STATUS_STYLE: Record<ContainerStatus["status"], string> = {
  NORMAL: "text-green-700 dark:text-green-400",
  WARNING: "text-amber-700 dark:text-amber-400",
  ERROR: "text-red-700 dark:text-red-400",
};

function StatusIcon({ status }: { status: ContainerStatus["status"] }) {
  const className = `h-4 w-4 ${STATUS_STYLE[status]}`;
  if (status === "NORMAL") return <CheckCircle2 className={className} aria-hidden="true" />;
  if (status === "WARNING") return <AlertTriangle className={className} aria-hidden="true" />;
  return <XCircle className={className} aria-hidden="true" />;
}

/**
 * このアプリを構成するDockerコンテナ(lbs-*)の稼働状況を表示する(issue #280)。
 * ConnectedServiceStatusPanelと同じSSE+ポーリングフォールバックの構成を踏襲する。
 */
export function ContainerStatusPanel({
  initialStatuses,
  personalTimeZone,
}: {
  initialStatuses: ContainerStatus[];
  /**
   * 個人設定(システム画面)で保存したタイムゾーン(issue #1362、親issue #1261 分割A)。
   * `lastUpdatedAt`は初期値nullでマウント後の更新でしか入らないため、SSRとクライアントの
   * 初期描画は常に一致しており、ここではmountedフラグによるgateは要らない(ThemeSwitcher
   * のような「マウント前に確定できない値をSSRにも描く」ケースではない)。
   */
  personalTimeZone: string | null;
}) {
  const [statuses, setStatuses] = useState(initialStatuses);
  const [lastUpdatedAt, setLastUpdatedAt] = useState<Date | null>(null);
  const [live, setLive] = useState(false);
  // 取得失敗を握り潰さず画面に出す(issue #876)。以前は `if (!res.ok) return;` で
  // 捨てていたため、認証エラー(401)でも「データが無い」ようにしか見えなかった。
  const [fetchError, setFetchError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    let pollIntervalId: ReturnType<typeof setInterval> | null = null;

    const applyStatuses = (data: ContainerStatus[]) => {
      if (cancelled) return;
      setStatuses(data);
      setLastUpdatedAt(new Date());
    };

    const refresh = async () => {
      try {
        const res = await fetch("/api/dashboard/container-status", { cache: "no-store" });
        if (!res.ok) {
          if (cancelled) return;
          setFetchError(
            res.status === 401 || res.status === 403
              ? "認証されていないためコンテナの状態を取得できません。再ログインしてください。"
              : `コンテナの状態の取得に失敗しました(HTTP ${res.status})。`
          );
          return;
        }
        if (!cancelled) setFetchError(null);
        applyStatuses((await res.json()) as ContainerStatus[]);
      } catch {
        // ネットワーク到達不能。次回の更新を待つ(直近の表示は維持する)。
        if (!cancelled) setFetchError("コンテナの状態を取得できませんでした(通信エラー)。");
      }
    };

    const startPolling = () => {
      if (pollIntervalId !== null) return;
      pollIntervalId = setInterval(refresh, POLL_INTERVAL_MS);
    };

    if (typeof EventSource === "undefined") {
      startPolling();
      return () => {
        cancelled = true;
        if (pollIntervalId !== null) clearInterval(pollIntervalId);
      };
    }

    const eventSource = new EventSource("/api/dashboard/container-status/stream");
    eventSource.addEventListener("open", () => {
      if (!cancelled) setLive(true);
    });
    eventSource.addEventListener("status", (event: MessageEvent<string>) => {
      try {
        applyStatuses(JSON.parse(event.data) as ContainerStatus[]);
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
        <h2 className="text-sm font-semibold text-neutral-700 dark:text-neutral-300">コンテナの稼働状況</h2>
        <div className="flex items-center gap-2">
          {live && (
            <span className="flex items-center gap-1 text-xs text-green-700 dark:text-green-400">
              <span className="h-1.5 w-1.5 rounded-full bg-green-500" aria-hidden="true" />
              リアルタイム更新中
            </span>
          )}
          {lastUpdatedAt && (
            <span className="text-xs text-neutral-500 dark:text-neutral-400">
              最終更新: {lastUpdatedAt.toLocaleTimeString("ja-JP", { timeZone: personalTimeZone ?? undefined })}
            </span>
          )}
        </div>
      </div>
      {fetchError && (
        <p className="mt-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">
          {fetchError}
        </p>
      )}
      {statuses.length === 0 ? (
        <p className="mt-4 text-sm text-neutral-500 dark:text-neutral-400">
          コンテナの状態を取得できませんでした(docker-socket-proxyが未設定/未起動の可能性があります)。
        </p>
      ) : (
        <ul className="mt-4 grid grid-cols-1 gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {statuses.map((container) => (
            <li
              key={container.id}
              className="flex items-center justify-between gap-2 rounded-md border border-neutral-200 dark:border-neutral-800 px-3 py-2"
              title={container.detail || undefined}
            >
              <span className="text-sm text-neutral-700 dark:text-neutral-300">{container.name}</span>
              <span className={`flex items-center gap-1 text-xs font-medium ${STATUS_STYLE[container.status]}`}>
                <StatusIcon status={container.status} />
                {STATUS_LABEL[container.status]}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
