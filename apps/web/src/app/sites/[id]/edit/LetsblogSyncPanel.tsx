"use client";

import { useEffect, useState, useTransition } from "react";
import type { LetsblogSyncState } from "@/lib/apiClient";
import { getLetsblogSyncAction, resyncLetsblogAction } from "../../actions";

function describe(state: LetsblogSyncState | null): string {
  if (state === null) {
    return "未同期";
  }
  if (state.status === "SYNCED") {
    return `同期済み(${(state.hash ?? "").slice(0, 8)})`;
  }
  return state.status === "FAILED" ? "同期失敗" : "見送り";
}

function errorMessage(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/**
 * タグ定義・統合 CSS・プレフィックス・デザインの letsblog プラグインへの同期の状態(同期済み(ハッシュ) /
 * 同期失敗 / 見送り / 未同期)を表示し、再同期できる(issue #1558)。同期はアプリでの変更時に自動で行われ、
 * 届かなかったときはここから再同期で回復する。
 */
export function LetsblogSyncPanel({ siteId }: { siteId: number }) {
  const [state, setState] = useState<LetsblogSyncState | null | undefined>(undefined);
  const [error, setError] = useState<string | null>(null);
  const [isPending, startTransition] = useTransition();

  useEffect(() => {
    let cancelled = false;
    getLetsblogSyncAction(siteId).then(
      (result) => {
        if (!cancelled) setState(result);
      },
      (err) => {
        if (!cancelled) setError(`同期状態を取得できませんでした: ${errorMessage(err)}`);
      }
    );
    return () => {
      cancelled = true;
    };
  }, [siteId]);

  function handleResync() {
    setError(null);
    startTransition(async () => {
      try {
        setState(await resyncLetsblogAction(siteId));
      } catch (err) {
        setError(`再同期に失敗しました: ${errorMessage(err)}`);
      }
    });
  }

  return (
    <section className="space-y-2 rounded border border-neutral-200 dark:border-neutral-800 p-3">
      <h2 className="text-sm font-semibold">タグ・CSS の同期</h2>
      <p data-testid="letsblog-sync-status" className="text-sm">
        {state !== undefined ? describe(state) : error ? null : "同期状態を確認中…"}
      </p>
      {state && state.error && (
        <p data-testid="letsblog-sync-error" className="text-sm text-amber-700">
          {state.error}
        </p>
      )}
      <button
        type="button"
        onClick={handleResync}
        disabled={isPending}
        className="w-fit rounded bg-neutral-100 dark:bg-neutral-800 px-3 py-1.5 text-sm text-neutral-700 dark:text-neutral-300 disabled:opacity-50"
      >
        {isPending ? "同期中…" : "再同期"}
      </button>
      {error && <p className="text-sm text-red-600">{error}</p>}
    </section>
  );
}
