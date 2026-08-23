import "server-only";
import { redirect } from "next/navigation";
import { getServerSession, type Session } from "next-auth";
import { authOptions } from "@/lib/auth";
import { getUserProfile } from "@/lib/apiClient";

export function getSession(): Promise<Session | null> {
  return getServerSession(authOptions);
}

/**
 * ログインユーザーが個人設定(システム画面)で保存したタイムゾーンを取得する。
 * 日時表示の際にブラウザのローカルタイムゾーンではなくこちらを使う。
 * 未ログイン・未設定・取得失敗時はnull(呼び出し側はブラウザのローカルタイムゾーンにフォールバックする)。
 */
export async function getViewerTimeZone(): Promise<string | null> {
  const session = await getSession();
  if (!session) {
    return null;
  }
  const userId = Number(session.user.id);
  try {
    const profile = await getUserProfile(userId, { id: userId, role: session.user.role });
    return profile.timezone ?? null;
  } catch {
    return null;
  }
}

/**
 * ログイン済みであることのみを要求する(role不問)。
 * 個人設定(言語・タイムゾーン)など、本人が自分自身を操作する画面で使う。
 */
export async function requireSession(): Promise<Session> {
  const session = await getServerSession(authOptions);
  // session.errorは"RefreshAccessTokenError"(auth.tsのjwtコールバック参照)。生きたアクセストークン
  // が無い状態なので、未ログインと同様に再ログインへ誘導する。
  if (!session || session.error) {
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
  if (!session || session.error) {
    redirect("/login");
  }
  if (session.user.role !== "admin") {
    redirect("/");
  }
  return session;
}
