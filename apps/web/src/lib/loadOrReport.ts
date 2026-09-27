import { redirect } from "next/navigation";
import { isApiStatusError, isSessionExpiredError } from "./sessionExpired";

/**
 * Server ComponentのAPI取得を、失敗を握り潰さずに扱うための共通ヘルパー(issue #1235)。
 *
 * 以前は各ページが`.catch(() => [])`で失敗を「0件」に変換しており、gateway障害と本当に0件の
 * 区別が利用者に付かなかった。ここでは
 * - セッション切れ(SESSION_EXPIRED_MESSAGE)は`/login`へリダイレクトする
 * - それ以外は`[scope] <label>の取得に失敗しました:`とともにサーバーログへ記録し、
 *   `failed: true`とフォールバックを返す(呼び出し側が`FetchErrorNotice`で失敗を示す)
 *
 * `notFoundIsEmpty`を指定すると404は「存在しない」という正当な結果として、ログも失敗扱いもせず
 * フォールバックを返す(単一リソース取得で`notFound()`へ流す用途)。
 */
export type LoadResult<T> = { data: T; failed: boolean; label: string };

export async function loadOrReport<T>(
  scope: string,
  label: string,
  promise: Promise<T>,
  fallback: T,
  options: { notFoundIsEmpty?: boolean } = {},
): Promise<LoadResult<T>> {
  try {
    return { data: await promise, failed: false, label };
  } catch (err) {
    if (isSessionExpiredError(err)) {
      redirect("/login");
    }
    if (options.notFoundIsEmpty && isApiStatusError(err, 404)) {
      return { data: fallback, failed: false, label };
    }
    console.error(`[${scope}] ${label}の取得に失敗しました:`, err);
    return { data: fallback, failed: true, label };
  }
}

export function failedLabels(...results: LoadResult<unknown>[]): string[] {
  return results.filter((r) => r.failed).map((r) => r.label);
}
