"use client";

import { useEffect, useState, useTransition } from "react";
import type { LetsblogPluginStatus } from "@/lib/apiClient";
import { getLetsblogPluginStatusAction, installLetsblogPluginAction } from "../../actions";

function describe(status: LetsblogPluginStatus): string {
  if (status.state === "INSTALLED") {
    return `導入済み(v${status.version ?? "不明"})`;
  }
  return status.state === "NEEDS_UPDATE" ? "要更新" : "未導入";
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/**
 * サイトの letsblog プラグインの導入状態(導入済み(バージョン) / 未導入 / 要更新)を表示し、
 * 導入済み以外では再導入できる(issue #1557)。導入済み以外のサイトへの投稿とプレビューは拒否される。
 */
export function LetsblogPluginPanel({ siteId }: { siteId: number }) {
  const [status, setStatus] = useState<LetsblogPluginStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isPending, startTransition] = useTransition();

  useEffect(() => {
    let cancelled = false;
    getLetsblogPluginStatusAction(siteId).then(
      (result) => {
        if (!cancelled) setStatus(result);
      },
      (err) => {
        if (!cancelled) setError(`導入状態を取得できませんでした: ${errorMessage(err)}`);
      }
    );
    return () => {
      cancelled = true;
    };
  }, [siteId]);

  function handleReinstall() {
    setError(null);
    startTransition(async () => {
      try {
        setStatus(await installLetsblogPluginAction(siteId));
      } catch (err) {
        setError(`再導入に失敗しました: ${errorMessage(err)}`);
      }
    });
  }

  return (
    <section className="space-y-2 rounded border border-neutral-200 dark:border-neutral-800 p-3">
      <h2 className="text-sm font-semibold">Lets Blog プラグイン</h2>
      <p data-testid="letsblog-plugin-status" className="text-sm">
        {status ? describe(status) : error ? null : "プラグインの状態を確認中…"}
      </p>
      {status && status.state !== "INSTALLED" && (
        <div className="space-y-1">
          <p className="text-sm text-amber-700">
            プラグインが使えないため、このサイトへの投稿とプレビューはできません。再導入してください。
          </p>
          <button
            type="button"
            onClick={handleReinstall}
            disabled={isPending}
            className="w-fit rounded bg-neutral-100 dark:bg-neutral-800 px-3 py-1.5 text-sm text-neutral-700 dark:text-neutral-300 disabled:opacity-50"
          >
            {isPending ? "再導入中…" : "プラグインを再導入"}
          </button>
        </div>
      )}
      {error && <p className="text-sm text-red-600">{error}</p>}
    </section>
  );
}
