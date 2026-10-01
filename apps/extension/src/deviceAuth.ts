import { httpRequest, HttpResponse } from './httpClient';
import { ApiError, NetworkError, TimeoutError } from './errorHandler';

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
 * デバイス認可要求で要求するスコープ(issue #1098)。
 *
 * 通常のリフレッシュトークンはKeycloakのSSOセッション(realmの ssoSessionIdleTimeout = 30分 /
 * ssoSessionMaxLifespan = 10時間)に紐づくため、IDE拡張の使い方——エディタを開いたまま、
 * 執筆の合間に断続的に呼ぶ——では30分の無操作や10時間の連続利用で強制ログアウトになる。
 * offline_accessを要求するとoffline tokenが発行され、SSOセッションの寿命から独立する
 * (offlineSessionIdleTimeout = 14日、offlineSessionMaxLifespan = 14日。#1100)。
 *
 * 要求するのはデバイス認可要求のときだけでよい。RFC 8628 §3.4のアクセストークン要求は
 * grant_type/device_code/client_idのみを取り、スコープはデバイス認可要求時に束縛される。
 * リフレッシュ(RFC 6749 §6)のscopeは「元の許諾を超えない範囲での絞り込み」なので、
 * 省略すれば元のスコープが維持される(apps/web/src/lib/auth.ts のrefreshも送っていない)。
 */
export const DEVICE_SCOPE = 'offline_access';

/**
 * トークンエンドポイントへの単発リクエストを諦めるまでの時間。中断はconfig.tsのAbortControllerが
 * 行うが、中断をTimeoutErrorとして表すのはここなので、値の定義もここに置いて一致させる。
 */
export const TOKEN_REQUEST_TIMEOUT_MS = 30_000;

/** リフレッシュトークン自体がもう使えないことを示すOAuth 2.0のエラーコード(RFC 6749 §5.2)。 */
const REVOKED_TOKEN_ERRORS = new Set(['invalid_grant', 'invalid_client', 'unauthorized_client']);

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
    body: new URLSearchParams({ client_id: DEVICE_CLIENT_ID, scope: DEVICE_SCOPE }).toString(),
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

/** 応答本文(文字列でもパース済みオブジェクトでも可)からOAuthのerrorコードを安全に取り出す。 */
function oauthErrorCodeOf(body: unknown): string | undefined {
  let parsed: unknown = body;
  if (typeof body === 'string') {
    // Keycloakが落ちている間はリバースプロキシがHTMLのエラーページを返すことがある。
    try {
      parsed = JSON.parse(body);
    } catch {
      return undefined;
    }
  }
  if (typeof parsed !== 'object' || parsed === null) {
    return undefined;
  }
  const code = (parsed as Record<string, unknown>).error;
  return typeof code === 'string' ? code : undefined;
}

/**
 * リフレッシュ失敗が「確定的な失効」——リフレッシュトークン自体が失効・取り消しされていて、
 * 何度試しても成功しない状態——かどうかを判定する純関数(issue #1098)。
 *
 * HTTPステータスだけでは判断しない。Keycloakは失効したリフレッシュトークンに対して
 * 400 + `{"error":"invalid_grant"}` を返すが、リバースプロキシ経由の一時的な400や
 * 本文を伴わない4xxもありうるため、応答本文のerror値まで見て初めて破棄を決める。
 * 5xx・429は一過性(isRetryable()が真)なので、本文の内容によらず失効とはみなさない。
 */
export function isRevokedRefreshResponse(status: number, body: unknown): boolean {
  if (status < 400 || status >= 500) {
    return false;
  }
  const code = oauthErrorCodeOf(body);
  return code !== undefined && REVOKED_TOKEN_ERRORS.has(code);
}

/** refreshAccessTokenが投げた例外が、確定的な失効を示すかどうかを判定する(issue #1098)。 */
export function isRefreshTokenRevoked(error: unknown): boolean {
  return error instanceof ApiError && isRevokedRefreshResponse(error.status, error.responseBody);
}

/**
 * リフレッシュトークンでアクセストークンを更新する。
 *
 * 失敗は拡張の既存の例外体系(errorHandler.ts)で表現し、一過性(再試行で回復しうる)か
 * 確定的かを呼び出し側が区別できるようにする(issue #1098)。分類の規則そのものは
 * isRetryable()に委ね、ここでは例外型の組み立てだけを行う——apiClient.tsのrequest()と同じ形。
 */
export async function refreshAccessToken(
  serverUrl: string,
  refreshToken: string,
  allowInsecureTls: boolean,
  signal: AbortSignal
): Promise<TokenResult> {
  const url = `${realmBaseUrl(serverUrl)}/protocol/openid-connect/token`;
  let res: HttpResponse;
  try {
    res = await httpRequest(url, {
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
  } catch (error) {
    if (signal.aborted) {
      throw new TimeoutError('トークンの更新が時間内に完了しませんでした', url, TOKEN_REQUEST_TIMEOUT_MS);
    }
    throw new NetworkError('トークンの更新でサーバーへ到達できませんでした', url, error);
  }
  if (!res.ok) {
    // 本文はApiErrorへそのまま持たせる。確定的な失効かどうかの判定材料になる
    // (Keycloakの400 invalid_grant)ため、読めなかった場合もstatusTextで補う。
    const body = await res.text().catch(() => '');
    throw new ApiError(
      `トークンの更新に失敗しました (HTTP ${res.status})`,
      res.status,
      body || res.statusText,
      url
    );
  }
  return parseTokenResult(await res.json());
}

/**
 * 保存済みリフレッシュトークンでKeycloak側のoffline sessionを終了させる(issue #1099)。
 *
 * revocation_endpoint(RFC 7009)を使う。end_session_endpointも実機確認済みで通るが、
 * revokeはRFC 7009準拠で未知/無効なトークンに対しても200を返す(=冪等)ため、
 * 「ログアウトを何度実行しても失敗にならない」という要件に自然に合う
 * (Implementation Notes参照)。publicクライアントであるためclient_secretは送らない。
 */
export async function revokeRefreshToken(
  serverUrl: string,
  refreshToken: string,
  allowInsecureTls: boolean,
  signal: AbortSignal
): Promise<void> {
  const url = `${realmBaseUrl(serverUrl)}/protocol/openid-connect/revoke`;
  let res: HttpResponse;
  try {
    res = await httpRequest(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        client_id: DEVICE_CLIENT_ID,
        token: refreshToken,
        token_type_hint: 'refresh_token',
      }).toString(),
      signal,
      allowInsecureTls,
    });
  } catch (error) {
    if (signal.aborted) {
      throw new TimeoutError('Keycloak側のoffline session終了が時間内に完了しませんでした', url, TOKEN_REQUEST_TIMEOUT_MS);
    }
    throw new NetworkError('Keycloak側のoffline session終了でサーバーへ到達できませんでした', url, error);
  }
  if (!res.ok) {
    const body = await res.text().catch(() => '');
    throw new ApiError(
      `Keycloak側のoffline sessionの終了に失敗しました (HTTP ${res.status})`,
      res.status,
      body || res.statusText,
      url
    );
  }
}
