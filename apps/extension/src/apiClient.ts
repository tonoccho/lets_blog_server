import * as vscode from 'vscode';
import { z, ZodType } from 'zod';
import { LocalImageReference, guessImageMimeType } from './frontMatter';
import { Actor, allowsInsecureTls } from './config';
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
import { gatewayBaseUrl, gatewayUrl } from './apiBaseUrl';
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
 * 画像生成(/api/ai/image)のタイムアウトを、要求枚数からmedia-service自身の予算として
 * 組み立てるための定数(issue #1105)。値はmedia側の実装と対にしてある:
 *
 * - {@link IMAGE_GEN_OVERHEAD_MS} = `ImageGenerationTimeoutChainTest.NON_POLLING_OVERHEAD_SECONDS`
 * - {@link IMAGE_GEN_BASE_POLL_MS} / {@link IMAGE_GEN_PER_IMAGE_POLL_MS} /
 *   {@link IMAGE_GEN_MIN_POLL_MS} = `ComfyUiClient.maxPollAttempts()`(1回1000ms)
 *
 * サーバーが自分へ許している時間より先にクライアントが諦めると、生成はサーバー側で続いて
 * `generated_images`へ保存されるのに、利用者にはTimeoutErrorしか見えない。#1105以前の
 * 固定120秒がまさにそれで、batch size 16(≒17秒)やbatch count併用では確実に超えていた。
 */
const IMAGE_GEN_OVERHEAD_MS = 300_000;
const IMAGE_GEN_BASE_POLL_MS = 60_000;
const IMAGE_GEN_PER_IMAGE_POLL_MS = 8_000;
const IMAGE_GEN_MIN_POLL_MS = 120_000;

/**
 * 画像生成のタイムアウトの上限(issue #1105)。
 *
 * 実クライアントのトラフィックは`lbs-reverse-proxy`を経由し、nginxの
 * `location = /api/ai/image`が`proxy_read_timeout 3600s`で頭打ちにする。これを超える値を
 * 拡張側に持たせても先にnginxが切るため意味が無い(タイムアウトの鎖:
 * media最悪ケース3308s ≦ gateway 3400s ≦ nginx 3600s)。
 */
const IMAGE_GEN_MAX_TIMEOUT_MS = 3_600_000;

/**
 * gatewayが全応答へ付与する相関IDのヘッダ名
 * (services/gateway CorrelationIdWebFilter.CORRELATION_ID_HEADER、issue #582)。
 */
const CORRELATION_ID_HEADER = 'X-Correlation-Id';

/**
 * リクエストヘッダを組み立てる。issue #565(Device Authorization Grantへの移行)により、
 * 「誰であるか」の判定はサーバー側がAuthorization: Bearerで送られたアクセストークン(JWT)を
 * 検証して行うため、従来の個別ヘッダによる自己申告(APIキー/実行者ID/実行者ロール)は廃止した。
 * actor引数は、この関数を呼ぶ~30個のエンドポイント関数(とその呼び出し元)のシグネチャを
 * 一括で変更する大きな機械的差分を避けるため#565時点では残しており、ヘッダ組み立てには
 * 使わない。issue #566(旧認証機構の撤去)でも、この引数自体は明示的なスコープに含まれて
 * いなかったため意図的に手を付けていない(呼び出し元シグネチャの整理は別途Issueを起票して
 * 対応する想定)。
 */
function buildHeaders(accessToken: string, actor?: Actor, contentType?: string): Record<string, string> {
  const headers: Record<string, string> = { Authorization: `Bearer ${accessToken}` };
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
 *
 * 宛先は gatewayUrl()(apiBaseUrl.ts)が組み立てる gateway 宛のURLで固定する(issue #585)。
 * 以前は呼び出し元がベースURLを引数で引き回していたが、拡張が呼ぶ`/api/**`は例外なく
 * gateway経由になったため、呼び出し元が別のベースURLを渡す余地自体を無くしている。
 */
async function request(path: string, spec: RequestSpec): Promise<HttpResponse> {
  const url = gatewayUrl(path);
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
        // gatewayが全応答へ付与する相関ID(issue #582)。下流サービス障害時に、
        // 利用者へ提示するメッセージとログの両方から同じIDで経路を追えるようにする(issue #585)。
        const correlationId = res.header(CORRELATION_ID_HEADER);
        logger.warn(`${spec.label}: ${method} ${url} -> ${res.status}`, { body, correlationId });
        throw new ApiError(
          `APIエラー (${res.status})`,
          res.status,
          body || res.statusText,
          url,
          correlationId
        );
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
  path: string,
  spec: RequestSpec,
  schema: S
): Promise<z.infer<S>> {
  const res = await request(path, spec);
  const url = gatewayUrl(path);

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
async function requestBinary(path: string, spec: RequestSpec): Promise<Buffer> {
  const res = await request(path, spec);
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

/** キャッシュを経由してJSONを取得する。キーが衝突しないようgatewayのベースURLとパラメータを含める。 */
async function cachedRequestJson<S extends ZodType>(
  cacheKey: string,
  path: string,
  spec: RequestSpec,
  schema: S,
  shouldCache?: (value: z.infer<S>) => boolean
): Promise<z.infer<S>> {
  return responseCache.getOrLoad(
    `${cacheKey}@${gatewayBaseUrl()}`,
    () => requestJson(path, spec, schema),
    shouldCache as ((value: unknown) => boolean) | undefined
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
  AiReviewStepSuggestionsResult,
  AiSectionResult,
  AiTagsResult,
  AssignIssueResult,
  ContentCacheResult,
  ImageGenerationOptions,
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
  AiAskResult,
  AiDraftResult,
  AiImagePromptResult,
  AiImageResult,
  AiReviewStepSuggestionsResult,
  AiSectionResult,
  AiTagsResult,
  AssignIssueResult,
  ContentCacheResult,
  ImageGenerationOptions,
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
  /** 任意。指定するとそのプロジェクトで選択したモデル・プロバイダーを使う(issue #1495)。 */
  projectId?: number;
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
  /**
   * batch size枚の生成を繰り返す回数(issue #1105)。合計はbatchSize × batchCount枚になる。
   * 合計枚数の上限は設けない方針(#1102)のため、最大256枚を要求できる。
   */
  batchCount?: number;
  checkpoint?: string;
  loraName?: string;
  loraWeight?: number;
}

/**
 * 画像生成が最低限確保すべきタイムアウト(ミリ秒)を要求枚数から求める(issue #1105)。
 *
 * media-serviceが1リクエストへ許している時間
 * (`NON_POLLING_OVERHEAD_SECONDS + batchCount × ComfyUiClient.maxPollSeconds(batchSize)`)
 * をそのまま辿るので、サーバーがまだ処理を続けている間にクライアントだけが諦めることがない。
 * nginxの上限(3600秒)は超えない——超えても先にnginxが切るため。
 *
 * 利用者設定`letsBlog.requestTimeoutMs`がこれより長ければ、そちらが優先される
 * (`request()`の`Math.max`)。
 */
export function imageGenerationMinTimeoutMs(params: ImageGenerationParams): number {
  const batchSize = params.batchSize != null && params.batchSize > 0 ? params.batchSize : 1;
  const batchCount = params.batchCount != null && params.batchCount > 0 ? params.batchCount : 1;
  const perRepeatMs = Math.max(
    IMAGE_GEN_MIN_POLL_MS,
    IMAGE_GEN_BASE_POLL_MS + IMAGE_GEN_PER_IMAGE_POLL_MS * batchSize
  );
  return Math.min(IMAGE_GEN_MAX_TIMEOUT_MS, IMAGE_GEN_OVERHEAD_MS + perRepeatMs * batchCount);
}

// メールアドレス/パスワードでのログイン(login()/verifyTotpLogin())は、issue #565で
// Device Authorization Grantへ移行した時点で拡張からは呼び出さなくなり、issue #566で
// サーバー側の対応エンドポイント(/api/auth/login、/api/auth/totp/verify)自体も撤去された
// ため、ここから削除した(詳細はgit history参照)。

/**
 * Markdown記事をCMSへ投稿する(既存投稿がある場合は更新)。
 * 本文中のローカル画像をマルチパートで同梱する。副作用があるため再試行しない。
 *
 * @param params 投稿内容。imagesは実ファイルが存在するものだけを渡すこと。
 */
export async function publishPost(
  apiKey: string,
  params: PublishParams,
  actor?: Actor
): Promise<PublishResult> {
  return requestJson('/api/posts/publish', {
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
  apiKey: string,
  actor: Actor | undefined,
  site: string,
  wpPostId: string
): Promise<void> {
  await request(`/api/posts/${encodeURIComponent(site)}/${encodeURIComponent(wpPostId)}`, {
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
  apiKey: string,
  siteKey: string,
  slug: string,
  actor?: Actor
): Promise<schemas.PostLookupResult | undefined> {
  try {
    return await requestJson(
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
  apiKey: string,
  actor?: Actor
): Promise<{ id: number; name: string; siteKey: string }[]> {
  return cachedRequestJson('sites', '/api/sites', {
    label: 'listSites',
    headers: buildHeaders(apiKey, actor),
  }, schemas.SiteSummaryListSchema);
}

/** 投稿ステータスの選択肢を取得する。UIのハードコードをサーバー側の正準リストへ統一する(issue #472)。 */
export async function getPostStatuses(
  apiKey: string
): Promise<schemas.PostStatusOption[]> {
  return cachedRequestJson('post-statuses', '/api/metadata/post-statuses', {
    label: 'getPostStatuses',
    headers: buildHeaders(apiKey),
  }, schemas.PostStatusOptionListSchema);
}

/** ロールの表示名一覧を取得する(issue #472)。 */
export async function getRoles(
  apiKey: string
): Promise<schemas.RoleOption[]> {
  return cachedRequestJson('roles', '/api/metadata/roles', {
    label: 'getRoles',
    headers: buildHeaders(apiKey),
  }, schemas.RoleOptionListSchema);
}

/**
 * 下書き生成・校正・要約をAIへ依頼する。
 * @param mode draft(下書き) / proofread(校正) / summarize(要約)
 * @param projectId 任意。指定するとそのプロジェクトで選択したモデル・プロバイダーを使う(issue #1495)。
 */
export async function askAi(
  apiKey: string,
  mode: 'draft' | 'proofread' | 'summarize',
  text: string,
  actor?: Actor,
  provider?: string,
  projectId?: number
): Promise<AiDraftResult> {
  return requestJson('/api/ai/draft', {
    label: 'askAi',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ mode, text, provider: provider || undefined, projectId }),
    // 生成結果を返すだけでサーバー状態を変えないため、再試行して差し支えない。
    retryable: true,
  }, schemas.AiGenerationResultSchema);
}

/**
 * エディタ右クリックメニュー「Ask AI」からの質問に、Web検索結果を踏まえて回答する(issue #526)。
 * @param signal 利用者によるキャンセル用。中断時はCancelledErrorが投げられる。
 */
export async function askAiSearch(
  apiKey: string,
  actor: Actor | undefined,
  question: string,
  provider?: string,
  signal?: AbortSignal,
  projectId?: number
): Promise<AiAskResult> {
  return requestJson('/api/ai/ask', {
    label: 'askAiSearch',
    signal,
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ question, provider: provider || undefined, projectId }),
    retryable: true,
  }, schemas.AiGenerationResultSchema);
}

/**
 * セクション本文またはリード文を生成する。
 * @param signal 利用者によるキャンセル用。中断時はCancelledErrorが投げられる。
 */
export async function generateSection(
  apiKey: string,
  actor: Actor | undefined,
  params: AiSectionParams,
  signal?: AbortSignal
): Promise<AiSectionResult> {
  return requestJson('/api/ai/section', {
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
  apiKey: string,
  text: string,
  actor?: Actor,
  provider?: string,
  projectId?: number
): Promise<AiTagsResult> {
  return requestJson('/api/ai/tags', {
    label: 'suggestTags',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ text, provider: provider || undefined, projectId }),
    retryable: true,
  }, schemas.AiTagsResultSchema);
}

/**
 * 多段レビューの1ステップ分の指摘をAIへ依頼する(issue #1215)。
 * プロバイダー/モデルはサーバーがプロジェクトのステップ設定から決めるため、本文だけを送る。
 * @param stepKey JAPANESE / PROOFREADING / FACT_CHECK / READER_PERSPECTIVE / STYLE
 * @param signal 再実行時に古いリクエストを打ち切るためのキャンセル用
 */
export async function reviewStepSuggestions(
  apiKey: string,
  actor: Actor | undefined,
  projectId: number,
  stepKey: string,
  text: string,
  signal?: AbortSignal
): Promise<AiReviewStepSuggestionsResult> {
  return requestJson(`/api/projects/${projectId}/ai/review-steps/${encodeURIComponent(stepKey)}/suggestions`, {
    label: 'reviewStepSuggestions',
    signal,
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ text }),
    retryable: true,
  }, schemas.AiReviewStepSuggestionsResultSchema);
}

/** プロジェクトのマスター環境サイトに既に存在するタグ名一覧。サイト未紐付け等の場合は空配列(issue #525)。 */
export async function listExistingTags(
  apiKey: string,
  actor: Actor | undefined,
  projectId: number
): Promise<string[]> {
  return cachedRequestJson(
    `project:${projectId}:tags`,
    `/api/projects/${projectId}/article-plan/tags`,
    { label: 'listExistingTags', headers: buildHeaders(apiKey, actor) },
    schemas.TagNameListSchema
  );
}

/**
 * 本文中に埋め込めるカスタムタグ一覧(プロジェクト固有 + グローバル)。本文でのコード補完に使う(issue #522)。
 */
export async function listCustomTags(
  apiKey: string,
  actor: Actor | undefined,
  projectId: number
): Promise<schemas.CustomTagSummary[]> {
  return cachedRequestJson(
    `project:${projectId}:custom-tags`,
    `/api/custom-tags?projectId=${projectId}`,
    { label: 'listCustomTags', headers: buildHeaders(apiKey, actor) },
    schemas.CustomTagSummaryListSchema,
    // 空の結果は保持しない。直後にWebで登録したタグが、TTLが切れるまで補完に出なくなるのを避ける(issue #1467)。
    (tags) => tags.length > 0
  );
}

/**
 * ComfyUIで画像を生成する。生成結果はサーバー側にも保存される。
 *
 * batch sizeで指定した枚数はサーバーが`AiImageBatchResponse.images`として全件返すため、
 * ここでも全件を返す(issue #1104)。以前は先頭1枚だけを返しており、残りは生成時間と
 * ディスクを消費したうえで利用者の目に触れずに失われていた。
 *
 * @param signal 利用者によるキャンセル用。
 */
export async function generateImage(
  apiKey: string,
  actor: Actor | undefined,
  projectId: number | undefined,
  params: ImageGenerationParams,
  signal?: AbortSignal
): Promise<AiImageResult[]> {
  // 生成画像はサーバー側に保存されるため、再試行すると重複した生成結果が残る。
  const batch = await requestJson('/api/ai/image', {
    label: 'generateImage',
    signal,
    method: 'POST',
    // 枚数に応じた時間を確保する(issue #1105)。既定の120秒ではbatch size 16や
    // batch countを使った生成が、サーバー側で成功しているのに失敗として扱われる。
    minTimeoutMs: imageGenerationMinTimeoutMs(params),
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody({ projectId, ...params }),
  }, schemas.AiImageBatchResponseSchema);
  return batch.images;
}

/** 画像生成で選択できるモデル/サンプラー/スケジューラ/LoRAの一覧を取得する。 */
export async function getImageGenerationOptions(
  apiKey: string,
  projectId?: number
): Promise<ImageGenerationOptions> {
  const query = projectId ? `?projectId=${projectId}` : '';
  return cachedRequestJson(
    `project:${projectId ?? 'none'}:image-options`,
    `/api/ai/image-options${query}`,
    { label: 'getImageGenerationOptions', headers: buildHeaders(apiKey) },
    schemas.ImageGenerationOptionsSchema
  );
}

/** ユーザー一覧を取得する。 */
export async function listUsers(apiKey: string): Promise<Actor[]> {
  return requestJson('/api/users', {
    label: 'listUsers',
    headers: buildHeaders(apiKey),
  }, schemas.ActorListSchema);
}

/** プロジェクト一覧を取得する。 */
export async function listProjects(apiKey: string, actor?: Actor): Promise<ProjectSummary[]> {
  return cachedRequestJson('projects', '/api/projects', {
    label: 'listProjects',
    headers: buildHeaders(apiKey, actor),
  }, schemas.ProjectSummaryListSchema);
}

/** プロジェクト詳細を取得する。ローカル/テスト/本番のサイト紐付けを含む。 */
export async function getProject(
  apiKey: string,
  actor: Actor | undefined,
  projectId: number
): Promise<ProjectDetail> {
  return cachedRequestJson(
    `project:${projectId}:detail`,
    `/api/projects/${projectId}`,
    { label: 'getProject', headers: buildHeaders(apiKey, actor) },
    schemas.ProjectDetailSchema
  );
}

/** リポジトリのissue一覧のうち、未割り当て(assigneesが空)のものだけを返す。 */
export async function listUnassignedIssues(
  apiKey: string,
  actor: Actor,
  projectId: number,
  state: string = 'open'
): Promise<RepositoryIssue[]> {
  const issues = await cachedRequestJson(
    `project:${projectId}:issues:${state}`,
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
  apiKey: string,
  actor: Actor,
  projectId: number,
  request: PlanChatRequestParams,
  signal?: AbortSignal
): Promise<PlanChatResult> {
  // チャットセッションがサーバー側に記録されるため、再試行すると履歴が重複する。
  return requestJson(`/api/projects/${projectId}/article-plan/chat`, {
    label: 'postPlanChat',
    signal,
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody(request),
  }, schemas.PlanChatResultSchema);
}

/** 記事提出APIへ渡す、push済みのheadブランチの情報(issue #1342)。 */
export interface ArticleSubmissionParams {
  headBranch: string;
  githubIssueNumber: number;
  articleSlug: string;
}

/**
 * push済みの記事ブランチを提出し、サーバー経由でPull Requestを作る(issue #1342)。
 * 拡張はGitHubへ直接アクセスしない。同じheadに開いているPRがあればサーバーがそれを返す(created=false)。
 * PRがサーバー側に記録されるため再試行しない。
 */
export async function submitArticleReview(
  apiKey: string,
  actor: Actor | undefined,
  projectId: number,
  params: ArticleSubmissionParams
): Promise<schemas.ArticleSubmissionResult> {
  return requestJson(`/api/projects/${projectId}/article-review/submissions`, {
    label: 'submitArticleReview',
    method: 'POST',
    headers: buildHeaders(apiKey, actor),
    createBody: jsonBody(params),
  }, schemas.ArticleSubmissionResultSchema);
}

/**
 * 自分が提出した記事のレビュー状態の一覧を取得する(issue #1347)。差し戻しの検知に使う。
 * 副作用の無い取得なので再試行して差し支えない。
 */
export async function listMyArticleReviews(
  apiKey: string,
  actor: Actor | undefined,
  projectId: number
): Promise<schemas.MyArticleReview[]> {
  return requestJson(`/api/projects/${projectId}/article-review/my-reviews`, {
    label: 'listMyArticleReviews',
    method: 'GET',
    headers: buildHeaders(apiKey, actor),
  }, z.array(schemas.MyArticleReviewSchema));
}

/**
 * チャットメッセージ(と任意の履歴)から画像生成プロンプトを作成する。
 * サーバー側で状態を持たないため、再試行して差し支えない。
 * @param signal 利用者によるキャンセル用。
 */
export async function generateImagePrompt(
  apiKey: string,
  actor: Actor | undefined,
  projectId: number,
  history: PlanChatMessage[],
  message: string,
  signal?: AbortSignal,
  provider?: string
): Promise<AiImagePromptResult> {
  return requestJson(`/api/projects/${projectId}/ai/generate-image-prompt`, {
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
  apiKey: string,
  actor: Actor,
  projectId: number,
  issueNumber: number
): Promise<string> {
  const data = await requestJson(
    `/api/projects/${projectId}/article-plan/issues/${issueNumber}/description`,
    { label: 'getIssueDescription', headers: buildHeaders(apiKey, actor) }, schemas.IssueDescriptionSchema);
  return data.body ?? '';
}

/** プロジェクトのマスター環境サイトに既に存在するカテゴリ名一覧。サイト未紐付け等の場合は空配列。 */
export async function listExistingCategories(
  apiKey: string,
  actor: Actor,
  projectId: number
): Promise<string[]> {
  return cachedRequestJson(
    `project:${projectId}:categories`,
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
  apiKey: string,
  actor: Actor,
  projectId: number
): Promise<schemas.CategoryOption[]> {
  return cachedRequestJson(
    `project:${projectId}:categories:hierarchy`,
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
  apiKey: string,
  actor: Actor,
  projectId: number,
  history: PlanChatMessage[],
  signal?: AbortSignal
): Promise<SuggestMetadataResult> {
  return requestJson(
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
  apiKey: string,
  actor: Actor,
  projectId: number,
  history: PlanChatMessage[],
  signal?: AbortSignal
): Promise<SuggestStructureResult> {
  return requestJson(
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
  apiKey: string,
  actor: Actor,
  projectId: number,
  issueNumber: number,
  structure: string
): Promise<AcceptStructureResult> {
  const result = await requestJson(
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
  apiKey: string,
  actor: Actor,
  projectId: number,
  issueNumber: number
): Promise<AssignIssueResult> {
  const result = await requestJson(
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
  apiKey: string,
  actor: Actor | undefined,
  projectId: number,
  markdown: string
): Promise<string> {
  const data = await requestJson(`/api/projects/${projectId}/preview/render`, {
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

/** 署名付きプレビューURLの発行依頼(issue #1562)。 */
export interface SignedPreviewUrlInput {
  siteId?: number;
  title: string;
  contentHtml: string;
  categories?: string[];
  tags?: string[];
  featuredImageDataUri?: string;
}

/**
 * 署名付きプレビューURLの発行結果。letsblogプラグインが使えないサイト(未導入・要更新)は、
 * サーバーが409で拒否する(issue #1557)。それは異常ではなく導入の案内が必要な状態なので、
 * 例外にせず`pluginUnavailable`として返す。
 */
export type SignedPreviewUrlResult =
  | { kind: 'ready'; url: string; expiresAt: number }
  | { kind: 'pluginUnavailable'; message: string; needsUpdate: boolean };

/** プラグインが使えないときにサーバーが409の本文へ載せる文言に含まれる目印。 */
const PLUGIN_UNAVAILABLE_MARKER = 'letsblog プラグイン';

/**
 * 投稿を作らずに実サイトのテーマで表示する署名付きプレビューURLを発行する(issue #1561)。
 * URLは短時間で失効するため、プレビューのたびに取得し直す(キャッシュしない)。
 */
export async function createSignedPreviewUrl(
  apiKey: string,
  actor: Actor | undefined,
  projectId: number,
  input: SignedPreviewUrlInput
): Promise<SignedPreviewUrlResult> {
  try {
    const signed = await requestJson(
      `/api/projects/${projectId}/preview/signed-url`,
      {
        label: 'createSignedPreviewUrl',
        method: 'POST',
        headers: buildHeaders(apiKey, actor),
        createBody: jsonBody(input),
      },
      schemas.SignedPreviewUrlSchema
    );
    return { kind: 'ready', url: signed.url, expiresAt: signed.expiresAt };
  } catch (err) {
    if (err instanceof ApiError && err.status === 409 && err.responseBody.includes(PLUGIN_UNAVAILABLE_MARKER)) {
      return {
        kind: 'pluginUnavailable',
        message: pluginUnavailableMessage(err.responseBody),
        needsUpdate: err.responseBody.includes('要更新'),
      };
    }
    throw err;
  }
}

/** 409の本文(JSONの`message`)から文言を取り出す。JSONでなければ本文をそのまま使う。 */
function pluginUnavailableMessage(body: string): string {
  try {
    const parsed: unknown = JSON.parse(body);
    if (parsed && typeof parsed === 'object' && typeof (parsed as { message?: unknown }).message === 'string') {
      return (parsed as { message: string }).message;
    }
  } catch {
    // JSONでない本文はそのまま使う。
  }
  return body;
}

/**
 * renderPreviewSkeletonがローカル/テスト環境向けに作成した非公開プレビュー投稿を削除する
 * (WordPressの既定挙動でゴミ箱へ移動する)。プレビューパネルを閉じた際に呼ばれる想定。
 */
export async function deletePreviewPost(
  apiKey: string,
  actor: Actor | undefined,
  projectId: number,
  siteId: number,
  postId: string
): Promise<void> {
  await request(
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
  apiKey: string,
  actor: Actor | undefined,
  projectId: number,
  siteId?: number
): Promise<ThemeCssResult> {
  const query = siteId != null ? `?siteId=${siteId}` : '';
  return cachedRequestJson(
    `project:${projectId}:theme-css:${siteId ?? 'master'}`,
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
  apiKey: string,
  actor: Actor | undefined,
  url: string
): Promise<schemas.ContentCacheResult> {
  return requestJson(
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
  apiKey: string,
  actor: Actor | undefined,
  projectId: number
): Promise<schemas.GeneratedImageSummary[]> {
  return cachedRequestJson(
    `project:${projectId}:generated-images`,
    `/api/generated-images?projectId=${projectId}`,
    { label: 'listGeneratedImages', headers: buildHeaders(apiKey, actor) },
    schemas.GeneratedImageSummaryListSchema
  );
}

/** 生成画像の詳細(生成に使ったパラメータ一式)を取得する(issue #294)。 */
export async function getGeneratedImageDetail(
  apiKey: string,
  actor: Actor | undefined,
  imageId: number
): Promise<schemas.GeneratedImageDetail> {
  return requestJson(
    `/api/generated-images/${imageId}`,
    { label: 'getGeneratedImageDetail', headers: buildHeaders(apiKey, actor) },
    schemas.GeneratedImageDetailSchema
  );
}

/** 生成画像のバイナリを取得する。 */
export async function downloadGeneratedImage(
  apiKey: string,
  actor: Actor | undefined,
  imageId: number
): Promise<Buffer> {
  return requestBinary(`/api/generated-images/${imageId}/file`, {
    label: 'downloadGeneratedImage',
    headers: buildHeaders(apiKey, actor),
  });
}

/** 生成画像をサーバーから削除する。 */
export async function deleteGeneratedImage(
  apiKey: string,
  actor: Actor | undefined,
  imageId: number
): Promise<void> {
  await request(`/api/generated-images/${imageId}`, {
    label: 'deleteGeneratedImage',
    method: 'DELETE',
    headers: buildHeaders(apiKey, actor),
  });
}

export type { GeneratedImageSummary, GeneratedImageDetail } from './schemas';

/** ダイアグラムの新規作成。 */
export async function createDiagram(
  apiKey: string,
  actor: Actor | undefined,
  params: { projectId: number; name: string; xml: string; svg: string }
): Promise<schemas.DiagramDetail> {
  const result = await requestJson(
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
  apiKey: string,
  actor: Actor | undefined,
  projectId: number
): Promise<schemas.DiagramSummary[]> {
  return cachedRequestJson(
    `project:${projectId}:diagrams`,
    `/api/diagrams?projectId=${projectId}`,
    { label: 'listDiagrams', headers: buildHeaders(apiKey, actor) },
    schemas.DiagramSummaryListSchema
  );
}

/** ダイアグラムの詳細(xml含む、再編集用)を取得する。 */
export async function getDiagramDetail(
  apiKey: string,
  actor: Actor | undefined,
  diagramId: number
): Promise<schemas.DiagramDetail> {
  return requestJson(
    `/api/diagrams/${diagramId}`,
    { label: 'getDiagramDetail', headers: buildHeaders(apiKey, actor) },
    schemas.DiagramDetailSchema
  );
}

/** ダイアグラムのSVG本体を取得する。 */
export async function getDiagramSvg(
  apiKey: string,
  actor: Actor | undefined,
  diagramId: number
): Promise<string> {
  const buffer = await requestBinary(`/api/diagrams/${diagramId}/svg`, {
    label: 'getDiagramSvg',
    headers: buildHeaders(apiKey, actor),
  });
  return buffer.toString('utf-8');
}

/** ダイアグラムの上書き保存。 */
export async function updateDiagram(
  apiKey: string,
  actor: Actor | undefined,
  diagramId: number,
  params: { name: string; xml: string; svg: string },
  projectId: number
): Promise<schemas.DiagramDetail> {
  const result = await requestJson(
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
  apiKey: string,
  actor: Actor | undefined,
  diagramId: number,
  projectId: number
): Promise<void> {
  await request(`/api/diagrams/${diagramId}`, {
    label: 'deleteDiagram',
    method: 'DELETE',
    headers: buildHeaders(apiKey, actor),
  });
  invalidateProjectCache(projectId);
}

export type { DiagramSummary, DiagramDetail } from './schemas';
