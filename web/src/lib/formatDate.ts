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
