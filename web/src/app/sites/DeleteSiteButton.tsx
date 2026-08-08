"use client";

import { useTransition } from "react";
import { deleteSiteAction } from "./actions";

export function DeleteSiteButton({ id, managedWordpress }: { id: number; managedWordpress: boolean }) {
  const [isPending, startTransition] = useTransition();

  function handleClick() {
    const message = managedWordpress
      ? "このサイトを削除しますか?自動構築されたWordPressインスタンスと専用データベースも削除されます。"
      : "このサイトを削除しますか?(登録情報のみ削除され、外部サイト自体には影響しません)";
    if (!window.confirm(message)) {
      return;
    }
    startTransition(() => {
      deleteSiteAction(id);
    });
  }

  return (
    <button
      type="button"
      onClick={handleClick}
      disabled={isPending}
      className="text-sm text-red-700 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500 rounded px-2 py-1 disabled:text-neutral-500"
    >
      {isPending ? "削除中…" : "削除"}
    </button>
  );
}
