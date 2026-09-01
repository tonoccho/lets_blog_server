"use client";

import { useState } from "react";
import { setupAction } from "./actions";

/**
 * issue #564でCredentialsプロバイダを廃止したため、このフォームは自動ログイン(signIn("keycloak")の
 * 即時呼び出し)を行わず、「作成後はログイン画面へ」の案内にとどめる。issue #681でlegacy-apiの
 * /api/auth/setupをKeycloak Admin REST API経由の実装に置き換えたため、ここで作成される管理者
 * アカウントは実際にKeycloak側にも作成され、指定したメールアドレス・パスワードでログイン画面から
 * すぐにログイン可能になる。
 */
export function SetupForm() {
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const [pending, setPending] = useState(false);

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPending(true);
    setError(null);

    const formData = new FormData(event.currentTarget);
    const setupResult = await setupAction({}, formData);
    setPending(false);

    if (setupResult.error) {
      setError(setupResult.error);
      return;
    }

    setSuccess(true);
  }

  if (success) {
    return (
      <div className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5 text-sm">
        <p className="text-green-600">管理者アカウントを作成しました。</p>
        <p className="text-neutral-600 dark:text-neutral-400">
          入力したメールアドレスとパスワードでログイン画面からログインできます。
        </p>
      </div>
    );
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
