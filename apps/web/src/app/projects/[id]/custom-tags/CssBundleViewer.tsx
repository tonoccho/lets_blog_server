"use client";

import { useEffect, useState } from "react";

/** 統合CSSを表示し、クリップボードへコピーできるようにする(issueに基づきダウンロードから変更)。 */
export function CssBundleViewer({ projectId }: { projectId: number }) {
  const [css, setCss] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetch(`/projects/${projectId}/custom-tags/css-bundle`)
      .then(async (res) => {
        if (!res.ok) {
          const body = await res.json().catch(() => null);
          throw new Error(body?.error || `取得に失敗しました (${res.status})`);
        }
        return res.text();
      })
      .then((text) => {
        if (!cancelled) setCss(text);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : String(err));
      });
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  async function handleCopy() {
    if (!css) return;
    await navigator.clipboard.writeText(css);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  if (error) {
    return <p className="text-sm text-red-600 dark:text-red-400">{error}</p>;
  }

  if (css === null) {
    return <p className="text-sm text-neutral-600 dark:text-neutral-400">読み込み中...</p>;
  }

  return (
    <div className="space-y-3">
      <button
        type="button"
        onClick={handleCopy}
        className="inline-block rounded bg-neutral-900 px-4 py-2 text-sm text-white hover:bg-neutral-800"
      >
        {copied ? "コピーしました" : "CSSをコピー"}
      </button>
      <pre className="max-h-96 overflow-auto rounded bg-neutral-50 dark:bg-neutral-800 p-3 font-mono text-xs text-neutral-600 dark:text-neutral-400">
        {css}
      </pre>
    </div>
  );
}
