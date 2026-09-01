import { httpRequest } from './httpClient';

/**
 * Device Authorization Grant(RFC 8628)によるログイン処理(issue #565)。
 *
 * VSCode拡張はOAuthのリダイレクト先を持てないため、Authorization Codeではなく
 * Device Codeフローを使う。Keycloak側にはredirect不要・client_secret不要のpublicクライアント
 * `letsblog-vscode` を用意済み(infra/keycloak/realm-export.json、
 * `oauth2.device.authorization.grant.enabled: true`)。
 *
 * このファイルは「サーバーの応答をどう解釈するか」「エラーコードから次に何をすべきか」という
 * 判定ロジックを、実際のHTTP呼び出しから分離して単体テストできるようにするために存在する
 * (extension.ts側のポーリングループはvscode.window.withProgress等のUIと絡むため、
 * ここでは1回分のリクエスト/判定だけを提供する)。
 */

/** Keycloak側に登録済みのpublicクライアントID(client_secret不要、infra/keycloak/realm-export.json参照)。 */
export const DEVICE_CLIENT_ID = 'letsblog-vscode';

/**
 * letsBlog.serverUrl(仲介APIサーバーの外部ベースURL)から、Keycloakのrealmエンドポイントの
 * ベースURLを組み立てる。nginxが `/auth/` をKeycloakへフォワードする構成
 * (web側の `keycloakExternalBase` と同じパターン、`apps/web/src/lib/auth.ts` 参照)。
 */
export function realmBaseUrl(serverUrl: string): string {
  return `${serverUrl}/auth/realms/letsblog`;
}

/** デバイス認可リクエスト(POST {realm}/protocol/openid-connect/auth/device)の成功応答。 */
export interface DeviceAuthorization {
  deviceCode: string;
  userCode: string;
  verificationUri: string;
  verificationUriComplete?: string;
  /** 認可コードの有効期限(秒)。 */
  expiresIn: number;
  /** ポーリング間隔(秒)。slow_downを受けた場合はこれより長い間隔へ切り替える。 */
  interval: number;
}

/** トークンエンドポイントの成功応答から必要な項目だけを取り出したもの。 */
export interface TokenResult {
  accessToken: string;
  refreshToken: string;
  /** アクセストークンの有効期限(秒)。 */
  expiresIn: number;
}

/**
 * デバイス認可レスポンスをパースする。想定外の形式(フィールド欠落等)は
 * サーバー側の不具合やバージョン不一致を早期に検出するため、例外として投げる。
 */
export function parseDeviceAuthorization(data: unknown): DeviceAuthorization {
  if (typeof data !== 'object' || data === null) {
    throw new Error('デバイス認可の応答が不正です。');
  }
  const record = data as Record<string, unknown>;
  const { device_code: deviceCode, user_code: userCode, verification_uri: verificationUri } = record;
  const { expires_in: expiresIn, interval } = record;
  if (
    typeof deviceCode !== 'string' ||
    typeof userCode !== 'string' ||
    typeof verificationUri !== 'string' ||
    typeof expiresIn !== 'number' ||
    typeof interval !== 'number'
  ) {
    throw new Error('デバイス認可の応答に必須項目が欠けています。');
  }
  const verificationUriComplete =
    typeof record.verification_uri_complete === 'string' ? record.verification_uri_complete : undefined;
  return { deviceCode, userCode, verificationUri, verificationUriComplete, expiresIn, interval };
}

/** トークンエンドポイントの成功応答をパースする。 */
export function parseTokenResult(data: unknown): TokenResult {
  if (typeof data !== 'object' || data === null) {
    throw new Error('トークン応答が不正です。');
  }
  const record = data as Record<string, unknown>;
  const { access_token: accessToken, refresh_token: refreshToken, expires_in: expiresIn } = record;
  if (typeof accessToken !== 'string' || typeof refreshToken !== 'string' || typeof expiresIn !== 'number') {
    throw new Error('トークン応答に必須項目が欠けています。');
  }
  return { accessToken, refreshToken, expiresIn };
}

/** トークンエンドポイントのポーリング1回分の結果。呼び出し側(extension.ts)がこれを見てループを制御する。 */
export type PollOutcome =
  | { kind: 'success'; tokens: TokenResult }
  | { kind: 'pending' }
  | { kind: 'slow_down' }
  | { kind: 'denied' }
  | { kind: 'expired' };

/**
 * トークンエンドポイントの応答(ステータスとボディ)から、ポーリングを継続すべきか、
 * 間隔を広げるべきか、諦めるべきかを判定する。RFC 8628の代表的なerrorコードに対応する。
 */
export function classifyPollResponse(status: number, body: unknown): PollOutcome {
  if (status >= 200 && status < 300) {
    return { kind: 'success', tokens: parseTokenResult(body) };
  }
  const error = typeof body === 'object' && body !== null ? (body as Record<string, unknown>).error : undefined;
  switch (error) {
    case 'authorization_pending':
      return { kind: 'pending' };
    case 'slow_down':
      return { kind: 'slow_down' };
    case 'access_denied':
      return { kind: 'denied' };
    case 'expired_token':
      return { kind: 'expired' };
    default:
      throw new Error(`トークンの取得に失敗しました (HTTP ${status})`);
  }
}

/** expiresIn(秒)からアクセストークンの有効期限(エポックミリ秒)を計算する。 */
export function computeExpiresAt(expiresInSeconds: number, now: number = Date.now()): number {
  return now + expiresInSeconds * 1000;
}

/** デバイス認可をKeycloakへリクエストする。 */
export async function requestDeviceAuthorization(
  serverUrl: string,
  allowInsecureTls: boolean,
  signal: AbortSignal
): Promise<DeviceAuthorization> {
  const res = await httpRequest(`${realmBaseUrl(serverUrl)}/protocol/openid-connect/auth/device`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ client_id: DEVICE_CLIENT_ID }).toString(),
    signal,
    allowInsecureTls,
  });
  if (!res.ok) {
    throw new Error(`デバイス認可のリクエストに失敗しました (HTTP ${res.status})`);
  }
  return parseDeviceAuthorization(await res.json());
}

/**
 * トークンエンドポイントを1回だけ呼び出す(ループ・待機は呼び出し側が行う)。
 * 4xxのauthorization_pending/slow_down/access_denied/expired_tokenはRFC 8628上「正常な」
 * ポーリング応答のため、resではなくclassifyPollResponseの戻り値として扱う。
 */
export async function pollForToken(
  serverUrl: string,
  deviceCode: string,
  allowInsecureTls: boolean,
  signal: AbortSignal
): Promise<PollOutcome> {
  const res = await httpRequest(`${realmBaseUrl(serverUrl)}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
      device_code: deviceCode,
      client_id: DEVICE_CLIENT_ID,
    }).toString(),
    signal,
    allowInsecureTls,
  });
  const body = await res.json().catch(() => undefined);
  return classifyPollResponse(res.status, body);
}

/** リフレッシュトークンでアクセストークンを更新する。失敗時(失効・取り消し等)は例外を投げる。 */
export async function refreshAccessToken(
  serverUrl: string,
  refreshToken: string,
  allowInsecureTls: boolean,
  signal: AbortSignal
): Promise<TokenResult> {
  const res = await httpRequest(`${realmBaseUrl(serverUrl)}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'refresh_token',
      refresh_token: refreshToken,
      client_id: DEVICE_CLIENT_ID,
    }).toString(),
    signal,
    allowInsecureTls,
  });
  if (!res.ok) {
    throw new Error(`トークンの更新に失敗しました (HTTP ${res.status})`);
  }
  return parseTokenResult(await res.json());
}
