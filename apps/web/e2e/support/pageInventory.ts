import type { APIRequestContext } from '@playwright/test';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  createFixtureProject,
  deleteFixtureProject,
  expect,
  fetchAccessToken,
} from '../support';

/**
 * Web管理画面の全ページ一覧(issue #944 / AT-18)。
 *
 * `docs/ACCEPTANCE_CRITERIA.md` §6/§7 が最新の実測(`find apps/web/src/app -name page.tsx`)
 * として記録している24ページを対象にする。issue #944 の本文は「23ページ」としているが、
 * それは #927 作成時点(2026-09-01)の古い値で、同じ表が既に24への訂正を明記している。
 * この一覧はその訂正後の値、つまり実装済みの `page.tsx` すべてに合わせてある。
 *
 * `role` はこのページへ到達するのに要るログイン権限。`none` は未ログインで到達できる
 * (`/login` はKeycloakへ即リダイレクト、`/setup` は本環境では既にユーザーが存在するため
 * `/login` へリダイレクトする — issue #929 のセットアップ拒否と同じ経路)。
 */
export type PageRole = 'none' | 'user' | 'admin';

export interface PageFixtures {
  projectId: number;
  siteId: number;
  siteKey: string;
  userId: number;
}

export interface PageSpec {
  /** 表示用の識別子。失敗時のレポートに使う。 */
  id: string;
  role: PageRole;
  path: (fixtures: PageFixtures) => string;
}

/**
 * role は各 `page.tsx` が実際に呼んでいるガード(`requireAdminSession` / `requireSession` /
 * ガード無し)に合わせてある。`/projects` 配下と `/sites/[id]/edit` `/custom-tag-templates`
 * は `requireAdminSession()` で admin 限定、`/` `/posts` `/sites` `/image-gallery` は
 * ガード無し(ログインさえしていればよい)、`/users/[id]/edit` `/operation-logs` は
 * `requireSession()` で一般ユーザーでも到達できる。
 */
export const PAGE_INVENTORY: PageSpec[] = [
  { id: '/', role: 'user', path: () => '/' },
  { id: '/login', role: 'none', path: () => '/login' },
  { id: '/setup', role: 'none', path: () => '/setup' },
  { id: '/users', role: 'admin', path: () => '/users' },
  { id: '/users/[id]/edit', role: 'user', path: (f) => `/users/${f.userId}/edit` },
  { id: '/admin/roles', role: 'admin', path: () => '/admin/roles' },
  { id: '/admin/ssh-keys', role: 'admin', path: () => '/admin/ssh-keys' },
  { id: '/admin/system-settings', role: 'admin', path: () => '/admin/system-settings' },
  { id: '/admin/backup', role: 'admin', path: () => '/admin/backup' },
  { id: '/admin/tag-design', role: 'admin', path: () => '/admin/tag-design' },
  { id: '/projects', role: 'admin', path: () => '/projects' },
  { id: '/projects/[id]', role: 'admin', path: (f) => `/projects/${f.projectId}` },
  { id: '/projects/[id]/dashboard', role: 'admin', path: (f) => `/projects/${f.projectId}/dashboard` },
  { id: '/projects/[id]/plan', role: 'admin', path: (f) => `/projects/${f.projectId}/plan` },
  { id: '/projects/[id]/posts', role: 'admin', path: (f) => `/projects/${f.projectId}/posts` },
  { id: '/projects/[id]/tags', role: 'admin', path: (f) => `/projects/${f.projectId}/tags` },
  {
    id: '/projects/[id]/settings/google-analytics',
    role: 'admin',
    path: (f) => `/projects/${f.projectId}/settings/google-analytics`,
  },
  {
    id: '/projects/[id]/settings/adsense',
    role: 'admin',
    path: (f) => `/projects/${f.projectId}/settings/adsense`,
  },
  { id: '/posts', role: 'user', path: () => '/posts' },
  { id: '/sites', role: 'user', path: () => '/sites' },
  { id: '/sites/[id]/edit', role: 'admin', path: (f) => `/sites/${f.siteId}/edit` },
  { id: '/custom-tag-templates', role: 'admin', path: () => '/custom-tag-templates' },
  { id: '/image-gallery', role: 'user', path: () => '/image-gallery' },
  { id: '/operation-logs', role: 'user', path: () => '/operation-logs' },
];

/**
 * `role: 'none'` の2ページ(`/login` `/setup`)を除いた、このアプリ自身がレンダリングする
 * ページだけの一覧。`/login` は即座にKeycloakのホスト型ログイン画面へリダイレクトし、
 * `/setup` も本環境(ユーザー登録済み)では同じ画面へリダイレクトする——どちらも
 * 最終的に表示されるのはKeycloakのテーマであり、このリポジトリのコードではない。
 *
 * axeによるcritical・serious違反の検査(シナリオ1)は issue #944 の受入基準により
 * 全24ページを対象にする必要があるため `PAGE_INVENTORY` をそのまま使うが、
 * 見出し階層(h1が1つ・レベルが飛ばない)はこのアプリのマークアップ規約であり、
 * Keycloakのテーマに課す性質のものではない。Keycloakログイン画面自身のアクセシビリティは
 * 別のシナリオ(「Keycloakのログイン画面も同じ基準を満たす」)がaxeで検証する。
 */
export const PAGE_INVENTORY_APP_ONLY: PageSpec[] = PAGE_INVENTORY.filter((spec) => spec.role !== 'none');

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

/**
 * `/projects/[id]/settings/*` などダイナミックルートの実在パラメータを一括で用意する
 * (issue #944の実装メモ: 「共通フィクスチャを1回だけ構築して使い回す」)。
 *
 * 呼び出し元(全ページ横断シナリオ)は1シナリオにつき1回だけこれを呼び、
 * 24ページを回る間ずっと同じフィクスチャを使い回す。ページごとに作り直さない。
 */
export async function buildPageFixtures(request: APIRequestContext): Promise<PageFixtures> {
  const token = await adminToken(request);
  const project = await createFixtureProject(request, token, 'at18-pages');

  const siteKey = `at18-pages-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const siteResponse = await request.post('/api/sites', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name: `AT-18 page inventory fixture ${siteKey}`,
      siteKey,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'AGENT',
        baseUrl: 'http://wordpress',
        username: 'at18-fixture',
      },
    },
  });
  expect(
    siteResponse.ok(),
    `AT-18 用サイトフィクスチャの作成に失敗しました (status=${siteResponse.status()}): ${await siteResponse.text()}`
  ).toBe(true);
  const siteId = ((await siteResponse.json()) as { id: number }).id;

  const meResponse = await request.get('/api/identity/me', {
    headers: { Authorization: `Bearer ${await fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD)}` },
  });
  expect(meResponse.ok(), '/api/identity/me の取得に失敗しました(一般ユーザー)').toBe(true);
  const userId = ((await meResponse.json()) as { id: number }).id;

  return { projectId: project.id, siteId, siteKey, userId };
}

/** {@link buildPageFixtures} が作ったフィクスチャの後始末。 */
export async function cleanupPageFixtures(
  request: APIRequestContext,
  fixtures: PageFixtures
): Promise<void> {
  const token = await adminToken(request);
  await request.delete(`/api/sites/${fixtures.siteId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  await deleteFixtureProject(request, token, fixtures.projectId);
}

/**
 * 1シナリオ内で {@link buildPageFixtures} を1回だけ呼ぶための遅延メモ化(issue #944)。
 * `ctx`(シナリオ限りの入れ物、`steps/fixtures.ts`)にキャッシュを持たせるので、
 * ワーカーをまたいでも他シナリオの値と混ざらない。
 */
export async function getOrBuildPageFixtures(
  ctx: Record<string, unknown>,
  request: APIRequestContext
): Promise<PageFixtures> {
  if (!ctx.at18PageFixtures) {
    ctx.at18PageFixtures = await buildPageFixtures(request);
  }
  return ctx.at18PageFixtures as PageFixtures;
}
