"use server";

import { revalidatePath } from "next/cache";
import { CmsType, registerSite } from "@/lib/apiClient";
import { getSession } from "@/lib/session";

export interface RegisterSiteState {
  error?: string;
  success?: boolean;
  connectionCheckStatus?: "SUCCESS" | "FAILED" | null;
}

const CREDENTIAL_FIELDS: Record<CmsType, string[]> = {
  WORDPRESS: ["baseUrl", "username", "appPassword"],
  MICROCMS: ["serviceId", "apiKey", "managementApiKey", "postsEndpoint", "categoriesEndpoint", "tagsEndpoint"],
};

export async function registerSiteAction(
  _prevState: RegisterSiteState,
  formData: FormData
): Promise<RegisterSiteState> {
  const name = String(formData.get("name") ?? "").trim();
  const siteKey = String(formData.get("siteKey") ?? "").trim();
  const cmsType = String(formData.get("cmsType") ?? "") as CmsType;

  if (!name || !siteKey) {
    return { error: "表示名とサイトキーは必須です。" };
  }

  const fields = CREDENTIAL_FIELDS[cmsType];
  if (!fields) {
    return { error: "CMS種別を選択してください。" };
  }

  const credentials: Record<string, string> = {};
  for (const field of fields) {
    const value = String(formData.get(field) ?? "").trim();
    if (!value) {
      return { error: `${field} は必須です。` };
    }
    credentials[field] = value;
  }

  const session = await getSession();
  const actor = session ? { id: Number(session.user.id), role: session.user.role } : undefined;

  let connectionCheckStatus: "SUCCESS" | "FAILED" | null;
  try {
    const site = await registerSite({ name, siteKey, cmsType, credentials }, actor);
    connectionCheckStatus = site.connectionCheckStatus;
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/sites");
  return { success: true, connectionCheckStatus };
}
