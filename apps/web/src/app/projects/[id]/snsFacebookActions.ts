"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import {
  disconnectProjectFacebook,
  selectProjectFacebookPage,
  startProjectFacebookAuthorization,
  testProjectFacebookPost,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * プロジェクト設定画面の「Facebook」欄(issue #1580)のServer Action。X の snsXActions と同型。
 * いずれも admin 限定。トークンはここを通らない(認可コードの交換とトークンの送信はバックエンドが行う)。
 */

export interface SnsFacebookFormState {
  error?: string;
  success?: boolean;
}

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/**
 * OAuth のアプリ情報を送って認可を始め、Facebook の認可画面へ遷移する。接続できない理由
 * (本番サイトが無い・プラグインが導入済みでない等)はエラーとして返し、遷移しない。
 * リダイレクト先はこのアプリの `/connect/facebook/callback`。
 */
export async function startProjectFacebookConnectionAction(
  projectId: number,
  _prevState: SnsFacebookFormState,
  formData: FormData
): Promise<SnsFacebookFormState> {
  await requireAdminSession();

  const clientId = String(formData.get("facebookAppId") ?? "").trim();
  const clientSecret = String(formData.get("facebookAppSecret") ?? "").trim();
  if (!clientId) {
    return { error: "Facebook のアプリIDを入力してください。" };
  }
  if (!clientSecret) {
    return { error: "Facebook のアプリシークレットを入力してください。" };
  }

  let authorizeUrl: string;
  try {
    ({ authorizeUrl } = await startProjectFacebookAuthorization(projectId, {
      clientId,
      clientSecret,
      redirectUri: `${process.env.NEXTAUTH_URL}/connect/facebook/callback`,
    }));
  } catch (err) {
    return { error: messageOf(err) };
  }
  // redirect() は例外で遷移を伝えるので try の外で呼ぶ。
  redirect(authorizeUrl);
}

/**
 * 認可のあとに選んだ投稿先のページを送る。バックエンドがそのページのトークンだけを本番サイトへ送る。
 * 成功したら接続完了として設定画面へ戻る。
 */
export async function selectProjectFacebookPageAction(
  projectId: number,
  state: string,
  _prevState: SnsFacebookFormState,
  formData: FormData
): Promise<SnsFacebookFormState> {
  await requireAdminSession();

  const pageId = String(formData.get("facebookPageId") ?? "").trim();
  if (!pageId) {
    return { error: "投稿先の Facebook ページを選んでください。" };
  }

  try {
    await selectProjectFacebookPage(projectId, { state, pageId });
  } catch (err) {
    return { error: messageOf(err) };
  }
  // redirect() は例外で遷移を伝えるので try の外で呼ぶ。
  redirect(`/projects/${projectId}/settings/sns?connected=facebook`);
}

/** テスト投稿。結果は告知履歴にも残るので、成否にかかわらず設定画面を再検証する。 */
export async function testProjectFacebookPostAction(projectId: number): Promise<SnsFacebookFormState> {
  await requireAdminSession();

  try {
    const result = await testProjectFacebookPost(projectId);
    revalidatePath(`/projects/${projectId}/settings/sns`);
    return result.success ? { success: true } : { error: result.error ?? "テスト投稿に失敗しました。" };
  } catch (err) {
    return { error: messageOf(err) };
  }
}

/** 切断。本番サイトのプラグインから Facebook の設定を消し、設定画面を再検証する。 */
export async function disconnectProjectFacebookAction(projectId: number): Promise<SnsFacebookFormState> {
  await requireAdminSession();

  try {
    await disconnectProjectFacebook(projectId);
  } catch (err) {
    return { error: messageOf(err) };
  }
  revalidatePath(`/projects/${projectId}/settings/sns`);
  return { success: true };
}
