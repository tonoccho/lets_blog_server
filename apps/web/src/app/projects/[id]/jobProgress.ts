export interface JobProgress {
  phase: string;
  percent: number | null;
  bytesDone: number | null;
  bytesTotal: number | null;
}

/**
 * GenerationJob.resultPayload(実行中は進捗JSON、完了後はsuccess/errorのJSON)から
 * 進捗情報を取り出す。進捗情報でない(完了後の形)場合はnullを返す。
 */
export function parseJobProgress(resultPayload: string | null): JobProgress | null {
  if (!resultPayload) {
    return null;
  }
  try {
    const parsed = JSON.parse(resultPayload) as Record<string, unknown>;
    if (typeof parsed.phase !== "string") {
      return null;
    }
    return {
      phase: parsed.phase,
      percent: typeof parsed.percent === "number" ? parsed.percent : null,
      bytesDone: typeof parsed.bytesDone === "number" ? parsed.bytesDone : null,
      bytesTotal: typeof parsed.bytesTotal === "number" ? parsed.bytesTotal : null,
    };
  } catch {
    return null;
  }
}

export function formatJobProgress(progress: JobProgress): string {
  const parts = [progress.phase];
  if (progress.percent !== null) {
    parts.push(`${progress.percent}%`);
  } else if (progress.bytesDone !== null && progress.bytesTotal !== null && progress.bytesTotal > 0) {
    parts.push(`${formatBytes(progress.bytesDone)} / ${formatBytes(progress.bytesTotal)}`);
  }
  return parts.join(" ");
}

export function formatBytes(bytes: number): string {
  if (bytes <= 0) {
    return "0 B";
  }
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  return `${value.toFixed(1)} ${units[unitIndex]}`;
}
