import 'server-only';
import { after } from 'next/server';
import { cookies, headers } from 'next/headers';
import { getToken } from 'next-auth/jwt';

export type CmsType = "WORDPRESS";

export interface Site {
  id: number;
  name: string;
  siteKey: string;
  cmsType: CmsType;
  baseUrl: string;
  createdAt: string;
  updatedAt: string;
  connectionCheckStatus: "SUCCESS" | "FAILED" | null;
  managedWordpress: boolean;
}

export interface PostSummary {
  id: number;
  siteId: number;
  siteName: string;
  wpPostId: string;
  slug: string | null;
  status: string;
  lastPublishedAt: string | null;
}

export interface GenerationJob {
  id: number;
  type: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface GeneratedImageSummary {
  id: number;
  projectId: number | null;
  prompt: string;
  checkpoint: string;
  createdAt: string;
  tags: string[];
}

export interface GeneratedImageDetail extends GeneratedImageSummary {
  negativePrompt: string;
  steps: number;
  cfgScale: number;
  samplerName: string;
  scheduler: string;
  seed: number;
  width: number;
  height: number;
  batchSize: number;
  loraName: string | null;
  loraWeight: number | null;
}

export interface SiteRegisterInput {
  name: string;
  siteKey: string;
  cmsType: CmsType;
  credentials: Record<string, string>;
}

export interface ManagedWordPressSiteInput {
  name: string;
  siteKey: string;
  title: string;
  adminUser: string;
  adminEmail: string;
  adminPassword: string;
  locale: string;
  templateSiteId?: number;
}

export interface AuthenticatedUser {
  id: number;
  email: string;
  role: "admin" | "user";
}

export interface LoginResult {
  user: AuthenticatedUser;
  twoFactorRequired: boolean;
  apiKey: string | null;
}

export interface TwoFactorSetup {
  qrCodeDataUrl: string;
  backupCodes: string[];
}

export interface AppUser {
  id: number;
  email: string;
  role: "admin" | "user";
  roleNames: string[];
  createdAt: string;
  updatedAt: string;
}

export interface RoleInfo {
  id: number;
  roleName: string;
  displayName: string;
  description: string | null;
  permissions: string[];
}

export interface UserCreateInput {
  email: string;
  password: string;
  role: "admin" | "user";
}

function serverUrl(): string {
  return (process.env.LETS_BLOG_API_URL ?? 'https://localhost').replace(/\/+$/, '');
}

/**
 * next-auth/jwtのgetToken()はreq.cookies/req.headersしか参照しないため、
 * NextRequestが無いServer Component/Server Actionからでもnext/headersのcookies()/headers()を
 * そのまま渡せる(型定義上はNextRequest等を期待しているため as any で吸収する)。
 */
async function currentToken() {
  return getToken({
    req: { cookies: await cookies(), headers: await headers() } as unknown as Parameters<typeof getToken>[0]['req'],
    secret: process.env.NEXTAUTH_SECRET,
  });
}

/** ログイン中ユーザーのAPIキーをNextAuthのJWT(HttpOnly cookie)から取得する。 */
async function currentApiKey(): Promise<string> {
  const token = await currentToken();
  if (!token?.apiKey) {
    throw new Error('ログインしていないか、APIキーが未取得です。再度ログインしてください。');
  }
  return token.apiKey;
}

export interface ActorInfo {
  id: number;
  role: "admin" | "user";
}

/** actorが明示指定されなかった呼び出しでも操作ログにユーザーを紐付けられるよう、JWTから補完する。 */
async function currentTokenActor(): Promise<ActorInfo | undefined> {
  const token = await currentToken();
  if (!token?.id || !token?.role) {
    return undefined;
  }
  return { id: Number(token.id), role: token.role };
}

const OPERATION_ID_HEADER = 'x-operation-id';

/** proxy.tsがリクエストごとに発番したIDを読み取り、1回の操作で発生した複数のAPI呼び出しを束ねる。 */
async function currentOperationId(): Promise<string> {
  const hdrs = await headers();
  return hdrs.get(OPERATION_ID_HEADER) ?? crypto.randomUUID();
}

interface OperationLogEntryInput {
  operationId: string;
  method: string;
  path: string;
  statusCode: number | null;
  durationMs: number;
  success: boolean;
  errorMessage?: string;
}

/**
 * 操作ログをバックエンドへ記録する(issue #143)。apiFetch()自身から呼ぶため、
 * 無限再帰を避けるためにapiFetch()を経由せず直接fetchする(=この記録リクエスト自体はログされない)。
 * 記録の失敗が本来のAPI呼び出しに影響しないよう例外は握りつぶす。
 */
async function recordOperationLog(apiKey: string, actor: ActorInfo | undefined, entry: OperationLogEntryInput): Promise<void> {
  try {
    await fetch(`${serverUrl()}/api/operation-logs`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-API-Key': apiKey,
        ...(actor ? { 'X-Actor-Id': String(actor.id), 'X-Actor-Role': actor.role } : {}),
      },
      body: JSON.stringify(entry),
      cache: 'no-store',
    });
  } catch {
    // 操作ログの記録失敗は無視する(本来の操作を妨げない)
  }
}

interface ApiFetchInit extends RequestInit {
  actor?: ActorInfo;
  /** ログイン前でも呼べる公開エンドポイント(signup/setup/setup-status)向け。既定はtrue。 */
  requiresAuth?: boolean;
}

async function apiFetch<T>(path: string, init?: ApiFetchInit): Promise<T> {
  const { actor, requiresAuth = true, ...requestInit } = init ?? {};
  const apiKey = requiresAuth ? await currentApiKey() : undefined;
  const method = (requestInit.method ?? 'GET').toString().toUpperCase();
  const startedAt = Date.now();
  // after()内ではRequest-time API(headers/cookies)を呼べないため、レンダリング中に読んでおく。
  const operationId = apiKey ? await currentOperationId() : null;
  const logActor = actor ?? (apiKey ? await currentTokenActor() : undefined);

  const scheduleLog = (entry: OperationLogEntryInput) => {
    if (apiKey && operationId) {
      after(() => recordOperationLog(apiKey, logActor, entry));
    }
  };

  let res: Response;
  try {
    res = await fetch(`${serverUrl()}${path}`, {
      ...requestInit,
      headers: {
        ...(apiKey ? { 'X-API-Key': apiKey } : {}),
        ...(actor ? { 'X-Actor-Id': String(actor.id), 'X-Actor-Role': actor.role } : {}),
        ...(requestInit.headers ?? {}),
      },
      cache: 'no-store',
    });
  } catch (err) {
    scheduleLog({
      operationId: operationId ?? '',
      method,
      path,
      statusCode: null,
      durationMs: Date.now() - startedAt,
      success: false,
      errorMessage: err instanceof Error ? err.message : String(err),
    });
    throw err;
  }

  const durationMs = Date.now() - startedAt;

  if (!res.ok) {
    const body = await res.text().catch(() => '');
    const message = `APIエラー (${res.status}): ${body || res.statusText}`;
    scheduleLog({ operationId: operationId ?? '', method, path, statusCode: res.status, durationMs, success: false, errorMessage: message });
    throw new Error(message);
  }

  scheduleLog({ operationId: operationId ?? '', method, path, statusCode: res.status, durationMs, success: true });

  if (res.status === 204) {
    return undefined as T;
  }
  const text = await res.text();
  if (text === '') {
    return undefined as T;
  }
  return JSON.parse(text) as T;
}

export function listSites(): Promise<Site[]> {
  return apiFetch<Site[]>('/api/sites');
}

export interface SiteDetail {
  id: number;
  name: string;
  siteKey: string;
  cmsType: CmsType;
  baseUrl: string;
  createdAt: string;
  updatedAt: string;
  managedWordpress: boolean;
  sshConfigured: boolean;
  credentials: Record<string, string>;
  configuredSecretFields: string[];
}

export function getSiteDetail(id: number, actor?: ActorInfo): Promise<SiteDetail> {
  return apiFetch<SiteDetail>(`/api/sites/${id}`, { actor });
}

export function registerSite(input: SiteRegisterInput, actor?: ActorInfo): Promise<Site> {
  return apiFetch<Site>('/api/sites', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function createManagedWordPressSite(input: ManagedWordPressSiteInput, actor?: ActorInfo): Promise<Site> {
  return apiFetch<Site>('/api/sites/managed-wordpress', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function deleteSite(id: number, actor?: ActorInfo): Promise<void> {
  return apiFetch<void>(`/api/sites/${id}`, { method: 'DELETE', actor });
}

export interface SiteUpdateInput {
  name?: string;
  credentials?: Record<string, string>;
}

export function updateSite(id: number, input: SiteUpdateInput, actor?: ActorInfo): Promise<Site> {
  return apiFetch<Site>(`/api/sites/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export interface SiteConnectionCheckResult {
  connectionCheckStatus: "SUCCESS" | "FAILED";
  hasAdminCapability: boolean | null;
  failureReason: string | null;
  detail: string | null;
}

export function checkSiteConnection(id: number): Promise<SiteConnectionCheckResult> {
  return apiFetch(`/api/sites/${id}/test-connection`, { method: 'POST' });
}

export interface WpCliInstallResult {
  message: string;
}

export function installWpCli(id: number, actor?: ActorInfo): Promise<WpCliInstallResult> {
  return apiFetch<WpCliInstallResult>(`/api/sites/${id}/install-wp-cli`, { method: 'POST', actor });
}

export interface SshKeyPair {
  publicKeyLine: string;
  privateKeyPem: string;
}

export function generateSshKeyPair(comment: string | undefined, actor?: ActorInfo): Promise<SshKeyPair> {
  return apiFetch<SshKeyPair>('/api/sites/ssh-keypair', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ comment: comment || null }),
    actor,
  });
}

/**
 * 名前をつけて保存・管理するSSH鍵ペア(issue #413)。admin限定。秘密鍵は生成直後の
 * レスポンス(createSshKeyPair)でのみ返り、一覧(listSshKeyPairs)では公開鍵のみを返す。
 */
export interface SavedSshKeyPair {
  id: number;
  name: string;
  comment: string | null;
  publicKeyLine: string;
  createdAt: string;
}

export interface GeneratedSshKeyPair extends SavedSshKeyPair {
  privateKeyPem: string;
}

export function listSshKeyPairs(actor?: ActorInfo): Promise<SavedSshKeyPair[]> {
  return apiFetch<SavedSshKeyPair[]>('/api/ssh-key-pairs', { actor });
}

export function createSshKeyPair(
  input: { name: string; comment?: string },
  actor: ActorInfo
): Promise<GeneratedSshKeyPair> {
  return apiFetch<GeneratedSshKeyPair>('/api/ssh-key-pairs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: input.name, comment: input.comment || null }),
    actor,
  });
}

export function deleteSshKeyPair(id: number, actor: ActorInfo): Promise<void> {
  return apiFetch<void>(`/api/ssh-key-pairs/${id}`, { method: 'DELETE', actor });
}

export function listPosts(): Promise<PostSummary[]> {
  return apiFetch<PostSummary[]>('/api/posts');
}

export function listGenerationJobs(): Promise<GenerationJob[]> {
  return apiFetch<GenerationJob[]>('/api/generation-jobs');
}

export function listGeneratedImages(projectId?: number): Promise<GeneratedImageSummary[]> {
  const query = projectId ? `?projectId=${projectId}` : '';
  return apiFetch<GeneratedImageSummary[]>(`/api/generated-images${query}`);
}

export function getGeneratedImage(id: number): Promise<GeneratedImageDetail> {
  return apiFetch<GeneratedImageDetail>(`/api/generated-images/${id}`);
}

export function deleteGeneratedImage(id: number): Promise<void> {
  return apiFetch<void>(`/api/generated-images/${id}`, { method: 'DELETE' });
}

/** 自動生成されたタグを手動で編集・追加する(issue #281)。 */
export function updateGeneratedImageTags(id: number, tags: string[]): Promise<GeneratedImageDetail> {
  return apiFetch<GeneratedImageDetail>(`/api/generated-images/${id}/tags`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tags }),
  });
}

export async function downloadGeneratedImageFile(id: number): Promise<{ body: ArrayBuffer; mimeType: string }> {
  const res = await fetch(`${serverUrl()}/api/generated-images/${id}/file`, {
    headers: { 'X-API-Key': await currentApiKey() },
    cache: 'no-store',
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`APIエラー (${res.status}): ${body || res.statusText}`);
  }
  return {
    body: await res.arrayBuffer(),
    mimeType: res.headers.get('content-type') ?? 'image/png',
  };
}

export interface ImageGenerationOptionsResponse {
  checkpoints: string[];
  selectedCheckpoint: string;
  samplers: string[];
  schedulers: string[];
  loras: string[];
  defaultWidth: number;
  defaultHeight: number;
  defaultNegativePrompt: string | null;
  defaultQualityPrompt: string | null;
}

export function getImageGenerationOptions(projectId: number, actor?: ActorInfo): Promise<ImageGenerationOptionsResponse> {
  return apiFetch<ImageGenerationOptionsResponse>(`/api/ai/image-options?projectId=${projectId}`, { actor });
}

export interface AiImageGenerationParams {
  prompt: string;
  negativePrompt?: string;
  steps?: number;
  cfgScale?: number;
  samplerName?: string;
  scheduler?: string;
  seed?: number | null;
  width?: number;
  height?: number;
  batchSize?: number;
  checkpoint?: string;
  loraName?: string;
  loraWeight?: number;
  projectId?: number;
}

export interface AiImageResult {
  id: number;
  fileName: string;
  dataBase64: string;
  mimeType: string;
}

export interface AiImageBatchResult {
  images: AiImageResult[];
}

export function generateProjectImages(
  params: AiImageGenerationParams,
  actor?: ActorInfo
): Promise<AiImageBatchResult> {
  return apiFetch<AiImageBatchResult>('/api/ai/image', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
    actor,
  });
}

export interface AiImagePromptResponse {
  prompt: string;
}

export function generateImagePromptFromChat(
  projectId: number,
  data: { history: PlanChatMessage[]; message: string },
  actor?: ActorInfo
): Promise<AiImagePromptResponse> {
  return apiFetch<AiImagePromptResponse>(`/api/projects/${projectId}/ai/generate-image-prompt`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
    actor,
  });
}

export function uploadProjectAssetImage(
  projectId: number,
  generatedImageId: number,
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/asset-images/${generatedImageId}/upload`, {
    method: 'POST',
    actor,
  });
}

export async function login(email: string, password: string): Promise<LoginResult | null> {
  const res = await fetch(`${serverUrl()}/api/auth/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ email, password, label: 'web' }),
    cache: 'no-store',
  });

  if (res.status === 401) {
    return null;
  }
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`APIエラー (${res.status}): ${body || res.statusText}`);
  }
  return (await res.json()) as LoginResult;
}

/**
 * ログイン2段階目。login()でtwoFactorRequired=trueだった場合に、
 * TOTPコード(またはバックアップコード)を検証してログインを完了する。
 * 401の場合はコードが無効なのでnullを返す。
 */
export async function verifyTotpLogin(userId: number, code: string): Promise<LoginResult | null> {
  const res = await fetch(`${serverUrl()}/api/auth/totp/verify`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ userId, code, label: 'web' }),
    cache: 'no-store',
  });

  if (res.status === 401) {
    return null;
  }
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`APIエラー (${res.status}): ${body || res.statusText}`);
  }
  return (await res.json()) as LoginResult;
}

export function getTwoFactorStatus(actor: ActorInfo): Promise<{ enabled: boolean }> {
  return apiFetch<{ enabled: boolean }>('/api/auth/totp/status', { actor });
}

export function setupTwoFactor(actor: ActorInfo): Promise<TwoFactorSetup> {
  return apiFetch<TwoFactorSetup>('/api/auth/totp/setup', { method: 'POST', actor });
}

export function verifyTwoFactorSetup(code: string, actor: ActorInfo): Promise<{ message: string }> {
  return apiFetch<{ message: string }>('/api/auth/totp/verify-setup', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ code }),
    actor,
  });
}

export function disableTwoFactor(actor: ActorInfo): Promise<{ message: string }> {
  return apiFetch<{ message: string }>('/api/auth/totp/disable', { method: 'POST', actor });
}

export function listUsers(): Promise<AppUser[]> {
  return apiFetch<AppUser[]>('/api/users');
}

export function signup(email: string, password: string): Promise<AuthenticatedUser> {
  return apiFetch<AuthenticatedUser>('/api/auth/signup', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
    requiresAuth: false,
  });
}

export function getSetupStatus(): Promise<{ needsSetup: boolean }> {
  return apiFetch<{ needsSetup: boolean }>('/api/auth/setup-status', { requiresAuth: false });
}

export function setupInitialAdmin(email: string, password: string): Promise<AuthenticatedUser> {
  return apiFetch<AuthenticatedUser>('/api/auth/setup', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
    requiresAuth: false,
  });
}

export function createUser(input: UserCreateInput, actor?: ActorInfo): Promise<AppUser> {
  return apiFetch<AppUser>('/api/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function updateUserRole(id: number, role: "admin" | "user", actor?: ActorInfo): Promise<AppUser> {
  return apiFetch<AppUser>(`/api/users/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role }),
    actor,
  });
}

export function deleteUser(id: number, actor?: ActorInfo): Promise<void> {
  return apiFetch<void>(`/api/users/${id}`, { method: 'DELETE', actor });
}

export interface SocialLinks {
  facebook: string | null;
  youtube: string | null;
  whatsapp: string | null;
  tiktok: string | null;
  instagram: string | null;
  wechat: string | null;
  x: string | null;
  threads: string | null;
  github: string | null;
  pinterest: string | null;
  meetup: string | null;
  line: string | null;
  linkedin: string | null;
  hatena: string | null;
}

export interface CustomLink {
  label: string;
  url: string;
}

export interface UserProfile {
  id: number;
  email: string;
  role: "admin" | "user";
  roleNames: string[];
  firstName: string | null;
  lastName: string | null;
  displayName: string | null;
  nickname: string | null;
  websiteUrl: string | null;
  bio: string | null;
  locale: string | null;
  timezone: string | null;
  avatarUrl: string | null;
  department: string | null;
  position: string | null;
  socialLinks: SocialLinks | null;
  customLinks: CustomLink[] | null;
  githubTokenConfigured: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface UserProfileInput {
  firstName: string | null;
  lastName: string | null;
  displayName: string | null;
  nickname: string | null;
  websiteUrl: string | null;
  bio: string | null;
  locale: string | null;
  avatarUrl: string | null;
  department: string | null;
  position: string | null;
  socialLinks: SocialLinks | null;
  customLinks: CustomLink[] | null;
}

export function getUserProfile(id: number, actor?: ActorInfo): Promise<UserProfile> {
  return apiFetch<UserProfile>(`/api/users/${id}`, { actor });
}

export function updateUserProfile(id: number, input: UserProfileInput, actor?: ActorInfo): Promise<UserProfile> {
  return apiFetch<UserProfile>(`/api/users/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export interface UpdateUserPreferencesInput {
  locale: string;
  timezone: string;
}

export function updateUserPreferences(
  id: number,
  input: UpdateUserPreferencesInput,
  actor?: ActorInfo
): Promise<UserProfile> {
  return apiFetch<UserProfile>(`/api/users/${id}/preferences`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function updateGithubToken(
  id: number,
  input: { githubToken: string },
  actor?: ActorInfo
): Promise<UserProfile> {
  return apiFetch<UserProfile>(`/api/users/${id}/github-token`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function listRoles(actor: ActorInfo): Promise<RoleInfo[]> {
  return apiFetch<RoleInfo[]>('/api/roles', { actor });
}

export function assignRole(userId: number, roleName: string, actor: ActorInfo): Promise<{ message: string }> {
  return apiFetch<{ message: string }>(`/api/users/${userId}/roles/${roleName}`, { method: 'POST', actor });
}

export function removeRole(userId: number, roleName: string, actor: ActorInfo): Promise<{ message: string }> {
  return apiFetch<{ message: string }>(`/api/users/${userId}/roles/${roleName}`, { method: 'DELETE', actor });
}

export interface PostStatusOption {
  value: string;
  label: string;
}

/** 投稿ステータスの正準リスト。VS Code拡張とサーバー側の選択肢を一致させるための共通取得元(issue #472)。 */
export function getPostStatuses(actor?: ActorInfo): Promise<PostStatusOption[]> {
  return apiFetch<PostStatusOption[]>('/api/metadata/post-statuses', { actor });
}

export function requestPasswordReset(email: string): Promise<{ message: string }> {
  return apiFetch<{ message: string }>('/api/auth/password-reset/request', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
  });
}

export function confirmPasswordReset(token: string, newPassword: string): Promise<{ message: string }> {
  return apiFetch<{ message: string }>('/api/auth/password-reset/confirm', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, newPassword }),
  });
}

export type CustomTagFormat = 'INLINE' | 'BLOCK';

export interface CustomTag {
  id: number;
  tagName: string;
  htmlTemplate: string;
  description: string | null;
  cssContent: string | null;
  tagFormat: CustomTagFormat;
  projectId: number | null;
  createdAt: string;
  updatedAt: string;
  /** AI生成時にLLMへのリクエストを元にPenpotへ作成したデザインファイルのURL(ベストエフォート、生成以外では常にnull)。 */
  penpotFileUrl?: string | null;
}

export interface CustomTagInput {
  tagName: string;
  htmlTemplate: string;
  description?: string;
  cssContent?: string;
  tagFormat?: CustomTagFormat;
  projectId?: number | null;
}

export interface GenerateCustomTagInput {
  prompt: string;
  tagName: string;
  description?: string;
  projectId?: number | null;
}

export interface ValidationErrorDetail {
  type: string;
  message: string;
  line?: number;
  severity: string;
}

export interface ValidationWarningDetail {
  type: string;
  message: string;
  line?: number;
}

export interface ValidationResult {
  isValid: boolean;
  errors: ValidationErrorDetail[];
  warnings: ValidationWarningDetail[];
}

export interface ValidateCustomTagRequest {
  htmlTemplate: string;
  cssContent?: string;
}

/** プロジェクト詳細のカスタムタグ画面向け。グローバルタグを含めず、プロジェクトのタグのみを返す。 */
export function listProjectCustomTags(projectId: number, actor?: ActorInfo): Promise<CustomTag[]> {
  return apiFetch<CustomTag[]>(`/api/projects/${projectId}/custom-tags`, { actor });
}

export function createCustomTag(input: CustomTagInput, actor: ActorInfo): Promise<CustomTag> {
  return apiFetch<CustomTag>('/api/custom-tags', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function updateCustomTag(id: number, input: CustomTagInput, actor: ActorInfo): Promise<CustomTag> {
  return apiFetch<CustomTag>(`/api/custom-tags/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function deleteCustomTag(id: number, actor: ActorInfo): Promise<void> {
  return apiFetch<void>(`/api/custom-tags/${id}`, { method: 'DELETE', actor });
}

export function generateCustomTag(input: GenerateCustomTagInput, actor: ActorInfo): Promise<CustomTag> {
  return apiFetch<CustomTag>('/api/custom-tags/generate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function validateCustomTag(input: ValidateCustomTagRequest, actor: ActorInfo): Promise<ValidationResult> {
  return apiFetch<ValidationResult>('/api/custom-tags/validate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export interface CustomTagPreviewInput {
  htmlTemplate: string;
  cssContent?: string;
  testContent: string;
}

export interface CustomTagPreviewResult {
  html: string;
  css: string;
}

/**
 * プロジェクト詳細のカスタムタグ画面向けプレビュー。DB未保存のテンプレート/CSSでも、実際の投稿と
 * 同じMarkdownレンダリングとCSSセレクタのプリフィックス付与を適用した結果を返す(issue #335)。
 */
export function previewProjectCustomTag(
  projectId: number,
  input: CustomTagPreviewInput,
  actor: ActorInfo
): Promise<CustomTagPreviewResult> {
  return apiFetch<CustomTagPreviewResult>(`/api/projects/${projectId}/custom-tags/preview`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

/** プロジェクト詳細/プロジェクト一覧向け。グローバルタグを含めず、プロジェクトのタグのCSSのみを連結する。 */
export async function downloadProjectCustomTagCssBundle(projectId: number, actor: ActorInfo): Promise<ArrayBuffer> {
  const res = await fetch(`${serverUrl()}/api/projects/${projectId}/custom-tags/css-bundle`, {
    headers: {
      'X-API-Key': await currentApiKey(),
      'X-Actor-Id': String(actor.id),
      'X-Actor-Role': actor.role,
    },
    cache: 'no-store',
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`APIエラー (${res.status}): ${body || res.statusText}`);
  }
  return res.arrayBuffer();
}

// --- 組み込みタグ([toc]/[blogcard]/[amazon])のデザインカスタマイズ (issue #150) ---

export type EmbedTagType = "TOC" | "BLOGCARD" | "AMAZON";

export interface TagDesignPreset {
  id: string;
  label: string;
  backgroundColor: string;
  textColor: string;
  accentColor: string;
}

export interface TagDesignSetting {
  tagType: EmbedTagType;
  presetId: string;
  backgroundColor: string;
  textColor: string;
  accentColor: string;
  customCss: string | null;
  htmlTemplate: string | null;
}

export interface TagDesignSettingsOverview {
  presets: TagDesignPreset[];
  settings: TagDesignSetting[];
}

export interface SaveTagDesignSettingInput {
  presetId: string;
  backgroundColor: string;
  textColor: string;
  accentColor: string;
  customCss?: string;
  htmlTemplate?: string;
}

export function getTagDesignSettings(projectId: number, actor?: ActorInfo): Promise<TagDesignSettingsOverview> {
  return apiFetch<TagDesignSettingsOverview>(`/api/projects/${projectId}/tag-design-settings`, { actor });
}

export function saveTagDesignSetting(
  projectId: number,
  tagType: EmbedTagType,
  input: SaveTagDesignSettingInput,
  actor: ActorInfo
): Promise<TagDesignSetting> {
  return apiFetch<TagDesignSetting>(`/api/projects/${projectId}/tag-design-settings/${tagType}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

/** htmlTemplateはAIが構造変更不要と判断した場合に空文字になりうる(その場合は現在の値を維持する)。 */
export interface GenerateTagDesignResult {
  htmlTemplate: string;
  cssContent: string;
}

export function generateTagDesign(
  projectId: number,
  tagType: EmbedTagType,
  prompt: string,
  actor: ActorInfo
): Promise<GenerateTagDesignResult> {
  return apiFetch<GenerateTagDesignResult>(`/api/projects/${projectId}/tag-design-settings/${tagType}/generate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt }),
    actor,
  });
}

export interface CustomTagTemplate {
  id: number;
  templateName: string;
  description: string | null;
  category: string | null;
  htmlTemplate: string;
  cssContent: string | null;
  version: number;
  isPublished: boolean;
  originalTagId: number | null;
  projectId: number | null;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface CustomTagTemplateInput {
  templateName: string;
  description?: string;
  category?: string;
  htmlTemplate: string;
  cssContent?: string;
  projectId?: number | null;
  originalTagId?: number | null;
}

export interface CloneCustomTagTemplateInput {
  newTemplateName: string;
  description?: string;
  category?: string;
  projectId?: number | null;
}

export function listCustomTagTemplates(actor?: ActorInfo, projectId?: number, options?: { category?: string; search?: string; showAll?: boolean }): Promise<CustomTagTemplate[]> {
  const params = new URLSearchParams();
  if (projectId != null) params.set('projectId', String(projectId));
  if (options?.category) params.set('category', options.category);
  if (options?.search) params.set('search', options.search);
  if (options?.showAll) params.set('showAll', 'true');
  const query = params.toString() ? `?${params.toString()}` : '';
  return apiFetch<CustomTagTemplate[]>(`/api/custom-tag-templates${query}`, { actor });
}

export function getCustomTagTemplate(id: number, actor?: ActorInfo): Promise<CustomTagTemplate> {
  return apiFetch<CustomTagTemplate>(`/api/custom-tag-templates/${id}`, { actor });
}

export function createCustomTagTemplate(input: CustomTagTemplateInput, actor: ActorInfo): Promise<CustomTagTemplate> {
  return apiFetch<CustomTagTemplate>('/api/custom-tag-templates', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function updateCustomTagTemplate(id: number, input: CustomTagTemplateInput, actor: ActorInfo): Promise<CustomTagTemplate> {
  return apiFetch<CustomTagTemplate>(`/api/custom-tag-templates/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function publishCustomTagTemplate(id: number, actor: ActorInfo): Promise<CustomTagTemplate> {
  return apiFetch<CustomTagTemplate>(`/api/custom-tag-templates/${id}/publish`, {
    method: 'POST',
    actor,
  });
}

export function unpublishCustomTagTemplate(id: number, actor: ActorInfo): Promise<CustomTagTemplate> {
  return apiFetch<CustomTagTemplate>(`/api/custom-tag-templates/${id}/unpublish`, {
    method: 'POST',
    actor,
  });
}

export function cloneCustomTagTemplate(id: number, input: CloneCustomTagTemplateInput, actor: ActorInfo): Promise<CustomTagTemplate> {
  return apiFetch<CustomTagTemplate>(`/api/custom-tag-templates/${id}/clone`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function deleteCustomTagTemplate(id: number, actor: ActorInfo): Promise<void> {
  return apiFetch<void>(`/api/custom-tag-templates/${id}`, { method: 'DELETE', actor });
}

export function getMyCustomTagTemplates(actor: ActorInfo): Promise<CustomTagTemplate[]> {
  return apiFetch<CustomTagTemplate[]>('/api/custom-tag-templates/my-templates', { actor });
}

/**
 * Let's Blogアプリ自身のバックアップアーカイブ(DB + 生成画像ファイル + メタデータをまとめたZIP)を
 * ダウンロードする(admin限定)。
 */
export async function downloadBackupFile(): Promise<{ body: ArrayBuffer; filename: string }> {
  const res = await fetch(`${serverUrl()}/api/backup/download`, {
    headers: { 'X-API-Key': await currentApiKey() },
    cache: 'no-store',
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`APIエラー (${res.status}): ${body || res.statusText}`);
  }
  const disposition = res.headers.get('content-disposition') ?? '';
  const match = disposition.match(/filename="([^"]+)"/);
  return { body: await res.arrayBuffer(), filename: match?.[1] ?? 'lets-blog-backup.zip' };
}

/**
 * アップロードしたバックアップアーカイブでDB+生成画像ファイルを復元する(admin限定、
 * 破壊的操作のためconfirm=trueが必須)。バックアップ作成時と異なるAPP_ENCRYPTION_KEYの
 * 環境へリストアしようとした場合、acknowledgeKeyMismatchがfalseだとサーバー側で拒否される
 * (サイト認証情報等が復号できなくなるデータ破損を防ぐため)。
 */
export async function restoreBackup(
  file: File,
  actor: ActorInfo,
  acknowledgeKeyMismatch: boolean
): Promise<void> {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('confirm', 'true');
  formData.append('acknowledgeKeyMismatch', String(acknowledgeKeyMismatch));
  return apiFetch<void>('/api/backup/restore', {
    method: 'POST',
    body: formData,
    actor,
  });
}

export interface OperationLogEntry {
  id: number;
  operationId: string;
  userId: number | null;
  method: string;
  path: string;
  statusCode: number | null;
  durationMs: number;
  success: boolean;
  errorMessage: string | null;
  createdAt: string;
}

export interface OperationLogPage {
  content: OperationLogEntry[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export function listOperationLogs(
  params: { page?: number; size?: number },
  actor: ActorInfo
): Promise<OperationLogPage> {
  const query = new URLSearchParams();
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 200));
  query.set('sort', 'createdAt,desc');
  return apiFetch<OperationLogPage>(`/api/operation-logs?${query.toString()}`, { actor });
}

export function getOperationTrace(operationId: string, actor: ActorInfo): Promise<OperationLogEntry[]> {
  return apiFetch<OperationLogEntry[]>(`/api/operation-logs/${encodeURIComponent(operationId)}`, { actor });
}

/** 操作ログ・AIジョブ・監査ログを一元表示するための統合エントリ(issue #187)。 */
export type UnifiedLogSourceType = "OPERATION" | "AI_JOB" | "AUDIT";

export interface UnifiedLogEntry {
  sourceType: UnifiedLogSourceType;
  id: number;
  createdAt: string;
  title: string;
  detail: string | null;
  status: string | null;
  operationId: string | null;
}

export interface UnifiedLogPage {
  content: UnifiedLogEntry[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export function listUnifiedOperationLogs(
  params: { type?: UnifiedLogSourceType; q?: string; page?: number; size?: number },
  actor: ActorInfo
): Promise<UnifiedLogPage> {
  const query = new URLSearchParams();
  if (params.type) query.set('type', params.type);
  if (params.q) query.set('q', params.q);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 50));
  return apiFetch<UnifiedLogPage>(`/api/operation-logs/unified?${query.toString()}`, { actor });
}

export interface Project {
  id: number;
  name: string;
  slug: string;
  localSite: Site | null;
  testSite: Site | null;
  productionSite: Site | null;
  masterEnvironment: "test" | "production";
  githubRepository: string | null;
  cssSelectorPrefix: string | null;
  defaultNegativePrompt: string | null;
  defaultQualityPrompt: string | null;
  defaultGeneratedImageWidth: number | null;
  defaultGeneratedImageHeight: number | null;
  defaultArticleImageLongEdgePx: number | null;
  createdAt: string;
  updatedAt: string;
}

export type ProjectEnvironment = "local" | "test" | "production";

export function listProjects(actor?: ActorInfo): Promise<Project[]> {
  return apiFetch<Project[]>('/api/projects', { actor });
}

export function getProject(id: number, actor?: ActorInfo): Promise<Project> {
  return apiFetch<Project>(`/api/projects/${id}`, { actor });
}

export function createProject(input: { name: string; slug: string }, actor?: ActorInfo): Promise<Project> {
  return apiFetch<Project>('/api/projects', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function updateProject(id: number, name: string, actor?: ActorInfo): Promise<Project> {
  return apiFetch<Project>(`/api/projects/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
    actor,
  });
}

export function deleteProject(id: number, actor?: ActorInfo): Promise<void> {
  return apiFetch<void>(`/api/projects/${id}`, { method: 'DELETE', actor });
}

export function updateProjectGithubRepository(
  id: number,
  githubRepository: string,
  actor?: ActorInfo
): Promise<Project> {
  return apiFetch<Project>(`/api/projects/${id}/github-repository`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ githubRepository }),
    actor,
  });
}

export function updateProjectCssSelectorPrefix(
  id: number,
  cssSelectorPrefix: string,
  actor?: ActorInfo
): Promise<Project> {
  return apiFetch<Project>(`/api/projects/${id}/css-selector-prefix`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cssSelectorPrefix }),
    actor,
  });
}

/** 画像生成時のnegative prompt/画質プロンプトのデフォルト値(issue #293)。空文字はアプリ全体のデフォルトへ戻す。 */
export function updateProjectImageGenerationPromptDefaults(
  id: number,
  defaultNegativePrompt: string,
  defaultQualityPrompt: string,
  actor?: ActorInfo
): Promise<Project> {
  return apiFetch<Project>(`/api/projects/${id}/image-generation-prompt-defaults`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ defaultNegativePrompt, defaultQualityPrompt }),
    actor,
  });
}

/** 画像生成時のデフォルトサイズ(issue #292)。nullはアプリ全体のデフォルト(1920x1080)へ戻す。 */
export function updateProjectImageGenerationSizeDefaults(
  id: number,
  defaultGeneratedImageWidth: number | null,
  defaultGeneratedImageHeight: number | null,
  actor?: ActorInfo
): Promise<Project> {
  return apiFetch<Project>(`/api/projects/${id}/image-generation-size-defaults`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ defaultGeneratedImageWidth, defaultGeneratedImageHeight }),
    actor,
  });
}

/** 記事投稿時に画像をリサイズする長編の目標px(issue #291)。nullはアプリ全体のデフォルト(1300px)へ戻す。 */
export function updateProjectArticleImageResizeDefault(
  id: number,
  defaultArticleImageLongEdgePx: number | null,
  actor?: ActorInfo
): Promise<Project> {
  return apiFetch<Project>(`/api/projects/${id}/article-image-resize-default`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ defaultArticleImageLongEdgePx }),
    actor,
  });
}

/** valueそのものは返さず、設定済みかどうかのみ返す(SiteDetailのconfiguredSecretFieldsと同じ方針)。 */
export interface ProjectApiKeyStatus {
  configured: boolean;
}

export function getProjectGithubTokenStatus(projectId: number, actor?: ActorInfo): Promise<ProjectApiKeyStatus> {
  return apiFetch<ProjectApiKeyStatus>(`/api/projects/${projectId}/api-keys/github-token`, { actor });
}

export function setProjectGithubToken(projectId: number, githubToken: string, actor: ActorInfo): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/github-token`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ githubToken }),
    actor,
  });
}

export function clearProjectGithubToken(projectId: number, actor: ActorInfo): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/github-token`, { method: 'DELETE', actor });
}

export function getProjectBraveSearchApiKeyStatus(
  projectId: number,
  actor?: ActorInfo
): Promise<ProjectApiKeyStatus> {
  return apiFetch<ProjectApiKeyStatus>(`/api/projects/${projectId}/api-keys/brave-search-api-key`, { actor });
}

export function setProjectBraveSearchApiKey(projectId: number, apiKey: string, actor: ActorInfo): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/brave-search-api-key`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ apiKey }),
    actor,
  });
}

export function clearProjectBraveSearchApiKey(projectId: number, actor: ActorInfo): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/brave-search-api-key`, { method: 'DELETE', actor });
}

export interface ProjectGoogleAnalyticsStatus {
  configured: boolean;
  propertyId: string | null;
}

export function getProjectGoogleAnalyticsStatus(
  projectId: number,
  actor?: ActorInfo
): Promise<ProjectGoogleAnalyticsStatus> {
  return apiFetch<ProjectGoogleAnalyticsStatus>(`/api/projects/${projectId}/api-keys/google-analytics`, { actor });
}

export function setProjectGoogleAnalyticsCredentials(
  projectId: number,
  input: { propertyId: string; serviceAccountJson: string },
  actor: ActorInfo
): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/google-analytics`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function clearProjectGoogleAnalyticsCredentials(projectId: number, actor: ActorInfo): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/google-analytics`, { method: 'DELETE', actor });
}

export interface GoogleAnalyticsReport {
  eligible: boolean;
  sessions: number | null;
  activeUsers: number | null;
  pageViews: number | null;
  periodLabel: string | null;
  errorMessage: string | null;
}

export function getProjectGoogleAnalyticsReport(
  projectId: number,
  actor?: ActorInfo
): Promise<GoogleAnalyticsReport> {
  return apiFetch<GoogleAnalyticsReport>(`/api/projects/${projectId}/dashboard/google-analytics`, { actor });
}

export interface ProjectAdSenseStatus {
  configured: boolean;
  accountId: string | null;
  clientId: string | null;
  hasClientSecret: boolean;
}

export function getProjectAdSenseStatus(projectId: number, actor?: ActorInfo): Promise<ProjectAdSenseStatus> {
  return apiFetch<ProjectAdSenseStatus>(`/api/projects/${projectId}/api-keys/adsense`, { actor });
}

export function setProjectAdSenseSettings(
  projectId: number,
  input: { accountId: string; clientId: string },
  actor: ActorInfo
): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/adsense`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function setProjectAdSenseClientSecret(
  projectId: number,
  clientSecret: string,
  actor: ActorInfo
): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/adsense/client-secret`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ clientSecret }),
    actor,
  });
}

export function clearProjectAdSenseCredentials(projectId: number, actor: ActorInfo): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/adsense`, { method: 'DELETE', actor });
}

export function completeProjectAdSenseOAuth(
  projectId: number,
  input: { code: string; redirectUri: string },
  actor: ActorInfo
): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/adsense/oauth-callback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export interface AdSenseReport {
  eligible: boolean;
  estimatedEarnings: string | null;
  clicks: number | null;
  impressions: number | null;
  periodLabel: string | null;
  errorMessage: string | null;
}

export function getProjectAdSenseReport(projectId: number, actor?: ActorInfo): Promise<AdSenseReport> {
  return apiFetch<AdSenseReport>(`/api/projects/${projectId}/dashboard/adsense`, { actor });
}

export interface ProjectBufferStatus {
  configured: boolean;
  enabled: boolean;
  hasAccessToken: boolean;
  profileIds: string | null;
  delayMinutes: number | null;
  messageTemplate: string | null;
}

export function getProjectBufferStatus(projectId: number, actor?: ActorInfo): Promise<ProjectBufferStatus> {
  return apiFetch<ProjectBufferStatus>(`/api/projects/${projectId}/api-keys/buffer`, { actor });
}

export function setProjectBufferSettings(
  projectId: number,
  input: { enabled: boolean; profileIds: string; delayMinutes: number | null; messageTemplate: string },
  actor: ActorInfo
): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/buffer`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function setProjectBufferAccessToken(projectId: number, accessToken: string, actor: ActorInfo): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/buffer/access-token`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ accessToken }),
    actor,
  });
}

export function clearProjectBufferSettings(projectId: number, actor: ActorInfo): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/buffer`, { method: 'DELETE', actor });
}

/**
 * adminユーザー限定のシステム設定画面(issue #403)向け。プロジェクトに紐付かない業務系のアプリ全体設定
 * (外部LLMサービス連携・メール送信・Google OAuthクライアント・Webフロントの公開URL)を扱う。
 * 秘匿情報(secret=true)はvalueを含まない(設定済みかどうか・設定元のみ)。
 */
export interface AppSetting {
  key: string;
  label: string;
  secret: boolean;
  configured: boolean;
  source: 'DATABASE' | 'ENVIRONMENT' | 'NONE';
  value: string | null;
}

export function listAppSettings(actor?: ActorInfo): Promise<AppSetting[]> {
  return apiFetch<AppSetting[]>('/api/system-settings/app-settings', { actor });
}

export function updateAppSettings(settings: Record<string, string>, actor: ActorInfo): Promise<void> {
  return apiFetch<void>('/api/system-settings/app-settings', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
    actor,
  });
}

export interface SocialStats {
  eligible: boolean;
  postCount: number | null;
  likes: number | null;
  shares: number | null;
  comments: number | null;
  clicks: number | null;
  errorMessage: string | null;
}

export function getProjectSocialStats(projectId: number, actor?: ActorInfo): Promise<SocialStats> {
  return apiFetch<SocialStats>(`/api/projects/${projectId}/dashboard/social-stats`, { actor });
}

export interface PlanChatMessage {
  role: "user" | "assistant";
  content: string;
}

export interface PlanChatResponse {
  reply: string;
  sessionId: number;
}

export interface ArticlePlanSessionSummary {
  id: number;
  title: string;
  githubIssueNumber: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface ArticlePlanSessionDetail {
  id: number;
  title: string;
  githubIssueNumber: number | null;
  history: PlanChatMessage[];
  createdAt: string;
  updatedAt: string;
}

export type RepositoryIssueState = "open" | "closed" | "all";

export interface RepositoryIssue {
  number: number;
  title: string;
  htmlUrl: string;
  state: string;
}

export interface SuggestTitlesResponse {
  titles: string[];
}

export interface SuggestStructureResponse {
  structure: string;
}

export interface AcceptStructureResponse {
  issueNumber: number;
  issueUrl: string;
}

export interface AcceptPlanResultItem {
  title: string;
  issueNumber?: number;
  issueUrl?: string;
  error?: string;
}

export interface AcceptPlanResponse {
  results: AcceptPlanResultItem[];
}

export function sendArticlePlanChatMessage(
  projectId: number,
  data: {
    history: PlanChatMessage[];
    message: string;
    sessionId?: number | null;
    githubIssueNumber?: number | null;
  },
  actor?: ActorInfo
): Promise<PlanChatResponse> {
  return apiFetch<PlanChatResponse>(`/api/projects/${projectId}/article-plan/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
    actor,
  });
}

export function listArticlePlanSessions(
  projectId: number,
  actor?: ActorInfo
): Promise<ArticlePlanSessionSummary[]> {
  return apiFetch<ArticlePlanSessionSummary[]>(`/api/projects/${projectId}/article-plan/sessions`, { actor });
}

export function getArticlePlanSession(
  projectId: number,
  sessionId: number,
  actor?: ActorInfo
): Promise<ArticlePlanSessionDetail> {
  return apiFetch<ArticlePlanSessionDetail>(
    `/api/projects/${projectId}/article-plan/sessions/${sessionId}`,
    { actor }
  );
}

export function getArticlePlanSessionByIssue(
  projectId: number,
  issueNumber: number,
  actor?: ActorInfo
): Promise<ArticlePlanSessionDetail> {
  return apiFetch<ArticlePlanSessionDetail>(
    `/api/projects/${projectId}/article-plan/sessions/by-issue/${issueNumber}`,
    { actor }
  );
}

export interface IssueDescriptionResponse {
  body: string;
}

export function getArticlePlanIssueDescription(
  projectId: number,
  issueNumber: number,
  actor?: ActorInfo
): Promise<IssueDescriptionResponse> {
  return apiFetch<IssueDescriptionResponse>(
    `/api/projects/${projectId}/article-plan/issues/${issueNumber}/description`,
    { actor }
  );
}

export function listArticlePlanIssues(
  projectId: number,
  state: RepositoryIssueState,
  actor?: ActorInfo
): Promise<RepositoryIssue[]> {
  return apiFetch<RepositoryIssue[]>(
    `/api/projects/${projectId}/article-plan/issues?state=${state}`,
    { actor }
  );
}

export function suggestArticlePlanTitles(
  projectId: number,
  data: { history: PlanChatMessage[] },
  actor?: ActorInfo
): Promise<SuggestTitlesResponse> {
  return apiFetch<SuggestTitlesResponse>(`/api/projects/${projectId}/article-plan/suggest-titles`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
    actor,
  });
}

export function acceptArticlePlan(
  projectId: number,
  data: { titles: string[] },
  actor?: ActorInfo
): Promise<AcceptPlanResponse> {
  return apiFetch<AcceptPlanResponse>(`/api/projects/${projectId}/article-plan/accept`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
    actor,
  });
}

export function suggestArticleStructure(
  projectId: number,
  data: { history: PlanChatMessage[] },
  actor?: ActorInfo
): Promise<SuggestStructureResponse> {
  return apiFetch<SuggestStructureResponse>(`/api/projects/${projectId}/article-plan/suggest-structure`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
    actor,
  });
}

export function acceptArticleStructure(
  projectId: number,
  issueNumber: number,
  data: { structure: string },
  actor?: ActorInfo
): Promise<AcceptStructureResponse> {
  return apiFetch<AcceptStructureResponse>(
    `/api/projects/${projectId}/article-plan/issues/${issueNumber}/accept-structure`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
      actor,
    }
  );
}

export function updateMasterEnvironment(
  id: number,
  masterEnvironment: "test" | "production",
  actor?: ActorInfo
): Promise<Project> {
  return apiFetch<Project>(`/api/projects/${id}/master-environment`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ masterEnvironment }),
    actor,
  });
}

export function bindProjectEnvironment(
  id: number,
  environment: ProjectEnvironment,
  siteId: number,
  actor?: ActorInfo
): Promise<Project> {
  return apiFetch<Project>(`/api/projects/${id}/environments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ environment, siteId }),
    actor,
  });
}

export function unbindProjectEnvironment(
  id: number,
  environment: ProjectEnvironment,
  actor?: ActorInfo
): Promise<Project> {
  return apiFetch<Project>(`/api/projects/${id}/environments/${environment}`, { method: 'DELETE', actor });
}

export type EnvironmentSyncTarget = "themes" | "plugins" | "media" | "db";

export function syncProjectEnvironment(
  id: number,
  input: { from: ProjectEnvironment; to: ProjectEnvironment; targets: EnvironmentSyncTarget[] },
  actor?: ActorInfo
): Promise<void> {
  return apiFetch<void>(`/api/projects/${id}/environments/sync`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export type BulkOperationType =
  | "CATEGORY_CREATE"
  | "CATEGORY_EDIT"
  | "CATEGORY_DELETE"
  | "TAG_CREATE"
  | "TAG_EDIT"
  | "TAG_DELETE"
  | "PLUGIN_INSTALL"
  | "PLUGIN_ACTIVATE"
  | "PLUGIN_DEACTIVATE"
  | "PLUGIN_DELETE"
  | "THEME_INSTALL"
  | "THEME_ACTIVATE"
  | "THEME_DELETE"
  | "CATEGORY_FETCH"
  | "TAG_FETCH"
  | "PLUGIN_FETCH"
  | "THEME_FETCH"
  | "POST_FETCH"
  | "MEDIA_UPLOAD"
  | "POST_DELETE"
  | "POST_STATUS_UPDATE";
export type ZipInstallOperationType = "PLUGIN_INSTALL" | "THEME_INSTALL";
export type BulkOperationSourceType = "SLUG" | "ZIP";
export type BulkOperationStatus = "SUCCESS" | "SKIPPED" | "FAILED";
export type BulkOperationLogLevel = "INFO" | "WARNING" | "ERROR";

export interface BulkOperationLog {
  operationType: BulkOperationType;
  sourceType: BulkOperationSourceType;
  value: string;
  categorySlug: string | null;
  categoryParentSlug: string | null;
  categoryTargetSlug: string | null;
  categoryDescription: string | null;
  originalFilename: string | null;
  postStatus: string | null;
  environment: ProjectEnvironment;
  status: BulkOperationStatus;
  level: BulkOperationLogLevel;
  errorMessage: string | null;
  stackTrace: string | null;
  createdAt: string;
}

export interface TermEnvironmentValue {
  available: boolean;
  error: boolean;
  errorMessage: string | null;
  slug: string | null;
  parentSlug: string | null;
  description: string | null;
}

export interface TermComparisonRow {
  name: string;
  slug: string;
  local: TermEnvironmentValue;
  test: TermEnvironmentValue;
  production: TermEnvironmentValue;
}

export interface TermComparisonPage {
  items: TermComparisonRow[];
  page: number;
  size: number;
  totalCount: number;
  masterEnvironment: "test" | "production";
}

export function listCategoryComparison(
  projectId: number,
  page: number,
  actor?: ActorInfo
): Promise<TermComparisonPage> {
  return apiFetch<TermComparisonPage>(
    `/api/projects/${projectId}/bulk-management/categories/comparison?page=${page}`,
    { actor }
  );
}

export function listTagComparison(
  projectId: number,
  page: number,
  actor?: ActorInfo
): Promise<TermComparisonPage> {
  return apiFetch<TermComparisonPage>(
    `/api/projects/${projectId}/bulk-management/tags/comparison?page=${page}`,
    { actor }
  );
}

export function applyToEnvironment(
  projectId: number,
  input: {
    environment: ProjectEnvironment;
    operationType: BulkOperationType;
    value?: string;
    categorySlug?: string;
    categoryParentSlug?: string;
    categoryDescription?: string;
    categoryTargetSlug?: string;
  },
  actor?: ActorInfo
): Promise<BulkOperationLog> {
  return apiFetch<BulkOperationLog>(`/api/projects/${projectId}/bulk-management/apply`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function applyToAllEnvironments(
  projectId: number,
  input: {
    operationType: BulkOperationType;
    value: string;
  },
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/apply-all`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function syncCategoryToMaster(
  projectId: number,
  slug: string,
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/categories/sync`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ slug }),
    actor,
  });
}

export function deleteCategoryEverywhere(
  projectId: number,
  slug: string,
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/categories/delete-all`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ slug }),
    actor,
  });
}

export function syncTagToMaster(
  projectId: number,
  slug: string,
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/tags/sync`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ slug }),
    actor,
  });
}

export interface EditTermInput {
  targetSlug: string;
  value: string;
  slug: string;
  parentSlug?: string;
  description?: string;
}

export function editCategoryAndSync(
  projectId: number,
  input: EditTermInput,
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/categories/edit-sync`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function editTagAndSync(
  projectId: number,
  input: EditTermInput,
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/tags/edit-sync`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function syncAllCategoriesToMaster(
  projectId: number,
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/categories/sync-all`, {
    method: 'POST',
    actor,
  });
}

export function syncAllTagsToMaster(
  projectId: number,
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/tags/sync-all`, {
    method: 'POST',
    actor,
  });
}

export function deleteTagEverywhere(
  projectId: number,
  slug: string,
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/tags/delete-all`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ slug }),
    actor,
  });
}

export type PluginThemeStatus = "NOT_INSTALLED" | "INACTIVE" | "ACTIVE";

export interface StatusEnvironmentValue {
  available: boolean;
  error: boolean;
  errorMessage: string | null;
  status: PluginThemeStatus | null;
}

export interface StatusComparisonRow {
  slug: string;
  local: StatusEnvironmentValue;
  test: StatusEnvironmentValue;
  production: StatusEnvironmentValue;
}

export interface StatusComparisonPage {
  items: StatusComparisonRow[];
  page: number;
  size: number;
  totalCount: number;
  masterEnvironment: "test" | "production";
}

export function listPluginComparison(
  projectId: number,
  page: number,
  actor?: ActorInfo
): Promise<StatusComparisonPage> {
  return apiFetch<StatusComparisonPage>(
    `/api/projects/${projectId}/bulk-management/plugins/comparison?page=${page}`,
    { actor }
  );
}

export function listThemeComparison(
  projectId: number,
  page: number,
  actor?: ActorInfo
): Promise<StatusComparisonPage> {
  return apiFetch<StatusComparisonPage>(
    `/api/projects/${projectId}/bulk-management/themes/comparison?page=${page}`,
    { actor }
  );
}

export function reconcilePluginState(
  projectId: number,
  input: { slug: string; changes: { environment: ProjectEnvironment; desiredStatus: PluginThemeStatus }[] },
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/plugins/reconcile`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function reconcileThemeState(
  projectId: number,
  input: { slug: string; changes: { environment: ProjectEnvironment; desiredStatus: PluginThemeStatus }[] },
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/themes/reconcile`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function deletePluginEverywhere(
  projectId: number,
  slug: string,
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/plugins/delete-all`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ slug }),
    actor,
  });
}

export function deleteThemeEverywhere(
  projectId: number,
  slug: string,
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/themes/delete-all`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ slug }),
    actor,
  });
}

export type PostType = "post" | "page";

export interface PostEnvironmentValue {
  available: boolean;
  error: boolean;
  errorMessage: string | null;
  postId: string | null;
  title: string | null;
  status: string | null;
}

export interface PostComparisonRow {
  slug: string;
  local: PostEnvironmentValue;
  test: PostEnvironmentValue;
  production: PostEnvironmentValue;
}

export interface PostComparisonPage {
  items: PostComparisonRow[];
  page: number;
  size: number;
  totalCount: number;
  postType: PostType;
}

export function listPostComparison(
  projectId: number,
  postType: PostType,
  page: number,
  actor?: ActorInfo
): Promise<PostComparisonPage> {
  return apiFetch<PostComparisonPage>(
    `/api/projects/${projectId}/bulk-management/posts/comparison?postType=${postType}&page=${page}`,
    { actor }
  );
}

export function deletePostEverywhere(
  projectId: number,
  postType: PostType,
  slug: string,
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(
    `/api/projects/${projectId}/bulk-management/posts/delete-all?postType=${postType}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug }),
      actor,
    }
  );
}

export function updatePostStatusEverywhere(
  projectId: number,
  postType: PostType,
  slug: string,
  status: string,
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(
    `/api/projects/${projectId}/bulk-management/posts/status-update?postType=${postType}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug, status }),
      actor,
    }
  );
}

export function runBulkOperationUpload(
  projectId: number,
  input: { operationType: ZipInstallOperationType; file: File },
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  const formData = new FormData();
  formData.append('operationType', input.operationType);
  formData.append('file', input.file);
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/upload`, {
    method: 'POST',
    body: formData,
    actor,
  });
}

export interface ProjectUser {
  userId: number;
  email: string | null;
  displayName: string | null;
  wpRole: string;
}

export function listProjectUsers(projectId: number, actor?: ActorInfo): Promise<ProjectUser[]> {
  return apiFetch<ProjectUser[]>(`/api/projects/${projectId}/users`, { actor });
}

export function addProjectUser(
  projectId: number,
  userId: number,
  wpRole: string,
  actor?: ActorInfo
): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/users`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId, wpRole }),
    actor,
  });
}

export function updateProjectUserRole(
  projectId: number,
  userId: number,
  wpRole: string,
  actor?: ActorInfo
): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/users/${userId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ wpRole }),
    actor,
  });
}

export function removeProjectUser(projectId: number, userId: number, actor?: ActorInfo): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/users/${userId}`, { method: 'DELETE', actor });
}

export interface ProjectUserSummary {
  projectId: number;
  userId: number;
  wpRole: string;
}

export function listAllProjectUsers(actor?: ActorInfo): Promise<ProjectUserSummary[]> {
  return apiFetch<ProjectUserSummary[]>('/api/project-users', { actor });
}

export interface GenerationJobDetail {
  id: number;
  type: string;
  status: string;
  requestPayload: string | null;
  resultPayload: string | null;
  createdAt: string;
  updatedAt: string;
}

export function getGenerationJob(id: number, actor?: ActorInfo): Promise<GenerationJobDetail> {
  return apiFetch<GenerationJobDetail>(`/api/generation-jobs/${id}`, { actor });
}

export interface LlmModelListResponse {
  availableModels: string[];
  selected: string;
}

export function listLlmModels(projectId: number, actor?: ActorInfo): Promise<LlmModelListResponse> {
  return apiFetch<LlmModelListResponse>(`/api/projects/${projectId}/ai-models/llm/models`, { actor });
}

export function selectLlmModel(
  projectId: number,
  modelName: string,
  actor?: ActorInfo
): Promise<LlmModelListResponse> {
  return apiFetch<LlmModelListResponse>(`/api/projects/${projectId}/ai-models/llm/models/selection`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ modelName }),
    actor,
  });
}

export interface ComfyUiCheckpointListResponse {
  checkpoints: string[];
  selected: string;
}

export function listComfyUiCheckpoints(
  projectId: number,
  actor?: ActorInfo
): Promise<ComfyUiCheckpointListResponse> {
  return apiFetch<ComfyUiCheckpointListResponse>(`/api/projects/${projectId}/ai-models/comfyui/checkpoints`, {
    actor,
  });
}

export function selectComfyUiCheckpoint(
  projectId: number,
  checkpointName: string,
  actor?: ActorInfo
): Promise<ComfyUiCheckpointListResponse> {
  return apiFetch<ComfyUiCheckpointListResponse>(
    `/api/projects/${projectId}/ai-models/comfyui/checkpoints/selection`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ checkpointName }),
      actor,
    }
  );
}

export function installComfyUiCheckpoint(
  projectId: number,
  downloadUrl: string,
  fileName: string,
  actor?: ActorInfo
): Promise<GenerationJob> {
  return apiFetch<GenerationJob>(`/api/projects/${projectId}/ai-models/comfyui/checkpoints/install`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ downloadUrl, fileName }),
    actor,
  });
}

export function deleteComfyUiCheckpoint(
  projectId: number,
  fileName: string,
  actor?: ActorInfo
): Promise<GenerationJob> {
  return apiFetch<GenerationJob>(
    `/api/projects/${projectId}/ai-models/comfyui/checkpoints/${encodeURIComponent(fileName)}`,
    { method: 'DELETE', actor }
  );
}

/**
 * VSCode拡張機能(.vsix)をAPIサーバーからダウンロードする。APIサーバー側で
 * オンデマンドビルド(初回は数十秒かかる場合がある)されるため、apiFetchのJSON前提の
 * エラーハンドリングは使わずバイナリを直接扱う。
 */
export async function downloadVscodeExtension(): Promise<{ body: ArrayBuffer; filename: string }> {
  const res = await fetch(`${serverUrl()}/api/system/vscode-extension`, {
    headers: { 'X-API-Key': await currentApiKey() },
    cache: 'no-store',
  });
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`APIエラー (${res.status}): ${body || res.statusText}`);
  }
  const disposition = res.headers.get('content-disposition') ?? '';
  const match = disposition.match(/filename="([^"]+)"/);
  const filename = match ? match[1] : 'letsblog-vscode.vsix';
  return { body: await res.arrayBuffer(), filename };
}

export interface ConnectedServiceStatus {
  id: string;
  name: string;
  status: "NORMAL" | "WARNING" | "ERROR";
}

export function getConnectedServiceStatuses(): Promise<ConnectedServiceStatus[]> {
  return apiFetch<ConnectedServiceStatus[]>('/api/dashboard/service-status');
}

export interface ConnectedServiceStatusDetail {
  id: string;
  name: string;
  status: "NORMAL" | "WARNING" | "ERROR";
  responseTimeMs: number;
  httpStatus: number | null;
  errorMessage: string | null;
  targetUrl: string | null;
  checkedAt: string;
}

/** 応答時間・エラー内容・チェック対象URLなどの詳細診断情報(issue #199)。admin限定、非adminが呼ぶと403になる。 */
export function getConnectedServiceStatusDetail(actor?: ActorInfo): Promise<ConnectedServiceStatusDetail[]> {
  return apiFetch<ConnectedServiceStatusDetail[]>('/api/dashboard/service-status/detail', { actor });
}

/**
 * 接続サービスの稼働状況をSSEで受け取るためのアップストリーム接続(issue #198)。
 * apiFetch()はJSONレスポンス前提のためストリーミングには使えず、ここだけ直接fetchする。
 * 呼び出し元(Route Handler)がbodyをそのままブラウザへ中継する。
 */
export async function streamConnectedServiceStatuses(): Promise<Response> {
  const apiKey = await currentApiKey();
  return fetch(`${serverUrl()}/api/dashboard/service-status/stream`, {
    headers: { 'X-API-Key': apiKey, Accept: 'text/event-stream' },
    cache: 'no-store',
  });
}

/** このアプリを構成するDockerコンテナ(lbs-*)の稼働状況(issue #280)。 */
export interface ContainerStatus {
  id: string;
  name: string;
  status: "NORMAL" | "WARNING" | "ERROR";
  state: string;
  detail: string;
}

export function getContainerStatuses(): Promise<ContainerStatus[]> {
  return apiFetch<ContainerStatus[]>('/api/dashboard/container-status');
}

/** コンテナ稼働状況をSSEで受け取るためのアップストリーム接続(issue #280)。 */
export async function streamContainerStatuses(): Promise<Response> {
  const apiKey = await currentApiKey();
  return fetch(`${serverUrl()}/api/dashboard/container-status/stream`, {
    headers: { 'X-API-Key': apiKey, Accept: 'text/event-stream' },
    cache: 'no-store',
  });
}
