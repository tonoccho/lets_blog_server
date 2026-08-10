"use server";

import { revalidatePath } from "next/cache";
import { updateGithubToken } from "@/lib/apiClient";
import { requireSession } from "@/lib/session";

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
