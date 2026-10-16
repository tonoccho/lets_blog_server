/**
 * 管理系の画面(`src/app/{admin,users,operation-logs,connect}`)のテストが共有する足場(#1833)。
 *
 * `serverRuntime.ts`(#1832)は `"<METHOD> <パスと検索文字列>"` の完全一致で gateway の応答を返す。
 * 一覧 API のように検索文字列が呼び出しごとに変わる通信を、パスだけで受けて、検索文字列を
 * テストの側で読めるようにしたものがここにある。差し替えるのは `global.fetch`(外部との通信)だけで、
 * 自前のモジュールは差し替えない。
 */
import { jsonResponse, type FetchHandler, type FetchRoutes } from './serverRuntime'

export const ADMIN_SESSION = { user: { id: 'admin-1', role: 'admin', email: 'admin@example.com' } }
export const USER_SESSION = { user: { id: 'u-1', role: 'user', email: 'u@example.com' } }

function matchRoute(routes: FetchRoutes, key: string): { handler: FetchHandler; params: string[] } | undefined {
  if (key in routes) return { handler: routes[key], params: [] }
  for (const [pattern, handler] of Object.entries(routes)) {
    if (!pattern.includes(':')) continue
    const regex = new RegExp(`^${pattern.replace(/[.?+*()[\]{}|\\^$]/g, '\\$&').replace(/:[A-Za-z]+/g, '([^/?]+)')}$`)
    const match = regex.exec(key)
    if (match) return { handler, params: match.slice(1) }
  }
  return undefined
}

/**
 * `global.fetch` を差し替える。キーは `"<METHOD> <パス>"` で、検索文字列は照合に使わない
 * (ハンドラの第 1 引数の URL から読む)。`:id` のような `:名前` は 1 区間のパラメータ。
 * 操作ログの記録(`POST /api/operation-logs`)は既定で 201 を返す。一致しない呼び出しは例外にする。
 */
export function installFetchByPath(routes: FetchRoutes): jest.Mock {
  const all: FetchRoutes = { 'POST /api/operation-logs': () => jsonResponse({}, 201), ...routes }
  const fetchMock = jest.fn(async (input: unknown, init?: RequestInit) => {
    const url = String(input)
    const path = url.replace(/^https?:\/\/[^/]+/, '').split('?')[0]
    const key = `${(init?.method ?? 'GET').toUpperCase()} ${path}`
    const found = matchRoute(all, key)
    if (!found) throw new Error(`unexpected fetch: ${key}`)
    return found.handler(url, init ?? {}, found.params)
  })
  global.fetch = fetchMock as unknown as typeof fetch
  return fetchMock
}

/** 呼び出された URL の検索文字列を `{ キー: 値 }` にする。 */
export function queryOf(url: string): Record<string, string> {
  return Object.fromEntries(new URL(url).searchParams.entries())
}

/** 業務 API(操作ログの記録を除く)に飛んだ `"<METHOD> <パスと検索文字列>"` を呼び出し順に返す。 */
export function calledRequests(fetchMock: jest.Mock): string[] {
  return fetchMock.mock.calls
    .map(([url, init]) => `${String((init as RequestInit | undefined)?.method ?? 'GET').toUpperCase()} ${String(url).replace(/^https?:\/\/[^/]+/, '')}`)
    .filter((request) => request !== 'POST /api/operation-logs')
}

/**
 * `<input type="file">` に、利用者がファイルを選んだ状態を作る。
 *
 * `fireEvent.change(input, { target: { files } })` は `files` プロパティを上書きするだけで、
 * jsdom が `<form>` から FormData を組み立てる(`new FormData(form)`)ときに読む内部の選択済みファイル一覧は
 * 空のままになる。そのため Server Action に届く FormData にファイルが載らない。
 * jsdom の内部実装の一覧へ直接足すことで、`required` の検証も FormData も実際のブラウザと同じ状態になる。
 */
export function attachFile(input: HTMLInputElement, file: File): void {
  // jsdom の wrapper は、内部実装への参照を Symbol("impl") のプロパティに持つ。
  const implOf = (wrapper: object): { files?: unknown[] } & object => {
    const symbol = Object.getOwnPropertySymbols(wrapper).find((s) => s.description === 'impl')
    if (!symbol) throw new Error('jsdom の内部実装を取得できませんでした')
    return (wrapper as Record<symbol, never>)[symbol]
  }
  const list = implOf(input).files as unknown[]
  list.length = 0
  list.push(implOf(file))
}
