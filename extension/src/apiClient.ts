import fetch from 'node-fetch';
import FormData from 'form-data';
import * as fs from 'fs';
import { LocalImageReference } from './frontMatter';

export interface PublishParams {
  site: string;
  title: string;
  slug?: string;
  status?: string;
  categories?: string[];
  tags?: string[];
  wpPostId?: number | null;
  markdown: string;
  images: LocalImageReference[];
}

export interface PublishResult {
  wpPostId: number;
  wpPostUrl: string;
  status: string;
}

export interface AiDraftResult {
  result: string;
}

export interface AiTagsResult {
  categories: string[];
  tags: string[];
}

export interface AiImageResult {
  fileName: string;
  dataBase64: string;
  mimeType: string;
}

class ApiError extends Error {}

async function assertOk(res: import('node-fetch').Response): Promise<void> {
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new ApiError(`APIエラー (${res.status}): ${body || res.statusText}`);
  }
}

export async function publishPost(serverUrl: string, apiKey: string, params: PublishParams): Promise<PublishResult> {
  const form = new FormData();
  form.append('site', params.site);
  form.append('title', params.title);
  if (params.slug) form.append('slug', params.slug);
  form.append('status', params.status ?? 'draft');
  for (const category of params.categories ?? []) {
    form.append('categories', category);
  }
  for (const tag of params.tags ?? []) {
    form.append('tags', tag);
  }
  if (params.wpPostId) {
    form.append('wpPostId', String(params.wpPostId));
  }
  form.append('markdown', params.markdown);
  for (const image of params.images) {
    form.append('images', fs.createReadStream(image.absolutePath), { filename: image.reference });
  }

  const res = await fetch(`${serverUrl}/api/posts/publish`, {
    method: 'POST',
    headers: { 'X-API-Key': apiKey, ...form.getHeaders() },
    body: form,
  });
  await assertOk(res);
  return (await res.json()) as PublishResult;
}

export async function listSites(serverUrl: string, apiKey: string): Promise<{ id: number; name: string; siteKey: string }[]> {
  const res = await fetch(`${serverUrl}/api/sites`, {
    headers: { 'X-API-Key': apiKey },
  });
  await assertOk(res);
  return (await res.json()) as { id: number; name: string; siteKey: string }[];
}

export async function askAi(
  serverUrl: string,
  apiKey: string,
  mode: 'draft' | 'proofread' | 'summarize',
  text: string
): Promise<AiDraftResult> {
  const res = await fetch(`${serverUrl}/api/ai/draft`, {
    method: 'POST',
    headers: { 'X-API-Key': apiKey, 'Content-Type': 'application/json' },
    body: JSON.stringify({ mode, text }),
  });
  await assertOk(res);
  return (await res.json()) as AiDraftResult;
}

export async function suggestTags(serverUrl: string, apiKey: string, text: string): Promise<AiTagsResult> {
  const res = await fetch(`${serverUrl}/api/ai/tags`, {
    method: 'POST',
    headers: { 'X-API-Key': apiKey, 'Content-Type': 'application/json' },
    body: JSON.stringify({ text }),
  });
  await assertOk(res);
  return (await res.json()) as AiTagsResult;
}

export async function generateImage(serverUrl: string, apiKey: string, prompt: string): Promise<AiImageResult> {
  const res = await fetch(`${serverUrl}/api/ai/image`, {
    method: 'POST',
    headers: { 'X-API-Key': apiKey, 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt }),
  });
  await assertOk(res);
  return (await res.json()) as AiImageResult;
}
