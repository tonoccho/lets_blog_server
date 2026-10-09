"use client";

import { useEffect, useRef, useState } from "react";
import { fetchGenerationJobAction } from "./actions";
import type { GenerationJobDetail } from "@/lib/apiClient";

export const POLL_INTERVAL_MS = 2000;
/** 取得が連続してこの回数失敗したらポーリングを止め、onPollFailed で呼び出し側へ伝える(2秒間隔で約8秒分)。 */
export const MAX_CONSECUTIVE_POLL_FAILURES = 5;

/** 進捗を確認できなくなったことを利用者へ示す文言(各画面で共通)。 */
export function pollFailureMessage(error: unknown): string {
  const reason = error instanceof Error ? error.message : String(error);
  return `進捗を確認できませんでした(${reason})。サーバ側の処理は続いている可能性があります。画面を再読み込みして状態を確認してください。`;
}

/**
 * モデルインストール等の非同期ジョブ(GenerationJob)の完了をポーリングして待つ。
 * startPolling(jobId) で開始し、status が done/failed になったら onSettled を呼んで停止する。
 * 取得が1回失敗しても同じ間隔で続け、成功すれば失敗の連続回数を数え直す。連続で
 * MAX_CONSECUTIVE_POLL_FAILURES 回失敗したら停止し、最後のエラーを onPollFailed へ渡す。
 */
export function useGenerationJobPolling(
  onSettled: (job: GenerationJobDetail) => void,
  onProgress?: (job: GenerationJobDetail) => void,
  onPollFailed?: (error: unknown) => void
) {
  const [activeJobId, setActiveJobId] = useState<number | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const onSettledRef = useRef(onSettled);
  // eslint-disable-next-line
  onSettledRef.current = onSettled;
  const onProgressRef = useRef(onProgress);
  // eslint-disable-next-line
  onProgressRef.current = onProgress;
  const onPollFailedRef = useRef(onPollFailed);
  // eslint-disable-next-line
  onPollFailedRef.current = onPollFailed;

  useEffect(() => {
    if (activeJobId === null) {
      return;
    }
    let cancelled = false;
    let consecutiveFailures = 0;

    async function poll() {
      let job: GenerationJobDetail;
      try {
        job = await fetchGenerationJobAction(activeJobId as number);
      } catch (error) {
        if (cancelled) {
          return;
        }
        consecutiveFailures += 1;
        if (consecutiveFailures >= MAX_CONSECUTIVE_POLL_FAILURES) {
          setActiveJobId(null);
          onPollFailedRef.current?.(error);
        } else {
          timerRef.current = setTimeout(poll, POLL_INTERVAL_MS);
        }
        return;
      }
      if (cancelled) {
        return;
      }
      consecutiveFailures = 0;
      if (job.status === "running" || job.status === "pending") {
        onProgressRef.current?.(job);
        timerRef.current = setTimeout(poll, POLL_INTERVAL_MS);
      } else {
        setActiveJobId(null);
        onSettledRef.current(job);
      }
    }

    poll();

    return () => {
      cancelled = true;
      if (timerRef.current) {
        clearTimeout(timerRef.current);
      }
    };
  }, [activeJobId]);

  return { startPolling: setActiveJobId, isPolling: activeJobId !== null };
}
