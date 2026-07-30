"use server";

import { revalidatePath } from "next/cache";
import {
  CmsType,
  checkSiteConnection,
  createManagedWordPressSite,
  deleteSite,
  registerSite,
  SiteConnectionCheckResult,
} from "@/lib/apiClient";
import { getSession, requireAdminSession } from "@/lib/session";

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

export interface CreateManagedWordPressSiteState {
  error?: string;
  success?: boolean;
}

export async function createManagedWordPressSiteAction(
  _prevState: CreateManagedWordPressSiteState,
  formData: FormData
): Promise<CreateManagedWordPressSiteState> {
  const name = String(formData.get("managedName") ?? "").trim();
  const siteKey = String(formData.get("managedSiteKey") ?? "").trim();
  const title = String(formData.get("managedTitle") ?? "").trim();
  const adminUser = String(formData.get("managedAdminUser") ?? "").trim();
  const adminEmail = String(formData.get("managedAdminEmail") ?? "").trim();
  const adminPassword = String(formData.get("managedAdminPassword") ?? "").trim();
  const locale = String(formData.get("managedLocale") ?? "ja").trim();

  if (!name || !siteKey || !title || !adminUser || !adminEmail || !adminPassword) {
    return { error: "すべての項目を入力してください。" };
  }

  const session = await getSession();
  const actor = session ? { id: Number(session.user.id), role: session.user.role } : undefined;

  try {
    await createManagedWordPressSite({ name, siteKey, title, adminUser, adminEmail, adminPassword, locale }, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/sites");
  return { success: true };
}

export async function deleteSiteAction(id: number) {
  const session = await requireAdminSession();
  await deleteSite(id, { id: Number(session.user.id), role: session.user.role });
  revalidatePath("/sites");
}

export async function checkSiteConnectionAction(id: number): Promise<SiteConnectionCheckResult> {
  return checkSiteConnection(id);
}
