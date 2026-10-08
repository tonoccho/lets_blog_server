"use server";

import { revalidatePath } from "next/cache";
import {
  CmsType,
  checkSiteConnection,
  startManagedWordPressSiteJob,
  deleteSite,
  generateSshKeyPair,
  startStaticContentGenerationJob,
  saveStaticContent,
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
  /** ジョブとして受理された。構築の完了ではない(完了は処理キューの「結果を見る」から辿る)。 */
  success?: boolean;
  jobId?: number;
}

/**
 * マネージドWordPressサイトの作成を、ジョブとして要求する(issue #1696)。認可の判断は {@link registerSiteAction} と同じ(#824)。
 *
 * 構築の完了(実測で最大240秒)を待たず、受理(`POST /api/sites/managed-wordpress/jobs`)の時点で返る。
 * サイトはまだ無いので `/sites` は再検証しない。完了後は処理キューのジョブが作成されたサイトの編集画面へ導く。
 * 同期API(`createManagedWordPressSite`)は VSCode 拡張のために残してあり、ここからは呼ばない。
 */
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
    const job = await startManagedWordPressSiteJob(
      { name, siteKey, title, adminUser, adminEmail, adminPassword, locale, templateSiteId });
    if (job.status === "failed") {
      // 実行枠と待ち行列が満杯のとき、ジョブは作られた上で failed として返る。
      return { error: "サイト構築の待ち行列が満杯です。しばらくしてからもう一度要求してください。" };
    }
    return { success: true, jobId: job.id };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
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
  jobId?: number;
  status?: string;
  error?: string;
}

/**
 * 静的コンテンツの生成を非同期ジョブとして要求する(issue #1409)。生成の完了を待たず、生成と同時に
 * 保存もしない。受理されたジョブ(ID・状態)だけを返し、結果は処理キューの「結果を見る」で確認して
 * 「保存」(`saveStaticContentAction`)で書き込む。`status` が `failed` なのは、待ち行列が満杯で
 * ジョブが作られたうえで失敗として返ったとき。
 */
export async function generateStaticContentAction(
  siteId: number,
  contentType: StaticContentType
): Promise<GenerateStaticContentResult> {
  await requireAdminSession();
  try {
    const job = await startStaticContentGenerationJob(siteId, contentType);
    return { jobId: job.id, status: job.status };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}

export interface SaveStaticContentResult {
  content?: StaticContent;
  error?: string;
}

/**
 * 生成結果を確認した利用者の「保存」(issue #1409)。既存の `static_content` へ書き込む
 * (同じサイト・種別は上書き)。
 */
export async function saveStaticContentAction(
  siteId: number,
  contentType: StaticContentType,
  body: string
): Promise<SaveStaticContentResult> {
  await requireAdminSession();
  try {
    const content = await saveStaticContent(siteId, contentType, body);
    revalidatePath(`/sites/${siteId}/edit`);
    return { content };
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }
}
