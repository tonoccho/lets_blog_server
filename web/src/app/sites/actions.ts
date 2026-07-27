"use server";

import { revalidatePath } from "next/cache";
import { registerSite } from "@/lib/apiClient";

export interface RegisterSiteState {
  error?: string;
  success?: boolean;
}

export async function registerSiteAction(
  _prevState: RegisterSiteState,
  formData: FormData
): Promise<RegisterSiteState> {
  const name = String(formData.get("name") ?? "").trim();
  const siteKey = String(formData.get("siteKey") ?? "").trim();
  const baseUrl = String(formData.get("baseUrl") ?? "").trim();
  const wpUsername = String(formData.get("wpUsername") ?? "").trim();
  const wpAppPassword = String(formData.get("wpAppPassword") ?? "").trim();

  if (!name || !siteKey || !baseUrl || !wpUsername || !wpAppPassword) {
    return { error: "すべての項目を入力してください。" };
  }

  try {
    await registerSite({ name, siteKey, baseUrl, wpUsername, wpAppPassword });
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/sites");
  return { success: true };
}
