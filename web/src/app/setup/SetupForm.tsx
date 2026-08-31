"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { signIn } from "next-auth/react";
import { setupAction } from "./actions";

export function SetupForm() {
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

    const setupResult = await setupAction({}, formData);
    if (setupResult.error) {
      setError(setupResult.error);
      setPending(false);
      return;
    }

    const signInResult = await signIn("credentials", { email, password, redirect: false });
    setPending(false);

    if (!signInResult || signInResult.error) {
      setError("管理者アカウントは作成されましたが、自動ログインに失敗しました。ログイン画面からお試しください。");
      return;
    }

    router.push("/");
    router.refresh();
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600 dark:text-neutral-400">メールアドレス</span>
        <input
          name="email"
          type="email"
          required
          className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
        />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        <span className="text-neutral-600 dark:text-neutral-400">パスワード(8文字以上)</span>
        <input
          name="password"
          type="password"
          required
          minLength={8}
          className="rounded border border-neutral-300 dark:border-neutral-700 px-3 py-2 text-sm"
        />
      </label>
      {error && <p className="text-sm text-red-600">{error}</p>}
      <button
        type="submit"
        disabled={pending}
        className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pending ? "作成中…" : "管理者アカウントを作成"}
      </button>
    </form>
  );
}
