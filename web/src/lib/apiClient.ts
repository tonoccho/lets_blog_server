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

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${serverUrl()}${path}`, {
    ...init,
    headers: {
      'X-API-Key': apiKey(),
      ...(init?.headers ?? {}),
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

export function registerSite(input: SiteRegisterInput): Promise<Site> {
  return apiFetch<Site>('/api/sites', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
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

export function createUser(input: UserCreateInput): Promise<AppUser> {
  return apiFetch<AppUser>('/api/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function updateUserRole(id: number, role: "admin" | "user"): Promise<AppUser> {
  return apiFetch<AppUser>(`/api/users/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role }),
  });
}

export function deleteUser(id: number): Promise<void> {
  return apiFetch<void>(`/api/users/${id}`, { method: 'DELETE' });
}
