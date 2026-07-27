import 'server-only';

export interface Site {
  id: number;
  name: string;
  siteKey: string;
  baseUrl: string;
  wpUsername: string;
  createdAt: string;
  updatedAt: string;
}

export interface PostSummary {
  id: number;
  siteId: number;
  siteName: string;
  wpPostId: number;
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
  baseUrl: string;
  wpUsername: string;
  wpAppPassword: string;
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
