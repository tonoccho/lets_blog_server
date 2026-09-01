"use client";

import { useState } from "react";
import { Download, Loader2 } from "lucide-react";
import { useI18n } from "./I18nProvider";

export function VscodeExtensionDownloadIconButton() {
  const { t } = useI18n();
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

  const label = pending
    ? t("header", "downloadVscodeExtensionPending")
    : t("header", "downloadVscodeExtension");

  return (
    <div className="relative">
      <button
        type="button"
        onClick={handleClick}
        disabled={pending}
        aria-label={label}
        title={label}
        className="rounded-md p-2 hover:bg-neutral-100 dark:hover:bg-neutral-800 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500 disabled:opacity-50"
      >
        {pending ? (
          <Loader2 className="h-5 w-5 animate-spin" aria-hidden="true" />
        ) : (
          <Download className="h-5 w-5" aria-hidden="true" />
        )}
      </button>
      {error && (
        <div
          role="alert"
          className="absolute top-full right-0 z-50 mt-1 w-64 rounded-md border border-red-200 dark:border-red-900 bg-white dark:bg-neutral-900 p-2 text-xs text-red-600 dark:text-red-400 shadow-lg"
        >
          {error}
        </div>
      )}
    </div>
  );
}
