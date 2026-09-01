"use client";

import { useTransition } from "react";
import { deleteProjectAction } from "../actions";

export function DeleteProjectButton({ id }: { id: number }) {
  const [isPending, startTransition] = useTransition();

  function handleClick() {
    if (!window.confirm("このプロジェクトを削除しますか?(紐付いているサイトは削除されません)")) {
      return;
    }
    startTransition(() => {
      deleteProjectAction(id);
    });
  }

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={isPending}
      className="text-sm text-red-600 hover:underline disabled:text-neutral-400"
    >
      {isPending ? "削除中…" : "プロジェクトを削除"}
    </button>
  );
}
