"use client";

import { useState } from "react";
import { setupAction } from "./actions";

/**
 * issue #564でCredentialsプロバイダを廃止したため、このフォームが作成する管理者アカウント
 * (legacy-apiの/api/auth/setupが直接ローカルDBへ作成するのみで、Keycloak側にはアカウントを
 * 作らない)は、作成しても自動ログインできない(KeycloakにログインできるアカウントではないためsignIn("keycloak")の対象にならない)。
 * この画面自体は元々「ユーザーが1人も居ない場合のみ」到達する初回セットアップ専用の画面であり、
 * 本Issueのスコープ(ログイン/サインアップ/パスワードリセット/2FA画面)には含まれないため、
 * 応急的に自動ログイン部分だけを外して「作成後はログイン画面へ」の案内に変更する
 * (Keycloak連携した初回セットアップ動線の整備は別Issueで扱う。詳細はPR説明を参照)。
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
          ログインするには別途Keycloakへのアカウント登録が必要です。管理者にお問い合わせください。
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
