import { decodeJwt } from "jose";

/**
 * deriveRole()単体のJest検証(issue #969)。
 *
 * 元は `auth.ts` 内に定義されていたが、`auth.ts` は `server-only` をimportしており、
 * server-onlyの既定エクスポートは "react-server" export conditionを解決しない限り
 * (現行のnext/jest + jsdom構成では解決されない)無条件にthrowするため、auth.ts自体を
 * Jestから直接importできない。ロール判定ロジックには`server-only`が守るべき
 * サーバー専用の副作用(Keycloakのclient secret等)が無い純粋関数なので、
 * このモジュールへ抽出しauth.tsから再利用することで、server-onlyガードの目的
 * (クライアントコンポーネントからのimport事故防止)を保ったまま単体テストを可能にする。
 */
interface KeycloakAccessTokenClaims {
  realm_access?: { roles?: string[] };
}

/**
 * Keycloakのrealmロール(admin/editor/viewer。ロールクレームなし)を、Webアプリ自身の
 * 2値ロールモデル(admin/user。requireAdminSession()・proxy.tsが依存する既存の権限判定)へ変換する。
 * ここでのJWTデコードは署名検証を伴わない(このapiClient内部の表示用ロールフラグとしてのみ使い、
 * 実際のAPI認可はlegacy-api側がAuthorizationヘッダーのJWTを署名検証した上で判定する)。
 */
export function deriveRole(accessToken: string): "admin" | "user" {
  try {
    const claims = decodeJwt(accessToken) as KeycloakAccessTokenClaims;
    const roles = claims.realm_access?.roles ?? [];
    return roles.includes("admin") ? "admin" : "user";
  } catch {
    return "user";
  }
}
