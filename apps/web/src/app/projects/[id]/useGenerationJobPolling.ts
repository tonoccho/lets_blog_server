"use client";

import { useEffect, useRef, useState } from "react";
import { fetchGenerationJobAction } from "./actions";
import type { GenerationJobDetail } from "@/lib/apiClient";

const POLL_INTERVAL_MS = 2000;

/**
 * モデルインストール等の非同期ジョブ(GenerationJob)の完了をポーリングして待つ。
 * startPolling(jobId) で開始し、status が done/failed になったら onSettled を呼んで停止する。
 */
export function useGenerationJobPolling(
  onSettled: (job: GenerationJobDetail) => void,
  onProgress?: (job: GenerationJobDetail) => void
) {
  const [activeJobId, setActiveJobId] = useState<number | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const onSettledRef = useRef(onSettled);
  // eslint-disable-next-line
  onSettledRef.current = onSettled;
  const onProgressRef = useRef(onProgress);
  // eslint-disable-next-line
  onProgressRef.current = onProgress;

  useEffect(() => {
    if (activeJobId === null) {
      return;
    }
    let cancelled = false;

    async function poll() {
      const job = await fetchGenerationJobAction(activeJobId as number);
      if (cancelled) {
        return;
      }
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
