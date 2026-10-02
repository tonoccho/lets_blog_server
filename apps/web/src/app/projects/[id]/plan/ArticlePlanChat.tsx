"use client";

import { useState } from "react";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import type { PlanChatMessage } from "@/lib/apiClient";

const SAFE_URL = /^(https?:|mailto:|tel:|#|\/|\.\.?\/|\?)/i;

/**
 * AI 応答は信頼できない入力として扱う(#1566)。生 HTML は rehype-raw を使わないので要素にならない。
 * それに加えて、リンク/画像の URL は安全なスキームか相対参照だけを通し、`javascript:` などは空にする。
 */
export function safeUrlTransform(url: string): string {
  const trimmed = url.trim();
  return SAFE_URL.test(trimmed) || !/^[a-z][a-z0-9+.-]*:/i.test(trimmed) ? trimmed : "";
}

const MARKDOWN_COMPONENTS: Components = {
  h1: (p) => <h1 className="mt-3 mb-1 text-lg font-bold first:mt-0" {...strip(p)} />,
  h2: (p) => <h2 className="mt-3 mb-1 text-base font-bold first:mt-0" {...strip(p)} />,
  h3: (p) => <h3 className="mt-3 mb-1 text-sm font-bold first:mt-0" {...strip(p)} />,
  h4: (p) => <h4 className="mt-2 mb-1 text-sm font-semibold first:mt-0" {...strip(p)} />,
  h5: (p) => <h5 className="mt-2 mb-1 text-sm font-semibold first:mt-0" {...strip(p)} />,
  h6: (p) => <h6 className="mt-2 mb-1 text-sm font-semibold first:mt-0" {...strip(p)} />,
  p: (p) => <p className="my-2 first:mt-0 last:mb-0 whitespace-pre-wrap" {...strip(p)} />,
  ul: (p) => <ul className="my-2 list-disc pl-5" {...strip(p)} />,
  ol: (p) => <ol className="my-2 list-decimal pl-5" {...strip(p)} />,
  li: (p) => <li className="my-0.5" {...strip(p)} />,
  blockquote: (p) => (
    <blockquote
      className="my-2 border-l-4 border-neutral-400 dark:border-neutral-500 pl-3 text-neutral-700 dark:text-neutral-200"
      {...strip(p)}
    />
  ),
  hr: () => <hr className="my-3 border-neutral-400 dark:border-neutral-500" />,
  a: (p) => (
    <a
      className="text-blue-700 dark:text-blue-300 underline break-all"
      {...strip(p)}
      target="_blank"
      rel="noopener noreferrer"
    />
  ),
  pre: (p) => (
    <pre
      className="my-2 max-w-full overflow-x-auto rounded bg-neutral-800 dark:bg-neutral-950 p-3 text-xs text-neutral-50"
      {...strip(p)}
    />
  ),
  code: (p) => (
    <code className="rounded bg-neutral-300 dark:bg-neutral-600 px-1 py-0.5 font-mono text-xs [pre_&]:bg-transparent [pre_&]:p-0 [pre_&]:dark:bg-transparent" {...strip(p)} />
  ),
  table: (p) => (
    <div className="my-2 max-w-full overflow-x-auto">
      <table className="min-w-full border-collapse text-xs" {...strip(p)} />
    </div>
  ),
  th: (p) => (
    <th
      className="border border-neutral-400 dark:border-neutral-500 bg-neutral-300 dark:bg-neutral-600 px-2 py-1 text-left font-semibold text-neutral-900 dark:text-neutral-50"
      {...strip(p)}
    />
  ),
  td: (p) => (
    <td
      className="border border-neutral-400 dark:border-neutral-500 px-2 py-1 text-neutral-900 dark:text-neutral-50"
      {...strip(p)}
    />
  ),
};

/** react-markdown が渡す `node` を DOM へ流さないための除去。 */
function strip<T extends { node?: unknown }>(props: T): Omit<T, "node"> {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const { node, ...rest } = props;
  return rest;
}

export function ArticlePlanChat({
  history,
  onSend,
  isLoading,
  error,
  issueNumber,
  issueTitle,
}: {
  history: PlanChatMessage[];
  onSend: (message: string) => Promise<void>;
  isLoading: boolean;
  error?: string;
  issueNumber?: number | null;
  issueTitle?: string | null;
}) {
  const [input, setInput] = useState("");

  const handleSendMessage = async () => {
    const message = input.trim();
    if (!message || isLoading) return;
    setInput("");
    await onSend(message);
  };

  return (
    <div className="rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <div className="mb-4 flex items-center justify-between gap-2">
        <h2 className="font-medium">AI との壁打ち</h2>
        {issueNumber && (
          <span className="truncate rounded bg-blue-100 px-2 py-1 text-xs font-medium text-blue-700">
            Issue #{issueNumber}
            {issueTitle ? `: ${issueTitle}` : ""}
          </span>
        )}
      </div>

      <div className="mb-4 max-h-96 space-y-3 overflow-y-auto rounded-lg bg-neutral-50 dark:bg-neutral-800 p-4">
        {history.length === 0 ? (
          <p className="text-sm text-neutral-500 dark:text-neutral-400">チャットを始めましょう。記事のテーマや企画を入力してください。</p>
        ) : (
          history.map((msg, idx) => (
            <div
              key={idx}
              className={`rounded px-3 py-2 text-sm ${
                msg.role === "user"
                  ? "bg-blue-100 text-blue-900"
                  : "bg-neutral-200 dark:bg-neutral-700 text-neutral-900 dark:text-neutral-50"
              }`}
            >
              <strong>{msg.role === "user" ? "あなた" : "AI"}:</strong>{" "}
              {msg.role === "user" ? (
                <span className="whitespace-pre-wrap break-words">{msg.content}</span>
              ) : (
                <div className="min-w-0 break-words">
                  <ReactMarkdown remarkPlugins={[remarkGfm]} urlTransform={safeUrlTransform} components={MARKDOWN_COMPONENTS}>
                    {msg.content}
                  </ReactMarkdown>
                </div>
              )}
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
          className="flex-1 rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm disabled:bg-neutral-100 dark:disabled:bg-neutral-800"
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
