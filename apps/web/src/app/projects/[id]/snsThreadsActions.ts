"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { disconnectProjectThreads, startProjectThreadsAuthorization, testProjectThreadsPost } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * プロジェクト設定画面の「Threads」欄(issue #1579)のServer Action。X の snsXActions と同型。
 * いずれも admin 限定。トークンはここを通らない(認可コードの交換とトークンの送信はバックエンドが行う)。
 */

export interface SnsThreadsFormState {
  error?: string;
  success?: boolean;
}

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/**
 * OAuth のアプリ情報を送って認可を始め、Threads の認可画面へ遷移する。接続できない理由
 * (本番サイトが無い・プラグインが導入済みでない等)はエラーとして返し、遷移しない。
 * リダイレクト先はこのアプリの `/connect/threads/callback`。
 */
export async function startProjectThreadsConnectionAction(
  projectId: number,
  _prevState: SnsThreadsFormState,
  formData: FormData
): Promise<SnsThreadsFormState> {
  await requireAdminSession();

  const clientId = String(formData.get("threadsAppId") ?? "").trim();
  const clientSecret = String(formData.get("threadsAppSecret") ?? "").trim();
  if (!clientId) {
    return { error: "Threads のアプリIDを入力してください。" };
  }
  if (!clientSecret) {
    return { error: "Threads のアプリシークレットを入力してください。" };
  }

  let authorizeUrl: string;
  try {
    ({ authorizeUrl } = await startProjectThreadsAuthorization(projectId, {
      clientId,
      clientSecret,
      redirectUri: `${process.env.NEXTAUTH_URL}/connect/threads/callback`,
    }));
  } catch (err) {
    return { error: messageOf(err) };
  }
  // redirect() は例外で遷移を伝えるので try の外で呼ぶ。
  redirect(authorizeUrl);
}

/** テスト投稿。結果は告知履歴にも残るので、成否にかかわらず設定画面を再検証する。 */
export async function testProjectThreadsPostAction(projectId: number): Promise<SnsThreadsFormState> {
  await requireAdminSession();

  try {
    const result = await testProjectThreadsPost(projectId);
    revalidatePath(`/projects/${projectId}/settings/sns`);
    return result.success ? { success: true } : { error: result.error ?? "テスト投稿に失敗しました。" };
  } catch (err) {
    return { error: messageOf(err) };
  }
}

/** 切断。本番サイトのプラグインから Threads の設定を消し、設定画面を再検証する。 */
export async function disconnectProjectThreadsAction(projectId: number): Promise<SnsThreadsFormState> {
  await requireAdminSession();

  try {
    await disconnectProjectThreads(projectId);
  } catch (err) {
    return { error: messageOf(err) };
  }
  revalidatePath(`/projects/${projectId}/settings/sns`);
  return { success: true };
}
