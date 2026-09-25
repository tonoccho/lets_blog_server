"use client";

import { useEffect, useRef, useState } from "react";
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
 *
 * issue #1393: signInの発行はマウントにつき1回に限る。React StrictModeはmount時の
 * effectを2回実行する(Next.js App Routerのdevは`reactStrictMode`が既定true —
 * node_modules/next/dist/docs/01-app/03-api-reference/05-config/01-next-config-js/
 * reactStrictMode.md)。ガードが無いと`signIn()`が2回呼ばれ、Keycloakの認可
 * エンドポイントへのトップレベル遷移が2本競合する。実害は3つあり、いずれも
 * リリース検証 run 8(`20260924T020450Z-869889`)で実測している:
 *
 *   1. 2本目が`next-auth.state`クッキーを上書きするため、先行する
 *      `/api/auth/callback/keycloak`が必ず弾かれる。`[next-auth][error]
 *      [OAUTH_CALLBACK_ERROR] state mismatch`が44件出ており、そのたびに
 *      `/api/auth/error` → `/login?…&error=OAuthCallback`へ遷移していた。
 *   2. 競合した片方が499(client closed request)で中断される。
 *
 * アクセスログ上の比は`GET /login` 291件 : `POST /api/auth/signin/keycloak` 538件、
 * 修正後は209件 : 188件(≒1:1)で`state mismatch`は0件になった。
 *
 * <p><b>`chrome-error://chromewebdata/`との因果は推論であって実測ではない。</b>
 * リリース検証を止めていた失敗(`logging/async-path.feature`のログインが
 * `chrome-error://chromewebdata/`で30秒タイムアウトする)は、当初この遷移競合が
 * 原因だと考えた。しかし競合する2本の遷移がその失敗の瞬間に存在したことを示すログは
 * 取れておらず、修正後も同じ失敗が1度再現している(その1度は直前のat-main実行が
 * 中断した異常な状態から始まっていた)。上の1と2は実例まで追跡できているが、
 * 3つ目を断定してはならない。#1391で「一過性の接続断」という見立てを実測で外している。
 *
 * <p>それでもこのガードが正しいことは、上の1と2だけで十分に正当化される —
 * `state mismatch`を44件から0件にしたのは実測値である。
 *
 * <p><b>ガードするのは`signIn`の発行だけである。</b>useEffectの本体全体をrefで
 * 早期returnすると、StrictModeの2回目で`setTimeout`が張られず、上の#1052の
 * 手動フォールバックが永久に表示されなくなる(`__tests__/page.test.tsx`の
 * 「signInをガードしても手動フォールバック(#1052)は3秒後に出る」がこれを固定している)。
 *
 * <p>`reactStrictMode: false`で黙らせる選択は採らない。StrictModeは
 * 「二重実行に耐えない副作用」を検出するための仕組みで、ここで検出されたのは
 * 迂回してよい誤検知ではなく本物の欠陥である。
 */
export default function LoginPage() {
  const [showManualFallback, setShowManualFallback] = useState(false);
  // issue #1393: StrictModeによるeffectの二重実行でsignInを2回発行しないための番兵。
  const signInStartedRef = useRef(false);

  useEffect(() => {
    if (!signInStartedRef.current) {
      signInStartedRef.current = true;
      void signIn("keycloak", { callbackUrl: "/" });
    }
    // タイマーはガードの外に置く。中に入れると2回目のマウントでフォールバックが出ない(#1052)。
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
