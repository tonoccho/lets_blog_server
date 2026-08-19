"use client";

import { useState, useTransition } from "react";
import type { StaticContent, StaticContentType } from "@/lib/apiClient";
import { generateStaticContentAction } from "../../actions";

const LABELS: Record<StaticContentType, string> = {
  PRIVACY_POLICY: "プライバシーポリシー",
  OPERATOR_INFO: "運営者情報",
  TERMS_OF_SERVICE: "利用規約",
};

const CONTENT_TYPES: StaticContentType[] = ["PRIVACY_POLICY", "OPERATOR_INFO", "TERMS_OF_SERVICE"];

export function StaticContentPanel({
  siteId,
  initialContents,
}: {
  siteId: number;
  initialContents: StaticContent[];
}) {
  const [contents, setContents] = useState<Record<StaticContentType, StaticContent | null>>({
    PRIVACY_POLICY: initialContents.find((c) => c.contentType === "PRIVACY_POLICY") ?? null,
    OPERATOR_INFO: initialContents.find((c) => c.contentType === "OPERATOR_INFO") ?? null,
    TERMS_OF_SERVICE: initialContents.find((c) => c.contentType === "TERMS_OF_SERVICE") ?? null,
  });

  return (
    <section className="max-w-xl space-y-4 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <h2 className="text-sm font-medium text-neutral-600 dark:text-neutral-400">静的コンテンツ</h2>
      {CONTENT_TYPES.map((contentType) => (
        <StaticContentItem
          key={contentType}
          siteId={siteId}
          contentType={contentType}
          content={contents[contentType]}
          onGenerated={(content) => setContents((prev) => ({ ...prev, [contentType]: content }))}
        />
      ))}
    </section>
  );
}

function StaticContentItem({
  siteId,
  contentType,
  content,
  onGenerated,
}: {
  siteId: number;
  contentType: StaticContentType;
  content: StaticContent | null;
  onGenerated: (content: StaticContent) => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [isPending, startTransition] = useTransition();

  function handleGenerate() {
    setError(null);
    startTransition(async () => {
      const result = await generateStaticContentAction(siteId, contentType);
      if (result.error) {
        setError(result.error);
        return;
      }
      if (result.content) {
        onGenerated(result.content);
      }
    });
  }

  async function handleCopy() {
    if (!content) return;
    await navigator.clipboard.writeText(content.body);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  return (
    <div className="space-y-2 border-t border-neutral-200 dark:border-neutral-800 pt-3 first:border-t-0 first:pt-0">
      <div className="flex items-center justify-between gap-2">
        <span className="text-sm font-medium">{LABELS[contentType]}</span>
        <button
          type="button"
          onClick={handleGenerate}
          disabled={isPending}
          className="rounded bg-neutral-100 dark:bg-neutral-800 px-3 py-1.5 text-sm text-neutral-700 dark:text-neutral-300 disabled:opacity-50"
        >
          {isPending ? "生成中…" : content ? "再生成" : "生成"}
        </button>
      </div>
      {error && <p className="text-sm text-red-600">{error}</p>}
      {content && (
        <div className="space-y-2">
          <button
            type="button"
            onClick={handleCopy}
            className="inline-block rounded bg-neutral-900 px-3 py-1.5 text-sm text-white hover:bg-neutral-800"
          >
            {copied ? "コピーしました" : "コピー"}
          </button>
          <pre className="max-h-64 overflow-auto whitespace-pre-wrap rounded bg-neutral-50 dark:bg-neutral-800 p-3 font-mono text-xs text-neutral-600 dark:text-neutral-400">
            {content.body}
          </pre>
        </div>
      )}
    </div>
  );
}
