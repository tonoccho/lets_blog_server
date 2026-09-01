import "server-only";
import { cache } from "react";
import { redirect } from "next/navigation";
import { getServerSession, type Session } from "next-auth";
import { authOptions } from "@/lib/auth";
import { getMyProfile, type UserProfile } from "@/lib/apiClient";

export function getSession(): Promise<Session | null> {
  return getServerSession(authOptions);
}

/**
 * ログイン中ユーザー自身のプロフィールを取得する(issue #784)。
 *
 * `session.user.id`はKeycloak移行(#564)以降Keycloakの`sub`(UUID)であり、ローカルの数値
 * ユーザーIDではない。数値IDが要る箇所(「自分自身か」の判定など)は、ここが返す
 * `profile.id`を使うこと。`Number(session.user.id)`は必ず`NaN`になる。
 *
 * 1リクエスト内で複数回呼ばれても identity-service への問い合わせが1回で済むよう
 * Reactの`cache()`でメモ化する(タイムゾーン取得と自己判定が同じページで同時に必要になる)。
 *
 * 未ログイン時と取得失敗時はnullを返す。失敗を握り潰すと同種の不具合が再び見えなくなるため、
 * 失敗時はサーバーログに残す(issue #784ではまさに`catch {}`の握り潰しによって
 * `/api/users/NaN`が203件/日発生していることに長く気付けなかった)。
 */
export const getViewerProfile = cache(async (): Promise<UserProfile | null> => {
  const session = await getSession();
  if (!session) {
    return null;
  }
  try {
    return await getMyProfile();
  } catch (err) {
    console.error("自ユーザーのプロフィール取得に失敗しました (GET /api/identity/me)", err);
    return null;
  }
});

/**
 * ログインユーザーが個人設定(システム画面)で保存したタイムゾーンを取得する。
 * 日時表示の際にブラウザのローカルタイムゾーンではなくこちらを使う。
 * 未ログイン・未設定・取得失敗時はnull(呼び出し側はブラウザのローカルタイムゾーンにフォールバックする)。
 */
export async function getViewerTimeZone(): Promise<string | null> {
  const profile = await getViewerProfile();
  return profile?.timezone ?? null;
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
