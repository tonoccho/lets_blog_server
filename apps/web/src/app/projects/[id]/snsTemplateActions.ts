"use server";

import { revalidatePath } from "next/cache";
import { resendProjectSnsTemplates, saveProjectSnsTemplates } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

/**
 * プロジェクト設定画面の「SNS 告知」欄の告知文テンプレート(issue #1583)のServer Action。いずれも admin 限定。
 * 入力の検証(文字数の上限・公開時に使えない差し込み項目)はバックエンドが行い、拒否の理由をそのまま返す。
 * 保存したあとの本番サイトへの送信もバックエンドが行い、結果(送信失敗を含む)は画面の再検証で読み直す。
 */

export interface SnsTemplateActionState {
  error?: string;
  success?: boolean;
}

function messageOf(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}

/**
 * 公開時と PV 達成時のテンプレートを保存する。空は「既定の告知文を使う」の意味でそのまま送る。
 * 本番サイトへ送れなくても保存はされているので成功として返し、失敗は送信状態に出る。
 */
export async function saveProjectSnsTemplatesAction(
  projectId: number,
  _prevState: SnsTemplateActionState,
  formData: FormData
): Promise<SnsTemplateActionState> {
  await requireAdminSession();

  try {
    await saveProjectSnsTemplates(projectId, {
      publishTemplate: String(formData.get("publishTemplate") ?? ""),
      pvTemplate: String(formData.get("pvTemplate") ?? ""),
    });
    revalidatePath(`/projects/${projectId}/settings/sns`);
    return { success: true };
  } catch (err) {
    return { error: messageOf(err) };
  }
}

/** 再送。まだ届かなければ理由を返す。成否にかかわらず送信状態を読み直す。 */
export async function resendProjectSnsTemplatesAction(projectId: number): Promise<SnsTemplateActionState> {
  await requireAdminSession();

  try {
    const view = await resendProjectSnsTemplates(projectId);
    revalidatePath(`/projects/${projectId}/settings/sns`);
    return view.send.state === "FAILED" ? { error: view.send.error ?? "再送に失敗しました。" } : { success: true };
  } catch (err) {
    return { error: messageOf(err) };
  }
}
