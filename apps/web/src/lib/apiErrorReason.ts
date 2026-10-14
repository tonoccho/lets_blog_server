/**
 * APIが失敗の応答に載せる、機械が読める原因(`details.reason`)を取り出す(issue #1771)。
 *
 * apiClient.ts(server-only)は失敗を `Error("APIエラー (<status>): <本文>")` で投げる。本文を JSON として読み、
 * `details.reason` が既知の値のときだけ返す。日本語の文言は照合しない。依存を持たない別モジュールに置き、
 * apiClient をモックする単体テストからも同じ値を参照できるようにする(sessionExpired.ts と同じ理由)。
 */
export const GITHUB_TOKEN_NOT_CONFIGURED = 'GITHUB_TOKEN_NOT_CONFIGURED';
export const GITHUB_AUTH_FAILED = 'GITHUB_AUTH_FAILED';

export type ApiErrorReason = typeof GITHUB_TOKEN_NOT_CONFIGURED | typeof GITHUB_AUTH_FAILED;

const KNOWN_REASONS: readonly string[] = [GITHUB_TOKEN_NOT_CONFIGURED, GITHUB_AUTH_FAILED];
const MESSAGE_PREFIX = /^APIエラー \(\d+\): /;

export function getApiErrorReason(err: unknown): ApiErrorReason | null {
  if (!(err instanceof Error) || !MESSAGE_PREFIX.test(err.message)) {
    return null;
  }
  let body: unknown;
  try {
    body = JSON.parse(err.message.replace(MESSAGE_PREFIX, ''));
  } catch {
    return null;
  }
  const details = (body as { details?: unknown } | null)?.details;
  const reason = (details as { reason?: unknown } | null | undefined)?.reason;
  return typeof reason === 'string' && KNOWN_REASONS.includes(reason) ? (reason as ApiErrorReason) : null;
}
