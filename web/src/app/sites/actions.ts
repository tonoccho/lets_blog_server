"use server";

import { revalidatePath } from "next/cache";
import {
  CmsType,
  checkSiteConnection,
  createManagedWordPressSite,
  deleteSite,
  generateSshKeyPair,
  generateStaticContent,
  installWpCli,
  registerSite,
  SiteConnectionCheckResult,
  StaticContent,
  StaticContentType,
  WpCliInstallResult,
} from "@/lib/apiClient";
import { requireAdminSession } from "@/lib/session";

export interface RegisterSiteState {
  error?: string;
  success?: boolean;
  connectionCheckStatus?: "SUCCESS" | "FAILED" | null;
}

const CREDENTIAL_FIELDS: Record<CmsType, string[]> = {
  WORDPRESS: ["baseUrl", "sshHost", "sshUser", "wpPath"],
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

  if (cmsType !== "WORDPRESS") {
    return { error: "CMS種別を選択してください。" };
  }

  const fields = CREDENTIAL_FIELDS[cmsType];

  const credentials: Record<string, string> = { transport: "SSH" };
  for (const field of fields) {
    const value = String(formData.get(field) ?? "").trim();
    if (!value) {
      return { error: `${field} は必須です。` };
    }
    credentials[field] = value;
  }

  const sshPort = String(formData.get("sshPort") ?? "").trim();
  if (sshPort) {
    credentials.sshPort = sshPort;
  }

  const sshKeyPairId = String(formData.get("sshKeyPairId") ?? "").trim();
  const sshPrivateKeyPem = String(formData.get("sshPrivateKeyPem") ?? "").trim();
  if (sshKeyPairId) {
    credentials.sshKeyPairId = sshKeyPairId;
  } else if (sshPrivateKeyPem) {
    credentials.sshPrivateKeyPem = sshPrivateKeyPem;
  } else {
    return { error: "SSH秘密鍵を指定してください(保存済みの鍵ペアを選択するか、新しい鍵ペアを生成してください)。" };
  }

  let connectionCheckStatus: "SUCCESS" | "FAILED" | null;
  try {
    const site = await registerSite({ name, siteKey, cmsType, credentials });
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
  await requireAdminSession();

  try {
    const keyPair = await generateSshKeyPair(comment);
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

  try {
    await createManagedWordPressSite(
      { name, siteKey, title, adminUser, adminEmail, adminPassword, locale, templateSiteId });
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/sites");
  return { success: true };
}

export async function deleteSiteAction(id: number) {
  await requireAdminSession();
  await deleteSite(id);
  revalidatePath("/sites");
}

export async function checkSiteConnectionAction(id: number): Promise<SiteConnectionCheckResult> {
  return checkSiteConnection(id);
}

export async function installWpCliAction(id: number): Promise<WpCliInstallResult> {
  await requireAdminSession();
  return installWpCli(id);
}

export interface GenerateStaticContentResult {
  content?: StaticContent;
  error?: string;
}

export async function generateStaticContentAction(
  siteId: number,
  contentType: StaticContentType
): Promise<GenerateStaticContentResult> {
  await requireAdminSession();
  try {
    const content = await generateStaticContent(siteId, contentType);
    return { content };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}
