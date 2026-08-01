import 'server-only';

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

function apiKey(): string {
  const key = process.env.LETS_BLOG_API_KEY;
  if (!key) {
    throw new Error('環境変数 LETS_BLOG_API_KEY が設定されていません。');
  }
  return key;
}

export interface ActorInfo {
  id: number;
  role: "admin" | "user";
}

interface ApiFetchInit extends RequestInit {
  actor?: ActorInfo;
}

async function apiFetch<T>(path: string, init?: ApiFetchInit): Promise<T> {
  const { actor, ...requestInit } = init ?? {};
  const res = await fetch(`${serverUrl()}${path}`, {
    ...requestInit,
    headers: {
      'X-API-Key': apiKey(),
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
}

export function checkSiteConnection(id: number): Promise<SiteConnectionCheckResult> {
  return apiFetch(`/api/sites/${id}/test-connection`, { method: 'POST' });
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
      'X-API-Key': apiKey(),
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ email, password }),
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
      'X-API-Key': apiKey(),
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ userId, code }),
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
  });
}

export function getSetupStatus(): Promise<{ needsSetup: boolean }> {
  return apiFetch<{ needsSetup: boolean }>('/api/auth/setup-status');
}

export function setupInitialAdmin(email: string, password: string): Promise<AuthenticatedUser> {
  return apiFetch<AuthenticatedUser>('/api/auth/setup', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
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
  avatarUrl: string | null;
  department: string | null;
  position: string | null;
  socialLinks: SocialLinks | null;
  customLinks: CustomLink[] | null;
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

export type BulkOperationType = "CATEGORY" | "PLUGIN" | "THEME";
export type BulkOperationSourceType = "SLUG" | "ZIP";
export type BulkOperationStatus = "SUCCESS" | "SKIPPED" | "FAILED";

export interface BulkOperationLog {
  id: number;
  operationType: BulkOperationType;
  sourceType: BulkOperationSourceType;
  value: string;
  categorySlug: string | null;
  categoryParentName: string | null;
  categoryDescription: string | null;
  originalFilename: string | null;
  environment: ProjectEnvironment;
  status: BulkOperationStatus;
  errorMessage: string | null;
  isReplay: boolean;
  createdAt: string;
}

export function runBulkOperation(
  projectId: number,
  input: {
    operationType: BulkOperationType;
    value: string;
    categorySlug?: string;
    categoryParentName?: string;
    categoryDescription?: string;
  },
  actor?: ActorInfo
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
    actor,
  });
}

export function runBulkOperationUpload(
  projectId: number,
  input: { operationType: "PLUGIN" | "THEME"; file: File },
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

export function listBulkOperationLogs(projectId: number, actor?: ActorInfo): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/logs`, { actor });
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
