import "server-only";
import type { NextAuthOptions } from "next-auth";
import CredentialsProvider from "next-auth/providers/credentials";
import { login, verifyTotpLogin } from "@/lib/apiClient";

/**
 * 2FAが有効なユーザーは、パスワード認証(login)は成功してもtwoFactorRequired=trueが返る。
 * その場合はTOTPコードが未提出なら"2FA_REQUIRED"を投げてUI側にコード入力欄を表示させ、
 * コード提出時はverifyTotpLoginで2段階目を検証してからNextAuthセッションを確立する。
 * (このAPIサーバーはログイン時にJWTを発行しないため、2段階目の検証もこのauthorize内で完結させる)
 */
export const authOptions: NextAuthOptions = {
  session: { strategy: "jwt" },
  pages: { signIn: "/login" },
  providers: [
    CredentialsProvider({
      name: "credentials",
      credentials: {
        email: { label: "Email", type: "email" },
        password: { label: "Password", type: "password" },
        totpCode: { label: "TOTP", type: "text" },
      },
      async authorize(credentials) {
        if (!credentials?.email || !credentials?.password) {
          return null;
        }
        const result = await login(credentials.email, credentials.password);
        if (!result) {
          return null;
        }

        if (result.twoFactorRequired) {
          if (!credentials.totpCode) {
            throw new Error("2FA_REQUIRED");
          }
          const verified = await verifyTotpLogin(result.user.id, credentials.totpCode);
          if (!verified) {
            throw new Error("2FA_INVALID");
          }
          return { id: String(verified.user.id), email: verified.user.email, role: verified.user.role };
        }

        return { id: String(result.user.id), email: result.user.email, role: result.user.role };
      },
    }),
  ],
  callbacks: {
    async jwt({ token, user }) {
      if (user) {
        token.id = user.id;
        token.role = user.role;
      }
      return token;
    },
    async session({ session, token }) {
      if (session.user) {
        session.user.id = token.id;
        session.user.role = token.role;
      }
      return session;
    },
  },
};
