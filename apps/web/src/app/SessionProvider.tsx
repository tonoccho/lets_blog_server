"use client";

import { SessionProvider as NextAuthSessionProvider } from "next-auth/react";
import type { Session } from "next-auth";
import type { ReactNode } from "react";
import { ACCESS_TOKEN_REFETCH_INTERVAL_SECONDS } from "@/lib/tokenRefreshPolicy";

// KeycloakのアクセストークンはHttpOnly cookie(NextAuthのJWT)の中にのみ保持されており、
// apiClient.tsのcurrentAccessToken()はnext-auth/jwtのgetToken()でCookieを生デコードするだけで
// jwtコールバック(リフレッシュ処理)を再実行しない。ブラウザが定期的に/api/auth/sessionへ
// アクセスするとNextAuthのgetServerSession相当の処理が走りjwtコールバックが再評価されるため、
// refetchIntervalを設定してこれを定期実行させる。
//
// 訂正(issue #1053): 以前ここには「240秒であれば直近の更新後に約60秒の余裕を持って次の
// 更新が走る」と書いてあったが、誤りだった。jwtコールバック(auth.ts)は当時「既に失効して
// いなければ更新しない」(猶予ゼロ)という条件を持っており、240秒の再取得ではトークンの
// 寿命(300秒)に達するまで一切更新されない。結果、t=300〜480秒の約180秒間、失効済みの
// アクセストークンがCookieに残り続けていた。「再取得間隔+猶予 が余裕を持ってトークン寿命を
// 上回ること」が不変条件であり、この値と猶予(ACCESS_TOKEN_SKEW_SECONDS)は
// `apps/web/src/lib/tokenRefreshPolicy.ts` に集約し、その関係を
// `apps/web/src/lib/__tests__/tokenRefreshPolicy.test.ts` がテストで固定している
// (コメントだけが整合を主張していた状態を繰り返さないため)。

/**
 * @param session サーバー側で解決済みのセッション(issue #778)。これを渡さないと
 *   `useSession()` はマウント後に `/api/auth/session` を取得し終えるまで
 *   `status === "loading"` / `data === undefined` を返す。その未解決期間に
 *   クライアントコンポーネントが `if (!session?.user) return;` のような分岐を持つと、
 *   利用者の操作が無反応のまま捨てられる(#778 で実際に起きた)。
 *   初期値を渡せば、通常のページ遷移ではこの窓自体が無くなる。
 *
 *   事実として記録しておく(issue #1053): **この初期値を渡していることの副作用で、
 *   ページの再読込ではセッションは復旧しない。** `hasInitialSession === true` になるため、
 *   next-authは `__NEXTAUTH._session` を初期化済み扱いにし、マウント時の
 *   `__NEXTAUTH._getSession()` は `event` 引数を持たないため早期returnし、
 *   `/api/auth/session` を叩かない(`node_modules/next-auth/react/index.js` の
 *   `hasInitialSession` 分岐 / `_getSession` の `if (!event || ...) return;` 参照)。
 *   Cookieを実際に更新できる経路は、この `refetchInterval` によるポーリングと、
 *   タブが非表示→表示に戻ったときの `visibilitychange` の2つだけである。
 */
export function SessionProvider({
  children,
  session,
}: {
  children: ReactNode;
  session: Session | null;
}) {
  return (
    <NextAuthSessionProvider session={session} refetchInterval={ACCESS_TOKEN_REFETCH_INTERVAL_SECONDS}>
      {children}
    </NextAuthSessionProvider>
  );
}
