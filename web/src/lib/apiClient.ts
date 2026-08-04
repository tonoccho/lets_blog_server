import 'server-only';
import { cookies, headers } from 'next/headers';
import { getToken } from 'next-auth/jwt';

export type CmsType = "WORDPRESS" | "MICROCMS";

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
 * ログイン中ユーザーのAPIキーをNextAuthのJWT(HttpOnly cookie)から取得する。
 * next-auth/jwtのgetToken()はreq.cookies/req.headersしか参照しないため、
 * NextRequestが無いServer Component/Server Actionからでもnext/headersのcookies()/headers()を
 * そのまま渡せる(型定義上はNextRequest等を期待しているため as any で吸収する)。
 */
async function currentApiKey(): Promise<string> {
  const token = await getToken({
    req: { cookies: await cookies(), headers: await headers() } as unknown as Parameters<typeof getToken>[0]['req'],
    secret: process.env.NEXTAUTH_SECRET,
  });
  if (!token?.apiKey) {
    throw new Error('ログインしていないか、APIキーが未取得です。再度ログインしてください。');
  }
  return token.apiKey;
}

export interface ActorInfo {
  id: number;
  role: "admin" | "user";
}

interface ApiFetchInit extends RequestInit {
  actor?: ActorInfo;
  /** ログイン前でも呼べる公開エンドポイント(signup/setup/setup-status)向け。既定はtrue。 */
  requiresAuth?: boolean;
}

async function apiFetch<T>(path: string, init?: ApiFetchInit): Promise<T> {
  const { actor, requiresAuth = true, ...requestInit } = init ?? {};
  const res = await fetch(`${serverUrl()}${path}`, {
    ...requestInit,
    headers: {
      ...(requiresAuth ? { 'X-API-Key': await currentApiKey() } : {}),
      ...(actor ? { 'X-Actor-Id': String(actor.id), 'X-Actor-Role': actor.role } : {}),
      ...(requestInit.headers ?? {}),
    },
    cache: 'no-store',
  });

  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new Error(`APIエラー (${res.status}): ${body || res.statusText}`);
  }
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

export function listPosts(): Promise<PostSummary[]> {
  return apiFetch<PostSummary[]>('/api/posts');
}

export function listGenerationJobs(): Promise<GenerationJob[]> {
  return apiFetch<GenerationJob[]>('/api/generation-jobs');
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

export interface CustomTag {
  id: number;
  tagName: string;
  htmlTemplate: string;
  description: string | null;
  cssContent: string | null;
  projectId: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface CustomTagInput {
  tagName: string;
  htmlTemplate: string;
  description?: string;
  cssContent?: string;
  projectId?: number | null;
}

export function listCustomTags(actor?: ActorInfo, projectId?: number): Promise<CustomTag[]> {
  const query = projectId != null ? `?projectId=${projectId}` : '';
  return apiFetch<CustomTag[]>(`/api/custom-tags${query}`, { actor });
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

export interface AuditLogEntry {
  id: number;
  userId: number | null;
  action: string;
  resourceType: string | null;
  resourceId: number | null;
  changes: string | null;
  remoteIp: string | null;
  userAgent: string | null;
  createdAt: string;
}

export interface AuditLogPage {
  content: AuditLogEntry[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export function listAuditLogs(
  params: { userId?: number; action?: string; page?: number; size?: number },
  actor: ActorInfo
): Promise<AuditLogPage> {
  const query = new URLSearchParams();
  if (params.userId != null) query.set('userId', String(params.userId));
  if (params.action) query.set('action', params.action);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 20));
  query.set('sort', 'createdAt,desc');

  return apiFetch<AuditLogPage>(`/api/audit-logs?${query.toString()}`, { actor });
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
  | "THEME_FETCH";
export type ZipInstallOperationType = "PLUGIN_INSTALL" | "THEME_INSTALL";
export type BulkOperationSourceType = "SLUG" | "ZIP";
export type BulkOperationStatus = "SUCCESS" | "SKIPPED" | "FAILED";
export type BulkOperationLogLevel = "INFO" | "WARNING" | "ERROR";

export interface BulkOperationLog {
  id: number;
  operationType: BulkOperationType;
  sourceType: BulkOperationSourceType;
  value: string;
  categorySlug: string | null;
  categoryParentSlug: string | null;
  categoryTargetSlug: string | null;
  categoryDescription: string | null;
  originalFilename: string | null;
  environment: ProjectEnvironment;
  status: BulkOperationStatus;
  level: BulkOperationLogLevel;
  errorMessage: string | null;
  stackTrace: string | null;
  isReplay: boolean;
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

export function replayBulkOperations(
  projectId: number,
  input: { environment: ProjectEnvironment },
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/replay`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export interface BulkOperationLogFilter {
  operationType?: BulkOperationType;
  environment?: ProjectEnvironment;
  level?: BulkOperationLogLevel;
}

export function listBulkOperationLogs(
  projectId: number,
  actor?: ActorInfo,
  filter?: BulkOperationLogFilter
): Promise<BulkOperationLog[]> {
  const query = new URLSearchParams();
  if (filter?.operationType) query.set("operationType", filter.operationType);
  if (filter?.environment) query.set("environment", filter.environment);
  if (filter?.level) query.set("level", filter.level);
  const qs = query.toString();
  return apiFetch<BulkOperationLog[]>(
    `/api/projects/${projectId}/bulk-management/logs${qs ? `?${qs}` : ""}`,
    { actor }
  );
}

export function clearBulkOperationLogs(projectId: number, actor?: ActorInfo): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/bulk-management/logs`, { method: "DELETE", actor });
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

export interface OllamaModelInfo {
  name: string;
  sizeBytes: number;
  modifiedAt: string | null;
}

export interface OllamaModelListResponse {
  models: OllamaModelInfo[];
  selected: string;
}

export function listOllamaModels(projectId: number, actor?: ActorInfo): Promise<OllamaModelListResponse> {
  return apiFetch<OllamaModelListResponse>(`/api/projects/${projectId}/ai-models/ollama/models`, { actor });
}

export function selectOllamaModel(
  projectId: number,
  modelName: string,
  actor?: ActorInfo
): Promise<OllamaModelListResponse> {
  return apiFetch<OllamaModelListResponse>(`/api/projects/${projectId}/ai-models/ollama/models/selection`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ modelName }),
    actor,
  });
}

export function installOllamaModel(
  projectId: number,
  modelName: string,
  actor?: ActorInfo
): Promise<GenerationJob> {
  return apiFetch<GenerationJob>(`/api/projects/${projectId}/ai-models/ollama/models/install`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ modelName }),
    actor,
  });
}

export function deleteOllamaModel(
  projectId: number,
  modelName: string,
  actor?: ActorInfo
): Promise<GenerationJob> {
  return apiFetch<GenerationJob>(
    `/api/projects/${projectId}/ai-models/ollama/models?modelName=${encodeURIComponent(modelName)}`,
    { method: 'DELETE', actor }
  );
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
