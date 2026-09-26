"use client";

import { TIMEZONE_PENDING_PLACEHOLDER } from "@/lib/formatDate";
import { useViewerTimeZone } from "./useViewerTimeZone";

/** 操作ログの日時が、どのタイムゾーンで表示されているかを画面に明示する(issue #1260)。 */
export function OperationLogTimeZoneLabel({ personalTimeZone }: { personalTimeZone: string | null }) {
  const timeZone = useViewerTimeZone(personalTimeZone);
  return (
    <p data-testid="operation-log-timezone" className="text-xs text-neutral-500 dark:text-neutral-400">
      表示タイムゾーン: {timeZone ?? TIMEZONE_PENDING_PLACEHOLDER}
    </p>
  );
}
