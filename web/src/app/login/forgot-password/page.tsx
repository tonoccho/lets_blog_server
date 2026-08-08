"use client";

import { useActionState } from "react";
import Link from "next/link";
import { requestPasswordResetAction, ForgotPasswordState } from "../actions";

const initialState: ForgotPasswordState = {};

export default function ForgotPasswordPage() {
  const [state, formAction, pending] = useActionState(requestPasswordResetAction, initialState);

  return (
    <div className="mx-auto max-w-sm">
      <h1 className="mb-6 text-xl font-semibold">パスワードをお忘れの方</h1>
      <form action={formAction} className="space-y-3 rounded-lg border border-neutral-200 bg-white p-5 dark:border-neutral-800 dark:bg-neutral-900">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">メールアドレス</span>
          <input
            name="email"
            type="email"
            required
            className="rounded border border-neutral-300 px-3 py-2 text-sm dark:border-neutral-700 dark:bg-neutral-800 dark:text-neutral-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
          />
        </label>
        {state.error && <p className="text-sm text-red-600">{state.error}</p>}
        {state.success && (
          <p className="text-sm text-green-600">
            再設定用メールを送信しました。メールボックスをご確認ください。
          </p>
        )}
        <button
          type="submit"
          disabled={pending}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600 dark:bg-neutral-50 dark:text-neutral-900 dark:disabled:bg-neutral-800 dark:disabled:text-neutral-400 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
        >
          {pending ? "送信中…" : "再設定メールを送信"}
        </button>
        <p className="text-sm">
          <Link href="/login" className="text-blue-600 hover:underline">
            ログイン画面に戻る
          </Link>
        </p>
      </form>
    </div>
  );
}
