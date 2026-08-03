"use client";

import { useState } from "react";
import type { PlanChatMessage } from "@/lib/apiClient";
import { sendPlanChatMessage } from "./actions";

export function ArticlePlanChat({
  projectId,
  history,
  setHistory,
}: {
  projectId: number;
  history: PlanChatMessage[];
  setHistory: (history: PlanChatMessage[]) => void;
}) {
  const [input, setInput] = useState("");
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | undefined>(undefined);

  const handleSendMessage = async () => {
    const message = input.trim();
    if (!message || isLoading) return;

    setInput("");
    setIsLoading(true);
    setError(undefined);

    const result = await sendPlanChatMessage(projectId, history, message);
    if (result.ok) {
      setHistory([
        ...history,
        { role: "user", content: message },
        { role: "assistant", content: result.data.reply },
      ]);
    } else {
      setError(result.error);
    }
    setIsLoading(false);
  };

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

      <div className="flex gap-2">
        <input
          type="text"
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !isLoading) {
              handleSendMessage();
            }
          }}
          placeholder="質問や企画案を入力..."
          disabled={isLoading}
          className="flex-1 rounded border border-neutral-300 px-3 py-2 text-sm disabled:bg-neutral-100"
        />
        <button
          onClick={handleSendMessage}
          disabled={isLoading || !input.trim()}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
        >
          {isLoading ? "送信中…" : "送信"}
        </button>
      </div>
      {error && <p className="mt-2 text-sm text-red-600">{error}</p>}
    </div>
  );
}
