import * as vscode from 'vscode';
import { z, ZodType } from 'zod';
import { LocalImageReference, guessImageMimeType } from './frontMatter';
import { Actor } from './config';
import {
  ApiError,
  CancelledError,
  NetworkError,
  ResponseValidationError,
  TimeoutError,
  messageOf,
  withRetry,
} from './errorHandler';
import { logger } from './logger';
import { httpRequest, HttpResponse } from './httpClient';
import { buildMultipartBody, MultipartPart } from './multipart';
import { LruCache } from './cache';
import * as schemas from './schemas';

/** 応答が返らない場合に諦めるまでの既定時間。AI生成は数十秒かかることがあるため長めに取る。 */
const DEFAULT_TIMEOUT_MS = 120_000;

/**
 * 記事公開(/api/posts/publish)が保証する最低タイムアウト。画像アップロードや
 * PlantUML/Draw.ioダイアグラムのレンダリング、SSH経由のwp-cli呼び出しを伴い、既定の
 * リクエストタイムアウト(120秒)を超えて処理が続くことがある。クライアント側が先に
 * タイムアウトして中断すると、サーバーの投稿処理自体は完了していても呼び出し元は失敗扱いとなり、
 * 次回投稿時にwpPostIdが空のまま送られて新規投稿として扱われ、画像・ダイアグラムが
 * 重複アップロードされる原因になっていた(issue #499)。サーバー側nginxのタイムアウト
 * (1200秒、issue #497)を下回らないようにする。
 */
const PUBLISH_MIN_TIMEOUT_MS = 1_200_000;

/**
 * TLS証明書の検証は既定で有効(allowInsecureTls=false)。
 * 検証を無効化すると中間者攻撃でAPIキーや記事内容を傍受・改竄されうるため、
 * 自己署名証明書のローカル環境へ接続する場合に限り、利用者が明示的に有効化する。
 * 危険な設定であることに気付けるよう、有効な間は警告としてログに残す。
 */
function allowsInsecureTls(): boolean {
  const allowed = vscode.workspace.getConfiguration('letsBlog').get<boolean>('allowInsecureTls', false);
  if (allowed) {
    logger.warn(
      'letsBlog.allowInsecureTlsが有効なため、TLS証明書の検証をスキップします。' +
        '信頼できるネットワーク上のローカル環境でのみ使用してください。'
    );
  }
  return allowed;
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
   * このリクエストが最低限確保すべきタイムアウト(ミリ秒)。利用者設定(letsBlog.requestTimeoutMs)
   * より長い場合のみ有効になる下限であり、利用者が明示的により長い値を設定していればそちらを尊重する。
   */
  minTimeoutMs?: number;
  /**
   * 一時的な失敗を再試行してよいか。既定はGETのみ(サーバー状態を変更しないため安全)。
   * タイムアウト後にサーバー側で処理が完了していた場合、投稿や課題の割り当てのような
   * 変更系を再試行すると重複して実行されてしまうため、安全なものだけ明示的に有効化する。
   */
  retryable?: boolean;
  /**
   * 呼び出し側からの中断シグナル。利用者がパネル上で「キャンセル」を押した場合に、
   * 進行中のリクエストを実際に打ち切るために使う(タイムアウトとは別系統)。
   */
  signal?: AbortSignal;
}

/**
 * 全API呼び出しの共通経路。タイムアウト・リトライ・ログ・エラー整形をここへ集約し、
 * 個々のエンドポイント関数がエラーハンドリングを取りこぼさないようにする。
 */
async function request(serverUrl: string, path: string, spec: RequestSpec): Promise<HttpResponse> {
  const url = `${serverUrl}${path}`;
  const method = spec.method ?? 'GET';
  const timeoutMs = Math.max(getTimeoutMs(), spec.minTimeoutMs ?? 0);

  return withRetry(
    async () => {
      const built = spec.createBody?.();
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      // 外部シグナル(利用者によるキャンセル)もこのリクエストの中断へつなぐ。
      const onExternalAbort = (): void => controller.abort();
      if (spec.signal?.aborted) {
        controller.abort();
      } else {
        spec.signal?.addEventListener('abort', onExternalAbort, { once: true });
      }
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
        // 利用者によるキャンセルとタイムアウトは、利用者への伝え方が異なるため区別する。
        if (spec.signal?.aborted) {
          throw new CancelledError(`${spec.label} was cancelled`);
        }
        if (controller.signal.aborted) {
          throw new TimeoutError(`${spec.label} timed out`, url, timeoutMs);
        }
        throw new NetworkError(`${spec.label} failed to reach server`, url, error);
      } finally {
        clearTimeout(timer);
        spec.signal?.removeEventListener('abort', onExternalAbort);
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

/** 画像などのバイナリを返すエンドポイント用のヘルパー。 */
async function requestBinary(serverUrl: string, path: string, spec: RequestSpec): Promise<Buffer> {
  const res = await request(serverUrl, path, spec);
  return Buffer.from(await res.arrayBuffer());
}

/** JSONボディを送るリクエストのボディファクトリ。 */
function jsonBody(payload: unknown): RequestSpec['createBody'] {
  return () => ({ body: JSON.stringify(payload), headers: { 'Content-Type': 'application/json' } });
}

/**
 * 参照系レスポンスのキャッシュ。同じ一覧をパネルの開閉やコマンド実行のたびに
 * 取得し直していたのを抑える。変更されうるデータのため有効期間は5分に留め、
 * 内容を変える操作(issueの割り当て等)の直後は明示的に無効化する。
 */
const responseCache = new LruCache<unknown>({ ttlMs: 5 * 60 * 1000, maxEntries: 50 });

/** キャッシュ全体を破棄する(ログイン/ログアウトやサーバー切り替え時に使う)。 */
export function clearResponseCache(): void {
  responseCache.clear();
}

/** 指定プロジェクトの参照系キャッシュを無効化する。 */
export function invalidateProjectCache(projectId: number): void {
  responseCache.invalidate(`project:${projectId}`);
}

/** キャッシュを経由してJSONを取得する。キーが衝突しないようserverUrlとパラメータを含める。 */
async function cachedRequestJson<S extends ZodType>(
  cacheKey: string,
  serverUrl: string,
  path: string,
  spec: RequestSpec,
  schema: S
): Promise<z.infer<S>> {
  return responseCache.getOrLoad(`${cacheKey}@${serverUrl}`, () =>
    requestJson(serverUrl, path, spec, schema)
  ) as Promise<z.infer<S>>;
}

/**
 * レスポンス型はschemas.tsの検証スキーマから導出する。従来ここで手書きしていた
 * interfaceと実際の検証内容が食い違わないよう、単一の定義元として再エクスポートする。
 */
import type {
  AcceptStructureResult,
  AiAskResult,
  AiDraftResult,
  AiImagePromptResult,
  AiImageResult,
  AiSectionResult,
  AiTagsResult,
  AssignIssueResult,
  ContentCacheResult,
  ImageGenerationOptions,
  LoginResult,
  PlanChatResult,
  ProjectDetail,
  ProjectSite,
  ProjectSummary,
  ProofreadResult,
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
  AiAskResult,
  AiDraftResult,
  AiImagePromptResult,
  AiImageResult,
  AiSectionResult,
  AiTagsResult,
  AssignIssueResult,
  ContentCacheResult,
  ImageGenerationOptions,
  LoginResult,
  PlanChatResult,
  ProjectDetail,
  ProjectSite,
  ProjectSummary,
  ProofreadResult,
  PublishResult,
  RepositoryIssue,
  SiteSummary,
  SourceReference,
  SuggestMetadataResult,
  SuggestStructureResult,
  ThemeCssResult,
};

/** 投稿(publishPost)へ渡すパラメータ。front matterと本文から組み立てる。 */
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
  /** 公開予定日時(ISO 8601)。本番サイトへの投稿時のみサーバー側で有効になる。 */
  publishScheduledAt?: string;
}

/**
 * セクション生成のパラメータ。modeはカーソル位置の見出し階層から自動判定される
 * (headingContext.resolveSectionContextを参照)。historyとmessageは壁打ち再生成時のみ使う。
 */
export interface AiSectionParams {
  mode: 'body' | 'lead' | 'lead-subsections';
  heading?: string;
  precedingContext?: string;
  articleTitle?: string;
  subsectionHeadings?: string[];
  history?: PlanChatMessage[];
  message?: string;
  /** OLLAMA/OPENAI/CLAUDEのいずれか(任意)。未指定時はサーバー側の既定プロバイダーを使う(issue #530)。 */
  provider?: string;
}

/** 画像生成のパラメータ。automatic1111相当の項目をそのまま受け渡す。 */
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

/**
 * Markdown記事をCMSへ投稿する(既存投稿がある場合は更新)。
 * 本文中のローカル画像をマルチパートで同梱する。副作用があるため再試行しない。
 *
 * @param params 投稿内容。imagesは実ファイルが存在するものだけを渡すこと。
 */
export async function publishPost(
  serverUrl: string,
  apiKey: string,
  params: PublishParams,
  actor?: Actor
): Promise<PublishResult> {
  return requestJson(serverUrl, '/api/posts/publish', {
    label: 'publishPost',
    method: 'POST',
    minTimeoutMs: PUBLISH_MIN_TIMEOUT_MS,
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
      if (params.publishScheduledAt) {
        parts.push({ kind: 'field', name: 'publishScheduledAt', value: params.publishScheduledAt });
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

/**
 * サイト+スラッグに対応する既存投稿を照会する(issue #505)。
 * front matterのwp_post_ids(廃止)に頼らず、DB側の情報から既存投稿の有無・WordPress投稿IDを
 * 取得するために使う。該当する投稿が無い場合(まだそのサイトへ投稿されていない)はundefinedを返す。
 */
export async function lookupExistingPost(
  serverUrl: string,
  apiKey: string,
  siteKey: string,
  slug: string,
  actor?: Actor
): Promise<schemas.PostLookupResult | undefined> {
  try {
    return await requestJson(
      serverUrl,
      `/api/posts/${encodeURIComponent(siteKey)}/by-slug/${encodeURIComponent(slug)}`,
      {
        label: 'lookupExistingPost',
        method: 'GET',
        headers: buildHeaders(apiKey, actor),
      },
      schemas.PostLookupResultSchema
    );
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) {
      return undefined;
    }
    throw error;
  }
}

/** 登録済みサイトの一覧を取得する。 */
export async function listSites(
  serverUrl: string,
  apiKey: string,
  actor?: Actor
): Promise<{ id: number; name: string; siteKey: string }[]> {
  return cachedRequestJson('sites', serverUrl, '/api/sites', {
    label: 'listSites',
    headers: buildHeaders(apiKey, actor),
  }, schemas.SiteSummaryListSchema);
}

/** 投稿ステータスの選択肢を取得する。UIのハードコードをサーバー側の正準リストへ統一する(issue #472)。 */
export async function getPostStatuses(
  serverUrl: string,
  apiKey: string
): Promise<schemas.PostStatusOption[]> {
  return cachedRequestJson('post-statuses', serverUrl, '/api/metadata/post-statuses', {
    label: 'getPostStatuses',
    headers: buildHeaders(apiKey),
  }, schemas.PostStatusOptionListSchema);
}

/** ロールの表示名一覧を取得する(issue #472)。 */
export async function getRoles(
  serverUrl: string,
  apiKey: string
): Promise<schemas.RoleOption[]> {
  return cachedRequestJson('roles', serverUrl, '/api/metadata/roles', {
    label: 'getRoles',
    headers: buildHeaders(apiKey),
  }, schemas.RoleOptionListSchema);
}

/**
 * 下書き生成・校正・要約をAIへ依頼する。
 * @param mode draft(下書き) / proofread(校正) / summarize(要約)
 */
export async function askAi(
  serverUrl: string,
  apiKey: string,
  mode: 'draft' | 'proofread' | 'summarize',
  text: string,
  actor?: Actor,
  provider?: string
): Promise<AiDraftResult> {
  return requestJson(serverUrl, '/api/ai/draft', {
    label: 'askAi',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ mode, text, provider: provider || undefined }),
    // 生成結果を返すだけでサーバー状態を変えないため、再試行して差し支えない。
    retryable: true,
  }, schemas.AiGenerationResultSchema);
}

/**
 * エディタ右クリックメニュー「Ask AI」からの質問に、Web検索結果を踏まえて回答する(issue #526)。
 * @param signal 利用者によるキャンセル用。中断時はCancelledErrorが投げられる。
 */
export async function askAiSearch(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  question: string,
  provider?: string,
  signal?: AbortSignal
): Promise<AiAskResult> {
  return requestJson(serverUrl, '/api/ai/ask', {
    label: 'askAiSearch',
    signal,
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ question, provider: provider || undefined }),
    retryable: true,
  }, schemas.AiGenerationResultSchema);
}

/**
 * セクション本文またはリード文を生成する。
 * @param signal 利用者によるキャンセル用。中断時はCancelledErrorが投げられる。
 */
export async function generateSection(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  params: AiSectionParams,
  signal?: AbortSignal
): Promise<AiSectionResult> {
  return requestJson(serverUrl, '/api/ai/section', {
    label: 'generateSection',
    signal,
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody(params),
    retryable: true,
  }, schemas.AiGenerationResultSchema);
}

/**
 * 本文からカテゴリ/タグの候補を提案させる。projectId指定時は、そのプロジェクトのマスター環境サイトに
 * 既存のタグを優先して提案する(issue #525)。
 */
export async function suggestTags(
  serverUrl: string,
  apiKey: string,
  text: string,
  actor?: Actor,
  provider?: string,
  projectId?: number
): Promise<AiTagsResult> {
  return requestJson(serverUrl, '/api/ai/tags', {
    label: 'suggestTags',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ text, provider: provider || undefined, projectId }),
    retryable: true,
  }, schemas.AiTagsResultSchema);
}

/**
 * 本文の校正チェックをAIへ依頼する。エディタでの波線表示に使うため、超過した指摘によって
 * 誤って古い結果を表示し続けないよう、呼び出し元でsignalによるキャンセルを行える。
 * @param signal 再入力等で古いリクエストを打ち切るためのキャンセル用(issue #523)。
 */
export async function proofreadContent(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  text: string,
  provider?: string,
  signal?: AbortSignal
): Promise<ProofreadResult> {
  return requestJson(serverUrl, '/api/ai/proofread', {
    label: 'proofreadContent',
    signal,
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ text, provider: provider || undefined }),
    retryable: true,
  }, schemas.ProofreadResultSchema);
}

/** プロジェクトのマスター環境サイトに既に存在するタグ名一覧。サイト未紐付け等の場合は空配列(issue #525)。 */
export async function listExistingTags(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  projectId: number
): Promise<string[]> {
  return cachedRequestJson(
    `project:${projectId}:tags`,
    serverUrl,
    `/api/projects/${projectId}/article-plan/tags`,
    { label: 'listExistingTags', headers: buildHeaders(apiKey, actor) },
    schemas.TagNameListSchema
  );
}

/**
 * ComfyUIで画像を生成する。生成結果はサーバー側にも保存される。
 * @param signal 利用者によるキャンセル用。
 */
export async function generateImage(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  projectId: number | undefined,
  params: ImageGenerationParams,
  signal?: AbortSignal
): Promise<AiImageResult> {
  // 生成画像はサーバー側に保存されるため、再試行すると重複した生成結果が残る。
  const batch = await requestJson(serverUrl, '/api/ai/image', {
    label: 'generateImage',
    signal,
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ projectId, ...params }),
  }, schemas.AiImageBatchResponseSchema);
  return batch.images[0];
}

/** 画像生成で選択できるモデル/サンプラー/スケジューラ/LoRAの一覧を取得する。 */
export async function getImageGenerationOptions(
  serverUrl: string,
  apiKey: string,
  projectId?: number
): Promise<ImageGenerationOptions> {
  const query = projectId ? `?projectId=${projectId}` : '';
  return cachedRequestJson(
    `project:${projectId ?? 'none'}:image-options`,
    serverUrl,
    `/api/ai/image-options${query}`,
    { label: 'getImageGenerationOptions', headers: buildHeaders(apiKey) },
    schemas.ImageGenerationOptionsSchema
  );
}

/** ユーザー一覧を取得する。 */
export async function listUsers(serverUrl: string, apiKey: string): Promise<Actor[]> {
  return requestJson(serverUrl, '/api/users', {
    label: 'listUsers',
    headers: buildHeaders(apiKey),
  }, schemas.ActorListSchema);
}

/** プロジェクト一覧を取得する。 */
export async function listProjects(serverUrl: string, apiKey: string, actor?: Actor): Promise<ProjectSummary[]> {
  return cachedRequestJson('projects', serverUrl, '/api/projects', {
    label: 'listProjects',
    headers: buildHeaders(apiKey, actor),
  }, schemas.ProjectSummaryListSchema);
}

/** プロジェクト詳細を取得する。ローカル/テスト/本番のサイト紐付けを含む。 */
export async function getProject(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  projectId: number
): Promise<ProjectDetail> {
  return cachedRequestJson(
    `project:${projectId}:detail`,
    serverUrl,
    `/api/projects/${projectId}`,
    { label: 'getProject', headers: buildHeaders(apiKey, actor) },
    schemas.ProjectDetailSchema
  );
}

/** リポジトリのissue一覧のうち、未割り当て(assigneesが空)のものだけを返す。 */
export async function listUnassignedIssues(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  state: string = 'open'
): Promise<RepositoryIssue[]> {
  const issues = await cachedRequestJson(
    `project:${projectId}:issues:${state}`,
    serverUrl,
    `/api/projects/${projectId}/article-plan/issues?state=${state}`,
    { label: 'listUnassignedIssues', headers: buildHeaders(apiKey, actor) },
    schemas.RepositoryIssueListSchema
  );
  return issues.filter((i) => !i.assignees || i.assignees.length === 0);
}

/** 壁打ちチャットの1発言。 */
export interface PlanChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

/** 壁打ちチャットの送信内容。sessionIdは2回目以降の継続時に指定する。 */
export interface PlanChatRequestParams {
  history: PlanChatMessage[];
  message: string;
  sessionId?: number;
  githubIssueNumber?: number;
}

/**
 * 記事の壁打ちチャットへメッセージを送る。
 * サーバー側にセッションが記録されるため再試行しない。
 * @param signal 利用者によるキャンセル用。
 */
export async function postPlanChat(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  request: PlanChatRequestParams,
  signal?: AbortSignal
): Promise<PlanChatResult> {
  // チャットセッションがサーバー側に記録されるため、再試行すると履歴が重複する。
  return requestJson(serverUrl, `/api/projects/${projectId}/article-plan/chat`, {
    label: 'postPlanChat',
    signal,
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody(request),
  }, schemas.PlanChatResultSchema);
}

/**
 * チャットメッセージ(と任意の履歴)から画像生成プロンプトを作成する。
 * サーバー側で状態を持たないため、再試行して差し支えない。
 * @param signal 利用者によるキャンセル用。
 */
export async function generateImagePrompt(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  projectId: number,
  history: PlanChatMessage[],
  message: string,
  signal?: AbortSignal,
  provider?: string
): Promise<AiImagePromptResult> {
  return requestJson(serverUrl, `/api/projects/${projectId}/ai/generate-image-prompt`, {
    label: 'generateImagePrompt',
    signal,
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ history, message, provider: provider || undefined }),
    retryable: true,
  }, schemas.AiImagePromptResultSchema);
}

/** GitHub Issueの本文を取得する。未記入のIssueでは空文字を返す。 */
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
  return cachedRequestJson(
    `project:${projectId}:categories`,
    serverUrl,
    `/api/projects/${projectId}/article-plan/categories`,
    { label: 'listExistingCategories', headers: buildHeaders(apiKey, actor) },
    schemas.CategoryNameListSchema
  );
}

/**
 * プロジェクトのマスター環境サイトに既に存在するカテゴリ一覧を、親カテゴリ名付きで取得する。
 * 子カテゴリ選択時に親カテゴリを自動選択するUIのために使う(issue #289)。
 */
export async function listExistingCategoriesWithParents(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number
): Promise<schemas.CategoryOption[]> {
  return cachedRequestJson(
    `project:${projectId}:categories:hierarchy`,
    serverUrl,
    `/api/projects/${projectId}/article-plan/categories/hierarchy`,
    { label: 'listExistingCategoriesWithParents', headers: buildHeaders(apiKey, actor) },
    schemas.CategoryOptionListSchema
  );
}

/**
 * チャット履歴からタイトル/スラッグ/カテゴリ/タグの候補を提案させる。
 * @param signal 利用者によるキャンセル用。
 */
export async function suggestMetadata(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  history: PlanChatMessage[],
  signal?: AbortSignal
): Promise<SuggestMetadataResult> {
  return requestJson(
    serverUrl,
    `/api/projects/${projectId}/article-plan/suggest-metadata`,
    {
      label: 'suggestMetadata',
      signal,
      method: 'POST',
      headers: buildHeaders(apiKey, actor),
      createBody: jsonBody({ history }),
      retryable: true,
    }, schemas.SuggestMetadataResultSchema);
}

/**
 * チャット履歴から記事の見出し構成を提案させる。
 * @param signal 利用者によるキャンセル用。
 */
export async function suggestArticleStructure(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  history: PlanChatMessage[],
  signal?: AbortSignal
): Promise<SuggestStructureResult> {
  return requestJson(
    serverUrl,
    `/api/projects/${projectId}/article-plan/suggest-structure`,
    {
      label: 'suggestArticleStructure',
      signal,
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
  const result = await requestJson(
    serverUrl,
    `/api/projects/${projectId}/article-plan/issues/${issueNumber}/accept-structure`,
    {
      label: 'acceptArticleStructure',
      method: 'POST',
      headers: buildHeaders(apiKey, actor),
      createBody: jsonBody({ structure }),
    },
    schemas.AcceptStructureResultSchema
  );
  // Issue本文を書き換えたため、このプロジェクトの参照系キャッシュを破棄する。
  invalidateProjectCache(projectId);
  return result;
}

/**
 * Issueを実行者へ割り当てる。割り当て後は未割り当て一覧から外れるため、
 * 該当プロジェクトのキャッシュを破棄する。副作用があるため再試行しない。
 */
export async function assignIssue(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  issueNumber: number
): Promise<AssignIssueResult> {
  const result = await requestJson(
    serverUrl,
    `/api/projects/${projectId}/article-plan/issues/${issueNumber}/assign`,
    {
      label: 'assignIssue',
      method: 'POST',
      headers: buildHeaders(apiKey, actor, 'application/json'),
    },
    schemas.AssignIssueResultSchema
  );
  // 割り当て済みになったissueは未割り当て一覧から外れるため、キャッシュを破棄する。
  invalidateProjectCache(projectId);
  return result;
}

/**
 * プレビュー用にMarkdownをHTMLへ変換する(カスタムタグの展開を含む)。
 * ローカル画像はサーバー側で解決できないため、呼び出し前にdata URIへ置換しておくこと。
 */
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

/**
 * サイト内の既存記事ページを骨格として流用し、実テーマのDOM構造(タイトル/カテゴリ/日付/
 * アイキャッチ等)を保ったままプレビュー対象記事の内容へ差し替えたHTML断片を取得する。
 * 参照記事が無い・差し替え位置を特定できない等の場合はavailable:falseが返る
 * (呼び出し側は従来のプレーンな表示へフォールバックすること)。
 *
 * ローカル/テスト環境(managed WordPress)では、差し替えの代わりに実際に非公開(private)投稿を
 * 作成/更新してその実ページを返す経路が使われることがある。existingPreviewPostIdに前回の
 * ThemeSkeletonResult.previewPostIdを渡すと新規作成せず更新し、返り値のpreviewPostIdを
 * 次回呼び出しへ渡すことでプレビュー用の投稿を積み上げずに済む
 * (投稿の作成/更新という副作用を伴うため再試行はしない)。
 */
export async function renderPreviewSkeleton(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  projectId: number,
  siteId: number | undefined,
  title: string,
  contentHtml: string,
  featuredImageDataUri: string | undefined,
  existingPreviewPostId: string | undefined,
  slug?: string,
  categories?: string[],
  tags?: string[]
): Promise<schemas.ThemeSkeletonResult> {
  return requestJson(
    serverUrl,
    `/api/projects/${projectId}/preview/skeleton`,
    {
      label: 'renderPreviewSkeleton',
      method: 'POST',
      headers: buildHeaders(apiKey, actor),
      createBody: jsonBody({
        title, contentHtml, featuredImageDataUri, siteId, existingPreviewPostId, slug, categories, tags,
      }),
    },
    schemas.ThemeSkeletonResultSchema
  );
}

/**
 * renderPreviewSkeletonがローカル/テスト環境向けに作成した非公開プレビュー投稿を削除する
 * (WordPressの既定挙動でゴミ箱へ移動する)。プレビューパネルを閉じた際に呼ばれる想定。
 */
export async function deletePreviewPost(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  projectId: number,
  siteId: number,
  postId: string
): Promise<void> {
  await request(
    serverUrl,
    `/api/projects/${projectId}/preview/preview-post?siteId=${siteId}&postId=${encodeURIComponent(postId)}`,
    {
      label: 'deletePreviewPost',
      method: 'DELETE',
      headers: buildHeaders(apiKey, actor),
    }
  );
}

/**
 * プレビューに適用するテーマCSSを取得する。siteIdを指定するとそのサイト、
 * 省略時はプロジェクトのマスター環境サイトのCSSを返す。
 * サイトのCSSは短時間で変わるものではないため、サイトごとにキャッシュする。
 */
export async function getThemeCss(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  projectId: number,
  siteId?: number
): Promise<ThemeCssResult> {
  const query = siteId != null ? `?siteId=${siteId}` : '';
  return cachedRequestJson(
    `project:${projectId}:theme-css:${siteId ?? 'master'}`,
    serverUrl,
    `/api/projects/${projectId}/preview/theme-css${query}`,
    { label: 'getThemeCss', headers: buildHeaders(apiKey, actor) },
    schemas.ThemeCssResultSchema
  );
}

/**
 * URLのOGP情報(ブログカード用)またはAmazon商品情報を取得する。[blogcard]/[amazon]組み込みタグの
 * レンダリング時に使われるキャッシュと同一のもので、ここで呼んでおくとレンダリング時には
 * 既にキャッシュ済みとなり再スクレイピングが発生しない(Issue #339: URLペースト時の先行取得)。
 * 変換結果を返すだけでサーバー状態を変えないため、再試行して差し支えない。
 */
export async function resolveContentCache(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  url: string
): Promise<schemas.ContentCacheResult> {
  return requestJson(
    serverUrl,
    `/api/content-cache?url=${encodeURIComponent(url)}`,
    { label: 'resolveContentCache', headers: buildHeaders(apiKey, actor), retryable: true },
    schemas.ContentCacheResultSchema
  );
}

/**
 * サーバーに保存された生成画像の一覧を取得する。
 * projectId未指定時は全プロジェクトが対象になるため、通常はプロジェクトを指定して呼ぶ。
 */
export async function listGeneratedImages(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  projectId: number
): Promise<schemas.GeneratedImageSummary[]> {
  return cachedRequestJson(
    `project:${projectId}:generated-images`,
    serverUrl,
    `/api/generated-images?projectId=${projectId}`,
    { label: 'listGeneratedImages', headers: buildHeaders(apiKey, actor) },
    schemas.GeneratedImageSummaryListSchema
  );
}

/** 生成画像の詳細(生成に使ったパラメータ一式)を取得する(issue #294)。 */
export async function getGeneratedImageDetail(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  imageId: number
): Promise<schemas.GeneratedImageDetail> {
  return requestJson(
    serverUrl,
    `/api/generated-images/${imageId}`,
    { label: 'getGeneratedImageDetail', headers: buildHeaders(apiKey, actor) },
    schemas.GeneratedImageDetailSchema
  );
}

/** 生成画像のバイナリを取得する。 */
export async function downloadGeneratedImage(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  imageId: number
): Promise<Buffer> {
  return requestBinary(serverUrl, `/api/generated-images/${imageId}/file`, {
    label: 'downloadGeneratedImage',
    headers: buildHeaders(apiKey, actor),
  });
}

/** 生成画像をサーバーから削除する。 */
export async function deleteGeneratedImage(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  imageId: number
): Promise<void> {
  await request(serverUrl, `/api/generated-images/${imageId}`, {
    label: 'deleteGeneratedImage',
    method: 'DELETE',
    headers: buildHeaders(apiKey, actor),
  });
}

export type { GeneratedImageSummary, GeneratedImageDetail } from './schemas';

/** ダイアグラムの新規作成。 */
export async function createDiagram(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  params: { projectId: number; name: string; xml: string; svg: string }
): Promise<schemas.DiagramDetail> {
  const result = await requestJson(
    serverUrl,
    '/api/diagrams',
    {
      label: 'createDiagram',
      method: 'POST',
      headers: buildHeaders(apiKey, actor),
      createBody: jsonBody(params),
    },
    schemas.DiagramDetailSchema
  );
  invalidateProjectCache(params.projectId);
  return result;
}

/** ダイアグラムの一覧。projectId未指定時は全件を返す。 */
export async function listDiagrams(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  projectId: number
): Promise<schemas.DiagramSummary[]> {
  return cachedRequestJson(
    `project:${projectId}:diagrams`,
    serverUrl,
    `/api/diagrams?projectId=${projectId}`,
    { label: 'listDiagrams', headers: buildHeaders(apiKey, actor) },
    schemas.DiagramSummaryListSchema
  );
}

/** ダイアグラムの詳細(xml含む、再編集用)を取得する。 */
export async function getDiagramDetail(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  diagramId: number
): Promise<schemas.DiagramDetail> {
  return requestJson(
    serverUrl,
    `/api/diagrams/${diagramId}`,
    { label: 'getDiagramDetail', headers: buildHeaders(apiKey, actor) },
    schemas.DiagramDetailSchema
  );
}

/** ダイアグラムのSVG本体を取得する。 */
export async function getDiagramSvg(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  diagramId: number
): Promise<string> {
  const buffer = await requestBinary(serverUrl, `/api/diagrams/${diagramId}/svg`, {
    label: 'getDiagramSvg',
    headers: buildHeaders(apiKey, actor),
  });
  return buffer.toString('utf-8');
}

/** ダイアグラムの上書き保存。 */
export async function updateDiagram(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  diagramId: number,
  params: { name: string; xml: string; svg: string },
  projectId: number
): Promise<schemas.DiagramDetail> {
  const result = await requestJson(
    serverUrl,
    `/api/diagrams/${diagramId}`,
    {
      label: 'updateDiagram',
      method: 'PUT',
      headers: buildHeaders(apiKey, actor),
      createBody: jsonBody(params),
    },
    schemas.DiagramDetailSchema
  );
  invalidateProjectCache(projectId);
  return result;
}

/** ダイアグラムをサーバーから削除する。 */
export async function deleteDiagram(
  serverUrl: string,
  apiKey: string,
  actor: Actor | undefined,
  diagramId: number,
  projectId: number
): Promise<void> {
  await request(serverUrl, `/api/diagrams/${diagramId}`, {
    label: 'deleteDiagram',
    method: 'DELETE',
    headers: buildHeaders(apiKey, actor),
  });
  invalidateProjectCache(projectId);
}

export type { DiagramSummary, DiagramDetail } from './schemas';
