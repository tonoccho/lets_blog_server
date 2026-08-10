import * as vscode from 'vscode';
import { LocalImageReference, guessImageMimeType } from './frontMatter';
import { Actor } from './config';
import { ApiError, NetworkError, TimeoutError, withRetry } from './errorHandler';
import { logger } from './logger';
import { httpRequest, HttpResponse } from './httpClient';
import { buildMultipartBody, MultipartPart } from './multipart';

/** 応答が返らない場合に諦めるまでの既定時間。AI生成は数十秒かかることがあるため長めに取る。 */
const DEFAULT_TIMEOUT_MS = 120_000;

/**
 * letsBlog.serverUrlは既定でリバースプロキシ経由の自己署名証明書(https://localhost)を
 * 指す個人用ローカル環境のため、既定で証明書検証をスキップする。実サーバーの正規証明書を
 * 使う場合は設定`letsBlog.allowInsecureTls`をfalseにすれば通常の検証に戻る。
 */
function allowsInsecureTls(): boolean {
  return vscode.workspace.getConfiguration('letsBlog').get<boolean>('allowInsecureTls', true);
}

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

function getTimeoutMs(): number {
  const configured = vscode.workspace
    .getConfiguration('letsBlog')
    .get<number>('requestTimeoutMs', DEFAULT_TIMEOUT_MS);
  return typeof configured === 'number' && configured > 0 ? configured : DEFAULT_TIMEOUT_MS;
}

interface RequestSpec {
  /** ログ上で処理を識別するラベル(例: "publishPost")。 */
  label: string;
  method?: string;
  headers?: Record<string, string>;
  /**
   * リクエストボディを組み立てる。マルチパートのボディは画像を読み込んで組み立てるため、
   * 実際に送信する直前まで構築を遅らせられるようファクトリで受け取る。
   */
  createBody?: () => { body: string | Buffer; headers?: Record<string, string> };
  /**
   * 一時的な失敗を再試行してよいか。既定はGETのみ(サーバー状態を変更しないため安全)。
   * タイムアウト後にサーバー側で処理が完了していた場合、投稿や課題の割り当てのような
   * 変更系を再試行すると重複して実行されてしまうため、安全なものだけ明示的に有効化する。
   */
  retryable?: boolean;
}

/**
 * 全API呼び出しの共通経路。タイムアウト・リトライ・ログ・エラー整形をここへ集約し、
 * 個々のエンドポイント関数がエラーハンドリングを取りこぼさないようにする。
 */
async function request(serverUrl: string, path: string, spec: RequestSpec): Promise<HttpResponse> {
  const url = `${serverUrl}${path}`;
  const method = spec.method ?? 'GET';
  const timeoutMs = getTimeoutMs();

  return withRetry(
    async () => {
      const built = spec.createBody?.();
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      logger.debug(`${spec.label}: ${method} ${url}`);

      let res: HttpResponse;
      try {
        res = await httpRequest(url, {
          method,
          headers: { ...(spec.headers ?? {}), ...(built?.headers ?? {}) },
          body: built?.body,
          signal: controller.signal,
          allowInsecureTls: allowsInsecureTls(),
        });
      } catch (error) {
        if (controller.signal.aborted) {
          throw new TimeoutError(`${spec.label} timed out`, url, timeoutMs);
        }
        throw new NetworkError(`${spec.label} failed to reach server`, url, error);
      } finally {
        clearTimeout(timer);
      }

      if (!res.ok) {
        const body = await res.text().catch(() => '');
        logger.warn(`${spec.label}: ${method} ${url} -> ${res.status}`, { body });
        throw new ApiError(`APIエラー (${res.status})`, res.status, body || res.statusText, url);
      }
      logger.debug(`${spec.label}: ${method} ${url} -> ${res.status}`);
      return res;
    },
    { label: spec.label, maxRetries: (spec.retryable ?? method === 'GET') ? undefined : 0 }
  );
}

/** JSONレスポンスを返すエンドポイント用のヘルパー。 */
async function requestJson<T>(serverUrl: string, path: string, spec: RequestSpec): Promise<T> {
  const res = await request(serverUrl, path, spec);
  return (await res.json()) as T;
}

/** JSONボディを送るリクエストのボディファクトリ。 */
function jsonBody(payload: unknown): RequestSpec['createBody'] {
  return () => ({ body: JSON.stringify(payload), headers: { 'Content-Type': 'application/json' } });
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
  featuredImageFilename?: string;
}

export interface PublishResult {
  wpPostId: string;
  wpPostUrl: string;
  status: string;
}

export interface SourceReference {
  title: string;
  url: string;
}

export interface AiDraftResult {
  result: string;
  sources: SourceReference[];
  searchNote: string | null;
}

export interface AiSectionParams {
  mode: 'body' | 'lead' | 'lead-subsections';
  heading?: string;
  precedingContext?: string;
  articleTitle?: string;
  subsectionHeadings?: string[];
  history?: PlanChatMessage[];
  message?: string;
}

export interface AiSectionResult {
  result: string;
  sources: SourceReference[];
  searchNote: string | null;
}

export interface AiTagsResult {
  categories: string[];
  tags: string[];
}

export interface AiImageResult {
  id: number;
  fileName: string;
  dataBase64: string;
  mimeType: string;
}

export interface ImageGenerationParams {
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
}

export interface ImageGenerationOptions {
  checkpoints: string[];
  selectedCheckpoint: string;
  samplers: string[];
  schedulers: string[];
  loras: string[];
}

export interface LoginResult {
  user: Actor;
  twoFactorRequired: boolean;
  apiKey: string | null;
}

/**
 * メールアドレス/パスワードでログインする。ログイン前はAPIキーを持たないため、
 * このエンドポイントはサーバー側でX-API-Keyヘッダなしでの呼び出しが許可されている。
 * 2FA未設定ユーザーはこの時点でapiKeyが発行される。
 */
export async function login(serverUrl: string, email: string, password: string): Promise<LoginResult> {
  return requestJson<LoginResult>(serverUrl, '/api/auth/login', {
    label: 'login',
    method: 'POST',
    createBody: jsonBody({ email, password, label: 'vscode' }),
  });
}

/** ログイン2段階目。login()でtwoFactorRequired=trueだった場合にTOTPコードを検証し、apiKeyを取得する。 */
export async function verifyTotpLogin(serverUrl: string, userId: number, code: string): Promise<LoginResult> {
  return requestJson<LoginResult>(serverUrl, '/api/auth/totp/verify', {
    label: 'verifyTotpLogin',
    method: 'POST',
    createBody: jsonBody({ userId, code, label: 'vscode' }),
  });
}

export async function publishPost(
  serverUrl: string,
  apiKey: string,
  params: PublishParams,
  actor?: Actor
): Promise<PublishResult> {
  return requestJson<PublishResult>(serverUrl, '/api/posts/publish', {
    label: 'publishPost',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: () => {
      const parts: MultipartPart[] = [
        { kind: 'field', name: 'site', value: params.site },
        { kind: 'field', name: 'title', value: params.title },
      ];
      if (params.slug) parts.push({ kind: 'field', name: 'slug', value: params.slug });
      parts.push({ kind: 'field', name: 'status', value: params.status ?? 'draft' });
      for (const category of params.categories ?? []) {
        parts.push({ kind: 'field', name: 'categories', value: category });
      }
      for (const tag of params.tags ?? []) {
        parts.push({ kind: 'field', name: 'tags', value: tag });
      }
      if (params.wpPostId) {
        parts.push({ kind: 'field', name: 'wpPostId', value: String(params.wpPostId) });
      }
      parts.push({ kind: 'field', name: 'markdown', value: params.markdown });
      for (const image of params.images) {
        // filenameはコンテナ/サーバー側のマルチパート処理でパス区切りがベース名のみに変換される
        // ことがあり往復しないため、Markdown中の実際の参照文字列はimageReferencesで別途明示的に送る。
        parts.push({
          kind: 'file',
          name: 'images',
          filename: image.reference,
          filePath: image.absolutePath,
          contentType: guessImageMimeType(image.reference),
        });
        parts.push({ kind: 'field', name: 'imageReferences', value: image.reference });
      }
      if (params.featuredImageFilename) {
        parts.push({ kind: 'field', name: 'featuredImageFilename', value: params.featuredImageFilename });
      }
      const multipart = buildMultipartBody(parts);
      return { body: multipart.body, headers: { 'Content-Type': multipart.contentType } };
    },
  });
}

/** 投稿を削除する(WordPressの場合、既定でゴミ箱へ移動する。完全削除は行わない)。 */
export async function deletePost(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  site: string,
  wpPostId: string
): Promise<void> {
  await request(serverUrl, `/api/posts/${encodeURIComponent(site)}/${encodeURIComponent(wpPostId)}`, {
    label: 'deletePost',
    method: 'DELETE',
    headers: buildHeaders(apiKey, actor),
  });
}

export async function listSites(
  serverUrl: string,
  apiKey: string,
  actor?: Actor
): Promise<{ id: number; name: string; siteKey: string }[]> {
  return requestJson<{ id: number; name: string; siteKey: string }[]>(serverUrl, '/api/sites', {
    label: 'listSites',
    headers: buildHeaders(apiKey, actor),
  });
}

export async function askAi(
  serverUrl: string,
  apiKey: string,
  mode: 'draft' | 'proofread' | 'summarize',
  text: string,
  actor?: Actor
): Promise<AiDraftResult> {
  return requestJson<AiDraftResult>(serverUrl, '/api/ai/draft', {
    label: 'askAi',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ mode, text }),
    // 生成結果を返すだけでサーバー状態を変えないため、再試行して差し支えない。
    retryable: true,
  });
}

export async function generateSection(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  params: AiSectionParams
): Promise<AiSectionResult> {
  return requestJson<AiSectionResult>(serverUrl, '/api/ai/section', {
    label: 'generateSection',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody(params),
    retryable: true,
  });
}

export async function suggestTags(
  serverUrl: string,
  apiKey: string,
  text: string,
  actor?: Actor
): Promise<AiTagsResult> {
  return requestJson<AiTagsResult>(serverUrl, '/api/ai/tags', {
    label: 'suggestTags',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ text }),
    retryable: true,
  });
}

export async function generateImage(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  projectId: number | undefined,
  params: ImageGenerationParams
): Promise<AiImageResult> {
  // 生成画像はサーバー側に保存されるため、再試行すると重複した生成結果が残る。
  return requestJson<AiImageResult>(serverUrl, '/api/ai/image', {
    label: 'generateImage',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ projectId, ...params }),
  });
}

export async function getImageGenerationOptions(
  serverUrl: string,
  apiKey: string,
  projectId?: number
): Promise<ImageGenerationOptions> {
  const query = projectId ? `?projectId=${projectId}` : '';
  return requestJson<ImageGenerationOptions>(serverUrl, `/api/ai/image-options${query}`, {
    label: 'getImageGenerationOptions',
    headers: buildHeaders(apiKey),
  });
}

export async function listUsers(serverUrl: string, apiKey: string): Promise<Actor[]> {
  return requestJson<Actor[]>(serverUrl, '/api/users', {
    label: 'listUsers',
    headers: buildHeaders(apiKey),
  });
}

export interface ProjectSummary {
  id: number;
  name: string;
  slug: string;
  githubRepository?: string | null;
}

export async function listProjects(serverUrl: string, apiKey: string, actor?: Actor): Promise<ProjectSummary[]> {
  return requestJson<ProjectSummary[]>(serverUrl, '/api/projects', {
    label: 'listProjects',
    headers: buildHeaders(apiKey, actor),
  });
}

export interface ProjectSite {
  id: number;
  name: string;
  siteKey: string;
}

export interface ProjectDetail {
  id: number;
  name: string;
  slug: string;
  localSite: ProjectSite | null;
  testSite: ProjectSite | null;
  productionSite: ProjectSite | null;
  masterEnvironment: string;
  githubRepository?: string | null;
}

export async function getProject(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  projectId: number
): Promise<ProjectDetail> {
  return requestJson<ProjectDetail>(serverUrl, `/api/projects/${projectId}`, {
    label: 'getProject',
    headers: buildHeaders(apiKey, actor),
  });
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
  const issues = await requestJson<RepositoryIssue[]>(
    serverUrl,
    `/api/projects/${projectId}/article-plan/issues?state=${state}`,
    { label: 'listUnassignedIssues', headers: buildHeaders(apiKey, actor) }
  );
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
  // チャットセッションがサーバー側に記録されるため、再試行すると履歴が重複する。
  return requestJson<PlanChatResult>(serverUrl, `/api/projects/${projectId}/article-plan/chat`, {
    label: 'postPlanChat',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody(request),
  });
}

export async function getIssueDescription(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  issueNumber: number
): Promise<string> {
  const data = await requestJson<{ body: string }>(
    serverUrl,
    `/api/projects/${projectId}/article-plan/issues/${issueNumber}/description`,
    { label: 'getIssueDescription', headers: buildHeaders(apiKey, actor) }
  );
  return data.body ?? '';
}

export interface SuggestMetadataResult {
  titles: string[];
  slugs: string[];
  categories: string[];
  tags: string[];
}

/** プロジェクトのマスター環境サイトに既に存在するカテゴリ名一覧。サイト未紐付け等の場合は空配列。 */
export async function listExistingCategories(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number
): Promise<string[]> {
  return requestJson<string[]>(serverUrl, `/api/projects/${projectId}/article-plan/categories`, {
    label: 'listExistingCategories',
    headers: buildHeaders(apiKey, actor),
  });
}

export async function suggestMetadata(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  history: PlanChatMessage[]
): Promise<SuggestMetadataResult> {
  return requestJson<SuggestMetadataResult>(
    serverUrl,
    `/api/projects/${projectId}/article-plan/suggest-metadata`,
    {
      label: 'suggestMetadata',
      method: 'POST',
      headers: buildHeaders(apiKey, actor),
      createBody: jsonBody({ history }),
      retryable: true,
    }
  );
}

export interface SuggestStructureResult {
  structure: string;
}

export async function suggestArticleStructure(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  history: PlanChatMessage[]
): Promise<SuggestStructureResult> {
  return requestJson<SuggestStructureResult>(
    serverUrl,
    `/api/projects/${projectId}/article-plan/suggest-structure`,
    {
      label: 'suggestArticleStructure',
      method: 'POST',
      headers: buildHeaders(apiKey, actor),
      createBody: jsonBody({ history }),
      retryable: true,
    }
  );
}

export interface AcceptStructureResult {
  issueNumber: number;
  issueUrl: string;
}

/** 提案された構成案でGitHub Issueの本文を更新する。スキャフォールド時にこの内容がarticle.mdへ反映される。 */
export async function acceptArticleStructure(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  issueNumber: number,
  structure: string
): Promise<AcceptStructureResult> {
  return requestJson<AcceptStructureResult>(
    serverUrl,
    `/api/projects/${projectId}/article-plan/issues/${issueNumber}/accept-structure`,
    {
      label: 'acceptArticleStructure',
      method: 'POST',
      headers: buildHeaders(apiKey, actor),
      createBody: jsonBody({ structure }),
    }
  );
}

export interface AssignIssueResult {
  issueNumber: number;
  htmlUrl: string;
  assignedLogin: string;
}

export async function assignIssue(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  issueNumber: number
): Promise<AssignIssueResult> {
  return requestJson<AssignIssueResult>(
    serverUrl,
    `/api/projects/${projectId}/article-plan/issues/${issueNumber}/assign`,
    {
      label: 'assignIssue',
      method: 'POST',
      headers: buildHeaders(apiKey, actor, 'application/json'),
    }
  );
}

export async function renderPreviewHtml(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  projectId: number,
  markdown: string
): Promise<string> {
  const data = await requestJson<{ html: string }>(serverUrl, `/api/projects/${projectId}/preview/render`, {
    label: 'renderPreviewHtml',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ markdown }),
    // 変換結果を返すだけでサーバー状態を変えないため、再試行して差し支えない。
    retryable: true,
  });
  return data.html;
}

export interface ThemeCssResult {
  css: string;
  available: boolean;
  reason?: string;
}

export async function getMasterThemeCss(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  projectId: number
): Promise<ThemeCssResult> {
  return requestJson<ThemeCssResult>(serverUrl, `/api/projects/${projectId}/preview/theme-css`, {
    label: 'getMasterThemeCss',
    headers: buildHeaders(apiKey, actor),
  });
}
