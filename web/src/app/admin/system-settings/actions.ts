"use server";

import { revalidatePath } from "next/cache";
import { updateAppSettings } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface UpdateAppSettingsFormState {
  error?: string;
  success?: boolean;
}

/**
 * フォームに含まれる全項目をまとめてPUTする(issue #403)。空欄の項目は「未設定に戻す(環境変数へ
 * フォールバック)」として送信する。APIサーバー側で1つのトランザクションとして扱われ、いずれかの値が
 * 不正な場合はこの保存操作での変更が全てロールバックされる。
 */
export async function updateAppSettingsAction(
  _prevState: UpdateAppSettingsFormState,
  formData: FormData
): Promise<UpdateAppSettingsFormState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const settings: Record<string, string> = {};
  for (const [key, value] of formData.entries()) {
    settings[key] = String(value).trim();
  }

  try {
    await updateAppSettings(settings, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/admin/system-settings");
  return { success: true };
}
