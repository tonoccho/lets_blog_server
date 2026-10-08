"use client";

import { useState, useTransition } from "react";
import { deleteUserAction } from "./actions";

/**
 * 削除に成功したら `onDeleted` を呼ぶ。行を除くのは呼び出し側(UserRow)のクライアント状態で、
 * サーバ再描画(revalidatePath)の到達を待たない(issue #1383、#1361と同じ方針)。
 * 失敗時は理由を表示し、ボタンを再び押せる状態に戻す。
 */
export function DeleteUserButton({ id, onDeleted }: { id: number; onDeleted?: () => void }) {
  const [isPending, startTransition] = useTransition();
  const [error, setError] = useState<string | null>(null);

  function handleClick() {
    if (!window.confirm("このユーザーを削除しますか?")) {
      return;
    }
    setError(null);
    startTransition(async () => {
      try {
        const result = await deleteUserAction(id);
        if (result.error) {
          setError(result.error);
        } else {
          onDeleted?.();
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : "削除に失敗しました。");
      }
    });
  }

  return (
    <div className="flex flex-col items-end gap-1">
      <button
        type="button"
        onClick={handleClick}
        disabled={isPending}
        className="text-sm text-red-600 hover:underline disabled:text-neutral-600 disabled:no-underline"
      >
        {isPending ? "削除中…" : "削除"}
      </button>
      {error && <p className="text-xs text-red-600">{error}</p>}
    </div>
  );
}
