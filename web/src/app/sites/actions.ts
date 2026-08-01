"use server";

import { revalidatePath } from "next/cache";
import {
  CmsType,
  checkSiteConnection,
  createManagedWordPressSite,
  deleteSite,
  generateSshKeyPair,
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

const WORDPRESS_SSH_FIELDS = ["baseUrl", "sshHost", "sshUser", "wpPath", "sshPrivateKeyPem"];

export async function registerSiteAction(
  _prevState: RegisterSiteState,
  formData: FormData
): Promise<RegisterSiteState> {
  const name = String(formData.get("name") ?? "").trim();
  const siteKey = String(formData.get("siteKey") ?? "").trim();
  const cmsType = String(formData.get("cmsType") ?? "") as CmsType;
  const transport = String(formData.get("transport") ?? "REST").trim();

  if (!name || !siteKey) {
    return { error: "表示名とサイトキーは必須です。" };
  }

  if (cmsType !== "WORDPRESS" && cmsType !== "MICROCMS") {
    return { error: "CMS種別を選択してください。" };
  }

  const useSsh = cmsType === "WORDPRESS" && transport === "SSH";
  const fields = useSsh ? WORDPRESS_SSH_FIELDS : CREDENTIAL_FIELDS[cmsType];

  const credentials: Record<string, string> = {};
  for (const field of fields) {
    const value = String(formData.get(field) ?? "").trim();
    if (!value) {
      return { error: `${field} は必須です。` };
    }
    credentials[field] = value;
  }

  if (useSsh) {
    credentials.transport = "SSH";
    const sshPort = String(formData.get("sshPort") ?? "").trim();
    if (sshPort) {
      credentials.sshPort = sshPort;
    }
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

export interface GenerateSshKeyPairResult {
  error?: string;
  publicKeyLine?: string;
  privateKeyPem?: string;
}

export async function generateSshKeyPairAction(comment: string): Promise<GenerateSshKeyPairResult> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  try {
    const keyPair = await generateSshKeyPair(comment, actor);
    return { publicKeyLine: keyPair.publicKeyLine, privateKeyPem: keyPair.privateKeyPem };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
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
  const templateSiteIdRaw = String(formData.get("managedTemplateSiteId") ?? "").trim();
  const templateSiteId = templateSiteIdRaw ? Number(templateSiteIdRaw) : undefined;

  if (!name || !siteKey || !title || !adminUser || !adminEmail || !adminPassword) {
    return { error: "すべての項目を入力してください。" };
  }

  const session = await getSession();
  const actor = session ? { id: Number(session.user.id), role: session.user.role } : undefined;

  try {
    await createManagedWordPressSite(
      { name, siteKey, title, adminUser, adminEmail, adminPassword, locale, templateSiteId },
      actor
    );
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
