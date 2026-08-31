"use client";

import { useActionState } from "react";
import Link from "next/link";
import { confirmPasswordResetAction, PasswordResetState } from "../actions";

const initialState: PasswordResetState = {};

export function PasswordResetForm({ token }: { token: string }) {
  const [state, formAction, pending] = useActionState(confirmPasswordResetAction, initialState);

  return (
    <form action={formAction} className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <input type="hidden" name="token" value={token} />
      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600 dark:text-neutral-400">新しいパスワード</span>
        <input
          name="newPassword"
          type="password"
          required
          minLength={8}
          className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
        />
      </label>
      {!token && <p className="text-sm text-red-600">無効なリンクです。再設定メールのリンクからアクセスしてください。</p>}
      {state.error && <p className="text-sm text-red-600">{state.error}</p>}
      {state.success && (
        <p className="text-sm text-green-600">
          パスワードをリセットしました。
          <Link href="/login" className="ml-1 text-blue-600 hover:underline">
            ログイン画面へ
          </Link>
        </p>
      )}
      <button
        type="submit"
        disabled={pending || !token}
        className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pending ? "設定中…" : "パスワードを再設定"}
      </button>
    </form>
  );
}
