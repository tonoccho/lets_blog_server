import { execFileSync } from 'node:child_process';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * 一括削除(delete-all)の対象範囲(issue #1180 / AT-7-4、AC-BULK-009)のステップ定義。
 *
 * 兄弟 issue のステップ定義には相乗りしない。`delete-all` をHTTPで直接呼び、
 * 結果は wp-cli(`wordpress` コンテナ)で WordPress の実状態を読み直して判定する。
 * 「1つの slug を、それが存在するプロジェクトの全環境から消す」(環境単位の削除は無い)が仕様。
 */

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

const PROJECT_SLUG = 'e2e-at7-del';
const OTHER_PROJECT_SLUG = 'e2e-at7-del-other';
const MASTER_SITE_KEY = 'at7delmaster';
const TARGET_SITE_KEY = 'at7deltarget';
const OTHER_SITE_KEY = 'at7delother';
const FREE_SITE_KEY = 'at7delfree';

const PROVISION_TIMEOUT_MS = 600_000;
const DELETE_TIMEOUT_MS = 300_000;

interface SiteFixture {
  id: number;
  siteKey: string;
}

interface DeleteLog {
  environment: string;
  status: string;
}

interface DeleteState {
  projectId: number;
  /** test 環境(マスター)のサイト。 */
  testSite: string;
  /** local 環境のサイト。 */
  localSite: string;
  otherProjectSite: string;
  freeSite: string;
  slug: string;
  keepSlug: string;
  /** 削除の前の local 環境のカテゴリのスラッグ一覧。 */
  localBefore: string[];
  logs: DeleteLog[];
  /** 後片付け用: 作ったものを消す関数。 */
  cleanups: (() => void)[];
}

/** `WordPressSiteProvisioningService#normalizeSlug` と同じ正規化。 */
function wpSlug(siteKey: string): string {
  return siteKey.toLowerCase().replace(/[^a-z0-9-]/g, '-');
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD)}` };
}

function wpCli(siteSlug: string, command: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'wordpress', 'sh', '-c', `cd /var/www/html/sites/${siteSlug} && wp --allow-root ${command}`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 180_000 }
  ).trim();
}

function unique(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

function state(ctx: Record<string, unknown>): DeleteState {
  return ctx.bulkDelete as DeleteState;
}

async function ensureProject(request: APIRequestContext, slug: string, name: string): Promise<number> {
  const headers = await adminHeaders(request);
  const list = await request.get('/api/projects', { headers });
  expect(list.ok(), `プロジェクト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`).toBe(true);
  const existing = ((await list.json()) as { id: number; slug: string }[]).find((project) => project.slug === slug);
  if (existing) {
    return existing.id;
  }
  const created = await request.post('/api/projects', { headers, data: { name, slug } });
  expect(created.ok(), `プロジェクトの作成に失敗しました (status=${created.status()}): ${await created.text()}`).toBe(
    true
  );
  return ((await created.json()) as { id: number }).id;
}

async function ensureManagedSite(request: APIRequestContext, siteKey: string): Promise<SiteFixture> {
  const headers = await adminHeaders(request);
  const list = await request.get('/api/sites', { headers });
  expect(list.ok(), `サイト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`).toBe(true);
  const existing = ((await list.json()) as SiteFixture[]).find((site) => site.siteKey === siteKey);
  if (existing) {
    return existing;
  }
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: `AT7 delete ${siteKey}`,
      siteKey,
      title: `AT7 ${siteKey}`,
      adminUser: `${siteKey}admin`.slice(0, 30),
      adminEmail: `${siteKey}@letsblog.local`,
      adminPassword: 'At7Delete#Passw0rd1',
    },
    timeout: PROVISION_TIMEOUT_MS,
  });
  expect(
    created.ok(),
    `マネージドWordPressの構築に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  return (await created.json()) as SiteFixture;
}

async function bindEnvironment(
  request: APIRequestContext,
  projectId: number,
  environment: 'local' | 'test',
  siteId: number
): Promise<void> {
  const response = await request.post(`/api/projects/${projectId}/environments`, {
    headers: await adminHeaders(request),
    data: { environment, siteId },
  });
  expect(
    response.ok(),
    `${environment}環境へのサイト紐付けに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

// ---- WordPress の実状態(wp-cli) ----

function createCategory(siteSlug: string, termSlug: string): void {
  const id = wpCli(siteSlug, `term create category 'Del ${termSlug}' --slug='${termSlug}' --porcelain`);
  registerCleanup(() => wpCli(siteSlug, `term delete category ${id}`));
}

let currentCleanups: (() => void)[] = [];
function registerCleanup(fn: () => void): void {
  currentCleanups.push(fn);
}

function categorySlugs(siteSlug: string): string[] {
  const json = wpCli(siteSlug, `term list category --fields=slug --format=json`);
  return (JSON.parse(json || '[]') as { slug: string }[]).map((row) => row.slug).sort();
}

function createPost(siteSlug: string, slug: string): void {
  const id = wpCli(
    siteSlug,
    `post create --post_type=post --post_title='Post ${slug}' --post_name='${slug}' --post_status=publish --porcelain`
  );
  registerCleanup(() => wpCli(siteSlug, `post delete ${id} --force`));
}

/** 投稿が存在するか(ゴミ箱の中は存在しないものとして扱う)。 */
function postExists(siteSlug: string, slug: string): boolean {
  const listing = wpCli(
    siteSlug,
    'post list --post_type=post --post_status=any --fields=post_name,post_status --format=csv'
  );
  return listing.split('\n').some((line) => line.startsWith(`${slug},`) && !line.endsWith(',trash'));
}

async function deleteAll(request: APIRequestContext, s: DeleteState, resource: string, slug: string): Promise<void> {
  const query = resource === 'posts' ? '?postType=post' : '';
  const response = await request.post(
    `/api/projects/${s.projectId}/bulk-management/${resource}/delete-all${query}`,
    { headers: await adminHeaders(request), data: { slug }, timeout: DELETE_TIMEOUT_MS }
  );
  expect(response.ok(), `一括削除APIが失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  s.logs = (await response.json()) as DeleteLog[];
}

Given(
  '一括削除の範囲を調べるための、2環境のプロジェクトと、別プロジェクトのサイトと、どのプロジェクトにも紐付かないサイトがある',
  async ({ ctx, request }) => {
    const projectId = await ensureProject(request, PROJECT_SLUG, 'E2E AT7 Bulk Delete');
    const otherProjectId = await ensureProject(request, OTHER_PROJECT_SLUG, 'E2E AT7 Bulk Delete Other');
    const master = await ensureManagedSite(request, MASTER_SITE_KEY);
    const target = await ensureManagedSite(request, TARGET_SITE_KEY);
    const other = await ensureManagedSite(request, OTHER_SITE_KEY);
    await ensureManagedSite(request, FREE_SITE_KEY);
    await bindEnvironment(request, projectId, 'test', master.id);
    await bindEnvironment(request, projectId, 'local', target.id);
    await bindEnvironment(request, otherProjectId, 'local', other.id);
    currentCleanups = [];
    ctx.bulkDelete = {
      projectId,
      testSite: wpSlug(MASTER_SITE_KEY),
      localSite: wpSlug(TARGET_SITE_KEY),
      otherProjectSite: wpSlug(OTHER_SITE_KEY),
      freeSite: wpSlug(FREE_SITE_KEY),
      slug: unique('at7del'),
      keepSlug: unique('at7keep'),
      localBefore: [],
      logs: [],
      cleanups: currentCleanups,
    } satisfies DeleteState;
  }
);

Given('一括削除用に、両環境に削除するカテゴリと別のカテゴリがある', async ({ ctx }) => {
  const s = state(ctx);
  for (const site of [s.testSite, s.localSite]) {
    createCategory(site, s.slug);
    createCategory(site, s.keepSlug);
  }
});

Given('一括削除用に、両環境に削除するカテゴリがある', async ({ ctx }) => {
  const s = state(ctx);
  for (const site of [s.testSite, s.localSite]) {
    createCategory(site, s.slug);
  }
});

Given(
  '一括削除用に、対象プロジェクトの両環境と、別プロジェクトのサイトと、紐付かないサイトに、同じスラッグの投稿がある',
  async ({ ctx }) => {
    const s = state(ctx);
    for (const site of [s.testSite, s.localSite, s.otherProjectSite, s.freeSite]) {
      createPost(site, s.slug);
    }
  }
);

Given(
  '一括削除用に、test環境にだけ削除するカテゴリがあり、local環境にはそのカテゴリが無く別のカテゴリがある',
  async ({ ctx }) => {
    const s = state(ctx);
    createCategory(s.testSite, s.slug);
    createCategory(s.localSite, s.keepSlug);
    s.localBefore = categorySlugs(s.localSite);
    expect(s.localBefore, '前提: local環境に削除対象が既にあります').not.toContain(s.slug);
  }
);

When('管理者が削除するカテゴリを一括削除する', async ({ ctx, request }) => {
  const s = state(ctx);
  await deleteAll(request, s, 'categories', s.slug);
});

When('管理者が対象プロジェクトでその投稿を一括削除する', async ({ ctx, request }) => {
  const s = state(ctx);
  await deleteAll(request, s, 'posts', s.slug);
});

Then('削除したカテゴリは両環境のWordPressから無くなっている', async ({ ctx }) => {
  const s = state(ctx);
  expect(categorySlugs(s.testSite), 'test環境に残っています').not.toContain(s.slug);
  expect(categorySlugs(s.localSite), 'local環境に残っています').not.toContain(s.slug);
});

Then('別のカテゴリは両環境のWordPressに残っている', async ({ ctx }) => {
  const s = state(ctx);
  expect(categorySlugs(s.testSite), 'test環境の別のカテゴリが消えています').toContain(s.keepSlug);
  expect(categorySlugs(s.localSite), 'local環境の別のカテゴリが消えています').toContain(s.keepSlug);
});

Then('その投稿は対象プロジェクトの両環境のWordPressから無くなっている', async ({ ctx }) => {
  const s = state(ctx);
  expect(postExists(s.testSite, s.slug), 'test環境に投稿が残っています').toBe(false);
  expect(postExists(s.localSite, s.slug), 'local環境に投稿が残っています').toBe(false);
});

Then('その投稿は別プロジェクトのサイトのWordPressに残っている', async ({ ctx }) => {
  const s = state(ctx);
  expect(postExists(s.otherProjectSite, s.slug), '別プロジェクトのサイトの投稿が消えています').toBe(true);
});

Then('その投稿は紐付かないサイトのWordPressに残っている', async ({ ctx }) => {
  const s = state(ctx);
  expect(postExists(s.freeSite, s.slug), '紐付かないサイトの投稿が消えています').toBe(true);
});

Then('削除したカテゴリはtest環境のWordPressから無くなっている', async ({ ctx }) => {
  const s = state(ctx);
  expect(categorySlugs(s.testSite), 'test環境に残っています').not.toContain(s.slug);
});

Then('local環境のWordPressのカテゴリの一覧は削除の前と変わっていない', async ({ ctx }) => {
  const s = state(ctx);
  expect(categorySlugs(s.localSite)).toEqual(s.localBefore);
});

Then('一括削除の結果にlocal環境は含まれない', async ({ ctx }) => {
  const s = state(ctx);
  expect(s.logs.length, '一括削除の結果が空です').toBeGreaterThan(0);
  expect(s.logs.map((log) => log.environment)).not.toContain('local');
});

Then('一括削除の結果にtest環境とlocal環境の成功が示される', async ({ ctx }) => {
  const s = state(ctx);
  for (const environment of ['test', 'local']) {
    const log = s.logs.find((entry) => entry.environment === environment);
    expect(log, `${environment}環境の結果がありません`).toBeTruthy();
    expect(log?.status).toBe('SUCCESS');
  }
});

/** 作ったカテゴリ・投稿のうち、まだ残っているものを消す。サイトそのものは残す。 */
After({ tags: '@destructive and @bulk' }, async ({ ctx }) => {
  const s = ctx.bulkDelete as DeleteState | undefined;
  for (const cleanup of [...(s?.cleanups ?? [])].reverse()) {
    try {
      cleanup();
    } catch {
      // 一括削除で既に消えたものを消そうとした場合は、後片付けの目的(残さない)を満たしている。
    }
  }
});
