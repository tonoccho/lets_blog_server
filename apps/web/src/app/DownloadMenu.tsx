"use client";

import { useEffect, useRef, useState } from "react";
import { ChevronDown, Download, Loader2 } from "lucide-react";
import { useI18n } from "./I18nProvider";

interface DownloadItem {
  id: string;
  url: string;
  defaultFilename: string;
  labelKey: string;
  pendingKey: string;
}

/** メニューに載せる配布物。項目を足すだけで、ダウンロード処理は共通で使われる。 */
const DOWNLOAD_ITEMS: DownloadItem[] = [
  {
    id: "vscode-extension",
    url: "/downloads/vscode-extension",
    defaultFilename: "letsblog-vscode.vsix",
    labelKey: "downloadVscodeExtension",
    pendingKey: "downloadVscodeExtensionPending",
  },
];

/** 狭い画面ではビューポート内に収め、広い画面ではトリガの右端に揃える。 */
const PANEL_POSITION =
  "z-50 mt-1 max-sm:fixed max-sm:left-4 max-sm:right-4 sm:absolute sm:right-0 sm:top-full sm:w-72";

export function DownloadMenu() {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const [pendingId, setPendingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function onMouseDown(e: MouseEvent) {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false);
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("mousedown", onMouseDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onMouseDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  async function download(item: DownloadItem) {
    setPendingId(item.id);
    setError(null);
    try {
      const res = await fetch(item.url);
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
      const filename = match ? match[1] : item.defaultFilename;

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
      setPendingId(null);
    }
  }

  const menuLabel = t("header", "downloadMenu");

  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={menuLabel}
        className="flex items-center gap-1 rounded-md px-2 py-1 text-sm hover:bg-neutral-100 dark:hover:bg-neutral-800 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
      >
        {pendingId ? (
          <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
        ) : (
          <Download className="h-4 w-4" aria-hidden="true" />
        )}
        <span>{menuLabel}</span>
        <ChevronDown className="h-4 w-4" aria-hidden="true" />
      </button>
      {open && (
        <div
          role="menu"
          className={`${PANEL_POSITION} rounded-md border border-neutral-200 bg-white p-1 text-sm shadow-lg dark:border-neutral-800 dark:bg-neutral-900`}
        >
          {DOWNLOAD_ITEMS.map((item) => {
            const pending = pendingId === item.id;
            return (
              <button
                key={item.id}
                type="button"
                role="menuitem"
                disabled={pendingId !== null}
                onClick={() => download(item)}
                className="block w-full rounded px-2 py-2 text-left hover:bg-neutral-100 dark:hover:bg-neutral-800 disabled:opacity-50"
              >
                {t("header", pending ? item.pendingKey : item.labelKey)}
              </button>
            );
          })}
          {error && (
            <div role="alert" className="p-2 text-xs text-red-600 dark:text-red-400">
              {error}
            </div>
          )}
        </div>
      )}
      {error && !open && (
        <div
          role="alert"
          className={`${PANEL_POSITION} rounded-md border border-red-200 bg-white p-2 text-xs text-red-600 shadow-lg dark:border-red-900 dark:bg-neutral-900 dark:text-red-400`}
        >
          {error}
        </div>
      )}
    </div>
  );
}
