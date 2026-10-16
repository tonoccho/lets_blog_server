/**
 * サーバー側のコード(`@/lib/apiClient`・`@/lib/session`・Server Action・Route Handler)を、
 * 自前のモジュールを差し替えずに動かすためのテスト用の足場(#1832)。
 *
 * 差し替えるのは次の 2 種類だけ。
 * - 外部との通信: `fetch`(gateway への HTTP)。`installFetch` で URL ごとに応答を返す。
 * - Next.js の実行基盤の代替: `next/headers`・`next/server`・`next-auth/jwt`・`next-auth`
 *   (リクエスト文脈が jest 上に無い)。ここの `*Module` を各テストの `jest.mock` から使う。
 *
 * 使い方(`jest.mock` は巻き上げられるので、ファクトリの中で `require` する):
 *
 *   jest.mock('server-only', () => ({}))
 *   jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule)
 *   jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule)
 *   jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule)
 *   jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule)
 */

export const afterMock = jest.fn((fn: () => unknown) => fn())
export const cookiesMock = jest.fn()
export const headersMock = jest.fn()
export const getTokenMock = jest.fn()
export const getServerSessionMock = jest.fn()

export const nextServerModule = { after: (fn: () => unknown) => afterMock(fn) }
export const nextHeadersModule = { cookies: () => cookiesMock(), headers: () => headersMock() }
export const nextAuthJwtModule = { getToken: (...args: unknown[]) => getTokenMock(...args) }
export const nextAuthModule = { getServerSession: (...args: unknown[]) => getServerSessionMock(...args) }

export const DEFAULT_SESSION = { user: { id: 'u-1', role: 'user', email: 'u@example.com' } }

/** 各テストの `beforeEach` で呼ぶ。ログイン済み(`session`)の既定状態にそろえる。`null` なら未ログイン。 */
export function resetServerRuntime(session: unknown = DEFAULT_SESSION): void {
  afterMock.mockReset()
  afterMock.mockImplementation((fn: () => unknown) => fn())
  cookiesMock.mockReset()
  cookiesMock.mockResolvedValue({})
  headersMock.mockReset()
  headersMock.mockResolvedValue({ get: () => 'op-123' })
  getTokenMock.mockReset()
  getTokenMock.mockResolvedValue({ accessToken: 'access-token' })
  getServerSessionMock.mockReset()
  getServerSessionMock.mockResolvedValue(session)
}

export function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 200 ? 'OK' : 'Error',
    text: async () => JSON.stringify(body),
    json: async () => body,
    arrayBuffer: async () => new ArrayBuffer(4),
    headers: { get: (name: string) => headers[name.toLowerCase()] ?? 'application/json' },
  } as unknown as Response
}

export function textResponse(text: string, status: number, headers: Record<string, string> = {}): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    statusText: 'Error',
    text: async () => text,
    headers: { get: (name: string) => headers[name.toLowerCase()] ?? null },
  } as unknown as Response
}

export type FetchHandler = (url: string, init: RequestInit, params: string[]) => Response | Promise<Response>
export type FetchRoutes = Record<string, FetchHandler>

function findRoute(routes: FetchRoutes, key: string): { handler: FetchHandler; params: string[] } | undefined {
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
 * `global.fetch` を差し替える。`routes` のキーは `"<METHOD> <パスと検索文字列>"`(gateway のホスト部は除く)。
 * `:id` のような `:名前` は 1 区間のパラメータで、ハンドラの第 3 引数に文字列で渡る。
 * 操作ログの記録(`POST /api/operation-logs`)は既定で 201 を返す。
 * 一致しない呼び出しは例外にして、想定外の通信を黙って通さない。
 */
export function installFetch(routes: FetchRoutes): jest.Mock {
  const all: FetchRoutes = { 'POST /api/operation-logs': () => jsonResponse({}, 201), ...routes }
  const fetchMock = jest.fn(async (input: unknown, init?: RequestInit) => {
    const url = String(input)
    const path = url.replace(/^https?:\/\/[^/]+/, '')
    const key = `${(init?.method ?? 'GET').toUpperCase()} ${path}`
    const found = findRoute(all, key)
    if (!found) throw new Error(`unexpected fetch: ${key}`)
    return found.handler(url, init ?? {}, found.params)
  })
  global.fetch = fetchMock as unknown as typeof fetch
  return fetchMock
}

/**
 * バックエンド(gateway の向こう)の振る舞いを `jest.fn()` で書くためのハンドラ。
 * 解決値を JSON の 200 応答に、拒否を 500 応答にする。`jest.fn()` はバックエンドの代役で、
 * 自前の TypeScript モジュールの差し替えではない。呼び出し引数には URL のパラメータが渡る。
 */
export function respondWith(backend: (...params: string[]) => unknown): FetchHandler {
  return async (_url, _init, params) => {
    try {
      return jsonResponse(await backend(...params))
    } catch (err) {
      return textResponse(err instanceof Error ? err.message : String(err), 500)
    }
  }
}
