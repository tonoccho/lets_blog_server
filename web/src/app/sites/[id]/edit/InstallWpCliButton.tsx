"use client";

import { useState, useTransition } from "react";
import type { WpCliInstallResult } from "@/lib/apiClient";
import { installWpCliAction } from "../../actions";

export function InstallWpCliButton({ id }: { id: number }) {
  const [result, setResult] = useState<WpCliInstallResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isPending, startTransition] = useTransition();

  function handleClick() {
    setError(null);
    startTransition(async () => {
      try {
        const res = await installWpCliAction(id);
        setResult(res);
      } catch (err) {
        setError(err instanceof Error ? err.message : String(err));
      }
    });
  }

  return (
    <div className="flex flex-col gap-1">
      <button
        type="button"
        onClick={handleClick}
        disabled={isPending}
        className="w-fit rounded bg-neutral-100 dark:bg-neutral-800 px-3 py-1.5 text-sm text-neutral-700 dark:text-neutral-300 disabled:opacity-50"
      >
        {isPending ? "インストール中…" : "wp-cliをインストール"}
      </button>
      {result && <p className="text-sm text-green-600">{result.message}</p>}
      {error && <p className="text-sm text-red-600">{error}</p>}
    </div>
  );
}
