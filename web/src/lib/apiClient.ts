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

export interface AuthenticatedUser {
  id: number;
  email: string;
  role: "admin" | "user";
}

export interface AppUser {
  id: number;
  email: string;
  role: "admin" | "user";
  createdAt: string;
  updatedAt: string;
}

export interface UserCreateInput {
  email: string;
  password: string;
  role: "admin" | "user";
}

function serverUrl(): string {
  return (process.env.LETS_BLOG_API_URL ?? 'http://localhost:8080').replace(/\/+$/, '');
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
  return (await res.json()) as T;
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

export function listPosts(): Promise<PostSummary[]> {
  return apiFetch<PostSummary[]>('/api/posts');
}

export function listGenerationJobs(): Promise<GenerationJob[]> {
  return apiFetch<GenerationJob[]>('/api/generation-jobs');
}

export async function login(email: string, password: string): Promise<AuthenticatedUser | null> {
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
  return (await res.json()) as AuthenticatedUser;
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
