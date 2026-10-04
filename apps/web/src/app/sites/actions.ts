"use server";

import { revalidatePath } from "next/cache";
import {
  CmsType,
  checkSiteConnection,
  createManagedWordPressSite,
  deleteSite,
  generateSshKeyPair,
  generateStaticContent,
  getLetsblogPluginStatus,
  getLetsblogSync,
  installLetsblogPlugin,
  installWpCli,
  LetsblogPluginStatus,
  LetsblogSyncState,
  resyncLetsblog,
  registerSite,
  SiteConnectionCheckResult,
  StaticContent,
  StaticContentType,
  WpCliInstallResult,
} from "@/lib/apiClient";
import { requireAdminSession, requireSession } from "@/lib/session";

export interface RegisterSiteState {
  error?: string;
  success?: boolean;
  connectionCheckStatus?: "SUCCESS" | "FAILED" | null;
}

const CREDENTIAL_FIELDS: Record<CmsType, string[]> = {
  WORDPRESS: ["baseUrl", "sshHost", "sshUser", "wpPath"],
};

/**
 * サイトを登録する(issue #824 で認可を追加)。
 *
 * **admin 限定**。同じファイルの `deleteSiteAction` / `installWpCliAction` /
 * `generateStaticContentAction` / `generateSshKeyPairAction` はいずれも
 * `requireAdminSession()` を要求しており、サイトの作成だけが素通りだった。
 * サイト登録は SSH 認証情報を保存しインフラを作る操作なので、削除と同じ水準が妥当。
 *
 * **挙動の変更を伴う**: これまで非 admin でもサイトを登録できた。
 * バックエンドの `SiteController` は `getDetail` / `update` / `delete` / `installWpCli` /
 * `reprovision` / `generateSshKeyPair` では `requireAdmin()` を呼ぶが、
 * **`register` / `createManagedWordPress` / `adopt` / `list` / `testConnection` は呼ばない**
 * (#830)。本変更以降は Server Action 側で 403 になる。
 * なお登録フォーム内の SSH 鍵生成(`generateSshKeyPairAction`)は元から admin 限定なので、
 * 非 admin はどのみち鍵を新規生成できなかった。
 */
export async function registerSiteAction(
  _prevState: RegisterSiteState,
  formData: FormData
): Promise<RegisterSiteState> {
  await requireAdminSession();

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

  // 空欄は送らない(バックエンドはNULL=グローバル既定として扱う)。不正値の400はcatchで表示する。
  const adminPath = String(formData.get("adminPath") ?? "").trim();

  let connectionCheckStatus: "SUCCESS" | "FAILED" | null;
  try {
    const site = await registerSite({ name, siteKey, cmsType, credentials, adminPath: adminPath || undefined });
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

/** マネージドWordPressサイトを作成する。認可の判断は {@link registerSiteAction} と同じ(#824)。 */
export async function createManagedWordPressSiteAction(
  _prevState: CreateManagedWordPressSiteState,
  formData: FormData
): Promise<CreateManagedWordPressSiteState> {
  await requireAdminSession();

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

/**
 * サイトへの疎通を確認する(issue #824 で認可を追加)。
 *
 * **ログイン必須だが admin 限定にはしない**。呼び出し元の `CheckConnectionButton` は
 * `SiteListTable` の `isAdmin &&` ガードの**外**で描画されており、ログイン済みなら誰でも
 * 押せる想定になっている(削除ボタンはガードの内側)。UI の意図に認可の粒度を揃える。
 *
 * 未認証で通してはいけないのは、この操作が**外部サイトへの接続を試みる**ため。
 * 認証していない相手に外部への疎通確認を代行させる形になる。
 */
export async function checkSiteConnectionAction(id: number): Promise<SiteConnectionCheckResult> {
  await requireSession();
  return checkSiteConnection(id);
}

export async function installWpCliAction(id: number): Promise<WpCliInstallResult> {
  await requireAdminSession();
  return installWpCli(id);
}

/** サイトの letsblog プラグインの導入状態を返す(admin 限定、issue #1557)。 */
export async function getLetsblogPluginStatusAction(id: number): Promise<LetsblogPluginStatus> {
  await requireAdminSession();
  return getLetsblogPluginStatus(id);
}

/** letsblog プラグインを再導入し、導入後の状態を返す(admin 限定、issue #1557)。 */
export async function installLetsblogPluginAction(id: number): Promise<LetsblogPluginStatus> {
  await requireAdminSession();
  return installLetsblogPlugin(id);
}

/** サイトの letsblog プラグインへの同期の状態を返す(admin 限定、issue #1558)。未同期なら null。 */
export async function getLetsblogSyncAction(id: number): Promise<LetsblogSyncState | null> {
  await requireAdminSession();
  return getLetsblogSync(id);
}

/** letsblog プラグインへ再同期し、同期後の状態を返す(admin 限定、issue #1558)。 */
export async function resyncLetsblogAction(id: number): Promise<LetsblogSyncState> {
  await requireAdminSession();
  return resyncLetsblog(id);
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
