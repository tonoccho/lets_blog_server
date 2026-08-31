"use client";

import { SessionProvider as NextAuthSessionProvider } from "next-auth/react";
import type { Session } from "next-auth";
import type { ReactNode } from "react";

// KeycloakのアクセストークンはHttpOnly cookie(NextAuthのJWT)の中にのみ保持されており、
// apiClient.tsのcurrentAccessToken()はnext-auth/jwtのgetToken()でCookieを生デコードするだけで
// jwtコールバック(リフレッシュ処理)を再実行しない。ブラウザが定期的に/api/auth/sessionへ
// アクセスするとNextAuthのgetServerSession相当の処理が走りjwtコールバックが再評価されるため、
// refetchIntervalを設定してこれを定期実行させ、Cookie内のアクセストークンをアクセストークンの
// 寿命(300秒)より十分短い間隔で更新し続ける。240秒であれば、直近の更新後に約60秒の余裕を
// 持って次の更新が走るため、失効間際の生アクセストークンをapiFetch()が読んでしまう窓を狭められる。
const ACCESS_TOKEN_REFETCH_INTERVAL_SECONDS = 240;

/**
 * @param session サーバー側で解決済みのセッション(issue #778)。これを渡さないと
 *   `useSession()` はマウント後に `/api/auth/session` を取得し終えるまで
 *   `status === "loading"` / `data === undefined` を返す。その未解決期間に
 *   クライアントコンポーネントが `if (!session?.user) return;` のような分岐を持つと、
 *   利用者の操作が無反応のまま捨てられる(#778 で実際に起きた)。
 *   初期値を渡せば、通常のページ遷移ではこの窓自体が無くなる。
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
