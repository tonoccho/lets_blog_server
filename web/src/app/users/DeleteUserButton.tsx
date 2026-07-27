"use client";

import { useTransition } from "react";
import { deleteUserAction } from "./actions";

export function DeleteUserButton({ id }: { id: number }) {
  const [isPending, startTransition] = useTransition();

  function handleClick() {
    if (!window.confirm("このユーザーを削除しますか?")) {
      return;
    }
    startTransition(() => {
      deleteUserAction(id);
    });
  }

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={isPending}
      className="text-sm text-red-600 hover:underline disabled:opacity-50"
    >
      {isPending ? "削除中…" : "削除"}
    </button>
  );
}
