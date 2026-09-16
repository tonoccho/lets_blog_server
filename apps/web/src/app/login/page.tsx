"use client";

import { useEffect, useState } from "react";
import { signIn } from "next-auth/react";
import { startNoJsLoginAction } from "./actions";

// 自動リダイレクトがこの時間内に成立しなければ、手動で進むリンクを表示する(issue #1052)。
// JSが動いていてもリダイレクト自体が失敗した場合(#1017のERR_ABORTED等)の救済も兼ねる。
const AUTO_REDIRECT_TIMEOUT_MS = 3000;

/**
 * ログイン画面。issue #564でCredentialsプロバイダ(自前フォーム)を廃止し、Keycloakへ
 * Authorization Code + PKCEでリダイレクトする方式へ移行した。このページ自体はUIを持たず、
 * マウント時にsignIn("keycloak")を呼んでKeycloakのホスト型ログイン画面へ即座に遷移させる
 * (パスワード再設定もKeycloak側のホスト型UIが担うため、ここには「パスワードをお忘れの方」
 * リンクも不要。自己登録はKeycloakのregistrationAllowed=falseで無効化されているため
 * 「新規登録」リンクも同様に不要)。
 *
 * pages.signIn(auth.ts)は引き続き"/login"を指す必要がある(NextAuthが未ログイン時の
 * 遷移先として使う)ため、このページ自体は消せない。
 *
 * issue #1052: クライアントJSが実行されない環境(JS無効設定、拡張機能によるブロック、
 * ハイドレーション中の例外)では上のuseEffectが動かず、この画面から永久に進めなくなって
 * いた。<noscript>にJS不要のフォーム(actions.tsのServer Action、Keycloakへの実POSTを
 * サーバー側で代行する)を常に置くことで、JS無効時にも利用者が自力でログインを開始できる
 * ようにする。JS有効時は、自動リダイレクトが一定時間で成立しなかった場合の救済として同じ
 * フォームを表示する。
 */
export default function LoginPage() {
  const [showManualFallback, setShowManualFallback] = useState(false);

  useEffect(() => {
    void signIn("keycloak", { callbackUrl: "/" });
    const timer = setTimeout(() => setShowManualFallback(true), AUTO_REDIRECT_TIMEOUT_MS);
    return () => clearTimeout(timer);
  }, []);

  return (
    <div className="mx-auto max-w-sm text-center text-sm text-neutral-600 dark:text-neutral-400">
      <p>ログイン画面へリダイレクトしています…</p>

      {showManualFallback && <ManualLoginFallback />}

      <noscript>
        <ManualLoginFallback />
      </noscript>
    </div>
  );
}

function ManualLoginFallback() {
  return (
    <div className="mt-4 space-y-2">
      <p>
        自動的に進まない場合は、JavaScriptが無効になっている可能性があります。
        JavaScriptを有効にするか、下のボタンからログインを開始してください。
      </p>
      <form method="post" action={startNoJsLoginAction}>
        <button
          type="submit"
          data-testid="nojs-login-submit"
          className="rounded bg-neutral-900 px-4 py-2 text-sm text-white"
        >
          ログインを開始する
        </button>
      </form>
    </div>
  );
}
