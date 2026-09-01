"use client";

import { useEffect } from "react";
import { signIn } from "next-auth/react";

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
 */
export default function LoginPage() {
  useEffect(() => {
    void signIn("keycloak", { callbackUrl: "/" });
  }, []);

  return (
    <div className="mx-auto max-w-sm text-center text-sm text-neutral-600 dark:text-neutral-400">
      ログイン画面へリダイレクトしています…
    </div>
  );
}
