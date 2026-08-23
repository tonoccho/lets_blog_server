import * as vscode from 'vscode';
import { z } from 'zod';
import { logger } from './logger';
import { Actor, ActorSchema } from './schemas';
import { messageOf } from './errorHandler';
import { computeExpiresAt, refreshAccessToken as requestTokenRefresh, TokenResult } from './deviceAuth';

const TOKENS_SECRET = 'letsBlog.tokens';
const ACTOR_SECRET = 'letsBlog.actor';
const PROJECT_ID_STATE = 'letsBlog.projectId';

/** Actorの定義元はschemas.ts(APIレスポンスの検証スキーマ)。ここでは型を中継する。 */
export type { Actor };

/** 設定された仲介APIサーバーのベースURL。末尾のスラッシュは除去して返す。 */
export function getServerUrl(): string {
  const url = vscode.workspace.getConfiguration('letsBlog').get<string>('serverUrl');
  return (url ?? 'https://localhost').replace(/\/+$/, '');
}

/**
 * TLS証明書の検証は既定で有効(allowInsecureTls=false)。
 * 検証を無効化すると中間者攻撃でアクセストークンや記事内容を傍受・改竄されうるため、
 * 自己署名証明書のローカル環境へ接続する場合に限り、利用者が明示的に有効化する。
 * 危険な設定であることに気付けるよう、有効な間は警告としてログに残す。
 * 通常のAPI呼び出し(apiClient.ts)とDevice Authorization Grant(deviceAuth.ts)の
 * トークン関連呼び出しの両方から使うため、設定値を扱うこのファイルに置く。
 */
export function allowsInsecureTls(): boolean {
  const allowed = vscode.workspace.getConfiguration('letsBlog').get<boolean>('allowInsecureTls', false);
  if (allowed) {
    logger.warn(
      'letsBlog.allowInsecureTlsが有効なため、TLS証明書の検証をスキップします。' +
        '信頼できるネットワーク上のローカル環境でのみ使用してください。'
    );
  }
  return allowed;
}

/**
 * 現在選択中のAIプロバイダー(OLLAMA/OPENAI/CLAUDE)。空文字は「サーバー(プロジェクト/システム設定)の
 * 既定値を使う」ことを意味する(issue #530)。「Let's Blog: Switch AI Provider」コマンドや各AI画面の
 * プロバイダー選択メニューから随時切り替えられるようにするための設定値。
 */
export function getConfiguredAiProvider(): string {
  return vscode.workspace.getConfiguration('letsBlog').get<string>('aiProvider', '');
}

/** アクティブなワークスペースのuser設定へAIプロバイダーを書き込む。 */
export async function setConfiguredAiProvider(provider: string): Promise<void> {
  await vscode.workspace
    .getConfiguration('letsBlog')
    .update('aiProvider', provider, vscode.ConfigurationTarget.Global);
}

/**
 * SecretStorageに保存するトークン一式(issue #565: Device Authorization Grant)。
 * expiresAtはaccess_tokenのexpires_in(秒)から計算したエポックミリ秒。
 */
const TokenSetSchema = z.object({
  accessToken: z.string(),
  refreshToken: z.string(),
  expiresAt: z.number(),
});
export type TokenSet = z.infer<typeof TokenSetSchema>;

/** アクセストークンの有効期限までこの猶予(ミリ秒)を切ったら、まだ有効でも先んじてリフレッシュする。 */
const REFRESH_SKEW_MS = 30_000;

/** リフレッシュ等、トークンエンドポイントへの単発リクエストのタイムアウト。 */
const TOKEN_REQUEST_TIMEOUT_MS = 30_000;

/**
 * SecretStorageに保存されたトークン一式を復元する。壊れた値が残っていると以降のログイン状態判定が
 * 常に失敗し続けるため、解析に失敗した場合はログに残した上で保存値を破棄し、再ログインを促す
 * (getActorと同じ方針)。
 */
async function getTokens(context: vscode.ExtensionContext): Promise<TokenSet | undefined> {
  const json = await context.secrets.get(TOKENS_SECRET);
  if (!json) return undefined;
  try {
    return TokenSetSchema.parse(JSON.parse(json));
  } catch (error) {
    logger.warn('保存されたログイン情報を読み込めませんでした。再ログインが必要です。', {
      reason: messageOf(error),
    });
    await context.secrets.delete(TOKENS_SECRET);
    return undefined;
  }
}

/** トークン一式をSecretStorageへ保管する。 */
async function setTokens(context: vscode.ExtensionContext, tokens: TokenSet): Promise<void> {
  await context.secrets.store(TOKENS_SECRET, JSON.stringify(tokens));
}

/** 保管されたトークンを削除する(リフレッシュ失敗時の破棄に使う)。 */
async function clearTokens(context: vscode.ExtensionContext): Promise<void> {
  await context.secrets.delete(TOKENS_SECRET);
}

/**
 * デバイスコードフローで取得したトークン(またはリフレッシュで更新したトークン)を保存する。
 * expires_in(秒)をこの時点のエポックミリ秒へ変換して保持する。
 */
export async function storeTokens(context: vscode.ExtensionContext, tokens: TokenResult): Promise<void> {
  await setTokens(context, {
    accessToken: tokens.accessToken,
    refreshToken: tokens.refreshToken,
    expiresAt: computeExpiresAt(tokens.expiresIn),
  });
}

function isExpiredOrNearExpiry(tokens: TokenSet, now: number): boolean {
  return now >= tokens.expiresAt - REFRESH_SKEW_MS;
}

/**
 * 有効なアクセストークンを返す。期限切れ間近/切れの場合はリフレッシュトークンで自動更新してから返す。
 * リフレッシュにも失敗した場合(リフレッシュトークン自体の失効・取り消し等)は保存済みトークンを破棄し、
 * 再ログインを促す例外を投げる。未ログインの場合も同様。
 */
export async function requireAccessToken(context: vscode.ExtensionContext): Promise<string> {
  const tokens = await getTokens(context);
  if (!tokens) {
    throw new Error("ログインしていません。「Let's Blog: Login」を先に実行してください。");
  }
  if (!isExpiredOrNearExpiry(tokens, Date.now())) {
    return tokens.accessToken;
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TOKEN_REQUEST_TIMEOUT_MS);
  try {
    const refreshed = await requestTokenRefresh(getServerUrl(), tokens.refreshToken, allowsInsecureTls(), controller.signal);
    await storeTokens(context, refreshed);
    return refreshed.accessToken;
  } catch (error) {
    logger.warn('アクセストークンの更新に失敗しました。再ログインが必要です。', { reason: messageOf(error) });
    await clearTokens(context);
    throw new Error("ログインの有効期限が切れました。「Let's Blog: Login」で再ログインしてください。");
  } finally {
    clearTimeout(timer);
  }
}

/**
 * requireAccessTokenのラップ。未ログイン/リフレッシュ失敗時は例外を投げずundefinedを返す
 * (front matterのコード補完等、未ログインでも他の機能は継続させたい呼び出し元向け)。
 */
export async function getAccessToken(context: vscode.ExtensionContext): Promise<string | undefined> {
  try {
    return await requireAccessToken(context);
  } catch {
    return undefined;
  }
}

/**
 * SecretStorageに保存されたActorを復元する。壊れた値が残っていると以降のログイン状態判定が
 * 常に失敗し続けるため、解析に失敗した場合はログに残した上で保存値を破棄し、再ログインを促す。
 */
export async function getActor(context: vscode.ExtensionContext): Promise<Actor | undefined> {
  const json = await context.secrets.get(ACTOR_SECRET);
  if (!json) return undefined;
  try {
    // 保存値もAPIレスポンスと同じスキーマで検証し、欠落や型不一致を早期に検出する。
    return ActorSchema.parse(JSON.parse(json));
  } catch (error) {
    logger.warn('保存されたログイン情報を読み込めませんでした。再ログインが必要です。', {
      reason: messageOf(error),
    });
    await context.secrets.delete(ACTOR_SECRET);
    void vscode.window.showWarningMessage(
      '保存されたログイン情報を読み込めませんでした。「Let\'s Blog: Login」で再ログインしてください。'
    );
    return undefined;
  }
}

/** ログインユーザーをSecretStorageへ保管する。 */
export async function setActor(context: vscode.ExtensionContext, actor: Actor): Promise<void> {
  await context.secrets.store(ACTOR_SECRET, JSON.stringify(actor));
}

/** 保管されたログインユーザーを削除する(ログアウト相当)。 */
export async function clearActor(context: vscode.ExtensionContext): Promise<void> {
  await context.secrets.delete(ACTOR_SECRET);
}

/** ログインユーザーを取得する。未ログインの場合は対応方法を含む例外を投げる。 */
export async function requireActor(context: vscode.ExtensionContext): Promise<Actor> {
  const actor = await getActor(context);
  if (!actor) {
    throw new Error('ログインしていません。「Let\'s Blog: Login」を先に実行してください。');
  }
  return actor;
}

/** 選択中のプロジェクトID。ワークスペース単位で保持する。 */
export function getProjectId(context: vscode.ExtensionContext): number | undefined {
  return context.workspaceState.get(PROJECT_ID_STATE);
}

/** 選択中のプロジェクトIDを保存する。 */
export async function setProjectId(context: vscode.ExtensionContext, projectId: number): Promise<void> {
  await context.workspaceState.update(PROJECT_ID_STATE, projectId);
}

/** 選択中のプロジェクトIDを取得する。未選択の場合は対応方法を含む例外を投げる。 */
export function requireProjectId(context: vscode.ExtensionContext): number {
  const projectId = getProjectId(context);
  if (projectId == null) {
    throw new Error('プロジェクトが未選択です。「Let\'s Blog: Select Project」を先に実行してください。');
  }
  return projectId;
}
