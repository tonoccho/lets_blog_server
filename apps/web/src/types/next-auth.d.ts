import type { DefaultSession } from "next-auth";

declare module "next-auth" {
  interface Session {
    user: {
      id: string;
      role: "admin" | "user";
    } & DefaultSession["user"];
    /** リフレッシュトークンによる自動更新に失敗した場合にセットされる。立っていれば再ログインを促す。 */
    error?: "RefreshAccessTokenError";
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    id: string;
    role: "admin" | "user";
    /** Keycloakが発行したアクセストークン(JWT)。apiClient.tsがAuthorizationヘッダーに使う。 */
    accessToken?: string;
    refreshToken?: string;
    idToken?: string;
    /** accessTokenの絶対失効時刻(epoch ms)。jwtコールバックがこれを見てリフレッシュ要否を判定する。 */
    accessTokenExpires?: number;
    error?: "RefreshAccessTokenError";
  }
}
