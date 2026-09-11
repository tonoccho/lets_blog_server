import * as vscode from 'vscode';
import { z } from 'zod';
import { logger } from './logger';
import { Actor, ActorSchema } from './schemas';
import { messageOf } from './errorHandler';
import {
  computeExpiresAt,
  isRefreshTokenRevoked,
  refreshAccessToken as requestTokenRefresh,
  revokeRefreshToken,
  TOKEN_REQUEST_TIMEOUT_MS,
  TokenResult,
} from './deviceAuth';

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

/** 保管されたトークンを削除する(リフレッシュトークンが確定的に失効した場合の破棄に使う)。 */
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
 *
 * リフレッシュに失敗した場合、保存済みトークンを破棄するのは**確定的な失効**
 * (リフレッシュトークン自体が失効・取り消しされ、何度試しても成功しない)と判断できるときだけにする
 * (issue #1098)。サーバーの再起動・瞬断・VPN切り替えのような一過性の失敗でトークンを捨てると、
 * まだ有効なリフレッシュトークンを失って強制ログアウトになるため、その場合は保管したまま例外を投げ、
 * 復旧後に同じcontextで呼び直せば再ログインなしで更新が成功するようにする。
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
    if (isRefreshTokenRevoked(error)) {
      logger.warn('リフレッシュトークンが失効しています。再ログインが必要です。', { reason: messageOf(error) });
      await clearTokens(context);
      throw new Error("ログインの有効期限が切れました。「Let's Blog: Login」で再ログインしてください。");
    }
    logger.warn('アクセストークンの更新に一時的に失敗しました。保存済みのログイン情報は保持します。', {
      reason: messageOf(error),
    });
    throw new Error(
      'アクセストークンを更新できませんでした(一時的な失敗の可能性があります)。' +
        `保存済みのログイン情報は保持しているため、しばらく待ってから再実行してください。 原因: ${messageOf(error)}`
    );
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

/** logoutの結果。Keycloak側の終了に成功したかを呼び出し側(extension.ts)が通知文言に反映する。 */
export interface LogoutResult {
  /** 実行前にログイン状態だったか。未ログインでの実行はKeycloakへ通信しない(issue #1099)。 */
  wasLoggedIn: boolean;
  /** Keycloak側のoffline sessionを終了できたか。未ログインだった場合はtrue(終了すべき対象が無いため)。 */
  keycloakSessionEnded: boolean;
}

/**
 * ログアウトする(issue #1099)。
 *
 * SecretStorageの `letsBlog.tokens` / `letsBlog.actor` の削除は、Keycloakへの通信結果によらず
 * 必ず行う——手元の資格情報を消せないほうが危険であるため(apps/web/src/lib/auth.tsのsignOutと
 * 同じ判断)。Keycloak側のoffline session終了に失敗しても、ログアウト操作自体は成功として扱い、
 * 呼び出し側がサーバー側セッションが残りうる旨を利用者に伝えられるよう結果を返す。
 */
export async function logout(context: vscode.ExtensionContext): Promise<LogoutResult> {
  const tokens = await getTokens(context);
  await clearTokens(context);
  await clearActor(context);

  if (!tokens) {
    return { wasLoggedIn: false, keycloakSessionEnded: true };
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TOKEN_REQUEST_TIMEOUT_MS);
  try {
    await revokeRefreshToken(getServerUrl(), tokens.refreshToken, allowsInsecureTls(), controller.signal);
    return { wasLoggedIn: true, keycloakSessionEnded: true };
  } catch (error) {
    logger.warn(
      'Keycloak側のoffline session終了に失敗しました。サーバー側のセッションが残っている可能性があります。',
      { reason: messageOf(error) }
    );
    return { wasLoggedIn: true, keycloakSessionEnded: false };
  } finally {
    clearTimeout(timer);
  }
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
