import { execFileSync } from 'node:child_process';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * カテゴリの環境間比較(issue #1177 / AT-7-1、AC-BULK-001)のステップ定義。
 *
 * 兄弟 issue(タグ・同期・一括削除等)と同じファイルに相乗りしないため、新規ファイルとして
 * 独立させている(前例: `mediaGarbageCollection.steps.ts`)。サイトの用意とwp-cliの呼び出しは
 * そのファイルと同じ方針だが、あちらのヘルパーは非公開なので、ここでは必要な分だけを持つ。
 *
 * 比較は読み取りのみ。差分は wp-cli で各サイトへ直接カテゴリを作って用意し、
 * 比較APIの応答を WordPress の実状態(wp-cli で読み直したスラッグと説明文)と突き合わせる。
 */

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

const PROJECT_SLUG = 'e2e-at7-cmp';
/** マスター環境(test)・対象環境(local)に紐づける固定キーのサイト。冪等に再利用する。 */
const MASTER_SITE_KEY = 'at7cmpmaster';
const TARGET_SITE_KEY = 'at7cmptarget';

const PROVISION_TIMEOUT_MS = 600_000;

interface SiteFixture {
  id: number;
  siteKey: string;
}

interface TermEnvironmentValue {
  available: boolean;
  error: boolean;
  slug: string | null;
  description: string | null;
}

interface TermComparisonRow {
  name: string;
  slug: string;
  local: TermEnvironmentValue;
  test: TermEnvironmentValue;
  production: TermEnvironmentValue;
}

interface TermComparisonPage {
  items: TermComparisonRow[];
  totalCount: number;
  masterEnvironment: string;
}

/** WordPress 側に実在するカテゴリ(wp-cli で読み直した実状態)。 */
interface CreatedTerm {
  siteSlug: string;
  termId: string;
}

/** `WordPressSiteProvisioningService#normalizeSlug` と同じ正規化。 */
function wpSlug(siteKey: string): string {
  return siteKey.toLowerCase().replace(/[^a-z0-9-]/g, '-');
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

/** `docker compose exec` の cwd はコンテナの WorkingDir になるので、サイトディレクトリへは自分で移る。 */
function wpCli(slug: string, command: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'wordpress', 'sh', '-c', `cd /var/www/html/sites/${slug} && wp --allow-root ${command}`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 180_000 }
  ).trim();
}

function createCategory(siteSlug: string, name: string, termSlug: string, description: string): CreatedTerm {
  const termId = wpCli(
    siteSlug,
    `term create category '${name}' --slug='${termSlug}' --description='${description}' --porcelain`
  );
  return { siteSlug, termId };
}

/** WordPress 側の実状態: スラッグから説明文を読み直す。存在しなければ null。 */
function readCategoryDescription(siteSlug: string, termSlug: string): string | null {
  const found = wpCli(siteSlug, `term list category --slug='${termSlug}' --field=description`);
  return found === '' ? null : found;
}

function uniqueTermSlug(): string {
  return `at7cmp-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function ensureProject(request: APIRequestContext): Promise<number> {
  const headers = { Authorization: `Bearer ${await adminToken(request)}` };
  const list = await request.get('/api/projects', { headers });
  expect(
    list.ok(),
    `プロジェクト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`
  ).toBe(true);
  const existing = ((await list.json()) as { id: number; slug: string }[]).find(
    (project) => project.slug === PROJECT_SLUG
  );
  if (existing) {
    return existing.id;
  }
  const created = await request.post('/api/projects', {
    headers,
    data: { name: 'E2E AT7 Category Comparison', slug: PROJECT_SLUG },
  });
  expect(
    created.ok(),
    `プロジェクトの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  return ((await created.json()) as { id: number }).id;
}

async function ensureManagedSite(request: APIRequestContext, siteKey: string): Promise<SiteFixture> {
  const headers = { Authorization: `Bearer ${await adminToken(request)}` };
  const list = await request.get('/api/sites', { headers });
  expect(
    list.ok(),
    `サイト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`
  ).toBe(true);
  const existing = ((await list.json()) as SiteFixture[]).find((site) => site.siteKey === siteKey);
  if (existing) {
    return existing;
  }
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: `AT7 comparison ${siteKey}`,
      siteKey,
      title: `AT7 ${siteKey}`,
      adminUser: `${siteKey}admin`.slice(0, 30),
      adminEmail: `${siteKey}@letsblog.local`,
      adminPassword: 'At7Comparison#Passw0rd1',
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
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: { environment, siteId },
  });
  expect(
    response.ok(),
    `${environment}環境へのサイト紐付けに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

/** 該当スラッグの行を返す(比較APIは20件ずつなので、全ページを辿る)。 */
async function findRow(
  request: APIRequestContext,
  projectId: number,
  termSlug: string
): Promise<{ row: TermComparisonRow | undefined; masterEnvironment: string }> {
  const headers = { Authorization: `Bearer ${await adminToken(request)}` };
  let masterEnvironment = '';
  for (let page = 0; ; page++) {
    const response = await request.get(
      `/api/projects/${projectId}/bulk-management/categories/comparison?page=${page}`,
      { headers, timeout: 120_000 }
    );
    expect(
      response.ok(),
      `カテゴリの比較に失敗しました (status=${response.status()}): ${await response.text()}`
    ).toBe(true);
    const body = (await response.json()) as TermComparisonPage;
    masterEnvironment = body.masterEnvironment;
    const row = body.items.find((item) => item.slug === termSlug);
    if (row || (page + 1) * body.items.length >= body.totalCount || body.items.length === 0) {
      return { row, masterEnvironment };
    }
  }
}

function requireRow(ctx: Record<string, unknown>): TermComparisonRow {
  const row = ctx.cmpRow as TermComparisonRow | undefined;
  expect(row, `比較結果に用意したカテゴリ(${ctx.cmpTermSlug})の行がありません`).toBeTruthy();
  return row as TermComparisonRow;
}

Given(
  'testをマスター環境、localを対象環境とする2つのWordPressサイトを持つ比較用プロジェクトがある',
  async ({ ctx, request }) => {
    const projectId = await ensureProject(request);
    const master = await ensureManagedSite(request, MASTER_SITE_KEY);
    const target = await ensureManagedSite(request, TARGET_SITE_KEY);
    await bindEnvironment(request, projectId, 'test', master.id);
    await bindEnvironment(request, projectId, 'local', target.id);
    ctx.cmpProjectId = projectId;
    ctx.cmpMasterSlug = wpSlug(MASTER_SITE_KEY);
    ctx.cmpTargetSlug = wpSlug(TARGET_SITE_KEY);
    ctx.cmpCreated = [] as CreatedTerm[];
  }
);

Given('マスター環境のサイトにだけカテゴリがある', async ({ ctx }) => {
  const termSlug = uniqueTermSlug();
  ctx.cmpTermSlug = termSlug;
  (ctx.cmpCreated as CreatedTerm[]).push(
    createCategory(ctx.cmpMasterSlug as string, `Master only ${termSlug}`, termSlug, 'master only')
  );
});

Given('対象環境のサイトにだけカテゴリがある', async ({ ctx }) => {
  const termSlug = uniqueTermSlug();
  ctx.cmpTermSlug = termSlug;
  (ctx.cmpCreated as CreatedTerm[]).push(
    createCategory(ctx.cmpTargetSlug as string, `Target only ${termSlug}`, termSlug, 'target only')
  );
});

Given('マスター環境と対象環境の両方に同じスラッグで説明文の異なるカテゴリがある', async ({ ctx }) => {
  const termSlug = uniqueTermSlug();
  ctx.cmpTermSlug = termSlug;
  const created = ctx.cmpCreated as CreatedTerm[];
  created.push(createCategory(ctx.cmpMasterSlug as string, `Both ${termSlug}`, termSlug, 'master description'));
  created.push(createCategory(ctx.cmpTargetSlug as string, `Both ${termSlug}`, termSlug, 'target description'));
});

When('そのプロジェクトのカテゴリを環境間で比較する', async ({ ctx, request }) => {
  const { row, masterEnvironment } = await findRow(
    request,
    ctx.cmpProjectId as number,
    ctx.cmpTermSlug as string
  );
  ctx.cmpRow = row;
  expect(masterEnvironment, 'この機能はtest環境がマスターである前提です').toBe('test');
});

Then('比較結果でそのカテゴリはマスター環境に存在する', async ({ ctx }) => {
  const row = requireRow(ctx);
  expect(row.test.available && row.test.slug === ctx.cmpTermSlug, 'マスター(test)に存在しません').toBe(true);
  // WordPress の実状態とも一致している
  expect(readCategoryDescription(ctx.cmpMasterSlug as string, ctx.cmpTermSlug as string)).not.toBeNull();
});

Then('比較結果でそのカテゴリは対象環境に不足している', async ({ ctx }) => {
  const row = requireRow(ctx);
  expect(row.local.error, '対象環境の取得がエラーになっています').toBe(false);
  expect(row.local.available, '対象環境が比較対象になっていません').toBe(true);
  expect(row.local.slug, '対象環境に存在しないはずのカテゴリが存在します').toBeNull();
  expect(
    readCategoryDescription(ctx.cmpTargetSlug as string, ctx.cmpTermSlug as string),
    'WordPress の対象環境に実際は存在します'
  ).toBeNull();
});

Then('比較結果でそのカテゴリは両方の環境に存在する', async ({ ctx }) => {
  const row = requireRow(ctx);
  expect(row.test.slug).toBe(ctx.cmpTermSlug);
  expect(row.local.slug).toBe(ctx.cmpTermSlug);
});

Then('比較結果でそのカテゴリの説明文は環境間で異なる', async ({ ctx }) => {
  const row = requireRow(ctx);
  expect(row.test.description).toBe('master description');
  expect(row.local.description).toBe('target description');
  expect(row.local.description).not.toBe(row.test.description);
  // WordPress の実状態と一致している
  expect(readCategoryDescription(ctx.cmpMasterSlug as string, ctx.cmpTermSlug as string)).toBe(
    'master description'
  );
  expect(readCategoryDescription(ctx.cmpTargetSlug as string, ctx.cmpTermSlug as string)).toBe(
    'target description'
  );
});

Then('比較結果でそのカテゴリは対象環境に存在する', async ({ ctx }) => {
  const row = requireRow(ctx);
  expect(row.local.available && row.local.slug === ctx.cmpTermSlug, '対象環境(local)に存在しません').toBe(true);
  expect(readCategoryDescription(ctx.cmpTargetSlug as string, ctx.cmpTermSlug as string)).not.toBeNull();
});

Then('比較結果でそのカテゴリはマスター環境に存在しない', async ({ ctx }) => {
  const row = requireRow(ctx);
  expect(row.test.error, 'マスター環境の取得がエラーになっています').toBe(false);
  expect(row.test.available, 'マスター環境が比較対象になっていません').toBe(true);
  expect(row.test.slug, 'マスター環境に存在しないはずのカテゴリが存在します').toBeNull();
  expect(
    readCategoryDescription(ctx.cmpMasterSlug as string, ctx.cmpTermSlug as string),
    'WordPress のマスター環境に実際は存在します'
  ).toBeNull();
});

/** シナリオが作ったカテゴリを消す。サイトそのものは残す(構築に分単位かかるため)。 */
After({ tags: '@bulk' }, async ({ ctx }) => {
  for (const term of (ctx.cmpCreated as CreatedTerm[] | undefined) ?? []) {
    try {
      wpCli(term.siteSlug, `term delete category ${term.termId}`);
    } catch {
      // 既に無いものを消そうとした場合は、後片付けの目的(残さない)を満たしている。
    }
  }
});
