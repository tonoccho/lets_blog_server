"use client";

import { useTransition } from "react";
import { clearBulkOperationLogsAction } from "./actions";

export function ClearLogsButton({ projectId }: { projectId: number }) {
  const [isPending, startTransition] = useTransition();

  function handleClick() {
    if (!window.confirm("作業ログをすべて削除します。よろしいですか?(この操作は取り消せません)")) {
      return;
    }
    startTransition(() => {
      clearBulkOperationLogsAction(projectId);
    });
  }

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={isPending}
      className="text-sm text-red-600 hover:underline disabled:text-neutral-400"
    >
      {isPending ? "削除中…" : "ログをクリア"}
    </button>
  );
}
