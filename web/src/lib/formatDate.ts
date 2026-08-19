function getDefaultTimeZone(): string {
  if (typeof Intl !== "undefined" && Intl.DateTimeFormat) {
    try {
      return Intl.DateTimeFormat().resolvedOptions().timeZone;
    } catch {
      return "UTC";
    }
  }
  return "UTC";
}

export function formatDateTime(iso: string, timeZone?: string | null): string {
  const tz = timeZone ?? getDefaultTimeZone();
  return new Date(iso).toLocaleString("ja-JP", { timeZone: tz });
}

/** 操作ログの日時表示を24時間表記(HH:mm:ss、ゼロ埋め)に統一する(issue #282)。 */
export function formatOperationLogDateTime(iso: string, timeZone?: string | null): string {
  const tz = timeZone ?? getDefaultTimeZone();
  return new Date(iso).toLocaleString("ja-JP", {
    timeZone: tz,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
}
