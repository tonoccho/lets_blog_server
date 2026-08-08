"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { signIn } from "next-auth/react";
import Link from "next/link";
import { signupAction } from "./actions";

export default function SignupPage() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    setError(null);

    const formData = new FormData(event.currentTarget);
    const email = String(formData.get("email") ?? "");
    const password = String(formData.get("password") ?? "");

    const signupResult = await signupAction({}, formData);
    if (signupResult.error) {
      setError(signupResult.error);
      setPending(false);
      return;
    }

    const signInResult = await signIn("credentials", { email, password, redirect: false });
    setPending(false);

    if (!signInResult || signInResult.error) {
      setError("登録は完了しましたが、自動ログインに失敗しました。ログイン画面からお試しください。");
      return;
    }

    router.push("/");
    router.refresh();
  }

  return (
    <div className="mx-auto max-w-sm">
      <h1 className="mb-6 text-xl font-semibold">新規登録</h1>
      <form onSubmit={handleSubmit} className="space-y-3 rounded-lg border border-neutral-200 bg-white p-5 dark:border-neutral-800 dark:bg-neutral-900">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600 dark:text-neutral-400">メールアドレス</span>
          <input
            name="email"
            type="email"
            required
            className="rounded border border-neutral-300 px-3 py-2 text-sm dark:border-neutral-700 dark:bg-neutral-800 dark:text-neutral-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">パスワード(8文字以上)</span>
          <input
            name="password"
            type="password"
            required
            minLength={8}
            className="rounded border border-neutral-300 px-3 py-2 text-sm dark:border-neutral-700 dark:bg-neutral-800 dark:text-neutral-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
          />
        </label>
        {error && <p className="text-sm text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={pending}
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600 dark:bg-neutral-50 dark:text-neutral-900 dark:disabled:bg-neutral-800 dark:disabled:text-neutral-400 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-500"
        >
          {pending ? "登録中…" : "登録"}
        </button>
        <p className="text-sm">
          <Link href="/login" className="text-blue-600 hover:underline">
            すでにアカウントをお持ちの方はこちら
          </Link>
        </p>
      </form>
    </div>
  );
}
