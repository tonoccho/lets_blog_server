import "server-only";
import type { Account, NextAuthOptions } from "next-auth";
import type { JWT } from "next-auth/jwt";
import { decodeJwt } from "jose";
import { resolveAccessToken } from "./tokenRefreshPolicy";

const KEYCLOAK_REALM_PATH = "/auth/realms/letsblog";
const KEYCLOAK_CLIENT_ID = "letsblog-web";
const KEYCLOAK_CLIENT_SECRET = process.env.KEYCLOAK_WEB_CLIENT_SECRET ?? "";

/**
 * Keycloakへは「ブラウザから見えるURL」と「webコンテナ自身が呼ぶURL」が異なる(コンテナ間通信は
 * lbs-net内部のサービス名で完結し、reverse-proxy/TLSを経由しない)。
 * - 認可エンドポイント(authorization)はユーザーのブラウザがリダイレクトされる先のため、外部URL
 *   (https://localhost/auth/... 。reverse-proxyがTLS終端して/auth/をkeycloak:8080へ中継する)を使う。
 * - トークン交換・リフレッシュ・JWKS取得・ログアウトはwebコンテナ自身がサーバーサイドで直接叩くため、
 *   内部URL(http://keycloak:8080/...)を使う。webコンテナ内でexternalの"localhost"を解決すると
 *   web自身を指してしまい到達できない。
 * - issuer(iss claim比較用)はKeycloak自身が発行者として名乗る値(KC_HOSTNAME起因で外部URL側に
 *   固定される)なので、内部/外部どちらでトークンを取得しても常に外部URLを期待値として使う
 *   (services/legacy-api/src/main/resources/application.ymlのkeycloak.issuerと同じ理由・同じ値)。
 */
const keycloakInternalBase = `http://keycloak:8080${KEYCLOAK_REALM_PATH}`;
const keycloakExternalBase = `${(process.env.NEXTAUTH_URL ?? "https://localhost").replace(/\/+$/, "")}${KEYCLOAK_REALM_PATH}`;

const keycloakTokenUrl = `${keycloakInternalBase}/protocol/openid-connect/token`;
const keycloakLogoutUrl = `${keycloakInternalBase}/protocol/openid-connect/logout`;

interface KeycloakAccessTokenClaims {
  realm_access?: { roles?: string[] };
}

/**
 * Keycloakのrealmロール(admin/editor/viewer。ロールクレームなし)を、Webアプリ自身の
 * 2値ロールモデル(admin/user。requireAdminSession()・proxy.tsが依存する既存の権限判定)へ変換する。
 * ここでのJWTデコードは署名検証を伴わない(このapiClient内部の表示用ロールフラグとしてのみ使い、
 * 実際のAPI認可はlegacy-api側がAuthorizationヘッダーのJWTを署名検証した上で判定する)。
 */
function deriveRole(accessToken: string): "admin" | "user" {
  try {
    const claims = decodeJwt(accessToken) as KeycloakAccessTokenClaims;
    const roles = claims.realm_access?.roles ?? [];
    return roles.includes("admin") ? "admin" : "user";
  } catch {
    return "user";
  }
}

interface KeycloakTokenResponse {
  access_token: string;
  refresh_token?: string;
  expires_in: number;
  error?: string;
}

/**
 * リフレッシュトークンによるアクセストークン更新(NextAuth v4の標準的なrefresh token rotationパターン)。
 * 失敗時はtoken.errorに"RefreshAccessTokenError"を立てて呼び出し元(proxy.ts/session.ts)に
 * 再ログインを促させる。
 */
async function refreshAccessToken(token: JWT): Promise<JWT> {
  try {
    if (!token.refreshToken) {
      throw new Error("リフレッシュトークンがありません。");
    }
    const res = await fetch(keycloakTokenUrl, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        client_id: KEYCLOAK_CLIENT_ID,
        client_secret: KEYCLOAK_CLIENT_SECRET,
        grant_type: "refresh_token",
        refresh_token: token.refreshToken,
      }),
      cache: "no-store",
    });
    const refreshed = (await res.json()) as KeycloakTokenResponse;
    if (!res.ok) {
      throw new Error(refreshed.error ?? `token refresh failed (${res.status})`);
    }
    return {
      ...token,
      accessToken: refreshed.access_token,
      accessTokenExpires: Date.now() + refreshed.expires_in * 1000,
      // Keycloakのrefresh tokenはローテーションされる(再利用不可)ため、新しい値で必ず置き換える。
      // レスポンスに含まれない場合(通常は含まれる)のみ既存値を維持する。
      refreshToken: refreshed.refresh_token ?? token.refreshToken,
      role: deriveRole(refreshed.access_token),
      error: undefined,
    };
  } catch (error) {
    // eslint-disable-next-line no-console -- リフレッシュ失敗はセッション断絶に直結するため必ずログへ残す
    console.error("アクセストークンのリフレッシュに失敗しました", error);
    return { ...token, error: "RefreshAccessTokenError" };
  }
}

interface KeycloakProfile {
  sub: string;
  email?: string;
  name?: string;
  preferred_username?: string;
  picture?: string;
}

export const authOptions: NextAuthOptions = {
  session: { strategy: "jwt" },
  pages: { signIn: "/login" },
  providers: [
    {
      id: "keycloak",
      name: "Keycloak",
      type: "oauth",
      clientId: KEYCLOAK_CLIENT_ID,
      clientSecret: KEYCLOAK_CLIENT_SECRET,
      // wellKnownを指定しない = .well-known/openid-configurationの自動探索を行わない。
      // 探索させるとwebコンテナが自分自身から見た"https://localhost/auth/..."を解決しようとして
      // 失敗する(上のkeycloakInternalBase/keycloakExternalBaseのコメント参照)ため、
      // 各エンドポイントを内部/外部の使い分けで個別に指定する。
      issuer: keycloakExternalBase,
      authorization: {
        url: `${keycloakExternalBase}/protocol/openid-connect/auth`,
        params: { scope: "openid email profile" },
      },
      token: { url: keycloakTokenUrl },
      jwks_endpoint: `${keycloakInternalBase}/protocol/openid-connect/certs`,
      checks: ["pkce", "state"],
      idToken: true,
      profile(profile: KeycloakProfile) {
        return {
          id: profile.sub,
          name: profile.name ?? profile.preferred_username,
          email: profile.email,
          image: profile.picture,
        };
      },
    },
  ],
  callbacks: {
    async jwt({ token, user, account }) {
      if (account && user) {
        // 初回サインイン。account.expires_in(秒)からアクセストークンの絶対失効時刻を算出して保持する
        // (Keycloakのaccess token寿命は300秒。以降のjwtコールバック呼び出し時にこれで判定し、
        // 期限が近ければrefreshAccessToken()でローテーションする)。
        const acc = account as Account & { expires_in?: number };
        token.id = user.id;
        token.accessToken = acc.access_token;
        token.refreshToken = acc.refresh_token;
        token.idToken = acc.id_token;
        token.accessTokenExpires = Date.now() + (acc.expires_in ?? 300) * 1000;
        token.role = acc.access_token ? deriveRole(acc.access_token) : "user";
        token.error = undefined;
        return token;
      }

      // 更新要否の判定(猶予込み)はtokenRefreshPolicy.tsへ切り出してある(issue #1053)。
      // 「既に失効していなければ更新しない」(猶予ゼロ)だったのが元の不具合であり、
      // 実際のリフレッシュ実行(refreshAccessToken)だけをここから注入する。
      return resolveAccessToken(token, Date.now(), refreshAccessToken);
    },
    // accessToken/refreshToken/idTokenはHttpOnly cookie内のJWTにのみ保持し、ブラウザ側JS
    // (useSession等)から参照可能なsession.userには意図的にコピーしない(旧apiKeyと同じ方針)。
    async session({ session, token }) {
      if (session.user) {
        session.user.id = token.id;
        session.user.role = token.role;
      }
      if (token.error) {
        session.error = token.error;
      }
      return session;
    },
  },
  events: {
    // ローカルのNextAuthセッションを破棄するだけでは、KeycloakのSSOセッションは有効なままになる
    // (ブラウザがKeycloakの認可エンドポイントへ再度リダイレクトされた際、ログイン画面を経ずに
    // 即座に再認証されてしまう=見かけ上ログアウトできていない)。refresh_tokenを使ってKeycloak側の
    // セッションもサーバーサイドで明示的に終了させる(ブラウザをKeycloakのend_session_endpointへ
    // リダイレクトする方式ではなく、こちらはリダイレクト無しでサーバー間通信のみで完結する)。
    async signOut({ token }) {
      const jwtToken = token as JWT | undefined;
      if (!jwtToken?.refreshToken) {
        return;
      }
      try {
        await fetch(keycloakLogoutUrl, {
          method: "POST",
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: new URLSearchParams({
            client_id: KEYCLOAK_CLIENT_ID,
            client_secret: KEYCLOAK_CLIENT_SECRET,
            refresh_token: jwtToken.refreshToken,
          }),
          cache: "no-store",
        });
      } catch {
        // Keycloak側セッション終了の失敗はログアウト操作自体を妨げない(ローカルセッションは
        // 既に破棄済み)。次回ログイン時にSSOセッションが残っていれば再認証を求められないだけ。
      }
    },
  },
};
