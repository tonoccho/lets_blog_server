"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { formatOperationLogDateTime, TIMEZONE_PENDING_PLACEHOLDER } from "@/lib/formatDate";
import { useI18n } from "./I18nProvider";
import { fetchQueueJobsAction } from "./infoRailActions";
import {
  CHECKPOINT_DOWNLOAD_JOB_TYPE,
  GARBAGE_COLLECTION_JOB_TYPE,
  IMAGE_GENERATION_JOB_TYPE,
  isActiveJobStatus,
  type QueueJob,
} from "./infoRailQueue";
import { useViewerTimeZone } from "./operation-logs/useViewerTimeZone";

/** useGenerationJobPolling と同じ間隔。 */
const POLL_INTERVAL_MS = 2000;
/** 進行中のジョブがないときの確認間隔。このページで始めたジョブを、遷移なしで拾うため。 */
const IDLE_POLL_INTERVAL_MS = 10000;

type QueueState =
  | { status: "loading" }
  | { status: "error" }
  | { status: "ready"; jobs: QueueJob[]; timeZone: string | null };

const TYPE_LABEL_KEYS: Record<string, string> = {
  [CHECKPOINT_DOWNLOAD_JOB_TYPE]: "queueTypeCheckpointDownload",
  [GARBAGE_COLLECTION_JOB_TYPE]: "queueTypeGarbageCollection",
  [IMAGE_GENERATION_JOB_TYPE]: "queueTypeImageGeneration",
};

const STATUS_LABEL_KEYS: Record<string, string> = {
  pending: "queueStatusPending",
  running: "queueStatusRunning",
  done: "queueStatusDone",
  failed: "queueStatusFailed",
};

/**
 * 情報表示レールの「処理キュー」タブ。表示中は取得し、進行中(running / pending)のジョブがある間は
 * 2 秒間隔、ないときは 10 秒間隔で取得し直す。ページ遷移のたびに取得し直し、アンマウントで止まる。
 * 取得に失敗したら、エラーを出してポーリングを止める(次のページ遷移で再試行する)。
 */
export function InfoRailQueue() {
  const { t } = useI18n();
  const pathname = usePathname();
  const [state, setState] = useState<QueueState>({ status: "loading" });
  const displayTimeZone = useViewerTimeZone(state.status === "ready" ? state.timeZone : null);

  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | null = null;

    function load() {
      fetchQueueJobsAction().then(
        (result) => {
          if (cancelled) return;
          setState({ status: "ready", ...result });
          const active = result.jobs.some((job) => isActiveJobStatus(job.status));
          timer = setTimeout(load, active ? POLL_INTERVAL_MS : IDLE_POLL_INTERVAL_MS);
        },
        () => {
          if (!cancelled) setState({ status: "error" });
        },
      );
    }

    load();
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, [pathname]);

  const label = (keys: Record<string, string>, value: string) =>
    keys[value] ? t("infoRail", keys[value]) : value;

  return (
    <div data-testid="info-rail-queue-panel" className="space-y-3 text-sm">
      {state.status === "loading" && (
        <p className="text-neutral-500 dark:text-neutral-400">{t("infoRail", "loading")}</p>
      )}
      {state.status === "error" && (
        <p className="text-red-600 dark:text-red-400">{t("infoRail", "queueLoadFailed")}</p>
      )}
      {state.status === "ready" && state.jobs.length === 0 && (
        <p className="text-neutral-500 dark:text-neutral-400">{t("infoRail", "queueEmpty")}</p>
      )}
      {state.status === "ready" && state.jobs.length > 0 && (
        <ul className="divide-y divide-neutral-100 dark:divide-neutral-800">
          {state.jobs.map((job) => (
            <li
              key={job.id}
              data-testid="info-rail-queue-item"
              data-job-id={job.id}
              className="min-w-0 space-y-0.5 py-2"
            >
              <p className="break-all text-xs font-medium">{label(TYPE_LABEL_KEYS, job.type)}</p>
              <p className="text-xs text-neutral-600 dark:text-neutral-400">
                {label(STATUS_LABEL_KEYS, job.status)}
              </p>
              <time dateTime={job.createdAt} className="block text-xs text-neutral-500 dark:text-neutral-400">
                {displayTimeZone
                  ? formatOperationLogDateTime(job.createdAt, displayTimeZone)
                  : TIMEZONE_PENDING_PLACEHOLDER}
              </time>
              {job.resultHref && (
                <Link
                  href={job.resultHref}
                  className="block text-xs font-medium text-blue-700 underline dark:text-blue-400"
                >
                  {t("infoRail", "queueViewResult")}
                </Link>
              )}
            </li>
          ))}
        </ul>
      )}
      <Link
        href="/operation-logs"
        className="block text-xs font-medium text-blue-700 underline dark:text-blue-400"
      >
        {t("infoRail", "queueViewLogs")}
      </Link>
    </div>
  );
}
