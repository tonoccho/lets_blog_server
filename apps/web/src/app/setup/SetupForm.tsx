"use client";

import { useActionState } from "react";
import { setupAction, type SetupState } from "./actions";

/**
 * issue #564でCredentialsプロバイダを廃止したため、このフォームは自動ログイン(signIn("keycloak")の
 * 即時呼び出し)を行わず、「作成後はログイン画面へ」の案内にとどめる。issue #681でlegacy-apiの
 * /api/auth/setupをKeycloak Admin REST API経由の実装に置き換えたため、ここで作成される管理者
 * アカウントは実際にKeycloak側にも作成され、指定したメールアドレス・パスワードでログイン画面から
 * すぐにログイン可能になる。
 *
 * issue #1051: 以前は<form onSubmit>のみでmethod/actionを持たず、hydration未完了やJS無効時に
 * ブラウザがネイティブのGETフォールバックを行い、email/passwordがクエリ文字列(ひいては
 * reverse-proxyのアクセスログ)へ漏れていた。setupActionは元々`(prevState, formData)`という
 * useActionState互換の署名だったため、`<form action={formAction}>`として直接渡す形へ変更した。
 * これによりJS無効時もブラウザはこのURLへネイティブPOSTを行い(Next.jsのServer Action
 * progressive enhancement)、GETフォールバックは発生しない。
 *
 * `initialState`はコンポーネント外のモジュール定数にしてある。useActionStateはactionが一度も
 * dispatchされていない間、渡した初期値をそのまま返す(参照が変わらない)ため、
 * `state !== initialState`で「まだ送信していない」と「送信済み(成功/失敗いずれか)」を
 * 区別できる。setupActionは成功時に`{}`、失敗時に`{error}`という新しいオブジェクトを返すため
 * この判定は安定している。
 */
const initialState: SetupState = {};

export function SetupForm() {
  const [state, formAction, pending] = useActionState(setupAction, initialState);
  const submitted = state !== initialState;
  const success = submitted && !state.error;

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
    <form action={formAction} className="space-y-3 rounded-lg border border-neutral-200 dark:border-neutral-800 bg-white dark:bg-neutral-900 p-5">
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
      {state.error && <p className="text-sm text-red-600">{state.error}</p>}
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
