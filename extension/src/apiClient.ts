import fetch from 'node-fetch';
import FormData from 'form-data';
import * as fs from 'fs';
import { LocalImageReference } from './frontMatter';
import { Actor } from './config';

function buildHeaders(apiKey: string, actor?: Actor, contentType?: string): Record<string, string> {
  const headers: Record<string, string> = { 'X-API-Key': apiKey };
  if (actor) {
    headers['X-Actor-Id'] = String(actor.id);
    headers['X-Actor-Role'] = actor.role;
  }
  if (contentType) {
    headers['Content-Type'] = contentType;
  }
  return headers;
}

export interface PublishParams {
  site: string;
  title: string;
  slug?: string;
  status?: string;
  categories?: string[];
  tags?: string[];
  wpPostId?: string | null;
  markdown: string;
  images: LocalImageReference[];
}

export interface PublishResult {
  wpPostId: string;
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

export async function publishPost(
  serverUrl: string,
  apiKey: string,
  params: PublishParams,
  actor?: Actor
): Promise<PublishResult> {
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
    headers: { ...buildHeaders(apiKey, actor), ...form.getHeaders() },
    body: form,
  });
  await assertOk(res);
  return (await res.json()) as PublishResult;
}

export async function listSites(
  serverUrl: string,
  apiKey: string,
  actor?: Actor
): Promise<{ id: number; name: string; siteKey: string }[]> {
  const res = await fetch(`${serverUrl}/api/sites`, {
    headers: buildHeaders(apiKey, actor),
  });
  await assertOk(res);
  return (await res.json()) as { id: number; name: string; siteKey: string }[];
}

export async function askAi(
  serverUrl: string,
  apiKey: string,
  mode: 'draft' | 'proofread' | 'summarize',
  text: string,
  actor?: Actor
): Promise<AiDraftResult> {
  const res = await fetch(`${serverUrl}/api/ai/draft`, {
    method: 'POST',
    headers: buildHeaders(apiKey, actor, 'application/json'),
    body: JSON.stringify({ mode, text }),
  });
  await assertOk(res);
  return (await res.json()) as AiDraftResult;
}

export async function suggestTags(
  serverUrl: string,
  apiKey: string,
  text: string,
  actor?: Actor
): Promise<AiTagsResult> {
  const res = await fetch(`${serverUrl}/api/ai/tags`, {
    method: 'POST',
    headers: buildHeaders(apiKey, actor, 'application/json'),
    body: JSON.stringify({ text }),
  });
  await assertOk(res);
  return (await res.json()) as AiTagsResult;
}

export async function generateImage(
  serverUrl: string,
  apiKey: string,
  prompt: string,
  actor?: Actor
): Promise<AiImageResult> {
  const res = await fetch(`${serverUrl}/api/ai/image`, {
    method: 'POST',
    headers: buildHeaders(apiKey, actor, 'application/json'),
    body: JSON.stringify({ prompt }),
  });
  await assertOk(res);
  return (await res.json()) as AiImageResult;
}

export async function listUsers(serverUrl: string, apiKey: string): Promise<Actor[]> {
  const res = await fetch(`${serverUrl}/api/users`, {
    headers: buildHeaders(apiKey),
  });
  await assertOk(res);
  return (await res.json()) as Actor[];
}

export interface ProjectSummary {
  id: number;
  name: string;
  slug: string;
  githubRepository?: string | null;
}

export async function listProjects(serverUrl: string, apiKey: string, actor?: Actor): Promise<ProjectSummary[]> {
  const res = await fetch(`${serverUrl}/api/projects`, {
    headers: buildHeaders(apiKey, actor),
  });
  await assertOk(res);
  return (await res.json()) as ProjectSummary[];
}

export interface RepositoryIssue {
  number: number;
  title: string;
  htmlUrl: string;
  state: string;
  assignees?: string[];
}

/** リポジトリのissue一覧のうち、未割り当て(assigneesが空)のものだけを返す。 */
export async function listUnassignedIssues(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  state: string = 'open'
): Promise<RepositoryIssue[]> {
  const res = await fetch(`${serverUrl}/api/projects/${projectId}/article-plan/issues?state=${state}`, {
    headers: buildHeaders(apiKey, actor),
  });
  await assertOk(res);
  const issues = (await res.json()) as RepositoryIssue[];
  return issues.filter((i) => !i.assignees || i.assignees.length === 0);
}

export interface PlanChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

export interface PlanChatRequestParams {
  history: PlanChatMessage[];
  message: string;
  sessionId?: number;
  githubIssueNumber?: number;
}

export interface PlanChatResult {
  reply: string;
  sessionId: number;
}

export async function postPlanChat(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  request: PlanChatRequestParams
): Promise<PlanChatResult> {
  const res = await fetch(`${serverUrl}/api/projects/${projectId}/article-plan/chat`, {
    method: 'POST',
    headers: buildHeaders(apiKey, actor, 'application/json'),
    body: JSON.stringify(request),
  });
  await assertOk(res);
  return (await res.json()) as PlanChatResult;
}

export async function getIssueDescription(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  issueNumber: number
): Promise<string> {
  const res = await fetch(
    `${serverUrl}/api/projects/${projectId}/article-plan/issues/${issueNumber}/description`,
    { headers: buildHeaders(apiKey, actor) }
  );
  await assertOk(res);
  const data = (await res.json()) as { body: string };
  return data.body ?? '';
}

export interface SuggestMetadataResult {
  title: string;
  slug: string;
  categories: string[];
  tags: string[];
}

export async function suggestMetadata(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  history: PlanChatMessage[]
): Promise<SuggestMetadataResult> {
  const res = await fetch(`${serverUrl}/api/projects/${projectId}/article-plan/suggest-metadata`, {
    method: 'POST',
    headers: buildHeaders(apiKey, actor, 'application/json'),
    body: JSON.stringify({ history }),
  });
  await assertOk(res);
  return (await res.json()) as SuggestMetadataResult;
}
