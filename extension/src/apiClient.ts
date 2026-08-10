import * as vscode from 'vscode';
import { z, ZodType } from 'zod';
import { LocalImageReference, guessImageMimeType } from './frontMatter';
import { Actor } from './config';
import {
  ApiError,
  NetworkError,
  ResponseValidationError,
  TimeoutError,
  messageOf,
  withRetry,
} from './errorHandler';
import { logger } from './logger';
import { httpRequest, HttpResponse } from './httpClient';
import { buildMultipartBody, MultipartPart } from './multipart';
import * as schemas from './schemas';

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

/**
 * JSONレスポンスを返すエンドポイント用のヘルパー。
 * 受信直後にZodスキーマで検証し、想定外の形式をそのまま拡張内部へ持ち込まないようにする。
 * 戻り値の型はスキーマから推論されるため、スキーマと型定義が乖離しない。
 */
async function requestJson<S extends ZodType>(
  serverUrl: string,
  path: string,
  spec: RequestSpec,
  schema: S
): Promise<z.infer<S>> {
  const res = await request(serverUrl, path, spec);
  const url = `${serverUrl}${path}`;

  let payload: unknown;
  try {
    payload = await res.json();
  } catch (error) {
    throw new ResponseValidationError(`${spec.label}: 応答をJSONとして解釈できませんでした`, url, [
      messageOf(error),
    ]);
  }

  const parsed = schema.safeParse(payload);
  if (!parsed.success) {
    const issues = parsed.error.issues.map(
      (issue) => `${issue.path.join('.') || '(root)'}: ${issue.message}`
    );
    logger.error(`${spec.label}: レスポンス検証に失敗しました`, { url, issues, payload });
    throw new ResponseValidationError(`${spec.label}: 応答の形式が想定と異なります`, url, issues);
  }
  return parsed.data;
}

/** JSONボディを送るリクエストのボディファクトリ。 */
function jsonBody(payload: unknown): RequestSpec['createBody'] {
  return () => ({ body: JSON.stringify(payload), headers: { 'Content-Type': 'application/json' } });
}

/**
 * レスポンス型はschemas.tsの検証スキーマから導出する。従来ここで手書きしていた
 * interfaceと実際の検証内容が食い違わないよう、単一の定義元として再エクスポートする。
 */
import type {
  AcceptStructureResult,
  AiDraftResult,
  AiImageResult,
  AiSectionResult,
  AiTagsResult,
  AssignIssueResult,
  ImageGenerationOptions,
  LoginResult,
  PlanChatResult,
  ProjectDetail,
  ProjectSite,
  ProjectSummary,
  PublishResult,
  RepositoryIssue,
  SiteSummary,
  SourceReference,
  SuggestMetadataResult,
  SuggestStructureResult,
  ThemeCssResult,
} from './schemas';

export type {
  Actor,
  AcceptStructureResult,
  AiDraftResult,
  AiImageResult,
  AiSectionResult,
  AiTagsResult,
  AssignIssueResult,
  ImageGenerationOptions,
  LoginResult,
  PlanChatResult,
  ProjectDetail,
  ProjectSite,
  ProjectSummary,
  PublishResult,
  RepositoryIssue,
  SiteSummary,
  SourceReference,
  SuggestMetadataResult,
  SuggestStructureResult,
  ThemeCssResult,
};

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

export interface AiSectionParams {
  mode: 'body' | 'lead' | 'lead-subsections';
  heading?: string;
  precedingContext?: string;
  articleTitle?: string;
  subsectionHeadings?: string[];
  history?: PlanChatMessage[];
  message?: string;
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

/**
 * メールアドレス/パスワードでログインする。ログイン前はAPIキーを持たないため、
 * このエンドポイントはサーバー側でX-API-Keyヘッダなしでの呼び出しが許可されている。
 * 2FA未設定ユーザーはこの時点でapiKeyが発行される。
 */
export async function login(serverUrl: string, email: string, password: string): Promise<LoginResult> {
  return requestJson(serverUrl, '/api/auth/login', {
    label: 'login',
    method: 'POST',
    createBody: jsonBody({ email, password, label: 'vscode' }),
  }, schemas.LoginResultSchema);
}

/** ログイン2段階目。login()でtwoFactorRequired=trueだった場合にTOTPコードを検証し、apiKeyを取得する。 */
export async function verifyTotpLogin(serverUrl: string, userId: number, code: string): Promise<LoginResult> {
  return requestJson(serverUrl, '/api/auth/totp/verify', {
    label: 'verifyTotpLogin',
    method: 'POST',
    createBody: jsonBody({ userId, code, label: 'vscode' }),
  }, schemas.LoginResultSchema);
}

export async function publishPost(
  serverUrl: string,
  apiKey: string,
  params: PublishParams,
  actor?: Actor
): Promise<PublishResult> {
  return requestJson(serverUrl, '/api/posts/publish', {
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
  }, schemas.PublishResultSchema);
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
  return requestJson(serverUrl, '/api/sites', {
    label: 'listSites',
    headers: buildHeaders(apiKey, actor),
  }, schemas.SiteSummaryListSchema);
}

export async function askAi(
  serverUrl: string,
  apiKey: string,
  mode: 'draft' | 'proofread' | 'summarize',
  text: string,
  actor?: Actor
): Promise<AiDraftResult> {
  return requestJson(serverUrl, '/api/ai/draft', {
    label: 'askAi',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ mode, text }),
    // 生成結果を返すだけでサーバー状態を変えないため、再試行して差し支えない。
    retryable: true,
  }, schemas.AiGenerationResultSchema);
}

export async function generateSection(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  params: AiSectionParams
): Promise<AiSectionResult> {
  return requestJson(serverUrl, '/api/ai/section', {
    label: 'generateSection',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody(params),
    retryable: true,
  }, schemas.AiGenerationResultSchema);
}

export async function suggestTags(
  serverUrl: string,
  apiKey: string,
  text: string,
  actor?: Actor
): Promise<AiTagsResult> {
  return requestJson(serverUrl, '/api/ai/tags', {
    label: 'suggestTags',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ text }),
    retryable: true,
  }, schemas.AiTagsResultSchema);
}

export async function generateImage(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  projectId: number | undefined,
  params: ImageGenerationParams
): Promise<AiImageResult> {
  // 生成画像はサーバー側に保存されるため、再試行すると重複した生成結果が残る。
  return requestJson(serverUrl, '/api/ai/image', {
    label: 'generateImage',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ projectId, ...params }),
  }, schemas.AiImageResultSchema);
}

export async function getImageGenerationOptions(
  serverUrl: string,
  apiKey: string,
  projectId?: number
): Promise<ImageGenerationOptions> {
  const query = projectId ? `?projectId=${projectId}` : '';
  return requestJson(serverUrl, `/api/ai/image-options${query}`, {
    label: 'getImageGenerationOptions',
    headers: buildHeaders(apiKey),
  }, schemas.ImageGenerationOptionsSchema);
}

export async function listUsers(serverUrl: string, apiKey: string): Promise<Actor[]> {
  return requestJson(serverUrl, '/api/users', {
    label: 'listUsers',
    headers: buildHeaders(apiKey),
  }, schemas.ActorListSchema);
}

export async function listProjects(serverUrl: string, apiKey: string, actor?: Actor): Promise<ProjectSummary[]> {
  return requestJson(serverUrl, '/api/projects', {
    label: 'listProjects',
    headers: buildHeaders(apiKey, actor),
  }, schemas.ProjectSummaryListSchema);
}

export async function getProject(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  projectId: number
): Promise<ProjectDetail> {
  return requestJson(serverUrl, `/api/projects/${projectId}`, {
    label: 'getProject',
    headers: buildHeaders(apiKey, actor),
  }, schemas.ProjectDetailSchema);
}

/** リポジトリのissue一覧のうち、未割り当て(assigneesが空)のものだけを返す。 */
export async function listUnassignedIssues(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  state: string = 'open'
): Promise<RepositoryIssue[]> {
  const issues = await requestJson(
    serverUrl,
    `/api/projects/${projectId}/article-plan/issues?state=${state}`,
    { label: 'listUnassignedIssues', headers: buildHeaders(apiKey, actor) }, schemas.RepositoryIssueListSchema);
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

export async function postPlanChat(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  request: PlanChatRequestParams
): Promise<PlanChatResult> {
  // チャットセッションがサーバー側に記録されるため、再試行すると履歴が重複する。
  return requestJson(serverUrl, `/api/projects/${projectId}/article-plan/chat`, {
    label: 'postPlanChat',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody(request),
  }, schemas.PlanChatResultSchema);
}

export async function getIssueDescription(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  issueNumber: number
): Promise<string> {
  const data = await requestJson(
    serverUrl,
    `/api/projects/${projectId}/article-plan/issues/${issueNumber}/description`,
    { label: 'getIssueDescription', headers: buildHeaders(apiKey, actor) }, schemas.IssueDescriptionSchema);
  return data.body ?? '';
}

/** プロジェクトのマスター環境サイトに既に存在するカテゴリ名一覧。サイト未紐付け等の場合は空配列。 */
export async function listExistingCategories(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number
): Promise<string[]> {
  return requestJson(serverUrl, `/api/projects/${projectId}/article-plan/categories`, {
    label: 'listExistingCategories',
    headers: buildHeaders(apiKey, actor),
  }, schemas.CategoryNameListSchema);
}

export async function suggestMetadata(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  history: PlanChatMessage[]
): Promise<SuggestMetadataResult> {
  return requestJson(
    serverUrl,
    `/api/projects/${projectId}/article-plan/suggest-metadata`,
    {
      label: 'suggestMetadata',
      method: 'POST',
      headers: buildHeaders(apiKey, actor),
      createBody: jsonBody({ history }),
      retryable: true,
    }, schemas.SuggestMetadataResultSchema);
}

export async function suggestArticleStructure(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  history: PlanChatMessage[]
): Promise<SuggestStructureResult> {
  return requestJson(
    serverUrl,
    `/api/projects/${projectId}/article-plan/suggest-structure`,
    {
      label: 'suggestArticleStructure',
      method: 'POST',
      headers: buildHeaders(apiKey, actor),
      createBody: jsonBody({ history }),
      retryable: true,
    }, schemas.SuggestStructureResultSchema);
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
  return requestJson(
    serverUrl,
    `/api/projects/${projectId}/article-plan/issues/${issueNumber}/accept-structure`,
    {
      label: 'acceptArticleStructure',
      method: 'POST',
      headers: buildHeaders(apiKey, actor),
      createBody: jsonBody({ structure }),
    }, schemas.AcceptStructureResultSchema);
}

export async function assignIssue(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  issueNumber: number
): Promise<AssignIssueResult> {
  return requestJson(
    serverUrl,
    `/api/projects/${projectId}/article-plan/issues/${issueNumber}/assign`,
    {
      label: 'assignIssue',
      method: 'POST',
      headers: buildHeaders(apiKey, actor, 'application/json'),
    }, schemas.AssignIssueResultSchema);
}

export async function renderPreviewHtml(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  projectId: number,
  markdown: string
): Promise<string> {
  const data = await requestJson(serverUrl, `/api/projects/${projectId}/preview/render`, {
    label: 'renderPreviewHtml',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ markdown }),
    // 変換結果を返すだけでサーバー状態を変えないため、再試行して差し支えない。
    retryable: true,
  }, schemas.RenderPreviewResultSchema);
  return data.html;
}

export async function getMasterThemeCss(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  projectId: number
): Promise<ThemeCssResult> {
  return requestJson(serverUrl, `/api/projects/${projectId}/preview/theme-css`, {
    label: 'getMasterThemeCss',
    headers: buildHeaders(apiKey, actor),
  }, schemas.ThemeCssResultSchema);
}
