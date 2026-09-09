import type { JWT } from "next-auth/jwt";

/**
 * アクセストークンの更新判定ロジック(issue #1053)。
 *
 * `apps/web/src/lib/auth.ts` は `server-only` をimportしているため、Jestから直接importできない
 * (issue #969)。jwtコールバックのうち「更新するかどうか」の判定と、実際のリフレッシュ実行を
 * 分離し、リフレッシュ実行側を注入できる形にすることで、`server-only` の無いこのモジュールを
 * 擬似時刻を渡すだけでJestから検証できるようにする。auth.tsはこのモジュールをそのまま使う
 * (#969着手時も同じ形を採ること。両者が別々の回避策を発明しないため)。
 */

/**
 * Keycloakのrealm設定(infra/keycloak/realm-export.json の `accessTokenLifespan`)と
 * 同じ値を保つこと。ずれていないかは
 * `apps/web/src/lib/__tests__/tokenRefreshPolicy.test.ts` が realm-export.json を直接読み、
 * この定数と突き合わせて検証する。
 */
export const ACCESS_TOKEN_LIFESPAN_SECONDS = 300;

/**
 * 失効前に更新へ入るための猶予(秒)。
 *
 * 元の不具合(issue #1053)は、jwtコールバックが「既に失効していなければ更新しない」
 * (猶予ゼロ)という条件を持っていたこと。`apps/web/src/app/SessionProvider.tsx` の
 * 再取得間隔(ACCESS_TOKEN_REFETCH_INTERVAL_SECONDS)がこの猶予より短い期間ごとに
 * `/api/auth/session` を叩き続ける限り、失効前に必ず一度は更新判定に引っかかる。
 *
 * 「再取得間隔 + 猶予 >= トークン寿命」が不変条件であり、これが破れると失効済みトークンが
 * API呼び出しに使われる窓が生まれる。3つの値(この定数・再取得間隔・トークン寿命)の
 * いずれか1つだけを変えても、`__tests__/tokenRefreshPolicy.test.ts` の不変条件テストが
 * 落ちるようにしてある。コメントだけが整合を主張していた状態(まさに本不具合の原因)を
 * 繰り返さないため。
 */
export const ACCESS_TOKEN_SKEW_SECONDS = 150;

/**
 * ブラウザが `/api/auth/session` を再取得する間隔(秒)。
 * `apps/web/src/app/SessionProvider.tsx` が next-auth の `SessionProvider` の
 * `refetchInterval` にこの値をそのまま渡す。
 */
export const ACCESS_TOKEN_REFETCH_INTERVAL_SECONDS = 180;

/**
 * jwtコールバックの更新要否判定。`accessTokenExpires` が無い(初回サインイン前などの異常系)
 * 場合は無条件で更新扱いとする。
 */
export function shouldRefreshAccessToken(
  accessTokenExpires: number | undefined,
  now: number
): boolean {
  if (!accessTokenExpires) {
    return true;
  }
  return now >= accessTokenExpires - ACCESS_TOKEN_SKEW_SECONDS * 1000;
}

/**
 * jwtコールバック相当の純粋ロジック。更新が不要ならtokenをそのまま返し、必要なら
 * `refresh`(実際のKeycloakへのリフレッシュ実行)へ委譲する。
 */
export async function resolveAccessToken(
  token: JWT,
  now: number,
  refresh: (token: JWT) => Promise<JWT>
): Promise<JWT> {
  if (!shouldRefreshAccessToken(token.accessTokenExpires, now)) {
    return token;
  }
  return refresh(token);
}
