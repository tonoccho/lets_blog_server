/**
 * プロジェクト詳細ページ(`(detail)/page.tsx`)の gateway の向こう側の代役(#1835)。
 *
 * 差し替えるのは `global.fetch`(外部との通信)だけ。ページが集める 10 種類の取得を、既定では成功する応答にして、
 * テストごとに `overrides`("<METHOD> <パス>" をキーにした応答)で一部だけ失敗・変更させる。
 */
import { installFetchByPath } from "@/lib/__tests__/testUtils/webAdminRuntime";
import { jsonResponse, type FetchRoutes } from "@/lib/__tests__/testUtils/serverRuntime";

export const SITE = { id: 1, siteKey: "site-1", name: "本番サイト", baseUrl: "https://s.example.com", adminPath: null };

export const PROJECT = {
  id: 7,
  name: "サンプル案件",
  slug: "sample",
  localSite: null,
  testSite: null,
  productionSite: null,
  masterEnvironment: "test",
  githubRepository: null,
};

export const EMPTY_COMPARISON = { items: [], page: 0, size: 20, totalCount: 0, masterEnvironment: "test" };

export function installDetailBackend(overrides: FetchRoutes = {}, project: Record<string, unknown> = PROJECT): jest.Mock {
  return installFetchByPath({
    "GET /api/projects/:id": () => jsonResponse(project),
    "GET /api/sites": () => jsonResponse([]),
    "GET /api/projects/:id/users": () => jsonResponse([]),
    "GET /api/users": () => jsonResponse([]),
    "GET /api/projects/:id/bulk-management/categories/comparison": () => jsonResponse(EMPTY_COMPARISON),
    "GET /api/projects/:id/api-keys/github-token": () => jsonResponse({ configured: false }),
    "GET /api/projects/:id/api-keys/brave-search-api-key": () => jsonResponse({ configured: false }),
    "GET /api/projects/:id/image-settings": () => jsonResponse({}),
    "GET /api/system-settings/site-admin-path": () => jsonResponse({ path: "wp-admin" }),
    "GET /api/identity/me": () => jsonResponse({ id: 1, timezone: null }),
    ...overrides,
  });
}
