"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { disconnectProjectHatena, startProjectHatenaAuthorization, testProjectHatenaPost } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * プロジェクト設定画面の「はてなブックマーク」欄(issue #1582)のServer Action。X の snsXActions と同型。
 * いずれも admin 限定。トークンはここを通らない(認可コードの交換とトークンの送信はバックエンドが行う)。
 */

export interface SnsHatenaFormState {
  error?: string;
  success?: boolean;
}

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/**
 * OAuth のアプリ情報を送って認可を始め、はてなの認可画面へ遷移する。接続できない理由
 * (本番サイトが無い・プラグインが導入済みでない等)はエラーとして返し、遷移しない。
 * リダイレクト先はこのアプリの `/connect/hatena/callback`。
 */
export async function startProjectHatenaConnectionAction(
  projectId: number,
  _prevState: SnsHatenaFormState,
  formData: FormData
): Promise<SnsHatenaFormState> {
  await requireAdminSession();

  const clientId = String(formData.get("hatenaConsumerKey") ?? "").trim();
  const clientSecret = String(formData.get("hatenaConsumerSecret") ?? "").trim();
  if (!clientId) {
    return { error: "はてなブックマーク の Consumer Key を入力してください。" };
  }
  if (!clientSecret) {
    return { error: "はてなブックマーク の Consumer Secret を入力してください。" };
  }

  let authorizeUrl: string;
  try {
    ({ authorizeUrl } = await startProjectHatenaAuthorization(projectId, {
      clientId,
      clientSecret,
      redirectUri: `${process.env.NEXTAUTH_URL}/connect/hatena/callback`,
    }));
  } catch (err) {
    return { error: messageOf(err) };
  }
  // redirect() は例外で遷移を伝えるので try の外で呼ぶ。
  redirect(authorizeUrl);
}

/** テスト投稿。結果は告知履歴にも残るので、成否にかかわらず設定画面を再検証する。 */
export async function testProjectHatenaPostAction(projectId: number): Promise<SnsHatenaFormState> {
  await requireAdminSession();

  try {
    const result = await testProjectHatenaPost(projectId);
    revalidatePath(`/projects/${projectId}/settings/sns`);
    return result.success ? { success: true } : { error: result.error ?? "テスト投稿に失敗しました。" };
  } catch (err) {
    return { error: messageOf(err) };
  }
}

/** 切断。本番サイトのプラグインから はてなブックマークの設定を消し、設定画面を再検証する。 */
export async function disconnectProjectHatenaAction(projectId: number): Promise<SnsHatenaFormState> {
  await requireAdminSession();

  try {
    await disconnectProjectHatena(projectId);
  } catch (err) {
    return { error: messageOf(err) };
  }
  revalidatePath(`/projects/${projectId}/settings/sns`);
  return { success: true };
}
