"use client";

import { useEffect, useState } from "react";
import type { ArticlePlanSessionSummary } from "@/lib/apiClient";
import { formatDateYYYYMMDD, TIMEZONE_PENDING_PLACEHOLDER } from "@/lib/formatDate";

export function ArticlePlanSessionList({
  sessions,
  activeSessionId,
  onSelect,
  onNewChat,
  isLoading,
  error,
  timezone,
}: {
  sessions: ArticlePlanSessionSummary[];
  activeSessionId: number | null;
  onSelect: (id: number) => void;
  onNewChat: () => void;
  isLoading: boolean;
  error?: string;
  timezone: string | null;
}) {
  // 個人設定TZが未設定のときだけ使う(mounted前後でサーバー/クライアントの出力を
  // 一致させるため、issue #1362と同じ形。issue #1366)。個人設定TZがあるときはSSR/
  // クライアントで常に同じ文字列になるためこのフラグを見ない(PostsTable.tsxと同じ形)。
  const [mounted, setMounted] = useState(false);
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setMounted(true);
  }, []);

  return (
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="font-medium">壁打ち一覧</h2>
        <button
          onClick={onNewChat}
          className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-1.5 text-sm text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800"
        >
          新規チャットを開始
        </button>
      </div>

      {error && <p className="mb-2 text-sm text-red-600">{error}</p>}

      {sessions.length === 0 ? (
        <p className="text-sm text-neutral-500 dark:text-neutral-400">まだ壁打ちセッションはありません。</p>
      ) : (
        <div className="flex flex-wrap gap-2">
          {sessions.map((s) => (
            <button
              key={s.id}
              onClick={() => onSelect(s.id)}
              disabled={isLoading}
              className={`rounded-full border px-3 py-1.5 text-sm disabled:opacity-50 ${
                s.id === activeSessionId
                  ? "border-neutral-900 bg-neutral-900 text-white"
                  : "border-neutral-300 dark:border-neutral-700 text-neutral-700 dark:text-neutral-300 hover:bg-neutral-50 dark:hover:bg-neutral-800"
              }`}
            >
              {s.githubIssueNumber && (
                <span className="mr-1 rounded bg-blue-100 px-1.5 py-0.5 text-xs text-blue-700">
                  #{s.githubIssueNumber}
                </span>
              )}
              {timezone
                ? formatDateYYYYMMDD(s.createdAt, timezone)
                : mounted
                  ? formatDateYYYYMMDD(s.createdAt)
                  : TIMEZONE_PENDING_PLACEHOLDER}
              -{s.title}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
