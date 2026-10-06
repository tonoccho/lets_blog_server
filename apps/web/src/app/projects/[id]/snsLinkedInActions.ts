"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { disconnectProjectLinkedIn, startProjectLinkedInAuthorization, testProjectLinkedInPost } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * プロジェクト設定画面の「LinkedIn」欄(issue #1581)のServer Action。X の snsXActions と同型。
 * いずれも admin 限定。トークンはここを通らない(認可コードの交換とトークンの送信はバックエンドが行う)。
 */

export interface SnsLinkedInFormState {
  error?: string;
  success?: boolean;
}

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/**
 * OAuth のアプリ情報を送って認可を始め、LinkedIn の認可画面へ遷移する。接続できない理由
 * (本番サイトが無い・プラグインが導入済みでない等)はエラーとして返し、遷移しない。
 * リダイレクト先はこのアプリの `/connect/linkedin/callback`。
 */
export async function startProjectLinkedInConnectionAction(
  projectId: number,
  _prevState: SnsLinkedInFormState,
  formData: FormData
): Promise<SnsLinkedInFormState> {
  await requireAdminSession();

  const clientId = String(formData.get("linkedinAppId") ?? "").trim();
  const clientSecret = String(formData.get("linkedinAppSecret") ?? "").trim();
  if (!clientId) {
    return { error: "LinkedIn の Client ID を入力してください。" };
  }
  if (!clientSecret) {
    return { error: "LinkedIn の Client Secret を入力してください。" };
  }

  let authorizeUrl: string;
  try {
    ({ authorizeUrl } = await startProjectLinkedInAuthorization(projectId, {
      clientId,
      clientSecret,
      redirectUri: `${process.env.NEXTAUTH_URL}/connect/linkedin/callback`,
    }));
  } catch (err) {
    return { error: messageOf(err) };
  }
  // redirect() は例外で遷移を伝えるので try の外で呼ぶ。
  redirect(authorizeUrl);
}

/** テスト投稿。結果は告知履歴にも残るので、成否にかかわらず設定画面を再検証する。 */
export async function testProjectLinkedInPostAction(projectId: number): Promise<SnsLinkedInFormState> {
  await requireAdminSession();

  try {
    const result = await testProjectLinkedInPost(projectId);
    revalidatePath(`/projects/${projectId}/settings/sns`);
    return result.success ? { success: true } : { error: result.error ?? "テスト投稿に失敗しました。" };
  } catch (err) {
    return { error: messageOf(err) };
  }
}

/** 切断。本番サイトのプラグインから LinkedIn の設定を消し、設定画面を再検証する。 */
export async function disconnectProjectLinkedInAction(projectId: number): Promise<SnsLinkedInFormState> {
  await requireAdminSession();

  try {
    await disconnectProjectLinkedIn(projectId);
  } catch (err) {
    return { error: messageOf(err) };
  }
  revalidatePath(`/projects/${projectId}/settings/sns`);
  return { success: true };
}
