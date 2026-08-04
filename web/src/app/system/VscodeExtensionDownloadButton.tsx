"use client";

import { useState } from "react";

export function VscodeExtensionDownloadButton() {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleClick() {
    setPending(true);
    setError(null);
    try {
      const res = await fetch("/downloads/vscode-extension");
      if (res.redirected) {
        throw new Error("セッションが切れている可能性があります。ページを再読み込みしてログインし直してください。");
      }
      if (!res.ok) {
        const contentType = res.headers.get("content-type") ?? "";
        const message = contentType.includes("application/json")
          ? ((await res.json()) as { error?: string }).error
          : await res.text();
        throw new Error(message || `ダウンロードに失敗しました (HTTP ${res.status})`);
      }

      const disposition = res.headers.get("content-disposition") ?? "";
      const match = disposition.match(/filename="([^"]+)"/);
      const filename = match ? match[1] : "letsblog-vscode.vsix";

      const blob = await res.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setPending(false);
    }
  }

  return (
    <div>
      <button
        type="button"
        onClick={handleClick}
        disabled={pending}
        className="inline-block rounded bg-neutral-900 px-4 py-2 text-sm text-white hover:bg-neutral-700 disabled:bg-neutral-400"
      >
        {pending ? "ビルド中…" : "拡張機能をダウンロード (.vsix)"}
      </button>
      {error && <p className="mt-2 text-sm text-red-600">エラー: {error}</p>}
    </div>
  );
}
