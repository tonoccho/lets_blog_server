/**
 * KeycloakのSSOセッションID(`sid`クレーム)を、そのセッションで発行されたアクセス
 * トークン/IDトークンから取り出す(issue #1329)。
 *
 * 【背景】`revokeKeycloakSsoSession`(auth.steps.ts、issue #1053)は元々
 * `kcadm create users/{id}/logout`でユーザーID全体のSSOセッションを終了させて
 * いた。同じ`E2E_ADMIN_EMAIL`を使う別の同時実行中シナリオ(既定の並列度では
 * `token-lifecycle.feature`内の複数シナリオが同時に走りうる)のセッションまで
 * 巻き添えで終了させてしまい、2026-09-16のAC検証で実際に再現した(issue #1329本文)。
 *
 * 【対策】KeycloakはOIDCのセッション管理仕様に従い、発行するアクセストークン/
 * IDトークンに発行元セッションのID(`sid`)を含める。このIDはAdmin REST APIの
 * `GET /admin/realms/{realm}/users/{id}/sessions`が返す各セッションの`id`と
 * 同じ値であり、`DELETE /admin/realms/{realm}/sessions/{session}`
 * (kcadm.sh経由では`kcadm.sh delete sessions/{sid}`)で個別セッションだけを
 * 終了できる。実機(Keycloak 26.7.2、`docker exec lbs-keycloak`)で以下を
 * 確認済み(issue #1329実装報告):
 *   - 既に認証済みのkcadmセッションのアクセストークンを復号すると`sid`クレームが
 *     実際に含まれている。
 *   - `kcadm.sh delete sessions/<存在しないid>`は"Resource not found for
 *     url: .../sessions/<id>"を返す(エンドポイント自体は生きている)。
 *
 * JWTのペイロード部分(`.`区切りの2番目)をBase64URLデコードしJSONとして読むだけで、
 * 署名検証は行わない(このE2Eヘルパー内部でどのセッションを対象にするかを決める
 * ためだけに使い、実際のログアウト実行は認証済みのkcadmセッション経由でKeycloak
 * 自身が行う)。src/lib/deriveRole.tsは同じ用途で`jose`の`decodeJwt`を使っているが、
 * `jose`(v6、ESM専用ビルド)はこの`apps/web/e2e/**`から読み込むとJestの変換設定
 * (`transformIgnorePatterns`がnode/geist以外のESM専用パッケージを除外している)の
 * 都合で"Unexpected token 'export'"になり単体テストできなかった(issue #1329実装
 * 報告に詳細を記録)。ペイロードのデコードだけなら外部ライブラリは不要なため、
 * 依存を増やさずBase64URL+JSON.parseで自前実装する。
 */
export function extractKeycloakSessionId(rawToken: string): string {
  const parts = rawToken.split('.');
  if (parts.length < 2) {
    throw new Error('JWTの形式が不正(ペイロード部分が見つからない)でSSOセッションを特定できない');
  }

  let payload: unknown;
  try {
    payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf-8'));
  } catch {
    throw new Error('JWTのペイロードをデコードできずSSOセッションを特定できない');
  }

  const sid = (payload as { sid?: unknown } | null)?.sid;
  if (typeof sid !== 'string' || sid.length === 0) {
    throw new Error(
      'Keycloakが発行したトークンにsidクレームが含まれていない(SSOセッションを個別に特定できない)'
    );
  }
  return sid;
}
