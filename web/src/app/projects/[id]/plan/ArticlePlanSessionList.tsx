"use client";

import type { ArticlePlanSessionSummary } from "@/lib/apiClient";

function formatSessionDate(iso: string): string {
  const d = new Date(iso);
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, "0");
  const dd = String(d.getDate()).padStart(2, "0");
  return `${yyyy}${mm}${dd}`;
}

export function ArticlePlanSessionList({
  sessions,
  activeSessionId,
  onSelect,
  onNewChat,
  isLoading,
  error,
}: {
  sessions: ArticlePlanSessionSummary[];
  activeSessionId: number | null;
  onSelect: (id: number) => void;
  onNewChat: () => void;
  isLoading: boolean;
  error?: string;
}) {
  return (
    <div className="rounded-lg border border-neutral-200 bg-white p-5">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="font-medium">壁打ち一覧</h2>
        <button
          onClick={onNewChat}
          className="rounded border border-neutral-300 px-3 py-1.5 text-sm text-neutral-700 hover:bg-neutral-50"
        >
          新規チャットを開始
        </button>
      </div>

      {error && <p className="mb-2 text-sm text-red-600">{error}</p>}

      {sessions.length === 0 ? (
        <p className="text-sm text-neutral-500">まだ壁打ちセッションはありません。</p>
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
                  : "border-neutral-300 text-neutral-700 hover:bg-neutral-50"
              }`}
            >
              {formatSessionDate(s.createdAt)}-{s.title}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
