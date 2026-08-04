"use server";

import { revalidatePath } from "next/cache";
import { clearBraveSearchApiKey, setBraveSearchApiKey } from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface SystemSettingsFormState {
  error?: string;
  success?: boolean;
}

export async function setBraveSearchApiKeyAction(
  _prevState: SystemSettingsFormState,
  formData: FormData
): Promise<SystemSettingsFormState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const apiKey = String(formData.get("apiKey") ?? "").trim();
  if (!apiKey) {
    return { error: "APIキーを入力してください。" };
  }

  try {
    await setBraveSearchApiKey(apiKey, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/admin/settings");
  return { success: true };
}

export async function clearBraveSearchApiKeyAction(): Promise<void> {
  const session = await requireAdminSession();
  await clearBraveSearchApiKey({ id: Number(session.user.id), role: session.user.role });
  revalidatePath("/admin/settings");
}
