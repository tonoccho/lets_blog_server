"use client";

import { useState, useTransition } from "react";
import { checkSiteConnectionAction } from "./actions";

export function CheckConnectionButton({ id }: { id: number }) {
  const [result, setResult] = useState<"SUCCESS" | "FAILED" | null>(null);
  const [isPending, startTransition] = useTransition();

  function handleClick() {
    startTransition(async () => {
      try {
        const res = await checkSiteConnectionAction(id);
        setResult(res.connectionCheckStatus);
      } catch {
        setResult("FAILED");
      }
    });
  }

  return (
    <div className="flex items-center gap-2">
      <button
        type="button"
        onClick={handleClick}
        disabled={isPending}
        className="text-sm text-blue-600 hover:underline disabled:text-neutral-400"
      >
        {isPending ? "確認中…" : "疎通確認"}
      </button>
      {result === "SUCCESS" && <span className="text-xs text-green-600">SUCCESS</span>}
      {result === "FAILED" && <span className="text-xs text-red-600">FAILED</span>}
    </div>
  );
}
