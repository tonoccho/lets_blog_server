/**
 * `src/app/projects/[id]/` 直下の Server Action・コンポーネントのテストが共有する足場(#1836)。
 *
 * 差し替えるのは `global.fetch`(外部との通信)だけ。自前の `@/lib/apiClient`・`@/lib/session`・`../actions` は実物を通す。
 * 応答は `installFetchByPath` のルート(`"<METHOD> <パス>"`)で返し、送られた本文は `sent()` / `bodies()` で読む。
 */
import { calledRequests, installFetchByPath } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { jsonResponse, textResponse, type FetchRoutes } from "@/lib/__tests__/testUtils/serverRuntime";

export { calledRequests, jsonResponse, textResponse };

/** 本文の無い成功(204)。`apiFetch` は `undefined` を返す。 */
export const noContent = () => jsonResponse(null, 204);

/** gateway が HTTP エラーを返した状態(`apiClient` が `APIエラー (<status>): <text>` の例外にする)。 */
export const failWith = (status: number, text: string) => () => textResponse(text, status);

export function installBackend(routes: FetchRoutes = {}): jest.Mock {
  return installFetchByPath(routes);
}

/** fetch 自体が `Error` でない値で失敗する状態(通信そのものの失敗)。 */
export function failFetchWith(value: unknown): jest.Mock {
  const fetchMock = jest.fn().mockRejectedValue(value);
  global.fetch = fetchMock as unknown as typeof fetch;
  return fetchMock;
}

/** 業務 API に送られた `"<METHOD> <パス>"` ごとの本文(JSON は parse、FormData はそのまま)を呼び出し順に返す。 */
export function bodies(fetchMock: jest.Mock, key: string): unknown[] {
  return fetchMock.mock.calls
    .filter(([url, init]) => {
      const path = String(url).replace(/^https?:\/\/[^/]+/, "").split("?")[0];
      return `${String((init as RequestInit | undefined)?.method ?? "GET").toUpperCase()} ${path}` === key;
    })
    .map(([, init]) => {
      const body = (init as RequestInit).body;
      return typeof body === "string" ? JSON.parse(body) : body;
    });
}

/** 業務 API に送られたリクエストの数(操作ログの記録を除く)。 */
export function requestCount(fetchMock: jest.Mock): number {
  return calledRequests(fetchMock).length;
}

export function form(values: Record<string, string>): FormData {
  const data = new FormData();
  for (const [key, value] of Object.entries(values)) data.set(key, value);
  return data;
}
