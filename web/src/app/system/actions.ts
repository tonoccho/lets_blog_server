"use server";

import { revalidatePath } from "next/cache";
import { updateUserPreferences, updateGithubToken } from "@/lib/apiClient";
import { requireSession } from "@/lib/session";

export interface UpdatePreferencesState {
  error?: string;
  success?: boolean;
}

export async function updatePreferencesAction(
  _prevState: UpdatePreferencesState,
  formData: FormData
): Promise<UpdatePreferencesState> {
  const session = await requireSession();
  const userId = Number(session.user.id);
  const actor = { id: userId, role: session.user.role };

  const locale = String(formData.get("locale") ?? "").trim();
  const timezone = String(formData.get("timezone") ?? "").trim();

  if (!locale || !timezone) {
    return { error: "言語とタイムゾーンを選択してください。" };
  }

  try {
    await updateUserPreferences(userId, { locale, timezone }, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/system");
  return { success: true };
}

export interface UpdateGithubTokenState {
  error?: string;
  success?: boolean;
}

export async function updateGithubTokenAction(
  _prevState: UpdateGithubTokenState,
  formData: FormData
): Promise<UpdateGithubTokenState> {
  const session = await requireSession();
  const userId = Number(session.user.id);
  const actor = { id: userId, role: session.user.role };

  const githubToken = String(formData.get("githubToken") ?? "").trim();

  if (!githubToken) {
    return { error: "Personal Access Token を入力してください。" };
  }

  try {
    await updateGithubToken(userId, { githubToken }, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/system");
  return { success: true };
}
