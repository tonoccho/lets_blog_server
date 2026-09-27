/**
 * gatewayが401を返したときに例外へ載せる文言(issue #1053)。
 *
 * apiClient.ts(server-only)からも、apiClientをモックする単体テストからも同じ値を参照できるよう、
 * 依存を持たない別モジュールに置く(issue #1235)。
 */
export const SESSION_EXPIRED_MESSAGE = 'セッションの有効期限が切れました。お手数ですが再度ログインしてください。';

export function isSessionExpiredError(err: unknown): boolean {
  return err instanceof Error && err.message === SESSION_EXPIRED_MESSAGE;
}

/** apiClientが投げる `APIエラー (404): ...` 形式の例外が、指定ステータスのものか。 */
export function isApiStatusError(err: unknown, status: number): boolean {
  return err instanceof Error && err.message.startsWith(`APIエラー (${status})`);
}
