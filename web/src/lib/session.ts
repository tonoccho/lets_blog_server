import "server-only";
import { redirect } from "next/navigation";
import { getServerSession, type Session } from "next-auth";
import { authOptions } from "@/lib/auth";

export function getSession(): Promise<Session | null> {
  return getServerSession(authOptions);
}

/**
 * ログイン済みであることのみを要求する(role不問)。
 * 2FA設定など、本人が自分自身を操作する画面で使う。
 */
export async function requireSession(): Promise<Session> {
  const session = await getServerSession(authOptions);
  if (!session) {
    redirect("/login");
  }
  return session;
}

/**
 * admin roleでなければ、Server Component/Server Actionの実行を止めてリダイレクトする。
 * proxy.tsによるページ保護はオプティミスティックな判定のため、
 * ユーザー管理のようなセンシティブな操作はここでも必ず検証する。
 */
export async function requireAdminSession(): Promise<Session> {
  const session = await getServerSession(authOptions);
  if (!session) {
    redirect("/login");
  }
  if (session.user.role !== "admin") {
    redirect("/");
  }
  return session;
}
