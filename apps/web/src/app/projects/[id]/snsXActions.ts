"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { startProjectXAuthorization, testProjectXPost } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * プロジェクト設定画面の「SNS 告知」欄(X。issue #1574)のServer Action。
 * いずれも admin 限定。トークンはここを通らない(認可コードの交換とトークンの送信はバックエンドが行う)。
 */

export interface SnsXFormState {
  error?: string;
  success?: boolean;
}

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/**
 * OAuth クライアントを送って認可を始め、X の認可画面へ遷移する。接続できない理由
 * (本番サイトが無い・プラグインが導入済みでない等)はエラーとして返し、遷移しない。
 * リダイレクト先はこのアプリの `/connect/x/callback`(GA の OAuth と同じ方式)。
 */
export async function startProjectXConnectionAction(
  projectId: number,
  _prevState: SnsXFormState,
  formData: FormData
): Promise<SnsXFormState> {
  await requireAdminSession();

  const clientId = String(formData.get("clientId") ?? "").trim();
  const clientSecret = String(formData.get("clientSecret") ?? "").trim();
  if (!clientId) {
    return { error: "X の OAuth クライアントIDを入力してください。" };
  }
  if (!clientSecret) {
    return { error: "X の OAuth クライアントシークレットを入力してください。" };
  }

  let authorizeUrl: string;
  try {
    ({ authorizeUrl } = await startProjectXAuthorization(projectId, {
      clientId,
      clientSecret,
      redirectUri: `${process.env.NEXTAUTH_URL}/connect/x/callback`,
    }));
  } catch (err) {
    return { error: messageOf(err) };
  }
  // redirect() は例外で遷移を伝えるので try の外で呼ぶ。
  redirect(authorizeUrl);
}

/** テスト投稿。結果は告知履歴にも残るので、成否にかかわらず設定画面を再検証する。 */
export async function testProjectXPostAction(projectId: number): Promise<SnsXFormState> {
  await requireAdminSession();

  try {
    const result = await testProjectXPost(projectId);
    revalidatePath(`/projects/${projectId}/settings/sns`);
    return result.success ? { success: true } : { error: result.error ?? "テスト投稿に失敗しました。" };
  } catch (err) {
    return { error: messageOf(err) };
  }
}
