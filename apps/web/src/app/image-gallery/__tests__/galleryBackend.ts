/**
 * 画像ギャラリーのテストが共有する足場(#1834)。
 *
 * 自前の Server Action(`../actions`)・`@/lib/apiClient`・`@/lib/session` は差し替えず、実物を動かす。
 * 差し替えるのは外部との通信(`global.fetch`。gateway への HTTP)だけで、ここの `backend` が
 * 「gateway の向こうの media-service」の代役になる。`backend.*` は `jest.fn()` で、
 * 引数には Server Action と同じ形(`fetchGalleryImagesPageAction(offset, tag, folder, source)` など)で
 * HTTP リクエストから読み取った値が渡る。拒否した値は、`APIエラー (<status>): <本文>` の形なら
 * その状態コードの応答に、そうでなければ 500 の応答になる。
 *
 * Next.js の実行基盤の代替(`server-only`・`next/server`・`next/headers`・`next-auth*`・`next/cache`・`next/navigation`)は、
 * `jest.mock` が巻き上げられるため各テストファイルの先頭に書く。書き方は次のとおり。
 *
 *   jest.mock('server-only', () => ({}))
 *   jest.mock('next/server', () => require('@/lib/__tests__/testUtils/serverRuntime').nextServerModule)
 *   jest.mock('next/headers', () => require('@/lib/__tests__/testUtils/serverRuntime').nextHeadersModule)
 *   jest.mock('next-auth/jwt', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthJwtModule)
 *   jest.mock('next-auth', () => require('@/lib/__tests__/testUtils/serverRuntime').nextAuthModule)
 *   jest.mock('next/cache', () => ({ revalidatePath: jest.fn() }))
 *   jest.mock('next/navigation', () => ({ redirect: (p: string) => { throw new Error(`NEXT_REDIRECT:${p}`) } }))
 */
import { DEFAULT_SESSION, installFetch, jsonResponse, resetServerRuntime, textResponse } from '@/lib/__tests__/testUtils/serverRuntime'
import { installFetchByPath, queryOf } from '@/lib/__tests__/testUtils/webAdminRuntime'

export { DEFAULT_SESSION, installFetch, jsonResponse, resetServerRuntime, textResponse, installFetchByPath, queryOf }

/** media-service の代役。引数は Server Action と同じ形。 */
export const backend = {
  /** (offset, tag, folder, source) */
  listImages: jest.fn(),
  /** 一覧の問い合わせ文字列(`limit` を含む)。 */
  listQuery: jest.fn(),
  getImage: jest.fn(),
  deleteImage: jest.fn(),
  bulkDelete: jest.fn(),
  updateTags: jest.fn(),
  listFolders: jest.fn(),
  createFolder: jest.fn(),
  setFolder: jest.fn(),
  renameFolder: jest.fn(),
  folderDeleteImpact: jest.fn(),
  deleteFolder: jest.fn(),
  editImage: jest.fn(),
}

function failure(err: unknown): Response {
  const message = err instanceof Error ? err.message : String(err)
  const match = /^APIエラー \((\d+)\): ([\s\S]*)$/.exec(message)
  return match ? textResponse(match[2], Number(match[1])) : textResponse(message, 500)
}

/** `backend` の関数を呼び、解決値を 200(値なしは 204)の応答に、拒否を失敗の応答にする。 */
async function answer(call: () => unknown): Promise<Response> {
  try {
    const value = await call()
    return value === undefined ? textResponse('', 204) : jsonResponse(value)
  } catch (err) {
    return failure(err)
  }
}

async function bodyOf(init: RequestInit): Promise<Record<string, unknown>> {
  return JSON.parse(String(init.body ?? '{}')) as Record<string, unknown>
}

/**
 * 各テストの `beforeEach` の先頭で呼ぶ。`backend` を初期化し、ログイン済みにして、`fetch` を差し替える。
 * `session` に `null` を渡すと未ログイン。
 */
export function installGallery(session: unknown = DEFAULT_SESSION): jest.Mock {
  Object.values(backend).forEach((fn) => fn.mockReset())
  resetServerRuntime(session)
  return installFetchByPath({
    'GET /api/generated-images': (url) => {
      const q = queryOf(url)
      backend.listQuery(q)
      const folder = q.folderId !== undefined ? Number(q.folderId) : q.unfiled === 'true' ? 'unfiled' : null
      return answer(() => backend.listImages(Number(q.offset ?? 0), q.tag ?? null, folder, q.source ?? null))
    },
    'GET /api/generated-images/folders': () => answer(() => backend.listFolders()),
    'POST /api/generated-images/folders': async (_u, init) => {
      const body = await bodyOf(init)
      return answer(() => backend.createFolder(body.name, body.parentId))
    },
    'PUT /api/generated-images/folders/:id/name': async (_u, init, [id]) => {
      const body = await bodyOf(init)
      return answer(() => backend.renameFolder(Number(id), body.name))
    },
    'GET /api/generated-images/folders/:id/delete-impact': (_u, _i, [id]) => answer(() => backend.folderDeleteImpact(Number(id))),
    'DELETE /api/generated-images/folders/:id': (_u, _i, [id]) => answer(() => backend.deleteFolder(Number(id))),
    'POST /api/generated-images/bulk-delete': async (_u, init) => {
      const body = await bodyOf(init)
      return answer(() => backend.bulkDelete(body.imageIds))
    },
    'GET /api/generated-images/:id': (_u, _i, [id]) => answer(() => backend.getImage(Number(id))),
    'DELETE /api/generated-images/:id': (_u, _i, [id]) => answer(() => backend.deleteImage(Number(id))),
    'PUT /api/generated-images/:id/tags': async (_u, init, [id]) => {
      const body = await bodyOf(init)
      return answer(() => backend.updateTags(Number(id), body.tags))
    },
    'PUT /api/generated-images/:id/folder': async (_u, init, [id]) => {
      const body = await bodyOf(init)
      return answer(() => backend.setFolder(Number(id), body.folderId))
    },
    'POST /api/generated-images/:id/edit': async (_u, init, [id]) => {
      const body = await bodyOf(init)
      return answer(() => backend.editImage(Number(id), body.operations, body.crop, body.adjustment))
    },
  })
}
