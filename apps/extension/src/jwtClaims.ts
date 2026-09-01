/**
 * ログイン成功メッセージ等の表示にのみ使う、アクセストークン(JWT)のクレーム読み取り(issue #565)。
 *
 * サーバー(各サービスのoauth2 resource server)がアクセストークンの署名を検証した上でAPIを
 * 処理するため、拡張側で再度署名検証する必要はない。ここでは email/role をクレームから
 * 読み取るためだけに使い、権限判定には使わない(実際の権限判定はサーバー側のCurrentActorServiceが
 * ローカルDBのRoleを直接引いた結果を正とする)。
 */

/** JWTのペイロード部分をデコードする(署名検証は行わない)。形式が不正な場合はundefined。 */
export function decodeJwtPayload(token: string): Record<string, unknown> | undefined {
  const parts = token.split('.');
  if (parts.length < 2) return undefined;
  try {
    const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '=');
    const parsed: unknown = JSON.parse(Buffer.from(padded, 'base64').toString('utf-8'));
    return typeof parsed === 'object' && parsed !== null && !Array.isArray(parsed)
      ? (parsed as Record<string, unknown>)
      : undefined;
  } catch {
    return undefined;
  }
}

/** emailクレームを取り出す。無ければpreferred_usernameで代用する。どちらも無ければ空文字。 */
export function extractEmail(claims: Record<string, unknown> | undefined): string {
  if (typeof claims?.email === 'string') return claims.email;
  if (typeof claims?.preferred_username === 'string') return claims.preferred_username;
  return '';
}

/** Keycloakがrealmロールに既定で付与する、アプリケーション固有ではないロール名。表示対象から除外する。 */
const KEYCLOAK_DEFAULT_ROLES = new Set(['offline_access', 'uma_authorization']);

/**
 * realm_access.rolesから、Keycloakの既定ロール(offline_access/uma_authorization/default-roles-*)を
 * 除いた最初のロールを、ローカルDBのrole_name表記(`ROLE_` + 大文字)へ変換して返す。
 * `services/legacy-api/.../KeycloakRealmRoleConverter.java` と同じ変換規則(表示専用の用途で
 * 同じ規則を踏襲しているだけであり、サーバー側の実際の権限判定はローカルDBのRoleを正とする)。
 */
export function extractPrimaryRoleName(claims: Record<string, unknown> | undefined): string | undefined {
  const realmAccess = claims?.realm_access;
  if (typeof realmAccess !== 'object' || realmAccess === null) return undefined;
  const roles = (realmAccess as Record<string, unknown>).roles;
  if (!Array.isArray(roles)) return undefined;
  const candidate = roles.find(
    (role): role is string =>
      typeof role === 'string' &&
      !KEYCLOAK_DEFAULT_ROLES.has(role) &&
      !role.startsWith('default-roles-')
  );
  return candidate ? `ROLE_${candidate.toUpperCase()}` : undefined;
}
