"use client";

import { SessionProvider as NextAuthSessionProvider } from "next-auth/react";
import type { ReactNode } from "react";

// KeycloakのアクセストークンはHttpOnly cookie(NextAuthのJWT)の中にのみ保持されており、
// apiClient.tsのcurrentAccessToken()はnext-auth/jwtのgetToken()でCookieを生デコードするだけで
// jwtコールバック(リフレッシュ処理)を再実行しない。ブラウザが定期的に/api/auth/sessionへ
// アクセスするとNextAuthのgetServerSession相当の処理が走りjwtコールバックが再評価されるため、
// refetchIntervalを設定してこれを定期実行させ、Cookie内のアクセストークンをアクセストークンの
// 寿命(300秒)より十分短い間隔で更新し続ける。240秒であれば、直近の更新後に約60秒の余裕を
// 持って次の更新が走るため、失効間際の生アクセストークンをapiFetch()が読んでしまう窓を狭められる。
const ACCESS_TOKEN_REFETCH_INTERVAL_SECONDS = 240;

export function SessionProvider({ children }: { children: ReactNode }) {
  return (
    <NextAuthSessionProvider refetchInterval={ACCESS_TOKEN_REFETCH_INTERVAL_SECONDS}>
      {children}
    </NextAuthSessionProvider>
  );
}
