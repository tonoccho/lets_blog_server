import 'server-only';
import { after } from 'next/server';
import { cookies, headers } from 'next/headers';
import { getToken } from 'next-auth/jwt';
import { gatewayUrl } from './apiBaseUrl';
import { SESSION_EXPIRED_MESSAGE } from './sessionExpired';

export type CmsType = "WORDPRESS";

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
  sshConfigured: boolean;
  /** letsblog プラグインへの同期の状態(issue #1558)。一度も同期していなければ null。 */
  letsblogSync?: LetsblogSyncState | null;
  /** サイト個別の管理画面パス。null・未設定はグローバル既定値を使う(issue #1534)。 */
  adminPath?: string | null;
}

/** letsblog プラグインへの同期の結果(issue #1558)。 */
export type LetsblogSyncStatus = "SYNCED" | "FAILED" | "SKIPPED";

export interface LetsblogSyncState {
  status: LetsblogSyncStatus;
  error: string | null;
  hash: string | null;
  syncedAt: string | null;
}

export interface PostSummary {
  id: number;
  siteId: number;
  siteName: string;
  wpPostId: string;
  slug: string | null;
  status: string;
  lastPublishedAt: string | null;
  categories: string[];
  publishScheduledAt: string | null;
}

export interface GenerationJob {
  id: number;
  type: string;
  status: string;
  createdAt: string;
  updatedAt: string;
}

export interface GeneratedImageSummary {
  id: number;
  projectId: number | null;
  /** アップロード画像(provider=UPLOAD、issue #1599)はprompt・checkpointを持たずnull。 */
  prompt: string | null;
  checkpoint: string | null;
  createdAt: string;
  tags: string[];
  provider: string;
  /** 所属フォルダ(issue #1493)。nullは未分類。 */
  folderId: number | null;
}

/**
 * 生成画像の入れ子フォルダ(issue #1493)。横断の共通ツリーで、`parentId` が null なら最上位。
 * 画像の件数や画像の情報は含まれない。
 */
export interface GeneratedImageFolder {
  id: number;
  name: string;
  parentId: number | null;
}

export interface GeneratedImageDetail extends GeneratedImageSummary {
  negativePrompt: string;
  steps: number;
  cfgScale: number;
  samplerName: string;
  scheduler: string;
  /**
   * 生成に実際に使われたseed(issue #1101)。COMFYUIでは常に入る。
   * ChatGPTの画像生成API(gpt-image-1)はseedを受け付けず再現できないためnull。
   * #1101以前に生成した画像もnullになる(遡って補完する手段が無い)。
   */
  seed: number | null;
  width: number;
  height: number;
  batchSize: number;
  /** バッチ内の位置(0起点、issue #1101)。#1101以前に生成した画像はnull。 */
  batchIndex: number | null;
  loraName: string | null;
  loraWeight: number | null;
  /** img2imgの参照元の画像ID(issue #1601)。参照画像を使っていない画像はnull。 */
  sourceImageId?: number | null;
}

export interface SiteRegisterInput {
  name: string;
  siteKey: string;
  cmsType: CmsType;
  credentials: Record<string, string>;
  /** 省略・空欄はグローバル既定を使う(issue #1533)。 */
  adminPath?: string;
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

/**
 * next-auth/jwtのgetToken()はreq.cookies/req.headersしか参照しないため、
 * NextRequestが無いServer Component/Server Actionからでもnext/headersのcookies()/headers()を
 * そのまま渡せる(型定義上はNextRequest等を期待しているため as any で吸収する)。
 *
 * 注意: ここで読むCookieは、SessionProvider(apps/web/src/app/SessionProvider.tsx)のrefetchIntervalに
 * よってブラウザが定期的に/api/auth/sessionを叩くことでjwtコールバックのリフレッシュが走り、
 * 更新され続けている前提(再取得間隔・更新猶予・トークン寿命の関係はissue #1053で
 * `apps/web/src/lib/tokenRefreshPolicy.ts` に集約し、テストで固定した)。
 * getToken()自体はjwtコールバックを再実行しない生のCookieデコードのため、
 * ここで読むaccessTokenが失効間際でないかはSessionProvider側の更新頻度に依存する。
 *
 * これは `@/lib/session.ts` の `requireSession()` / `requireAdminSession()` が呼ぶ
 * `getServerSession(authOptions)`(引数1個・RSCモード)がjwtコールバックを再評価して得た
 * 更新済みトークンとは**別物**である点に注意(issue #1053)。RSCモードではnext-authが
 * `setCookie(){}` という何もしないresスタブを組み立てるため、その呼び出しがリフレッシュに
 * 成功してもCookieへは書き戻されず、結果は捨てられる。したがってServer Component /
 * Server Actionからの`requireAdminSession()`通過は「Cookie内のaccessTokenが更新された」
 * ことを意味しない。ここ(currentToken)が実際に読むのは、あくまで
 * SessionProvider側のポーリングが最後に書き込んだCookieの生の値である。
 */
async function currentToken() {
  return getToken({
    req: { cookies: await cookies(), headers: await headers() } as unknown as Parameters<typeof getToken>[0]['req'],
    secret: process.env.NEXTAUTH_SECRET,
  });
}

/** ログイン中ユーザーのKeycloakアクセストークンをNextAuthのJWT(HttpOnly cookie)から取得する。 */
async function currentAccessToken(): Promise<string> {
  const token = await currentToken();
  if (!token?.accessToken) {
    throw new Error('ログインしていないか、アクセストークンが未取得です。再度ログインしてください。');
  }
  return token.accessToken;
}

const OPERATION_ID_HEADER = 'x-operation-id';

/** proxy.tsがリクエストごとに発番したIDを読み取り、1回の操作で発生した複数のAPI呼び出しを束ねる。 */
async function currentOperationId(): Promise<string> {
  const hdrs = await headers();
  return hdrs.get(OPERATION_ID_HEADER) ?? crypto.randomUUID();
}

interface OperationLogEntryInput {
  operationId: string;
  method: string;
  path: string;
  statusCode: number | null;
  durationMs: number;
  success: boolean;
  errorMessage?: string;
}

/**
 * 操作ログをバックエンドへ記録する(issue #143)。apiRequest()自身から呼ぶため、
 * 無限再帰を避けるためにapiRequest()を経由せず直接fetchする(=この記録リクエスト自体はログされない)。
 * ベースURLの組み立てだけは共通のgatewayUrl()を使う(issue #584)。
 * 記録の失敗が本来のAPI呼び出しに影響しないよう例外は握りつぶす。
 */
async function recordOperationLog(accessToken: string, entry: OperationLogEntryInput): Promise<void> {
  try {
    await fetch(gatewayUrl('/api/operation-logs'), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${accessToken}`,
      },
      body: JSON.stringify(entry),
      cache: 'no-store',
    });
  } catch {
    // 操作ログの記録失敗は無視する(本来の操作を妨げない)
  }
}

interface ApiRequestInit extends RequestInit {
  /** ログイン前でも呼べる公開エンドポイント(setup/setup-status)向け。既定はtrue。 */
  requiresAuth?: boolean;
  /**
   * falseにすると、HTTPエラー応答でも例外を投げずResponseをそのまま返す。
   * 上流のステータスをそのままブラウザへ中継したい呼び出し元(SSE中継ルート)向け。既定はtrue。
   */
  throwOnError?: boolean;
}

/**
 * gatewayが401を返したときに例外へ載せる文言(issue #1053)。
 *
 * 更新猶予(tokenRefreshPolicy.ts)を入れてもなお401が返るのは、`ssoSessionIdleTimeout`
 * 超過など**正当に再ログインが必要な場合**に限られる(auth.tsのrefreshAccessToken()が
 * 失敗しtoken.errorが立つが、currentToken()は生のCookieデコードのためtoken.errorを見ずに
 * 失効済みaccessTokenをそのまま送ってしまう。gatewayはBearerが付いていれば無条件に検証し
 * 失効していれば401を返す。SecurityConfigのjavadoc参照)。この場合に生の
 * `APIエラー (401): Unauthorized` を出すと、利用者は何をすればよいか分からない。
 * 401のときだけ再ログインを促す文言に差し替える(他のステータスコードの文言整備は
 * 本Issueのスコープ外)。
 */

/**
 * バックエンド(gateway)への全リクエストが通る唯一の共通経路(issue #584)。
 * ベースURLの組み立て・Authorizationヘッダーの付与・キャッシュ無効化・操作ログの記録・
 * エラーハンドリングをここに集約する。
 *
 * JSONを返さない呼び出し(バイナリのダウンロード・SSEの中継)もこの関数を直接使い、
 * apiFetch()はこの上に乗るJSONデコード用の薄いラッパーとする。
 */
async function apiRequest(path: string, init?: ApiRequestInit): Promise<Response> {
  const { requiresAuth = true, throwOnError = true, ...requestInit } = init ?? {};
  const accessToken = requiresAuth ? await currentAccessToken() : undefined;
  const method = (requestInit.method ?? 'GET').toString().toUpperCase();
  const startedAt = Date.now();
  // after()内ではRequest-time API(headers/cookies)を呼べないため、レンダリング中に読んでおく。
  const operationId = accessToken ? await currentOperationId() : null;

  const scheduleLog = (entry: OperationLogEntryInput) => {
    if (accessToken && operationId) {
      after(() => recordOperationLog(accessToken, entry));
    }
  };

  let res: Response;
  try {
    res = await fetch(gatewayUrl(path), {
      ...requestInit,
      headers: {
        ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
        ...(requestInit.headers ?? {}),
      },
      cache: 'no-store',
    });
  } catch (err) {
    scheduleLog({
      operationId: operationId ?? '',
      method,
      path,
      statusCode: null,
      durationMs: Date.now() - startedAt,
      success: false,
      errorMessage: err instanceof Error ? err.message : String(err),
    });
    throw err;
  }

  const durationMs = Date.now() - startedAt;

  if (!res.ok) {
    // throwOnError=falseのときはボディを呼び出し元へそのまま渡すため消費しない
    // (SSE中継等の意味論。この分岐自体は変えない。issue #1053のスコープはthrowOnError=trueの
    // 401のみ)。
    const message = throwOnError
      ? res.status === 401
        ? SESSION_EXPIRED_MESSAGE
        : `APIエラー (${res.status}): ${(await res.text().catch(() => '')) || res.statusText}`
      : `APIエラー (${res.status}): ${res.statusText}`;
    scheduleLog({ operationId: operationId ?? '', method, path, statusCode: res.status, durationMs, success: false, errorMessage: message });
    if (throwOnError) {
      throw new Error(message);
    }
    return res;
  }

  scheduleLog({ operationId: operationId ?? '', method, path, statusCode: res.status, durationMs, success: true });
  return res;
}

async function apiFetch<T>(path: string, init?: ApiRequestInit): Promise<T> {
  const res = await apiRequest(path, init);
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

export interface SiteDetail {
  id: number;
  name: string;
  siteKey: string;
  cmsType: CmsType;
  baseUrl: string;
  createdAt: string;
  updatedAt: string;
  managedWordpress: boolean;
  sshConfigured: boolean;
  credentials: Record<string, string>;
  configuredSecretFields: string[];
  /** サイト個別の管理画面パス。null はグローバル既定値を使う(上書きなし)。 */
  adminPath: string | null;
}

export function getSiteDetail(id: number): Promise<SiteDetail> {
  return apiFetch<SiteDetail>(`/api/sites/${id}`);
}

export function registerSite(input: SiteRegisterInput): Promise<Site> {
  return apiFetch<Site>('/api/sites', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function createManagedWordPressSite(input: ManagedWordPressSiteInput): Promise<Site> {
  return apiFetch<Site>('/api/sites/managed-wordpress', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function deleteSite(id: number): Promise<void> {
  return apiFetch<void>(`/api/sites/${id}`, { method: 'DELETE' });
}

export interface SiteUpdateInput {
  name?: string;
  credentials?: Record<string, string>;
  /** 省略=変更しない、空文字=上書き解除、それ以外=設定。 */
  adminPath?: string;
}

export function updateSite(id: number, input: SiteUpdateInput): Promise<Site> {
  return apiFetch<Site>(`/api/sites/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export interface SiteConnectionCheckResult {
  connectionCheckStatus: "SUCCESS" | "FAILED";
  hasAdminCapability: boolean | null;
  failureReason: string | null;
  detail: string | null;
}

export function checkSiteConnection(id: number): Promise<SiteConnectionCheckResult> {
  return apiFetch(`/api/sites/${id}/test-connection`, { method: 'POST' });
}

export interface WpCliInstallResult {
  message: string;
}

export function installWpCli(id: number): Promise<WpCliInstallResult> {
  return apiFetch<WpCliInstallResult>(`/api/sites/${id}/install-wp-cli`, { method: 'POST' });
}

/** サイトの letsblog プラグインの導入状態(issue #1557)。 */
export type LetsblogPluginState = "INSTALLED" | "NOT_INSTALLED" | "NEEDS_UPDATE";

export interface LetsblogPluginStatus {
  state: LetsblogPluginState;
  version: string | null;
  protocolVersion: number | null;
}

export function getLetsblogPluginStatus(id: number): Promise<LetsblogPluginStatus> {
  return apiFetch<LetsblogPluginStatus>(`/api/sites/${id}/letsblog-plugin`);
}

export function installLetsblogPlugin(id: number): Promise<LetsblogPluginStatus> {
  return apiFetch<LetsblogPluginStatus>(`/api/sites/${id}/letsblog-plugin/install`, { method: 'POST' });
}

export async function getLetsblogSync(id: number): Promise<LetsblogSyncState | null> {
  // 未同期のとき応答本文は空で、apiFetch は undefined を返す。Server Action の往復で
  // undefined は「未取得」と区別できないため、ここで null(未同期)に揃える(issue #1660)。
  return (await apiFetch<LetsblogSyncState | undefined>(`/api/sites/${id}/letsblog-sync`)) ?? null;
}

export function resyncLetsblog(id: number): Promise<LetsblogSyncState> {
  return apiFetch<LetsblogSyncState>(`/api/sites/${id}/letsblog-sync`, { method: 'POST' });
}

export type StaticContentType = "PRIVACY_POLICY" | "OPERATOR_INFO" | "TERMS_OF_SERVICE";

export interface StaticContent {
  id: number;
  siteId: number;
  contentType: StaticContentType;
  body: string;
  createdAt: string;
  updatedAt: string;
}

export function listStaticContent(siteId: number): Promise<StaticContent[]> {
  return apiFetch<StaticContent[]>(`/api/sites/${siteId}/static-content`);
}

export function generateStaticContent(
  siteId: number,
  contentType: StaticContentType
): Promise<StaticContent> {
  return apiFetch<StaticContent>(`/api/sites/${siteId}/static-content/generate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ contentType }),
  });
}

/**
 * 静的コンテンツの生成を非同期ジョブとして要求する(`POST /api/sites/{id}/static-content/generate/jobs`、issue #1409)。
 * 生成の完了を待たずに受理されたジョブ(id・状態)が返り、生成した本文は `static_content` へ書かれず、
 * ジョブの結果(`GET /api/generation-jobs/{id}` の `resultPayload.body`)にだけ置かれる。
 */
export function startStaticContentGenerationJob(
  siteId: number,
  contentType: StaticContentType
): Promise<GenerationJob> {
  return apiFetch<GenerationJob>(`/api/sites/${siteId}/static-content/generate/jobs`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ contentType }),
  });
}

/** 静的コンテンツの「保存」(`PUT /api/sites/{id}/static-content/{種別}`、issue #1409)。同じ種別があれば上書きする。 */
export function saveStaticContent(
  siteId: number,
  contentType: StaticContentType,
  body: string
): Promise<StaticContent> {
  return apiFetch<StaticContent>(`/api/sites/${siteId}/static-content/${contentType}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ body }),
  });
}

export interface SshKeyPair {
  publicKeyLine: string;
  privateKeyPem: string;
}

export function generateSshKeyPair(comment: string | undefined): Promise<SshKeyPair> {
  return apiFetch<SshKeyPair>('/api/sites/ssh-keypair', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ comment: comment || null }),
  });
}

/**
 * 名前をつけて保存・管理するSSH鍵ペア(issue #413)。admin限定。秘密鍵は生成直後の
 * レスポンス(createSshKeyPair)でのみ返り、一覧(listSshKeyPairs)では公開鍵のみを返す。
 */
export interface SavedSshKeyPair {
  id: number;
  name: string;
  comment: string | null;
  publicKeyLine: string;
  createdAt: string;
}

export interface GeneratedSshKeyPair extends SavedSshKeyPair {
  privateKeyPem: string;
}

export function listSshKeyPairs(): Promise<SavedSshKeyPair[]> {
  return apiFetch<SavedSshKeyPair[]>('/api/ssh-key-pairs');
}

export function createSshKeyPair(
  input: { name: string; comment?: string }
): Promise<GeneratedSshKeyPair> {
  return apiFetch<GeneratedSshKeyPair>('/api/ssh-key-pairs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: input.name, comment: input.comment || null }),
  });
}

export function deleteSshKeyPair(id: number): Promise<void> {
  return apiFetch<void>(`/api/ssh-key-pairs/${id}`, { method: 'DELETE' });
}

export function listPosts(): Promise<PostSummary[]> {
  return apiFetch<PostSummary[]>('/api/posts');
}

export function listGenerationJobs(): Promise<GenerationJob[]> {
  return apiFetch<GenerationJob[]>('/api/generation-jobs');
}

/**
 * 生成画像の一覧。`options` を省略すると従来どおり全件を返す(issue #1472)。
 * `limit` を指定すると createdAt 降順・同時刻は id 降順の並びで `offset` 件を飛ばした位置から最大 `limit` 件、
 * `tag` は絞り込んだ後の一覧に対して適用される。
 * `folderId` はそのフォルダと子孫フォルダの画像、`unfiled` はどのフォルダにも属さない画像に絞る(issue #1493)。
 */
export function listGeneratedImages(
  projectId?: number,
  options: {
    limit?: number;
    offset?: number;
    tag?: string;
    folderId?: number;
    unfiled?: boolean;
    source?: 'UPLOAD' | 'AI';
  } = {},
): Promise<GeneratedImageSummary[]> {
  const params = new URLSearchParams();
  if (projectId) params.set('projectId', String(projectId));
  if (options.tag !== undefined) params.set('tag', options.tag);
  // フォルダ(子孫を含む)と未分類の絞り込み(issue #1493)。同時指定はサーバが400で拒否する。
  if (options.folderId !== undefined) params.set('folderId', String(options.folderId));
  if (options.unfiled) params.set('unfiled', 'true');
  // 種別(アップロード / AI生成)の絞り込み(issue #1647)。不正値はサーバが400で拒否する。
  if (options.source !== undefined) params.set('source', options.source);
  if (options.limit !== undefined) params.set('limit', String(options.limit));
  if (options.offset !== undefined) params.set('offset', String(options.offset));
  const query = params.toString();
  return apiFetch<GeneratedImageSummary[]>(`/api/generated-images${query ? `?${query}` : ''}`);
}

export function getGeneratedImage(id: number): Promise<GeneratedImageDetail> {
  return apiFetch<GeneratedImageDetail>(`/api/generated-images/${id}`);
}

/**
 * 手元の画像(JPEG/PNG)を生成画像ギャラリーへ登録する(issue #1599)。サーバーは切り抜き・拡縮せず元の解像度のまま
 * (メタ情報は除去)、出所を`UPLOAD`として保存する(issue #1654)。`uploadAvatar`と同じくmultipartの`file`で送る。
 */
export function uploadGeneratedImage(projectId: number, file: File): Promise<GeneratedImageDetail> {
  const formData = new FormData();
  formData.append('file', file);
  return apiFetch<GeneratedImageDetail>(`/api/generated-images/upload?projectId=${projectId}`, {
    method: 'POST',
    body: formData,
  });
}

/** 画像の編集(issue #1655)の切り抜き範囲。画素単位で、座標は操作を適用した後の画像の左上が原点。 */
export interface GeneratedImageCrop {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** 画像の編集(issue #1656)の明るさ・コントラスト。どちらも -100〜+100 で、0 が変更なし。 */
export interface GeneratedImageAdjustment {
  brightness: number;
  contrast: number;
}

/**
 * 画像を回転・反転・切り抜き・明るさ/コントラスト調整して、新しい画像として保存する(issue #1655, #1656)。
 * 操作は並べた順に適用し、切り抜きは操作後の画像の座標で指定する。調整は変更なし(0・0)なら `null`。
 * 元の画像は変わらず、新しい画像は元のタグ・フォルダ・種別を引き継ぐ。
 */
export function editGeneratedImage(
  id: number,
  operations: string[],
  crop: GeneratedImageCrop | null,
  adjustment: GeneratedImageAdjustment | null,
): Promise<GeneratedImageDetail> {
  return apiFetch<GeneratedImageDetail>(`/api/generated-images/${id}/edit`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ operations, crop, adjustment }),
  });
}

export function deleteGeneratedImage(id: number): Promise<void> {
  return apiFetch<void>(`/api/generated-images/${id}`, { method: 'DELETE' });
}

/** 生成画像の一括削除の結果(issue #1492)。`failures` は画像id(文字列)から失敗理由。 */
export interface GeneratedImageBulkDeleteResult {
  deletedCount: number;
  failedCount: number;
  deletedIds: number[];
  failures: Record<string, string>;
}

/**
 * 生成画像をまとめて削除する(issue #1492)。権限の無い画像が1枚でも含まれれば403で、1枚も削除されない。
 */
export function bulkDeleteGeneratedImages(imageIds: number[]): Promise<GeneratedImageBulkDeleteResult> {
  return apiFetch<GeneratedImageBulkDeleteResult>('/api/generated-images/bulk-delete', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ imageIds }),
  });
}

/** 生成画像フォルダの一覧(フラット。親子は `parentId`)。認証済みなら誰でも取得できる(issue #1493)。 */
export function listGeneratedImageFolders(): Promise<GeneratedImageFolder[]> {
  return apiFetch<GeneratedImageFolder[]>('/api/generated-images/folders');
}

/** フォルダを作成する。`parentId` が null なら最上位。admin のみ(issue #1493)。 */
export function createGeneratedImageFolder(name: string, parentId: number | null): Promise<GeneratedImageFolder> {
  return apiFetch<GeneratedImageFolder>('/api/generated-images/folders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, parentId }),
  });
}

/** 画像をフォルダへ入れる。`folderId` が null なら未分類へ戻す。admin のみ(issue #1493)。 */
export function setGeneratedImageFolder(id: number, folderId: number | null): Promise<GeneratedImageDetail> {
  return apiFetch<GeneratedImageDetail>(`/api/generated-images/${id}/folder`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ folderId }),
  });
}

/** フォルダ削除の影響範囲(issue #1494)。`descendantFolderCount` は自分を含まない子孫フォルダ数、`imageCount` は未分類へ戻る画像の枚数。 */
export interface GeneratedImageFolderDeleteImpact {
  descendantFolderCount: number;
  imageCount: number;
}

/** フォルダを改名する。同じ親の下の重複名は検査しない。admin のみ(issue #1494)。 */
export function renameGeneratedImageFolder(id: number, name: string): Promise<GeneratedImageFolder> {
  return apiFetch<GeneratedImageFolder>(`/api/generated-images/folders/${id}/name`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  });
}

/** フォルダ削除の影響範囲(確認ダイアログ用、何も変更しない)。admin のみ(issue #1494)。 */
export function getGeneratedImageFolderDeleteImpact(id: number): Promise<GeneratedImageFolderDeleteImpact> {
  return apiFetch<GeneratedImageFolderDeleteImpact>(`/api/generated-images/folders/${id}/delete-impact`);
}

/** フォルダと子孫を削除する。所属画像は削除せず未分類へ戻る。admin のみ(issue #1494)。 */
export function deleteGeneratedImageFolder(id: number): Promise<void> {
  return apiFetch<void>(`/api/generated-images/folders/${id}`, { method: 'DELETE' });
}

/** 自動生成されたタグを手動で編集・追加する(issue #281)。 */
export function updateGeneratedImageTags(id: number, tags: string[]): Promise<GeneratedImageDetail> {
  return apiFetch<GeneratedImageDetail>(`/api/generated-images/${id}/tags`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tags }),
  });
}

export async function downloadGeneratedImageFile(id: number): Promise<{ body: ArrayBuffer; mimeType: string }> {
  const res = await apiRequest(`/api/generated-images/${id}/file`);
  return {
    body: await res.arrayBuffer(),
    mimeType: res.headers.get('content-type') ?? 'image/png',
  };
}

export interface ImageGenerationOptionsResponse {
  checkpoints: string[];
  selectedCheckpoint: string;
  samplers: string[];
  schedulers: string[];
  loras: string[];
  defaultWidth: number;
  defaultHeight: number;
  defaultNegativePrompt: string | null;
  defaultQualityPrompt: string | null;
}

export function getImageGenerationOptions(projectId: number): Promise<ImageGenerationOptionsResponse> {
  return apiFetch<ImageGenerationOptionsResponse>(`/api/ai/image-options?projectId=${projectId}`);
}

export interface AiImageGenerationParams {
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
  /** batch sizeでの生成を何回繰り返すか(1リクエスト内、リピートごとにseedが変わる。issue #1102/#1103)。 */
  batchCount?: number;
  checkpoint?: string;
  loraName?: string;
  loraWeight?: number;
  projectId?: number;
  /** img2imgの参照画像(同じプロジェクトのギャラリー画像のID、issue #1601)。省略するとtxt2img。 */
  referenceImageId?: number;
  /** img2imgの変化の強さ(0〜1、issue #1601)。参照画像があるときだけ使われ、省略すると0.6。 */
  denoise?: number;
}

/**
 * 画像生成を非同期のジョブとして要求する(`POST /api/ai/image/jobs`、issue #1405/#1408)。
 * 生成の完了を待たずに受理されたジョブ(id・状態)が返り、結果は処理キューと
 * `GET /api/generation-jobs/{id}` の `resultPayload.imageIds` で引く。
 */
export function startProjectImageJob(params: AiImageGenerationParams): Promise<GenerationJob> {
  return apiFetch<GenerationJob>('/api/ai/image/jobs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
  });
}

export interface AiImagePromptResponse {
  prompt: string;
}

export function generateImagePromptFromChat(
  projectId: number,
  data: { history: PlanChatMessage[]; message: string; provider?: string }
): Promise<AiImagePromptResponse> {
  return apiFetch<AiImagePromptResponse>(`/api/projects/${projectId}/ai/generate-image-prompt`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
}

export function uploadProjectAssetImage(
  projectId: number,
  generatedImageId: number
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/asset-images/${generatedImageId}/upload`, {
    method: 'POST',
  });
}

export function listUsers(): Promise<AppUser[]> {
  return apiFetch<AppUser[]>('/api/users');
}

export function getSetupStatus(): Promise<{ needsSetup: boolean }> {
  return apiFetch<{ needsSetup: boolean }>('/api/auth/setup-status', { requiresAuth: false });
}

export function setupInitialAdmin(email: string, password: string): Promise<AuthenticatedUser> {
  return apiFetch<AuthenticatedUser>('/api/auth/setup', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
    requiresAuth: false,
  });
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
  timezone: string | null;
  avatarUrl: string | null;
  department: string | null;
  position: string | null;
  socialLinks: SocialLinks | null;
  customLinks: CustomLink[] | null;
  githubTokenConfigured: boolean;
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
  /** 変更後のメールアドレス(admin限定。null/未指定は変更なし)。 */
  email?: string | null;
}

export function getUserProfile(id: number): Promise<UserProfile> {
  return apiFetch<UserProfile>(`/api/users/${id}`);
}

/**
 * ログイン中ユーザー自身のプロフィール(issue #784)。
 *
 * identity-serviceが検証済みJWTの`sub`から自ユーザーを解決するため、**呼び出し側は
 * ユーザーIDを渡さない**。Keycloak移行(#564)以降`session.user.id`はKeycloakの`sub`(UUID)で
 * あり、これを`Number()`に通すと必ず`NaN`になる。`/api/users/${Number(session.user.id)}`という
 * 組み立て方は`/api/users/NaN`という壊れたリクエストを生み、24時間で203件観測されていた。
 *
 * ID指定版の{@link getUserProfile}はadminが他ユーザーを操作する経路のため残す。
 */
export function getMyProfile(): Promise<UserProfile> {
  return apiFetch<UserProfile>('/api/identity/me');
}

export function updateUserProfile(id: number, input: UserProfileInput): Promise<UserProfile> {
  return apiFetch<UserProfile>(`/api/users/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

/**
 * プロフィール編集画面でクライアント側(Canvas)で切り抜いた正方形画像をアップロードする
 * (issue #1241)。`restoreBackup`/`runBulkOperationUpload`と同じFormData + apiFetchの形。
 * 成功するとidentity-service側がavatarUrlを配信URL(`/api/users/{id}/avatar`)へ更新する。
 */
export function uploadAvatar(id: number, file: File): Promise<UserProfile> {
  const formData = new FormData();
  formData.append('file', file);
  return apiFetch<UserProfile>(`/api/users/${id}/avatar`, {
    method: 'POST',
    body: formData,
  });
}

/**
 * アバター画像のバイト列を取得する(issue #1241)。`downloadBackupFile`と同じ
 * 「JSONを返さない呼び出しは`apiRequest`を直接使う」パターン。
 *
 * ブラウザの`<img src="/api/users/{id}/avatar">`はAuthorizationヘッダーを付けられないため、
 * Route Handler(`apps/web/src/app/api/users/[id]/avatar/route.ts`)がサーバー側の
 * アクセストークンでこの関数を呼び、結果をそのままブラウザへ中継する。
 */
export async function getAvatarBytes(id: number): Promise<{ body: ArrayBuffer; contentType: string }> {
  const res = await apiRequest(`/api/users/${id}/avatar`);
  return { body: await res.arrayBuffer(), contentType: res.headers.get('content-type') ?? 'image/jpeg' };
}

/**
 * issue #1259: 個人設定のタイムゾーンは任意の上書き。`timezone: null`は
 * 「ブラウザのタイムゾーンに従う(未設定)」への変更を表す。
 */
export interface UpdateUserPreferencesInput {
  locale: string;
  timezone: string | null;
}

export function updateUserPreferences(
  id: number,
  input: UpdateUserPreferencesInput
): Promise<UserProfile> {
  return apiFetch<UserProfile>(`/api/users/${id}/preferences`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

/**
 * ログイン中ユーザー自身の個人設定(言語・タイムゾーン)を更新する(issue #784)。
 * {@link getMyProfile}と同じ理由でユーザーIDを渡さない。
 */
export function updateMyPreferences(input: UpdateUserPreferencesInput): Promise<UserProfile> {
  return apiFetch<UserProfile>('/api/identity/me/preferences', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function updateGithubToken(
  id: number,
  input: { githubToken: string }
): Promise<UserProfile> {
  return apiFetch<UserProfile>(`/api/users/${id}/github-token`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function listRoles(): Promise<RoleInfo[]> {
  return apiFetch<RoleInfo[]>('/api/roles');
}

export function assignRole(userId: number, roleName: string): Promise<{ message: string }> {
  return apiFetch<{ message: string }>(`/api/users/${userId}/roles/${roleName}`, { method: 'POST' });
}

export function removeRole(userId: number, roleName: string): Promise<{ message: string }> {
  return apiFetch<{ message: string }>(`/api/users/${userId}/roles/${roleName}`, { method: 'DELETE' });
}

export interface PostStatusOption {
  value: string;
  label: string;
}

/** 投稿ステータスの正準リスト。VS Code拡張とサーバー側の選択肢を一致させるための共通取得元(issue #472)。 */
export function getPostStatuses(): Promise<PostStatusOption[]> {
  return apiFetch<PostStatusOption[]>('/api/metadata/post-statuses');
}

export type CustomTagFormat = 'INLINE' | 'BLOCK';

export interface CustomTag {
  id: number;
  tagName: string;
  htmlTemplate: string;
  description: string | null;
  cssContent: string | null;
  tagFormat: CustomTagFormat;
  projectId: number | null;
  createdAt: string;
  updatedAt: string;
  /** AI生成時にLLMへのリクエストを元にPenpotへ作成したデザインファイルのURL(ベストエフォート、生成以外では常にnull)。 */
  penpotFileUrl?: string | null;
}

export interface CustomTagInput {
  tagName: string;
  htmlTemplate: string;
  description?: string;
  cssContent?: string;
  tagFormat?: CustomTagFormat;
  projectId?: number | null;
}

export interface GenerateCustomTagInput {
  prompt: string;
  tagName: string;
  description?: string;
  projectId?: number | null;
}

export interface ValidationErrorDetail {
  type: string;
  message: string;
  line?: number;
  severity: string;
}

export interface ValidationWarningDetail {
  type: string;
  message: string;
  line?: number;
}

export interface ValidationResult {
  isValid: boolean;
  errors: ValidationErrorDetail[];
  warnings: ValidationWarningDetail[];
}

export interface ValidateCustomTagRequest {
  htmlTemplate: string;
  cssContent?: string;
}

/** プロジェクト詳細のカスタムタグ画面向け。グローバルタグを含めず、プロジェクトのタグのみを返す。 */
export function listProjectCustomTags(projectId: number): Promise<CustomTag[]> {
  return apiFetch<CustomTag[]>(`/api/projects/${projectId}/custom-tags`);
}

export function createCustomTag(input: CustomTagInput): Promise<CustomTag> {
  return apiFetch<CustomTag>('/api/custom-tags', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function updateCustomTag(id: number, input: CustomTagInput): Promise<CustomTag> {
  return apiFetch<CustomTag>(`/api/custom-tags/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function deleteCustomTag(id: number): Promise<void> {
  return apiFetch<void>(`/api/custom-tags/${id}`, { method: 'DELETE' });
}

export function generateCustomTag(input: GenerateCustomTagInput): Promise<CustomTag> {
  return apiFetch<CustomTag>('/api/custom-tags/generate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

/**
 * カスタムタグのAI生成を非同期ジョブとして要求する(`POST /api/custom-tags/generate/jobs`、issue #1409)。
 * 生成の完了を待たずに受理されたジョブ(id・状態)が返り、生成したHTML/CSSは `custom_tags` へ書かれず、
 * ジョブの結果(`GET /api/generation-jobs/{id}` の `resultPayload`)にだけ置かれる。保存は `createCustomTag`。
 */
export function startCustomTagGenerationJob(input: GenerateCustomTagInput): Promise<GenerationJob> {
  return apiFetch<GenerationJob>('/api/custom-tags/generate/jobs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function validateCustomTag(input: ValidateCustomTagRequest): Promise<ValidationResult> {
  return apiFetch<ValidationResult>('/api/custom-tags/validate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export interface CustomTagPreviewInput {
  htmlTemplate: string;
  cssContent?: string;
  testContent: string;
}

export interface CustomTagPreviewResult {
  html: string;
  css: string;
}

/**
 * プロジェクト詳細のカスタムタグ画面向けプレビュー。DB未保存のテンプレート/CSSでも、実際の投稿と
 * 同じMarkdownレンダリングとCSSセレクタのプリフィックス付与を適用した結果を返す(issue #335)。
 */
export function previewProjectCustomTag(
  projectId: number,
  input: CustomTagPreviewInput
): Promise<CustomTagPreviewResult> {
  return apiFetch<CustomTagPreviewResult>(`/api/projects/${projectId}/custom-tags/preview`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

/**
 * プロジェクト詳細/プロジェクト一覧向け。グローバルタグを含めず、プロジェクトのタグのCSSのみを連結する。
 */
export async function downloadProjectCustomTagCssBundle(
  projectId: number
): Promise<ArrayBuffer> {
  const res = await apiRequest(`/api/projects/${projectId}/custom-tags/css-bundle`);
  return res.arrayBuffer();
}

// --- 組み込みタグ([toc]/[blogcard]/[amazon])のデザインカスタマイズ (issue #150) ---

export type EmbedTagType = "TOC" | "BLOGCARD" | "AMAZON";

export interface TagDesignPreset {
  id: string;
  label: string;
  backgroundColor: string;
  textColor: string;
  accentColor: string;
}

export interface TagDesignSetting {
  tagType: EmbedTagType;
  presetId: string;
  backgroundColor: string;
  textColor: string;
  accentColor: string;
  customCss: string | null;
  htmlTemplate: string | null;
}

export interface TagDesignSettingsOverview {
  presets: TagDesignPreset[];
  settings: TagDesignSetting[];
}

export interface SaveTagDesignSettingInput {
  presetId: string;
  backgroundColor: string;
  textColor: string;
  accentColor: string;
  customCss?: string;
  htmlTemplate?: string;
}

/**
 * タグデザイン設定のベースパス。
 *
 * projectIdがnullならプロジェクトに紐付いていないサイト向けの「グローバル既定」を指す(issue #763)。
 * サーバー側は同じtag_design_settingsテーブルのproject_id IS NULLの行を読み書きする。
 * グローバル側はadmin限定(GlobalTagDesignSettingController)。
 */
function tagDesignBasePath(projectId: number | null): string {
  return projectId === null
    ? '/api/tag-design-settings'
    : `/api/projects/${projectId}/tag-design-settings`;
}

export function getTagDesignSettings(projectId: number | null): Promise<TagDesignSettingsOverview> {
  return apiFetch<TagDesignSettingsOverview>(tagDesignBasePath(projectId));
}

export function saveTagDesignSetting(
  projectId: number | null,
  tagType: EmbedTagType,
  input: SaveTagDesignSettingInput
): Promise<TagDesignSetting> {
  return apiFetch<TagDesignSetting>(`${tagDesignBasePath(projectId)}/${tagType}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

/** htmlTemplateはAIが構造変更不要と判断した場合に空文字になりうる(その場合は現在の値を維持する)。 */
export interface GenerateTagDesignResult {
  htmlTemplate: string;
  cssContent: string;
}

export function generateTagDesign(
  projectId: number | null,
  tagType: EmbedTagType,
  prompt: string
): Promise<GenerateTagDesignResult> {
  return apiFetch<GenerateTagDesignResult>(`${tagDesignBasePath(projectId)}/${tagType}/generate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt }),
  });
}

/**
 * タグデザインのAI生成を非同期ジョブとして要求する(`POST .../{tagType}/generate/jobs`、issue #1409)。
 * projectIdがnullならグローバル既定。生成したCSS/HTMLは設定へ書かれず、ジョブの結果にだけ置かれる。
 * 保存は `saveTagDesignSetting`。
 */
export function startTagDesignGenerationJob(
  projectId: number | null,
  tagType: EmbedTagType,
  prompt: string
): Promise<GenerationJob> {
  return apiFetch<GenerationJob>(`${tagDesignBasePath(projectId)}/${tagType}/generate/jobs`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prompt }),
  });
}

export interface CustomTagTemplate {
  id: number;
  templateName: string;
  description: string | null;
  category: string | null;
  htmlTemplate: string;
  cssContent: string | null;
  version: number;
  isPublished: boolean;
  originalTagId: number | null;
  projectId: number | null;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

export interface CustomTagTemplateInput {
  templateName: string;
  description?: string;
  category?: string;
  htmlTemplate: string;
  cssContent?: string;
  projectId?: number | null;
  originalTagId?: number | null;
}

export interface CloneCustomTagTemplateInput {
  newTemplateName: string;
  description?: string;
  category?: string;
  projectId?: number | null;
}

/** テンプレートをプロジェクトの custom_tags 行として適用する要求(issue #1131)。 */
export interface ApplyCustomTagTemplateInput {
  projectId: number;
  tagName: string;
}

export function listCustomTagTemplates(projectId?: number, options?: { category?: string; search?: string; showAll?: boolean }): Promise<CustomTagTemplate[]> {
  const params = new URLSearchParams();
  if (projectId != null) params.set('projectId', String(projectId));
  if (options?.category) params.set('category', options.category);
  if (options?.search) params.set('search', options.search);
  if (options?.showAll) params.set('showAll', 'true');
  const query = params.toString() ? `?${params.toString()}` : '';
  return apiFetch<CustomTagTemplate[]>(`/api/custom-tag-templates${query}`);
}

export function getCustomTagTemplate(id: number): Promise<CustomTagTemplate> {
  return apiFetch<CustomTagTemplate>(`/api/custom-tag-templates/${id}`);
}

export function createCustomTagTemplate(input: CustomTagTemplateInput): Promise<CustomTagTemplate> {
  return apiFetch<CustomTagTemplate>('/api/custom-tag-templates', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function updateCustomTagTemplate(id: number, input: CustomTagTemplateInput): Promise<CustomTagTemplate> {
  return apiFetch<CustomTagTemplate>(`/api/custom-tag-templates/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function publishCustomTagTemplate(id: number): Promise<CustomTagTemplate> {
  return apiFetch<CustomTagTemplate>(`/api/custom-tag-templates/${id}/publish`, {
    method: 'POST',
  });
}

export function unpublishCustomTagTemplate(id: number): Promise<CustomTagTemplate> {
  return apiFetch<CustomTagTemplate>(`/api/custom-tag-templates/${id}/unpublish`, {
    method: 'POST',
  });
}

export function cloneCustomTagTemplate(id: number, input: CloneCustomTagTemplateInput): Promise<CustomTagTemplate> {
  return apiFetch<CustomTagTemplate>(`/api/custom-tag-templates/${id}/clone`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

/**
 * テンプレートをプロジェクトのカスタムタグ(custom_tags 行)として適用する。記事で [tagname] として
 * 使える。cloneCustomTagTemplate(テンプレート間の複製)とは別の操作。同名タグがあれば 409。
 */
export function applyCustomTagTemplate(id: number, input: ApplyCustomTagTemplateInput): Promise<CustomTag> {
  return apiFetch<CustomTag>(`/api/custom-tag-templates/${id}/apply`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function deleteCustomTagTemplate(id: number): Promise<void> {
  return apiFetch<void>(`/api/custom-tag-templates/${id}`, { method: 'DELETE' });
}

export function getMyCustomTagTemplates(): Promise<CustomTagTemplate[]> {
  return apiFetch<CustomTagTemplate[]>('/api/custom-tag-templates/my-templates');
}

/**
 * Let's Blogアプリ自身のバックアップアーカイブ(DB + 生成画像ファイル + メタデータをまとめたZIP)を
 * ダウンロードする(admin限定)。
 */
export async function downloadBackupFile(): Promise<{ body: ArrayBuffer; filename: string }> {
  const res = await apiRequest('/api/backup/download');
  const disposition = res.headers.get('content-disposition') ?? '';
  const match = disposition.match(/filename="([^"]+)"/);
  return { body: await res.arrayBuffer(), filename: match?.[1] ?? 'lets-blog-backup.zip' };
}

/**
 * アップロードしたバックアップアーカイブでDB+生成画像ファイルを復元する(admin限定、
 * 破壊的操作のためconfirm=trueが必須)。バックアップ作成時と異なるAPP_ENCRYPTION_KEYの
 * 環境へリストアしようとした場合、acknowledgeKeyMismatchがfalseだとサーバー側で拒否される
 * (サイト認証情報等が復号できなくなるデータ破損を防ぐため)。
 */
export async function restoreBackup(
  file: File,
  acknowledgeKeyMismatch: boolean
): Promise<void> {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('confirm', 'true');
  formData.append('acknowledgeKeyMismatch', String(acknowledgeKeyMismatch));
  return apiFetch<void>('/api/backup/restore', {
    method: 'POST',
    body: formData,
  });
}

export interface OperationLogEntry {
  id: number;
  operationId: string;
  userId: number | null;
  /** JWTのsubクレーム(issue #569)。旧ヘッダーベース(廃止済み)の操作や未認証の場合はnull。 */
  actorKeycloakSub: string | null;
  method: string;
  path: string;
  statusCode: number | null;
  durationMs: number;
  success: boolean;
  errorMessage: string | null;
  createdAt: string;
}

export interface OperationLogPage {
  content: OperationLogEntry[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export function listOperationLogs(
  params: { page?: number; size?: number }
): Promise<OperationLogPage> {
  const query = new URLSearchParams();
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 200));
  query.set('sort', 'createdAt,desc');
  return apiFetch<OperationLogPage>(`/api/operation-logs?${query.toString()}`);
}

export function getOperationTrace(operationId: string): Promise<OperationLogEntry[]> {
  return apiFetch<OperationLogEntry[]>(`/api/operation-logs/${encodeURIComponent(operationId)}`);
}

/** ルート別集計の1行(issue #1471)。pathは数値ID・UUIDを{id}にしクエリを除いた形。p50/p95はnearest-rank法。 */
export interface RouteStat {
  method: string;
  path: string;
  count: number;
  p50Ms: number;
  p95Ms: number;
  maxMs: number;
}

/** 操作別集計の1行(issue #1471)。 */
export interface OperationStat {
  operationId: string;
  totalDurationMs: number;
  callCount: number;
  startedAt: string;
  userId: number | null;
}

export interface OperationStatsParams {
  /** 期間の開始。UTCのISO日時(オフセット指定子なし)。必須。 */
  startDate: string;
  /** 期間の終了。UTCのISO日時(オフセット指定子なし)。必須。 */
  endDate: string;
  sort?: string;
  direction?: 'asc' | 'desc';
  limit?: number;
}

function statsQuery(params: OperationStatsParams): string {
  const query = new URLSearchParams();
  query.set('startDate', params.startDate);
  query.set('endDate', params.endDate);
  if (params.sort) query.set('sort', params.sort);
  if (params.direction) query.set('direction', params.direction);
  if (params.limit !== undefined) query.set('limit', String(params.limit));
  return query.toString();
}

/** admin限定。ルート別(method + 正規化したパス)の件数・p50・p95・最大(issue #1471)。 */
export function getRouteStats(params: OperationStatsParams): Promise<RouteStat[]> {
  return apiFetch<RouteStat[]>(`/api/operation-logs/stats/routes?${statsQuery(params)}`);
}

/** admin限定。操作(operationId)別の合計所要時間・呼び出し数・開始時刻・利用者ID(issue #1471)。 */
export function getOperationStats(params: OperationStatsParams): Promise<OperationStat[]> {
  return apiFetch<OperationStat[]>(`/api/operation-logs/stats/operations?${statsQuery(params)}`);
}

/** 操作ログ・AIジョブ・監査ログを一元表示するための統合エントリ(issue #187)。 */
export type UnifiedLogSourceType = "OPERATION" | "AI_JOB" | "SYSTEM_JOB" | "AUDIT";

export interface UnifiedLogEntry {
  sourceType: UnifiedLogSourceType;
  id: number;
  createdAt: string;
  title: string;
  detail: string | null;
  status: string | null;
  operationId: string | null;
  /**
   * JWTのsubクレーム(issue #569)。OPERATION/AUDITでJWT認証時のみ値を持つ。
   * 旧ヘッダーベース(廃止済み)の操作、AI_JOB、未認証の場合はnull
   * (issue #564でWebはKeycloakのアクセストークンを送るようになったため、Web発の操作は
   * 基本的に値を持つ。VSCode拡張は#565が未着手のため、そちらの操作は引き続きnullになる)。
   */
  actorKeycloakSub: string | null;
}

export interface UnifiedLogPage {
  content: UnifiedLogEntry[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export function listUnifiedOperationLogs(
  params: {
    type?: UnifiedLogSourceType;
    q?: string;
    /** 範囲の開始(含む)。UTCのISO日時(オフセット指定子なし)。未指定なら従来どおり直近から。 */
    startDate?: string;
    /** 範囲の終了(含む)。UTCのISO日時(オフセット指定子なし)。 */
    endDate?: string;
    page?: number;
    size?: number;
  }
): Promise<UnifiedLogPage> {
  const query = new URLSearchParams();
  if (params.type) query.set('type', params.type);
  if (params.q) query.set('q', params.q);
  if (params.startDate) query.set('startDate', params.startDate);
  if (params.endDate) query.set('endDate', params.endDate);
  query.set('page', String(params.page ?? 0));
  query.set('size', String(params.size ?? 50));
  return apiFetch<UnifiedLogPage>(`/api/operation-logs/unified?${query.toString()}`);
}

/**
 * ブラウザで発生したエラーをlog-writerへ記録する(issue #791)。
 *
 * ブラウザから gateway を直叩きしていた頃の名残で認証情報が付かず、#772 で
 * log-writer に認証ゲートが戻った際に401で無言に全滅していた。現在は
 * apps/web/src/app/client-errors/route.ts (BFF) だけがこの関数を呼び、Bearerが付く。
 */
export interface FrontendErrorLogInput {
  message: string;
  stack?: string;
  componentStack?: string;
  level: 'error' | 'warn';
  context?: Record<string, unknown>;
  url?: string;
  userAgent?: string;
  timestamp: string;
}

export function logFrontendError(input: FrontendErrorLogInput): Promise<void> {
  return apiFetch<void>('/api/logs/errors', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

/**
 * `GET /api/projects/{id}` が実際に返すフィールド(project-service の ProjectResponse)。
 *
 * issue #913: 以前はここに cssSelectorPrefix と画像生成設定8件も宣言していたが、
 * project-service はそれらを返さない(所有者が content-service / media-service のため)。
 * 型だけが「返る」と言っていたので、保存はできるのに画面には常に空が表示されていた。
 * 実態に合わせて外し、各設定は所有サービスから個別に取得する
 * (getProjectContentSettings / getProjectImageSettings)。
 */
export interface Project {
  id: number;
  name: string;
  slug: string;
  localSite: Site | null;
  testSite: Site | null;
  productionSite: Site | null;
  masterEnvironment: "test" | "production";
  githubRepository: string | null;
  createdAt: string;
  updatedAt: string;
}

/** プロジェクト単位のコンテンツ設定(content-service が所有、issue #576)。 */
export interface ProjectContentSettings {
  cssSelectorPrefix: string | null;
}

/** プロジェクト単位の画像生成設定(media-service が所有、issue #583)。null は「未設定」。 */
export interface ProjectImageSettings {
  projectId: number;
  imageProvider: string | null;
  comfyuiCheckpoint: string | null;
  defaultNegativePrompt: string | null;
  defaultQualityPrompt: string | null;
  defaultGeneratedImageWidth: number | null;
  defaultGeneratedImageHeight: number | null;
  defaultArticleImageLongEdgePx: number | null;
  blockSexualContent: boolean | null;
  blockViolentContent: boolean | null;
  blockDiscriminatoryContent: boolean | null;
}

export function getProjectContentSettings(projectId: number): Promise<ProjectContentSettings> {
  return apiFetch<ProjectContentSettings>(`/api/projects/${projectId}/content-settings`);
}

export function getProjectImageSettings(projectId: number): Promise<ProjectImageSettings> {
  return apiFetch<ProjectImageSettings>(`/api/projects/${projectId}/image-settings`);
}

export type ProjectEnvironment = "local" | "test" | "production";

export function listProjects(): Promise<Project[]> {
  return apiFetch<Project[]>('/api/projects');
}

export function getProject(id: number): Promise<Project> {
  return apiFetch<Project>(`/api/projects/${id}`);
}

export function createProject(input: { name: string; slug: string }): Promise<Project> {
  return apiFetch<Project>('/api/projects', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function updateProject(id: number, name: string): Promise<Project> {
  return apiFetch<Project>(`/api/projects/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  });
}

export function deleteProject(id: number): Promise<void> {
  return apiFetch<void>(`/api/projects/${id}`, { method: 'DELETE' });
}

/** レビュー待ち(開いている)Pull Request 1件(publishing-service、issue #1337)。 */
export interface ArticleReviewPullRequest {
  number: number;
  title: string;
  headBranch: string;
  createdAt: string;
  url: string;
}

/**
 * プロジェクトの GitHub リポジトリで開いている Pull Request の一覧(issue #1340)。
 * 未設定(409)・GitHub 認証失敗(502)などは例外になる。呼び出し側は空配列へ握り潰さず、
 * 失敗として表示すること。
 */
export function listArticleReviewPullRequests(projectId: number): Promise<ArticleReviewPullRequest[]> {
  return apiFetch<ArticleReviewPullRequest[]>(`/api/projects/${projectId}/article-review/pull-requests`);
}

export function updateProjectGithubRepository(
  id: number,
  githubRepository: string
): Promise<Project> {
  return apiFetch<Project>(`/api/projects/${id}/github-repository`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ githubRepository }),
  });
}

export function updateProjectCssSelectorPrefix(
  id: number,
  cssSelectorPrefix: string
): Promise<ProjectContentSettings> {
  return apiFetch<ProjectContentSettings>(`/api/projects/${id}/css-selector-prefix`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cssSelectorPrefix }),
  });
}

/** 画像生成時のnegative prompt/画質プロンプトのデフォルト値(issue #293)。空文字はアプリ全体のデフォルトへ戻す。 */
export function updateProjectImageGenerationPromptDefaults(
  id: number,
  defaultNegativePrompt: string,
  defaultQualityPrompt: string
): Promise<ProjectImageSettings> {
  return apiFetch<ProjectImageSettings>(`/api/projects/${id}/image-generation-prompt-defaults`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ defaultNegativePrompt, defaultQualityPrompt }),
  });
}

/** 画像生成時のデフォルトサイズ(issue #292)。nullはアプリ全体のデフォルト(1920x1080)へ戻す。 */
export function updateProjectImageGenerationSizeDefaults(
  id: number,
  defaultGeneratedImageWidth: number | null,
  defaultGeneratedImageHeight: number | null
): Promise<ProjectImageSettings> {
  return apiFetch<ProjectImageSettings>(`/api/projects/${id}/image-generation-size-defaults`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ defaultGeneratedImageWidth, defaultGeneratedImageHeight }),
  });
}

/** 記事投稿時に画像をリサイズする長編の目標px(issue #291)。nullはアプリ全体のデフォルト(1300px)へ戻す。 */
export function updateProjectArticleImageResizeDefault(
  id: number,
  defaultArticleImageLongEdgePx: number | null
): Promise<ProjectImageSettings> {
  return apiFetch<ProjectImageSettings>(`/api/projects/${id}/article-image-resize-default`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ defaultArticleImageLongEdgePx }),
  });
}

/** 画像生成時の不適切コンテンツ(性的/暴力的/差別的表現)のカテゴリ別禁止設定(issue #532)。 */
export function updateProjectImageContentFilterSettings(
  id: number,
  blockSexualContent: boolean,
  blockViolentContent: boolean,
  blockDiscriminatoryContent: boolean
): Promise<ProjectImageSettings> {
  return apiFetch<ProjectImageSettings>(`/api/projects/${id}/image-content-filter-settings`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ blockSexualContent, blockViolentContent, blockDiscriminatoryContent }),
  });
}

/** valueそのものは返さず、設定済みかどうかのみ返す(SiteDetailのconfiguredSecretFieldsと同じ方針)。 */
export interface ProjectApiKeyStatus {
  configured: boolean;
}

export function getProjectGithubTokenStatus(projectId: number): Promise<ProjectApiKeyStatus> {
  return apiFetch<ProjectApiKeyStatus>(`/api/projects/${projectId}/api-keys/github-token`);
}

export function setProjectGithubToken(projectId: number, githubToken: string): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/github-token`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ githubToken }),
  });
}

export function clearProjectGithubToken(projectId: number): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/github-token`, { method: 'DELETE' });
}

export function getProjectBraveSearchApiKeyStatus(
  projectId: number
): Promise<ProjectApiKeyStatus> {
  return apiFetch<ProjectApiKeyStatus>(`/api/projects/${projectId}/api-keys/brave-search-api-key`);
}

export function setProjectBraveSearchApiKey(projectId: number, apiKey: string): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/brave-search-api-key`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ apiKey }),
  });
}

export function clearProjectBraveSearchApiKey(projectId: number): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/brave-search-api-key`, { method: 'DELETE' });
}

/** プロジェクト単位のChatGPT(OpenAI) APIキーを保存する(issue #1506)。状態の取得はlistAiConnectionsのOPENAI行で行う。 */
export function setProjectOpenAiApiKey(projectId: number, apiKey: string): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/openai-api-key`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ apiKey }),
  });
}

export function clearProjectOpenAiApiKey(projectId: number): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/openai-api-key`, { method: 'DELETE' });
}

/** プロジェクト単位のClaude(Anthropic) APIキーを保存する(issue #1507)。状態の取得はlistAiConnectionsのCLAUDE行で行う。 */
export function setProjectClaudeApiKey(projectId: number, apiKey: string): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/claude-api-key`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ apiKey }),
  });
}

export function clearProjectClaudeApiKey(projectId: number): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/claude-api-key`, { method: 'DELETE' });
}

export interface ProjectGoogleAnalyticsStatus {
  /** 連携済みかつプロパティ選択済み(ダッシュボードに表示できる)。 */
  configured: boolean;
  propertyId: string | null;
  clientId: string | null;
  /** クライアントシークレットは値を返さず、保存済みかどうかだけ。 */
  hasClientSecret: boolean;
  /** Googleアカウントとの連携(リフレッシュトークン保存)済み。プロパティ選択の有無は問わない。 */
  connected: boolean;
}

export function getProjectGoogleAnalyticsStatus(
  projectId: number
): Promise<ProjectGoogleAnalyticsStatus> {
  return apiFetch<ProjectGoogleAnalyticsStatus>(`/api/projects/${projectId}/api-keys/google-analytics`);
}

/** clientSecretを省略すると保存済みのシークレットは変更されない。 */
export function saveProjectGoogleAnalyticsClient(
  projectId: number,
  input: { clientId: string; clientSecret?: string }
): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/google-analytics/client`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function completeProjectGoogleAnalyticsOAuth(
  projectId: number,
  input: { code: string; redirectUri: string }
): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/google-analytics/oauth-callback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export interface GoogleAnalyticsPropertyOption {
  propertyId: string;
  displayName: string | null;
  accountDisplayName: string | null;
}

export function listProjectGoogleAnalyticsProperties(
  projectId: number
): Promise<GoogleAnalyticsPropertyOption[]> {
  return apiFetch<GoogleAnalyticsPropertyOption[]>(
    `/api/projects/${projectId}/api-keys/google-analytics/properties`
  );
}

export function selectProjectGoogleAnalyticsProperty(
  projectId: number,
  propertyId: string
): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/google-analytics/property`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ propertyId }),
  });
}

export function clearProjectGoogleAnalyticsCredentials(projectId: number): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/google-analytics`, { method: 'DELETE' });
}

/** 本番サイトのプラグインが持つ X の接続状態(未設定 / 接続済み / 要再接続)。 */
export type XConnectionState = 'UNSET' | 'CONNECTED' | 'RECONNECT';

/**
 * プロジェクト設定画面の X 接続(issue #1574)。トークンもクライアントの秘密も含まない。
 * 本番サイトが無い・プラグインが導入済みでないときは connectable=false と reason、status/log は null。
 * 本番サイトに届かないときは status.available / log.available が false(画面は「取得できない」と示す)。
 */
export interface XConnectionView {
  connectable: boolean;
  reason: string | null;
  siteName: string | null;
  status: {
    available: boolean;
    state: XConnectionState | null;
    accountName: string | null;
    error: string | null;
  } | null;
  log: {
    available: boolean;
    entries: { kind: string; success: boolean; error: string | null; at: string | null }[];
    error: string | null;
  } | null;
}

export function getProjectXConnection(projectId: number): Promise<XConnectionView> {
  return apiFetch<XConnectionView>(`/api/projects/${projectId}/sns/x`);
}

/** クライアントの情報は認可の間だけバックエンドのメモリに置かれる(保存されない)。 */
export function startProjectXAuthorization(
  projectId: number,
  input: { clientId: string; clientSecret: string; redirectUri: string }
): Promise<{ authorizeUrl: string }> {
  return apiFetch<{ authorizeUrl: string }>(`/api/projects/${projectId}/sns/x/authorize`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function completeProjectXAuthorization(
  projectId: number,
  input: { state: string; code: string }
): Promise<{ projectId: number; accountName: string }> {
  return apiFetch<{ projectId: number; accountName: string }>(`/api/projects/${projectId}/sns/x/callback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function testProjectXPost(projectId: number): Promise<{ success: boolean; error: string | null }> {
  return apiFetch<{ success: boolean; error: string | null }>(`/api/projects/${projectId}/sns/x/test`, {
    method: 'POST',
  });
}

/**
 * 設定画面の「SNS 告知」欄の PV 達成ルール(issue #1578)。GA4 の認証情報は含まない。
 * `addable=false` のとき `reason` が理由(GA が未連携など)。`send` は本番サイトのプラグインへ最後に送った結果。
 */
export interface PvRulesView {
  addable: boolean;
  reason: string | null;
  rules: { id: string; period: 'daily' | 'total'; threshold: number }[];
  send: { state: 'NONE' | 'SENT' | 'FAILED'; error: string | null; at: string | null };
}

export function getProjectPvRules(projectId: number): Promise<PvRulesView> {
  return apiFetch<PvRulesView>(`/api/projects/${projectId}/sns/pv`);
}

export function addProjectPvRule(
  projectId: number,
  input: { period: 'daily' | 'total'; threshold: number }
): Promise<PvRulesView> {
  return apiFetch<PvRulesView>(`/api/projects/${projectId}/sns/pv/rules`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function deleteProjectPvRule(projectId: number, ruleId: string): Promise<PvRulesView> {
  return apiFetch<PvRulesView>(`/api/projects/${projectId}/sns/pv/rules/${encodeURIComponent(ruleId)}`, {
    method: 'DELETE',
  });
}

/** 送信失敗からの回復。GA4 の認証情報とルール全件を本番サイトのプラグインへ送り直す。 */
export function resendProjectPvRules(projectId: number): Promise<PvRulesView> {
  return apiFetch<PvRulesView>(`/api/projects/${projectId}/sns/pv/resend`, { method: 'POST' });
}

/**
 * 設定画面の「SNS 告知」欄の告知文テンプレート(issue #1583)。公開時と PV 達成時を別々に持つ。空は「既定の告知文を使う」。
 * 差し込み項目は {title}・{url}、PV 達成時のみ {period}・{threshold}。`send` は本番サイトのプラグインへ最後に送った結果。
 */
export interface SnsTemplatesView {
  publishTemplate: string;
  pvTemplate: string;
  send: { state: 'NONE' | 'SENT' | 'FAILED'; error: string | null; at: string | null };
}

export function getProjectSnsTemplates(projectId: number): Promise<SnsTemplatesView> {
  return apiFetch<SnsTemplatesView>(`/api/projects/${projectId}/sns/templates`);
}

/** 保存して本番サイトのプラグインへ送る(送れなくても保存は残り、`send.state` が FAILED になる)。 */
export function saveProjectSnsTemplates(
  projectId: number,
  input: { publishTemplate: string; pvTemplate: string }
): Promise<SnsTemplatesView> {
  return apiFetch<SnsTemplatesView>(`/api/projects/${projectId}/sns/templates`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

/** 送信失敗からの回復。保存済みのテンプレートを本番サイトのプラグインへ送り直す。 */
export function resendProjectSnsTemplates(projectId: number): Promise<SnsTemplatesView> {
  return apiFetch<SnsTemplatesView>(`/api/projects/${projectId}/sns/templates/resend`, { method: 'POST' });
}

/**
 * プロジェクト設定画面の Threads 接続(issue #1579)。応答の形は X と同じ(トークンもアプリの秘密も含まない)。
 */
export function getProjectThreadsConnection(projectId: number): Promise<XConnectionView> {
  return apiFetch<XConnectionView>(`/api/projects/${projectId}/sns/threads`);
}

/** アプリの情報は認可の間だけバックエンドのメモリに置かれる(保存されない)。 */
export function startProjectThreadsAuthorization(
  projectId: number,
  input: { clientId: string; clientSecret: string; redirectUri: string }
): Promise<{ authorizeUrl: string }> {
  return apiFetch<{ authorizeUrl: string }>(`/api/projects/${projectId}/sns/threads/authorize`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function completeProjectThreadsAuthorization(
  projectId: number,
  input: { state: string; code: string }
): Promise<{ projectId: number; accountName: string }> {
  return apiFetch<{ projectId: number; accountName: string }>(`/api/projects/${projectId}/sns/threads/callback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function testProjectThreadsPost(projectId: number): Promise<{ success: boolean; error: string | null }> {
  return apiFetch<{ success: boolean; error: string | null }>(`/api/projects/${projectId}/sns/threads/test`, {
    method: 'POST',
  });
}

/** 切断。本番サイトのプラグインから Threads の設定を消す。 */
export function disconnectProjectThreads(projectId: number): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/sns/threads`, { method: 'DELETE' });
}

/**
 * プロジェクト設定画面の LinkedIn 接続(issue #1581)。応答の形は X と同じ(トークンもアプリの秘密も含まない)。
 */
export function getProjectLinkedInConnection(projectId: number): Promise<XConnectionView> {
  return apiFetch<XConnectionView>(`/api/projects/${projectId}/sns/linkedin`);
}

/** アプリの情報は認可の間だけバックエンドのメモリに置かれる(保存されない)。 */
export function startProjectLinkedInAuthorization(
  projectId: number,
  input: { clientId: string; clientSecret: string; redirectUri: string }
): Promise<{ authorizeUrl: string }> {
  return apiFetch<{ authorizeUrl: string }>(`/api/projects/${projectId}/sns/linkedin/authorize`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function completeProjectLinkedInAuthorization(
  projectId: number,
  input: { state: string; code: string }
): Promise<{ projectId: number; accountName: string }> {
  return apiFetch<{ projectId: number; accountName: string }>(`/api/projects/${projectId}/sns/linkedin/callback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function testProjectLinkedInPost(projectId: number): Promise<{ success: boolean; error: string | null }> {
  return apiFetch<{ success: boolean; error: string | null }>(`/api/projects/${projectId}/sns/linkedin/test`, {
    method: 'POST',
  });
}

/** 切断。本番サイトのプラグインから LinkedIn の設定を消す。 */
export function disconnectProjectLinkedIn(projectId: number): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/sns/linkedin`, { method: 'DELETE' });
}

/**
 * プロジェクト設定画面の Facebook ページ接続(issue #1580)。応答の形は X と同じ(トークンもアプリの秘密も含まない)。
 * 個人アカウントには投稿しないので、認可のあとに投稿先のページを選ぶ(pages / page)。
 */
export function getProjectFacebookConnection(projectId: number): Promise<XConnectionView> {
  return apiFetch<XConnectionView>(`/api/projects/${projectId}/sns/facebook`);
}

/** アプリの情報は認可の間だけバックエンドのメモリに置かれる(保存されない)。 */
export function startProjectFacebookAuthorization(
  projectId: number,
  input: { clientId: string; clientSecret: string; redirectUri: string }
): Promise<{ authorizeUrl: string }> {
  return apiFetch<{ authorizeUrl: string }>(`/api/projects/${projectId}/sns/facebook/authorize`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export interface FacebookPagesView {
  projectId: number;
  pages: { id: string; name: string }[];
}

/** 認可から戻ったときに、選べるページの一覧(ID と名前だけ)を受け取る。 */
export function completeProjectFacebookAuthorization(
  projectId: number,
  input: { state: string; code: string }
): Promise<FacebookPagesView> {
  return apiFetch<FacebookPagesView>(`/api/projects/${projectId}/sns/facebook/callback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function getProjectFacebookPages(projectId: number, state: string): Promise<FacebookPagesView> {
  return apiFetch<FacebookPagesView>(
    `/api/projects/${projectId}/sns/facebook/pages?state=${encodeURIComponent(state)}`
  );
}

/** 投稿先のページを選ぶ。そのページのトークンだけが本番サイトへ送られる。 */
export function selectProjectFacebookPage(
  projectId: number,
  input: { state: string; pageId: string }
): Promise<{ projectId: number; accountName: string }> {
  return apiFetch<{ projectId: number; accountName: string }>(`/api/projects/${projectId}/sns/facebook/page`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function testProjectFacebookPost(projectId: number): Promise<{ success: boolean; error: string | null }> {
  return apiFetch<{ success: boolean; error: string | null }>(`/api/projects/${projectId}/sns/facebook/test`, {
    method: 'POST',
  });
}

/** 切断。本番サイトのプラグインから Facebook の設定を消す。 */
export function disconnectProjectFacebook(projectId: number): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/sns/facebook`, { method: 'DELETE' });
}

export interface GoogleAnalyticsDailyDataPoint {
  date: string;
  sessions: number;
  activeUsers: number;
  pageViews: number;
}

export interface GoogleAnalyticsChannelBreakdown {
  channel: string;
  sessions: number;
  activeUsers: number;
  pageViews: number;
}

export interface GoogleAnalyticsReport {
  eligible: boolean;
  sessions: number | null;
  activeUsers: number | null;
  pageViews: number | null;
  periodLabel: string | null;
  errorMessage: string | null;
  dailyDataPoints: GoogleAnalyticsDailyDataPoint[];
  channelBreakdown: GoogleAnalyticsChannelBreakdown[];
}

export function getProjectGoogleAnalyticsReport(
  projectId: number
): Promise<GoogleAnalyticsReport> {
  return apiFetch<GoogleAnalyticsReport>(`/api/projects/${projectId}/dashboard/google-analytics`);
}

export interface ProjectAdSenseStatus {
  configured: boolean;
  accountId: string | null;
  clientId: string | null;
  hasClientSecret: boolean;
  /** Googleアカウントとの連携(リフレッシュトークン保存)済みか。パブリッシャーIDの有無は問わない(#1232)。 */
  connected: boolean;
}

export function getProjectAdSenseStatus(projectId: number): Promise<ProjectAdSenseStatus> {
  return apiFetch<ProjectAdSenseStatus>(`/api/projects/${projectId}/api-keys/adsense`);
}

/** `accountId`(パブリッシャーID)は任意。空なら連携後にaccounts.listから自動取得される(#1232)。 */
export function setProjectAdSenseSettings(
  projectId: number,
  input: { accountId?: string; clientId: string }
): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/adsense`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function setProjectAdSenseClientSecret(
  projectId: number,
  clientSecret: string
): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/adsense/client-secret`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ clientSecret }),
  });
}

export interface AdSenseAccountOption {
  accountId: string;
  displayName: string | null;
}

export function listProjectAdSenseAccounts(projectId: number): Promise<AdSenseAccountOption[]> {
  return apiFetch<AdSenseAccountOption[]>(`/api/projects/${projectId}/api-keys/adsense/accounts`);
}

export function selectProjectAdSenseAccount(projectId: number, accountId: string): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/adsense/account`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ accountId }),
  });
}

export function clearProjectAdSenseCredentials(projectId: number): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/adsense`, { method: 'DELETE' });
}

export function completeProjectAdSenseOAuth(
  projectId: number,
  input: { code: string; redirectUri: string }
): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/api-keys/adsense/oauth-callback`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export interface AdSenseDailyDataPoint {
  date: string;
  estimatedEarnings: string;
  clicks: number;
  impressions: number;
}

export interface AdSensePlatformBreakdown {
  platform: string;
  estimatedEarnings: string;
  clicks: number;
  impressions: number;
}

export interface AdSenseReport {
  eligible: boolean;
  estimatedEarnings: string | null;
  clicks: number | null;
  impressions: number | null;
  periodLabel: string | null;
  errorMessage: string | null;
  dailyDataPoints: AdSenseDailyDataPoint[];
  platformBreakdown: AdSensePlatformBreakdown[];
}

export function getProjectAdSenseReport(projectId: number): Promise<AdSenseReport> {
  return apiFetch<AdSenseReport>(`/api/projects/${projectId}/dashboard/adsense`);
}

/**
 * adminユーザー限定のシステム設定画面(issue #403)向け。プロジェクトに紐付かない業務系のアプリ全体設定
 * (外部LLMサービス連携・メール送信・Google OAuthクライアント・Webフロントの公開URL)を扱う。
 * 秘匿情報(secret=true)はvalueを含まない(設定済みかどうか・設定元のみ)。
 */
export interface AppSetting {
  key: string;
  label: string;
  secret: boolean;
  configured: boolean;
  source: 'DATABASE' | 'ENVIRONMENT' | 'NONE';
  value: string | null;
}

export function listAppSettings(): Promise<AppSetting[]> {
  return apiFetch<AppSetting[]>('/api/system-settings/app-settings');
}

export interface SiteAdminPathResponse {
  path: string;
}

/** サイトの管理画面パスのグローバル既定値(ログイン済みなら非 admin でも読める)。 */
export function getSiteAdminPath(): Promise<SiteAdminPathResponse> {
  return apiFetch<SiteAdminPathResponse>('/api/system-settings/site-admin-path');
}

export function updateAppSettings(settings: Record<string, string>): Promise<void> {
  return apiFetch<void>('/api/system-settings/app-settings', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(settings),
  });
}

/**
 * ComfyUI などの演算デバイス(GPU / CPU)切り替え(issue #1399)。管理者だけが参照・適用できる。
 * 現在の構成は実際に動いているコンテナから判定した値で、DBの選択値ではない。
 */
export type ComputeDevice = 'GPU' | 'CPU';

export interface ComputeDeviceApplyStatus {
  state: 'IDLE' | 'APPLYING' | 'SUCCEEDED' | 'FAILED';
  requestedDevice: ComputeDevice | null;
  message: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface ComputeDeviceStatus {
  target: string;
  currentDevice: ComputeDevice | 'NONE' | 'BOTH';
  cpuFixed: boolean;
  gpuSelectable: boolean;
  cpuSelectable: boolean;
  gpuUnavailableReason: string | null;
  cpuUnavailableReason: string | null;
  apply: ComputeDeviceApplyStatus;
}

export function getComputeDeviceStatus(target: string): Promise<ComputeDeviceStatus> {
  return apiFetch<ComputeDeviceStatus>(`/api/system-settings/compute-devices/${encodeURIComponent(target)}`);
}

/** 適用を受け付けたらすぐ返る(202)。進行は {@link getComputeDeviceStatus} で取得する。 */
export function applyComputeDevice(device: ComputeDevice, target: string = 'comfyui'): Promise<ComputeDeviceStatus> {
  return apiFetch<ComputeDeviceStatus>(`/api/system-settings/compute-devices/${encodeURIComponent(target)}/apply`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ device }),
  });
}

export interface PlanChatMessage {
  role: "user" | "assistant";
  content: string;
}

export interface PlanChatResponse {
  reply: string;
  sessionId: number;
}

export interface ArticlePlanSessionSummary {
  id: number;
  title: string;
  githubIssueNumber: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface ArticlePlanSessionDetail {
  id: number;
  title: string;
  githubIssueNumber: number | null;
  history: PlanChatMessage[];
  createdAt: string;
  updatedAt: string;
}

export type RepositoryIssueState = "open" | "closed" | "all";

export interface RepositoryIssue {
  number: number;
  title: string;
  htmlUrl: string;
  state: string;
}

export interface SuggestTitlesResponse {
  titles: string[];
}

export interface SuggestStructureResponse {
  structure: string;
}

export interface AcceptStructureResponse {
  issueNumber: number;
  issueUrl: string;
}

export interface AcceptPlanResultItem {
  title: string;
  issueNumber?: number;
  issueUrl?: string;
  error?: string;
}

export interface AcceptPlanResponse {
  results: AcceptPlanResultItem[];
}

export function sendArticlePlanChatMessage(
  projectId: number,
  data: {
    history: PlanChatMessage[];
    message: string;
    sessionId?: number | null;
    githubIssueNumber?: number | null;
  }
): Promise<PlanChatResponse> {
  return apiFetch<PlanChatResponse>(`/api/projects/${projectId}/article-plan/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
}

export function listArticlePlanSessions(
  projectId: number
): Promise<ArticlePlanSessionSummary[]> {
  return apiFetch<ArticlePlanSessionSummary[]>(`/api/projects/${projectId}/article-plan/sessions`);
}

export function getArticlePlanSession(
  projectId: number,
  sessionId: number
): Promise<ArticlePlanSessionDetail> {
  return apiFetch<ArticlePlanSessionDetail>(
    `/api/projects/${projectId}/article-plan/sessions/${sessionId}`
  );
}

export function getArticlePlanSessionByIssue(
  projectId: number,
  issueNumber: number
): Promise<ArticlePlanSessionDetail> {
  return apiFetch<ArticlePlanSessionDetail>(
    `/api/projects/${projectId}/article-plan/sessions/by-issue/${issueNumber}`
  );
}

export interface IssueDescriptionResponse {
  body: string;
}

export function getArticlePlanIssueDescription(
  projectId: number,
  issueNumber: number
): Promise<IssueDescriptionResponse> {
  return apiFetch<IssueDescriptionResponse>(
    `/api/projects/${projectId}/article-plan/issues/${issueNumber}/description`
  );
}

export function listArticlePlanIssues(
  projectId: number,
  state: RepositoryIssueState
): Promise<RepositoryIssue[]> {
  return apiFetch<RepositoryIssue[]>(
    `/api/projects/${projectId}/article-plan/issues?state=${state}`
  );
}

export function suggestArticlePlanTitles(
  projectId: number,
  data: { history: PlanChatMessage[] }
): Promise<SuggestTitlesResponse> {
  return apiFetch<SuggestTitlesResponse>(`/api/projects/${projectId}/article-plan/suggest-titles`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
}

export function acceptArticlePlan(
  projectId: number,
  data: { titles: string[] }
): Promise<AcceptPlanResponse> {
  return apiFetch<AcceptPlanResponse>(`/api/projects/${projectId}/article-plan/accept`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
}

export function suggestArticleStructure(
  projectId: number,
  data: { history: PlanChatMessage[] }
): Promise<SuggestStructureResponse> {
  return apiFetch<SuggestStructureResponse>(`/api/projects/${projectId}/article-plan/suggest-structure`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  });
}

export function acceptArticleStructure(
  projectId: number,
  issueNumber: number,
  data: { structure: string }
): Promise<AcceptStructureResponse> {
  return apiFetch<AcceptStructureResponse>(
    `/api/projects/${projectId}/article-plan/issues/${issueNumber}/accept-structure`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data),
    }
  );
}

export function updateMasterEnvironment(
  id: number,
  masterEnvironment: "test" | "production"
): Promise<Project> {
  return apiFetch<Project>(`/api/projects/${id}/master-environment`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ masterEnvironment }),
  });
}

export function bindProjectEnvironment(
  id: number,
  environment: ProjectEnvironment,
  siteId: number
): Promise<Project> {
  return apiFetch<Project>(`/api/projects/${id}/environments`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ environment, siteId }),
  });
}

export function unbindProjectEnvironment(
  id: number,
  environment: ProjectEnvironment
): Promise<Project> {
  return apiFetch<Project>(`/api/projects/${id}/environments/${environment}`, { method: 'DELETE' });
}

export type EnvironmentSyncTarget = "themes" | "plugins" | "media" | "db";

export function syncProjectEnvironment(
  id: number,
  input: { from: ProjectEnvironment; to: ProjectEnvironment; targets: EnvironmentSyncTarget[] }
): Promise<void> {
  return apiFetch<void>(`/api/projects/${id}/environments/sync`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export type BulkOperationType =
  | "CATEGORY_CREATE"
  | "CATEGORY_EDIT"
  | "CATEGORY_DELETE"
  | "TAG_CREATE"
  | "TAG_EDIT"
  | "TAG_DELETE"
  | "PLUGIN_INSTALL"
  | "PLUGIN_ACTIVATE"
  | "PLUGIN_DEACTIVATE"
  | "PLUGIN_DELETE"
  | "THEME_INSTALL"
  | "THEME_ACTIVATE"
  | "THEME_DELETE"
  | "CATEGORY_FETCH"
  | "TAG_FETCH"
  | "PLUGIN_FETCH"
  | "THEME_FETCH"
  | "POST_FETCH"
  | "MEDIA_UPLOAD"
  | "POST_DELETE"
  | "POST_STATUS_UPDATE";
export type ZipInstallOperationType = "PLUGIN_INSTALL" | "THEME_INSTALL";
export type BulkOperationSourceType = "SLUG" | "ZIP";
export type BulkOperationStatus = "SUCCESS" | "SKIPPED" | "FAILED";
export type BulkOperationLogLevel = "INFO" | "WARNING" | "ERROR";

export interface BulkOperationLog {
  operationType: BulkOperationType;
  sourceType: BulkOperationSourceType;
  value: string;
  categorySlug: string | null;
  categoryParentSlug: string | null;
  categoryTargetSlug: string | null;
  categoryDescription: string | null;
  originalFilename: string | null;
  postStatus: string | null;
  environment: ProjectEnvironment;
  status: BulkOperationStatus;
  level: BulkOperationLogLevel;
  errorMessage: string | null;
  stackTrace: string | null;
  createdAt: string;
}

export interface TermEnvironmentValue {
  available: boolean;
  error: boolean;
  errorMessage: string | null;
  slug: string | null;
  parentSlug: string | null;
  description: string | null;
}

export interface TermComparisonRow {
  name: string;
  slug: string;
  local: TermEnvironmentValue;
  test: TermEnvironmentValue;
  production: TermEnvironmentValue;
}

export interface TermComparisonPage {
  items: TermComparisonRow[];
  page: number;
  size: number;
  totalCount: number;
  masterEnvironment: "test" | "production";
}

export function listCategoryComparison(
  projectId: number,
  page: number
): Promise<TermComparisonPage> {
  return apiFetch<TermComparisonPage>(
    `/api/projects/${projectId}/bulk-management/categories/comparison?page=${page}`
  );
}

export function listTagComparison(
  projectId: number,
  page: number
): Promise<TermComparisonPage> {
  return apiFetch<TermComparisonPage>(
    `/api/projects/${projectId}/bulk-management/tags/comparison?page=${page}`
  );
}

export function applyToEnvironment(
  projectId: number,
  input: {
    environment: ProjectEnvironment;
    operationType: BulkOperationType;
    value?: string;
    categorySlug?: string;
    categoryParentSlug?: string;
    categoryDescription?: string;
    categoryTargetSlug?: string;
  }
): Promise<BulkOperationLog> {
  return apiFetch<BulkOperationLog>(`/api/projects/${projectId}/bulk-management/apply`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function applyToAllEnvironments(
  projectId: number,
  input: {
    operationType: BulkOperationType;
    value: string;
  }
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/apply-all`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function syncCategoryToMaster(
  projectId: number,
  slug: string
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/categories/sync`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ slug }),
  });
}

export function deleteCategoryEverywhere(
  projectId: number,
  slug: string
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/categories/delete-all`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ slug }),
  });
}

export function syncTagToMaster(
  projectId: number,
  slug: string
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/tags/sync`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ slug }),
  });
}

export interface EditTermInput {
  targetSlug: string;
  value: string;
  slug: string;
  parentSlug?: string;
  description?: string;
}

export function editCategoryAndSync(
  projectId: number,
  input: EditTermInput
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/categories/edit-sync`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function editTagAndSync(
  projectId: number,
  input: EditTermInput
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/tags/edit-sync`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function syncAllCategoriesToMaster(
  projectId: number
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/categories/sync-all`, {
    method: 'POST',
  });
}

export function syncAllTagsToMaster(
  projectId: number
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/tags/sync-all`, {
    method: 'POST',
  });
}

export function deleteTagEverywhere(
  projectId: number,
  slug: string
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/tags/delete-all`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ slug }),
  });
}

export type PluginThemeStatus = "NOT_INSTALLED" | "INACTIVE" | "ACTIVE";

export interface StatusEnvironmentValue {
  available: boolean;
  error: boolean;
  errorMessage: string | null;
  status: PluginThemeStatus | null;
}

export interface StatusComparisonRow {
  slug: string;
  local: StatusEnvironmentValue;
  test: StatusEnvironmentValue;
  production: StatusEnvironmentValue;
}

export interface StatusComparisonPage {
  items: StatusComparisonRow[];
  page: number;
  size: number;
  totalCount: number;
  masterEnvironment: "test" | "production";
}

export function listPluginComparison(
  projectId: number,
  page: number
): Promise<StatusComparisonPage> {
  return apiFetch<StatusComparisonPage>(
    `/api/projects/${projectId}/bulk-management/plugins/comparison?page=${page}`
  );
}

export function listThemeComparison(
  projectId: number,
  page: number
): Promise<StatusComparisonPage> {
  return apiFetch<StatusComparisonPage>(
    `/api/projects/${projectId}/bulk-management/themes/comparison?page=${page}`
  );
}

export function reconcilePluginState(
  projectId: number,
  input: { slug: string; changes: { environment: ProjectEnvironment; desiredStatus: PluginThemeStatus }[] }
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/plugins/reconcile`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function reconcileThemeState(
  projectId: number,
  input: { slug: string; changes: { environment: ProjectEnvironment; desiredStatus: PluginThemeStatus }[] }
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/themes/reconcile`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
}

export function deletePluginEverywhere(
  projectId: number,
  slug: string
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/plugins/delete-all`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ slug }),
  });
}

export function deleteThemeEverywhere(
  projectId: number,
  slug: string
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/themes/delete-all`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ slug }),
  });
}

export type PostType = "post" | "page";

export interface PostEnvironmentValue {
  available: boolean;
  error: boolean;
  errorMessage: string | null;
  postId: string | null;
  title: string | null;
  status: string | null;
}

export interface PostComparisonRow {
  slug: string;
  local: PostEnvironmentValue;
  test: PostEnvironmentValue;
  production: PostEnvironmentValue;
}

export interface PostComparisonPage {
  items: PostComparisonRow[];
  page: number;
  size: number;
  totalCount: number;
  postType: PostType;
}

export function listPostComparison(
  projectId: number,
  postType: PostType,
  page: number
): Promise<PostComparisonPage> {
  return apiFetch<PostComparisonPage>(
    `/api/projects/${projectId}/bulk-management/posts/comparison?postType=${postType}&page=${page}`
  );
}

export function deletePostEverywhere(
  projectId: number,
  postType: PostType,
  slug: string
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(
    `/api/projects/${projectId}/bulk-management/posts/delete-all?postType=${postType}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug }),
    }
  );
}

export function updatePostStatusEverywhere(
  projectId: number,
  postType: PostType,
  slug: string,
  status: string
): Promise<BulkOperationLog[]> {
  return apiFetch<BulkOperationLog[]>(
    `/api/projects/${projectId}/bulk-management/posts/status-update?postType=${postType}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ slug, status }),
    }
  );
}

export function runBulkOperationUpload(
  projectId: number,
  input: { operationType: ZipInstallOperationType; file: File }
): Promise<BulkOperationLog[]> {
  const formData = new FormData();
  formData.append('operationType', input.operationType);
  formData.append('file', input.file);
  return apiFetch<BulkOperationLog[]>(`/api/projects/${projectId}/bulk-management/upload`, {
    method: 'POST',
    body: formData,
  });
}

export interface ProjectUser {
  userId: number;
  email: string | null;
  displayName: string | null;
  wpRole: string;
}

export function listProjectUsers(projectId: number): Promise<ProjectUser[]> {
  return apiFetch<ProjectUser[]>(`/api/projects/${projectId}/users`);
}

export function addProjectUser(
  projectId: number,
  userId: number,
  wpRole: string
): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/users`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId, wpRole }),
  });
}

export function updateProjectUserRole(
  projectId: number,
  userId: number,
  wpRole: string
): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/users/${userId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ wpRole }),
  });
}

export function removeProjectUser(projectId: number, userId: number): Promise<void> {
  return apiFetch<void>(`/api/projects/${projectId}/users/${userId}`, { method: 'DELETE' });
}

/** issue #1242: メンバー個別のユーザー情報再同期の、サイト1件分の結果。 */
export interface ProjectUserSyncSiteResult {
  siteId: number;
  siteKey: string;
  siteName: string;
  success: boolean;
  errorMessage: string | null;
}

/**
 * issue #1242: ロール変更を伴わず、メンバーの現在のプロフィールをプロジェクトに紐づく
 * 全WordPress環境へ再送信する。一部の環境が失敗しても他の環境の結果は保持される。
 */
export function syncProjectUser(projectId: number, userId: number): Promise<ProjectUserSyncSiteResult[]> {
  return apiFetch<ProjectUserSyncSiteResult[]>(`/api/projects/${projectId}/users/${userId}/sync`, {
    method: 'POST',
  });
}

export interface ProjectUserSummary {
  projectId: number;
  userId: number;
  wpRole: string;
}

export function listAllProjectUsers(): Promise<ProjectUserSummary[]> {
  return apiFetch<ProjectUserSummary[]>('/api/project-users');
}

export interface GenerationJobDetail {
  id: number;
  type: string;
  status: string;
  requestPayload: string | null;
  resultPayload: string | null;
  createdAt: string;
  updatedAt: string;
}

export function getGenerationJob(id: number): Promise<GenerationJobDetail> {
  return apiFetch<GenerationJobDetail>(`/api/generation-jobs/${id}`);
}

export interface LlmModelListResponse {
  availableModels: string[];
  selected: string;
}

export function listLlmModels(projectId: number): Promise<LlmModelListResponse> {
  return apiFetch<LlmModelListResponse>(`/api/projects/${projectId}/ai-models/llm/models`);
}

export function selectLlmModel(
  projectId: number,
  modelName: string
): Promise<LlmModelListResponse> {
  return apiFetch<LlmModelListResponse>(`/api/projects/${projectId}/ai-models/llm/models/selection`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ modelName }),
  });
}

/** selectedはプロジェクト単位の上書き値(未設定時null)。resolvedのグローバル既定への解決はサーバー側で行う。 */
export interface LlmProviderListResponse {
  availableProviders: string[];
  selected: string | null;
}

export function listLlmProvider(projectId: number): Promise<LlmProviderListResponse> {
  return apiFetch<LlmProviderListResponse>(`/api/projects/${projectId}/ai-models/llm/provider`);
}

/** provider未指定(空文字)はプロジェクト単位の上書きを解除し、グローバル既定へ戻す。 */
export function selectLlmProvider(
  projectId: number,
  provider: string
): Promise<LlmProviderListResponse> {
  return apiFetch<LlmProviderListResponse>(`/api/projects/${projectId}/ai-models/llm/provider/selection`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider: provider || null }),
  });
}

/**
 * 多段レビュー(issue #1210)のステップ別LLMプロバイダー/モデル設定(issue #1211のAPI、
 * issue #1212で画面から使う)。5ステップぶんの選択値(未設定はnull)と選択可能な
 * provider/model一覧を返す。
 */
export interface ReviewStepSetting {
  stepKey: string;
  provider: string | null;
  model: string | null;
}

export interface ReviewStepSettingsResponse {
  steps: ReviewStepSetting[];
  availableProviders: string[];
  /** provider未設定のステップ用(システム既定providerの一覧)。 */
  availableModels: string[];
  /** providerごとの一覧(issue #1423)。工程のproviderで使えるモデル名だけを候補にする。 */
  availableModelsByProvider: Record<string, string[]>;
}

export function listReviewStepSettings(projectId: number): Promise<ReviewStepSettingsResponse> {
  return apiFetch<ReviewStepSettingsResponse>(`/api/projects/${projectId}/ai-models/llm/review-steps`);
}

/** provider/modelともに空文字は、そのステップの上書きを解除する(null送信)。 */
export function updateReviewStepSetting(
  projectId: number,
  stepKey: string,
  provider: string,
  model: string
): Promise<ReviewStepSettingsResponse> {
  return apiFetch<ReviewStepSettingsResponse>(
    `/api/projects/${projectId}/ai-models/llm/review-steps/${stepKey}`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider: provider || null, model: model || null }),
    }
  );
}

export type AiConnectionProvider = 'OLLAMA' | 'COMFYUI' | 'OPENAI' | 'CLAUDE';
export type ConnectionSource = 'PROJECT' | 'DATABASE' | 'NONE';

/** `GET /api/projects/{id}/ai-connections`(issue #1499)の1件。targetUrlは疎通確認に使ったURL。 */
export interface AiConnection {
  provider: AiConnectionProvider;
  displayName: string;
  targetUrl: string | null;
  source: ConnectionSource;
  status: 'NORMAL' | 'WARNING' | 'ERROR';
  detail: string | null;
  configured: boolean;
}

export function listAiConnections(projectId: number): Promise<AiConnection[]> {
  return apiFetch<AiConnection[]>(`/api/projects/${projectId}/ai-connections`);
}

/** overrideBaseUrlはこのプロジェクトの上書き値(無ければnull)、baseUrl/sourceは解決結果。 */
export interface ProjectConnectionEntry {
  overrideBaseUrl: string | null;
  baseUrl: string | null;
  source: ConnectionSource;
}

export interface ProjectConnectionsResponse {
  ollama?: ProjectConnectionEntry;
  comfyui?: ProjectConnectionEntry;
}

/** 項目を省略すると変更せず、空文字はその項目の上書きを解除する(issue #1503)。 */
export interface UpdateProjectConnectionsRequest {
  ollamaBaseUrl?: string;
  comfyuiBaseUrl?: string;
}

export function getProjectConnections(projectId: number): Promise<ProjectConnectionsResponse> {
  return apiFetch<ProjectConnectionsResponse>(`/api/projects/${projectId}/ai-models/connections`);
}

export function updateProjectConnections(
  projectId: number,
  request: UpdateProjectConnectionsRequest
): Promise<ProjectConnectionsResponse> {
  return apiFetch<ProjectConnectionsResponse>(`/api/projects/${projectId}/ai-models/connections`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
}

/** selectedはプロジェクト単位の上書き値(未設定時null)。未設定時はComfyUIとして扱われる。 */
export interface ImageProviderListResponse {
  availableProviders: string[];
  selected: string | null;
}

export function listImageProvider(projectId: number): Promise<ImageProviderListResponse> {
  return apiFetch<ImageProviderListResponse>(`/api/projects/${projectId}/ai-models/image/provider`);
}

/** provider未指定(空文字)はプロジェクト単位の上書きを解除し、ComfyUIへ戻す。 */
export function selectImageProvider(
  projectId: number,
  provider: string
): Promise<ImageProviderListResponse> {
  return apiFetch<ImageProviderListResponse>(`/api/projects/${projectId}/ai-models/image/provider/selection`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider: provider || null }),
  });
}

export interface ComfyUiCheckpointListResponse {
  checkpoints: string[];
  selected: string;
}

export function listComfyUiCheckpoints(
  projectId: number
): Promise<ComfyUiCheckpointListResponse> {
  return apiFetch<ComfyUiCheckpointListResponse>(`/api/projects/${projectId}/ai-models/comfyui/checkpoints`, {
  });
}

export function selectComfyUiCheckpoint(
  projectId: number,
  checkpointName: string
): Promise<ComfyUiCheckpointListResponse> {
  return apiFetch<ComfyUiCheckpointListResponse>(
    `/api/projects/${projectId}/ai-models/comfyui/checkpoints/selection`,
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ checkpointName }),
    }
  );
}

export function installComfyUiCheckpoint(
  projectId: number,
  downloadUrl: string,
  fileName: string
): Promise<GenerationJob> {
  return apiFetch<GenerationJob>(`/api/projects/${projectId}/ai-models/comfyui/checkpoints/install`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ downloadUrl, fileName }),
  });
}

export function deleteComfyUiCheckpoint(
  projectId: number,
  fileName: string
): Promise<GenerationJob> {
  return apiFetch<GenerationJob>(
    `/api/projects/${projectId}/ai-models/comfyui/checkpoints/${encodeURIComponent(fileName)}`,
    { method: 'DELETE' }
  );
}

/** ガベージコレクション画面(issue #500)の一覧行。 */
export interface UnreferencedMediaItem {
  mediaId: string;
  guid: string;
  title: string;
  mimeType: string;
  uploadedAt: string;
}

export interface MediaGarbageCollectionScanResponse {
  environment: ProjectEnvironment;
  items: UnreferencedMediaItem[];
  totalMediaCount: number;
  referencedMediaCount: number;
  unreferencedMediaCount: number;
}

export function scanMediaGarbage(
  projectId: number,
  environment: ProjectEnvironment
): Promise<MediaGarbageCollectionScanResponse> {
  return apiFetch<MediaGarbageCollectionScanResponse>(
    `/api/projects/${projectId}/media-garbage-collection/scan?environment=${environment}`
  );
}

export function deleteMediaGarbage(
  projectId: number,
  environment: ProjectEnvironment,
  mediaIds: string[]
): Promise<GenerationJob> {
  return apiFetch<GenerationJob>(
    `/api/projects/${projectId}/media-garbage-collection/delete?environment=${environment}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ mediaIds }),
    }
  );
}

/**
 * VSCode拡張機能(.vsix)をAPIサーバーからダウンロードする。APIサーバー側で
 * オンデマンドビルド(初回は数十秒かかる場合がある)されるため、JSONデコードを行うapiFetch()では
 * なく、共通処理はそのままにResponseを返すapiRequest()を使ってバイナリを直接扱う。
 */
export async function downloadVscodeExtension(): Promise<{ body: ArrayBuffer; filename: string }> {
  return downloadSystemFile('/api/system/vscode-extension', 'letsblog-vscode.vsix');
}

/** Penpotプラグインのソース一式 + setup.sh のZip(issue #1491)。ビルドは利用者の手元で走る。 */
export function downloadPenpotPlugin(): Promise<{ body: ArrayBuffer; filename: string }> {
  return downloadSystemFile('/api/system/penpot-plugin', 'letsblog-penpot-plugin.zip');
}

/** MCPサーバーのソース一式 + setup.sh のZip(issue #1491)。ビルドは利用者の手元で走る。 */
export function downloadMcpServer(): Promise<{ body: ArrayBuffer; filename: string }> {
  return downloadSystemFile('/api/system/mcp-server', 'letsblog-mcp-server.zip');
}

async function downloadSystemFile(
  path: string,
  defaultFilename: string,
): Promise<{ body: ArrayBuffer; filename: string }> {
  const res = await apiRequest(path);
  const disposition = res.headers.get('content-disposition') ?? '';
  const match = disposition.match(/filename="([^"]+)"/);
  const filename = match ? match[1] : defaultFilename;
  return { body: await res.arrayBuffer(), filename };
}

export interface ConnectedServiceStatus {
  id: string;
  name: string;
  status: "NORMAL" | "WARNING" | "ERROR";
}

export function getConnectedServiceStatuses(): Promise<ConnectedServiceStatus[]> {
  return apiFetch<ConnectedServiceStatus[]>('/api/dashboard/service-status');
}

export interface ConnectedServiceStatusDetail {
  id: string;
  name: string;
  status: "NORMAL" | "WARNING" | "ERROR";
  responseTimeMs: number;
  httpStatus: number | null;
  errorMessage: string | null;
  targetUrl: string | null;
  checkedAt: string;
  /**
   * この依存/サービスが落ちたときに使えなくなる機能(issue #589)。
   * 外部依存など、影響を定義していないものは null。
   */
  impact: string | null;
  /**
   * そのサービスが実際に使っている演算デバイス(issue #1397)。ComfyUIは cpu / cuda など、
   * Ollama は cpu / gpu / gpu+cpu、モデル未ロードで判別できなければ unknown。
   * 取得できない・対象外のサービスは null。admin向け詳細診断にのみ含まれる。
   */
  computeDevice: string | null;
}

/** 応答時間・エラー内容・チェック対象URLなどの詳細診断情報(issue #199)。admin限定、非adminが呼ぶと403になる。 */
export function getConnectedServiceStatusDetail(): Promise<ConnectedServiceStatusDetail[]> {
  return apiFetch<ConnectedServiceStatusDetail[]>('/api/dashboard/service-status/detail');
}

/**
 * 接続サービスの稼働状況をSSEで受け取るためのアップストリーム接続(issue #198)。
 * apiFetch()はJSONレスポンス前提のためストリーミングには使えないが、共通処理(gatewayのベースURL・
 * 認証ヘッダ・操作ログ)は共有できるようapiRequest()を使う(issue #584)。
 * 呼び出し元(Route Handler)がstatusとbodyをそのままブラウザへ中継するため、
 * HTTPエラーでも例外は投げずResponseを返す(throwOnError: false)。
 */
export async function streamConnectedServiceStatuses(): Promise<Response> {
  return apiRequest('/api/dashboard/service-status/stream', {
    headers: { Accept: 'text/event-stream' },
    throwOnError: false,
  });
}

/** このアプリを構成するDockerコンテナ(lbs-*)の稼働状況(issue #280)。 */
export interface ContainerStatus {
  id: string;
  name: string;
  status: "NORMAL" | "WARNING" | "ERROR";
  /** "standby" は演算デバイスの代替構成として意図的に停止していることを表す(issue #1584)。 */
  state: string;
  detail: string;
}

export function getContainerStatuses(): Promise<ContainerStatus[]> {
  return apiFetch<ContainerStatus[]>('/api/dashboard/container-status');
}

/** コンテナ稼働状況をSSEで受け取るためのアップストリーム接続(issue #280)。streamConnectedServiceStatuses()と同じ扱い。 */
export async function streamContainerStatuses(): Promise<Response> {
  return apiRequest('/api/dashboard/container-status/stream', {
    headers: { Accept: 'text/event-stream' },
    throwOnError: false,
  });
}
