"use client";

import type { PlanChatMessage } from "@/lib/apiClient";

export function ArticlePlanChat({
  history,
  error,
  formAction,
  pending,
}: {
  history: PlanChatMessage[];
  error?: string;
  formAction: (formData: FormData) => void;
  pending: boolean;
}) {
  return (
    <div className="rounded-lg border border-neutral-200 bg-white p-5">
      <h2 className="mb-4 font-medium">AI との壁打ち</h2>

      <div className="mb-4 max-h-96 space-y-3 overflow-y-auto rounded-lg bg-neutral-50 p-4">
        {history.length === 0 ? (
          <p className="text-sm text-neutral-500">チャットを始めましょう。記事のテーマや企画を入力してください。</p>
        ) : (
          history.map((msg, idx) => (
            <div
              key={idx}
              className={`rounded px-3 py-2 text-sm ${
                msg.role === "user" ? "bg-blue-100 text-blue-900" : "bg-neutral-200 text-neutral-900"
              }`}
            >
              <strong>{msg.role === "user" ? "あなた" : "AI"}:</strong> {msg.content}
            </div>
          ))
        )}
      </div>

      <form action={formAction} className="flex gap-2">
        <input
          key={history.length}
          type="text"
          name="message"
          placeholder="質問や企画案を入力..."
          required
          disabled={pending}
          className="flex-1 rounded border border-neutral-300 px-3 py-2 text-sm disabled:bg-neutral-100"
        />
        <button
          type="submit"
          disabled={pending}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {pending ? "送信中…" : "送信"}
        </button>
      </form>
      {error && <p className="mt-2 text-sm text-red-600">{error}</p>}
    </div>
  );
}
