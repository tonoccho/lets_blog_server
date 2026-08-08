"use client";

import { useState, useTransition } from "react";
import type { SiteConnectionCheckResult } from "@/lib/apiClient";
import { checkSiteConnectionAction } from "./actions";

export function CheckConnectionButton({ id }: { id: number }) {
  const [result, setResult] = useState<SiteConnectionCheckResult | null>(null);
  const [isPending, startTransition] = useTransition();

  function handleClick() {
    startTransition(async () => {
      try {
        const res = await checkSiteConnectionAction(id);
        setResult(res);
      } catch {
        setResult({ connectionCheckStatus: "FAILED", hasAdminCapability: null, failureReason: null, detail: null });
      }
    });
  }

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={handleClick}
          disabled={isPending}
          className="text-sm text-blue-700 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500 rounded px-2 py-1 disabled:text-neutral-500"
        >
          {isPending ? "確認中…" : "疎通確認"}
        </button>
        {result?.connectionCheckStatus === "SUCCESS" && <span className="text-xs text-green-700 font-medium">SUCCESS</span>}
        {result?.connectionCheckStatus === "FAILED" && <span className="text-xs text-red-700 font-medium">FAILED</span>}
      </div>
      {result?.connectionCheckStatus === "SUCCESS" && result.detail && (
        <span className="text-xs text-neutral-700">{result.detail}</span>
      )}
      {result?.connectionCheckStatus === "FAILED" && result.failureReason && (
        <span className="text-xs text-red-700">{result.failureReason}</span>
      )}
      {result?.hasAdminCapability === false && (
        <span className="text-xs text-amber-800 font-medium">
          ⚠ このサイトの認証情報には管理者権限(ユーザー作成)がありません
        </span>
      )}
    </div>
  );
}
