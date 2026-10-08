import { execFileSync } from 'node:child_process';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * カテゴリの環境間同期(issue #1179 / AT-7-3、AC-BULK-006)と、
 * タグの環境間同期(issue #1679 / AT-7-3b、AC-BULK-007)のステップ定義。
 *
 * 比較の兄弟 issue(#1177 / #1178)の `bulkComparison*.steps.ts` には依存しない(相乗りもしない)。
 * 差分の作り込みも結果の確認も、このファイルの wp-cli(`wordpress` コンテナ)で WordPress の
 * 実状態を直接読み書きして完結させる。同期APIの応答は「失敗が利用者に示されるか」の確認にだけ使い、
 * 同期が効いたかどうかは WordPress の実状態で判定する。
 */

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

const PROJECT_SLUG = 'e2e-at7-sync';
/** マスター環境(test)・対象環境(local)に紐づける固定キーのサイト。冪等に再利用する。 */
const MASTER_SITE_KEY = 'at7syncmaster';
const TARGET_SITE_KEY = 'at7synctarget';

const PROVISION_TIMEOUT_MS = 600_000;
const SYNC_TIMEOUT_MS = 300_000;

interface SiteFixture {
  id: number;
  siteKey: string;
}

interface TermEnvironmentValue {
  available: boolean;
  error: boolean;
  slug: string | null;
  parentSlug: string | null;
  description: string | null;
}

interface TermComparisonRow {
  slug: string;
  local: TermEnvironmentValue;
  test: TermEnvironmentValue;
}

interface TermComparisonPage {
  items: TermComparisonRow[];
  totalCount: number;
}

interface SyncLog {
  environment: string;
  status: string;
  categorySlug: string | null;
  errorMessage: string | null;
}

interface SyncState {
  projectId: number;
  masterSite: string;
  targetSite: string;
  /** このシナリオが用意したカテゴリのスラッグ(後片付けと結果の絞り込みに使う)。 */
  slugs: string[];
  descriptions: Record<string, string>;
  logs: SyncLog[];
  slug?: string;
  editedDescription?: string;
  missingSlug?: string;
  differingSlug?: string;
  parentSlug?: string;
  childSlug?: string;
  aloneSlug?: string;
}

/** `WordPressSiteProvisioningService#normalizeSlug` と同じ正規化。 */
function wpSlug(siteKey: string): string {
  return siteKey.toLowerCase().replace(/[^a-z0-9-]/g, '-');
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

/** `docker compose exec` の cwd はコンテナの WorkingDir になるので、サイトディレクトリへは自分で移る。 */
function wpCli(siteSlug: string, command: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'wordpress', 'sh', '-c', `cd /var/www/html/sites/${siteSlug} && wp --allow-root ${command}`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 180_000 }
  ).trim();
}

/** WordPress の実状態: スラッグでタグを1件読み直す。無ければ null。 */
function readTag(siteSlug: string, termSlug: string): { name: string; description: string } | null {
  const json = wpCli(siteSlug, `term list post_tag --slug='${termSlug}' --fields=name,description --format=json`);
  const rows = JSON.parse(json || '[]') as { name: string; description: string }[];
  return rows.length === 0 ? null : { name: rows[0].name, description: rows[0].description };
}

function createTag(siteSlug: string, name: string, termSlug: string, description: string): string {
  return wpCli(siteSlug, `term create post_tag '${name}' --slug='${termSlug}' --description='${description}' --porcelain`);
}

/** WordPress の実状態: スラッグでカテゴリを1件読み直す。無ければ null。 */
function readCategory(
  siteSlug: string,
  termSlug: string
): { name: string; description: string; parent: number } | null {
  const json = wpCli(siteSlug, `term list category --slug='${termSlug}' --fields=name,description,parent --format=json`);
  const rows = JSON.parse(json || '[]') as { name: string; description: string; parent: number | string }[];
  if (rows.length === 0) {
    return null;
  }
  return { name: rows[0].name, description: rows[0].description, parent: Number(rows[0].parent) };
}

function createCategory(siteSlug: string, name: string, termSlug: string, description: string, parentId?: string): string {
  const parent = parentId ? ` --parent=${parentId}` : '';
  return wpCli(
    siteSlug,
    `term create category '${name}' --slug='${termSlug}' --description='${description}'${parent} --porcelain`
  );
}

function uniqueTermSlug(): string {
  return `at7sync-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

function state(ctx: Record<string, unknown>): SyncState {
  return ctx.syncState as SyncState;
}

function register(s: SyncState, slug: string, description: string): void {
  s.slugs.push(slug);
  s.descriptions[slug] = description;
}

async function ensureProject(request: APIRequestContext): Promise<number> {
  const headers = { Authorization: `Bearer ${await adminToken(request)}` };
  const list = await request.get('/api/projects', { headers });
  expect(list.ok(), `プロジェクト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`).toBe(true);
  const existing = ((await list.json()) as { id: number; slug: string }[]).find(
    (project) => project.slug === PROJECT_SLUG
  );
  if (existing) {
    return existing.id;
  }
  const created = await request.post('/api/projects', {
    headers,
    data: { name: 'E2E AT7 Category Sync', slug: PROJECT_SLUG },
  });
  expect(created.ok(), `プロジェクトの作成に失敗しました (status=${created.status()}): ${await created.text()}`).toBe(
    true
  );
  return ((await created.json()) as { id: number }).id;
}

async function ensureManagedSite(request: APIRequestContext, siteKey: string): Promise<SiteFixture> {
  const headers = { Authorization: `Bearer ${await adminToken(request)}` };
  const list = await request.get('/api/sites', { headers });
  expect(list.ok(), `サイト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`).toBe(true);
  const existing = ((await list.json()) as SiteFixture[]).find((site) => site.siteKey === siteKey);
  if (existing) {
    return existing;
  }
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: `AT7 sync ${siteKey}`,
      siteKey,
      title: `AT7 ${siteKey}`,
      adminUser: `${siteKey}admin`.slice(0, 30),
      adminEmail: `${siteKey}@letsblog.local`,
      adminPassword: 'At7Sync#Passw0rd1',
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

async function postSync(
  request: APIRequestContext,
  s: SyncState,
  endpoint: string,
  data?: Record<string, unknown>,
  resource: 'categories' | 'tags' = 'categories'
): Promise<void> {
  const response = await request.post(`/api/projects/${s.projectId}/bulk-management/${resource}/${endpoint}`, {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data,
    timeout: SYNC_TIMEOUT_MS,
  });
  expect(response.ok(), `同期APIが失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  s.logs = (await response.json()) as SyncLog[];
}

/** 再比較: 全ページを辿り、スラッグごとの行を返す。 */
async function compareAll(
  request: APIRequestContext,
  s: SyncState,
  resource: 'categories' | 'tags' = 'categories'
): Promise<Map<string, TermComparisonRow>> {
  const headers = { Authorization: `Bearer ${await adminToken(request)}` };
  const rows = new Map<string, TermComparisonRow>();
  for (let page = 0; ; page++) {
    const response = await request.get(
      `/api/projects/${s.projectId}/bulk-management/${resource}/comparison?page=${page}`,
      { headers, timeout: 120_000 }
    );
    expect(response.ok(), `比較に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(
      true
    );
    const body = (await response.json()) as TermComparisonPage;
    body.items.forEach((item) => rows.set(item.slug, item));
    if (body.items.length === 0 || (page + 1) * body.items.length >= body.totalCount) {
      return rows;
    }
  }
}

function expectNoDiff(row: TermComparisonRow | undefined, slug: string): void {
  expect(row, `再比較の結果に ${slug} の行がありません`).toBeTruthy();
  const { local, test } = row as TermComparisonRow;
  expect(local.slug, `${slug}: 対象環境に存在しません`).toBe(slug);
  expect(test.slug, `${slug}: マスター環境に存在しません`).toBe(slug);
  expect(local.description, `${slug}: 説明文が環境間で異なります`).toBe(test.description);
  expect(local.parentSlug, `${slug}: 親が環境間で異なります`).toBe(test.parentSlug);
}

/** 用意したカテゴリに関する同期結果だけを取り出す(他の残骸の結果と混ざらないように)。 */
function logsOf(s: SyncState, slugs: string[]): SyncLog[] {
  return s.logs.filter((log) => log.categorySlug !== null && slugs.includes(log.categorySlug));
}

Given(
  'testをマスター環境、localを対象環境とする2つのWordPressサイトを持つ同期用プロジェクトがある',
  async ({ ctx, request }) => {
    const projectId = await ensureProject(request);
    const master = await ensureManagedSite(request, MASTER_SITE_KEY);
    const target = await ensureManagedSite(request, TARGET_SITE_KEY);
    await bindEnvironment(request, projectId, 'test', master.id);
    await bindEnvironment(request, projectId, 'local', target.id);
    ctx.syncState = {
      projectId,
      masterSite: wpSlug(MASTER_SITE_KEY),
      targetSite: wpSlug(TARGET_SITE_KEY),
      slugs: [],
      descriptions: {},
      logs: [],
    } satisfies SyncState;
  }
);

Given('同期用に、マスター環境のサイトにだけカテゴリがある', async ({ ctx }) => {
  const s = state(ctx);
  s.slug = uniqueTermSlug();
  createCategory(s.masterSite, `Sync missing ${s.slug}`, s.slug, 'master only');
  register(s, s.slug, 'master only');
  expect(readCategory(s.targetSite, s.slug), '前提: 対象環境に既に存在します').toBeNull();
});

Given('同期用に、マスター環境と対象環境の両方に同じスラッグで説明文の異なるカテゴリがある', async ({ ctx }) => {
  const s = state(ctx);
  s.slug = uniqueTermSlug();
  createCategory(s.masterSite, `Sync both ${s.slug}`, s.slug, 'master description');
  createCategory(s.targetSite, `Sync both ${s.slug}`, s.slug, 'target description');
  register(s, s.slug, 'master description');
  expect(readCategory(s.targetSite, s.slug)?.description).toBe('target description');
});

Given('同期用に、マスター環境にだけあるカテゴリと、両環境で説明文の異なるカテゴリがある', async ({ ctx }) => {
  const s = state(ctx);
  s.missingSlug = uniqueTermSlug();
  s.differingSlug = uniqueTermSlug();
  createCategory(s.masterSite, `Sync all missing ${s.missingSlug}`, s.missingSlug, 'missing on target');
  createCategory(s.masterSite, `Sync all differing ${s.differingSlug}`, s.differingSlug, 'master side');
  createCategory(s.targetSite, `Sync all differing ${s.differingSlug}`, s.differingSlug, 'target side');
  register(s, s.missingSlug, 'missing on target');
  register(s, s.differingSlug, 'master side');
  expect(readCategory(s.targetSite, s.missingSlug), '前提: 対象環境に既に存在します').toBeNull();
});

Given('同期用に、マスター環境にだけ親カテゴリとその子カテゴリと単独のカテゴリがある', async ({ ctx }) => {
  const s = state(ctx);
  s.parentSlug = uniqueTermSlug();
  s.childSlug = uniqueTermSlug();
  s.aloneSlug = uniqueTermSlug();
  // 同期は名前順に処理される。子の名前を親より前に並べ、親が対象環境に作られる前に子が処理されるようにする。
  const parentId = createCategory(s.masterSite, `Sync fail B parent ${s.parentSlug}`, s.parentSlug, 'parent');
  createCategory(s.masterSite, `Sync fail A child ${s.childSlug}`, s.childSlug, 'child', parentId);
  createCategory(s.masterSite, `Sync fail C alone ${s.aloneSlug}`, s.aloneSlug, 'alone');
  register(s, s.parentSlug, 'parent');
  register(s, s.childSlug, 'child');
  register(s, s.aloneSlug, 'alone');
  expect(readCategory(s.targetSite, s.parentSlug), '前提: 対象環境に既に存在します').toBeNull();
});

When('そのカテゴリを同期する', async ({ ctx, request }) => {
  const s = state(ctx);
  await postSync(request, s, 'sync', { slug: s.slug });
});

When('そのカテゴリの説明文を編集して同期する', async ({ ctx, request }) => {
  const s = state(ctx);
  s.editedDescription = 'edited description';
  s.descriptions[s.slug as string] = s.editedDescription;
  await postSync(request, s, 'edit-sync', {
    targetSlug: s.slug,
    value: `Sync both ${s.slug}`,
    slug: s.slug,
    description: s.editedDescription,
  });
});

When('カテゴリをすべて同期する', async ({ ctx, request }) => {
  await postSync(request, state(ctx), 'sync-all');
});

Then('対象環境のWordPressにそのカテゴリがマスター環境と同じ内容で作成されている', async ({ ctx }) => {
  const s = state(ctx);
  const slug = s.slug as string;
  const target = readCategory(s.targetSite, slug);
  expect(target, 'WordPress の対象環境にカテゴリが作成されていません').not.toBeNull();
  expect(target?.name).toBe(readCategory(s.masterSite, slug)?.name);
  expect(target?.description).toBe('master only');
});

Then('マスター環境のWordPressのそのカテゴリの説明文が編集後の内容になっている', async ({ ctx }) => {
  const s = state(ctx);
  expect(readCategory(s.masterSite, s.slug as string)?.description).toBe(s.editedDescription);
});

Then('対象環境のWordPressのそのカテゴリの説明文がマスター環境と一致している', async ({ ctx }) => {
  const s = state(ctx);
  const slug = s.slug as string;
  const target = readCategory(s.targetSite, slug);
  expect(target, 'WordPress の対象環境にカテゴリがありません').not.toBeNull();
  expect(target?.description).toBe(readCategory(s.masterSite, slug)?.description);
  expect(target?.description).toBe(s.editedDescription);
});

Then('同期の結果に失敗した環境は含まれない', async ({ ctx }) => {
  const s = state(ctx);
  const mine = logsOf(s, s.slugs);
  expect(mine.length, '同期の結果が空です').toBeGreaterThan(0);
  expect(mine.filter((log) => log.status === 'FAILED')).toEqual([]);
});

Then('再比較すると、そのカテゴリに環境間の差分は無い', async ({ ctx, request }) => {
  const s = state(ctx);
  const rows = await compareAll(request, s);
  expectNoDiff(rows.get(s.slug as string), s.slug as string);
});

Then('対象環境のWordPressにマスター環境にだけあったカテゴリが作成されている', async ({ ctx }) => {
  const s = state(ctx);
  const target = readCategory(s.targetSite, s.missingSlug as string);
  expect(target, 'WordPress の対象環境にカテゴリが作成されていません').not.toBeNull();
  expect(target?.description).toBe('missing on target');
});

Then('対象環境のWordPressの説明文の異なっていたカテゴリがマスター環境と一致している', async ({ ctx }) => {
  const s = state(ctx);
  const slug = s.differingSlug as string;
  expect(readCategory(s.targetSite, slug)?.description).toBe('master side');
  expect(readCategory(s.masterSite, slug)?.description).toBe('master side');
});

Then('再比較すると、用意したすべてのカテゴリに環境間の差分は無い', async ({ ctx, request }) => {
  const s = state(ctx);
  const rows = await compareAll(request, s);
  for (const slug of s.slugs) {
    expectNoDiff(rows.get(slug), slug);
  }
});

Then('対象環境のWordPressに親カテゴリと単独のカテゴリが作成されている', async ({ ctx }) => {
  const s = state(ctx);
  expect(readCategory(s.targetSite, s.parentSlug as string)?.description, '親カテゴリが作成されていません').toBe(
    'parent'
  );
  expect(readCategory(s.targetSite, s.aloneSlug as string)?.description, '単独のカテゴリが作成されていません').toBe(
    'alone'
  );
});

Then('対象環境のWordPressに子カテゴリは作成されていない', async ({ ctx }) => {
  const s = state(ctx);
  expect(readCategory(s.targetSite, s.childSlug as string), '親の無い子カテゴリが作成されています').toBeNull();
});

Then('同期の結果に子カテゴリの失敗が対象環境の失敗として示される', async ({ ctx }) => {
  const s = state(ctx);
  const child = logsOf(s, [s.childSlug as string]);
  const failed = child.filter((log) => log.status === 'FAILED');
  expect(failed.length, '子カテゴリの失敗が同期の結果に現れていません').toBeGreaterThan(0);
  expect(failed.every((log) => log.environment === 'local'), '失敗した環境が対象環境(local)ではありません').toBe(true);
  expect(failed[0].errorMessage, '失敗の理由が示されていません').toBeTruthy();
});

Then('同期の結果に親カテゴリと単独のカテゴリの成功が示される', async ({ ctx }) => {
  const s = state(ctx);
  for (const slug of [s.parentSlug as string, s.aloneSlug as string]) {
    const logs = logsOf(s, [slug]);
    expect(logs.length, `${slug} の結果が同期の結果にありません`).toBeGreaterThan(0);
    expect(logs.every((log) => log.status === 'SUCCESS'), `${slug} が成功と示されていません`).toBe(true);
  }
});

/** シナリオが作った(同期で作られたものも含む)カテゴリを両サイトから消す。サイトそのものは残す。 */
After({ tags: '@bulk' }, async ({ ctx }) => {
  const s = ctx.syncState as SyncState | undefined;
  if (!s) {
    return;
  }
  // 子を先に消す(親を先に消すと子が付け替わる)。用意した順の逆に消せば足りる。
  for (const site of [s.masterSite, s.targetSite]) {
    for (const slug of [...s.slugs].reverse()) {
      try {
        const id = wpCli(site, `term list category --slug='${slug}' --field=term_id`);
        if (id !== '') {
          wpCli(site, `term delete category ${id}`);
        }
      } catch {
        // 既に無いものを消そうとした場合は、後片付けの目的(残さない)を満たしている。
      }
      try {
        const id = wpCli(site, `term list post_tag --slug='${slug}' --field=term_id`);
        if (id !== '') {
          wpCli(site, `term delete post_tag ${id}`);
        }
      } catch {
        // 同上。
      }
    }
  }
});

// ---- タグ(issue #1679 / AC-BULK-007) ----

Given('同期用に、マスター環境のサイトにだけタグがある', async ({ ctx }) => {
  const s = state(ctx);
  s.slug = uniqueTermSlug();
  createTag(s.masterSite, `Sync tag missing ${s.slug}`, s.slug, 'master only');
  register(s, s.slug, 'master only');
  expect(readTag(s.targetSite, s.slug), '前提: 対象環境に既に存在します').toBeNull();
});

Given('同期用に、マスター環境と対象環境の両方に同じスラッグで説明文の異なるタグがある', async ({ ctx }) => {
  const s = state(ctx);
  s.slug = uniqueTermSlug();
  createTag(s.masterSite, `Sync tag both ${s.slug}`, s.slug, 'master description');
  createTag(s.targetSite, `Sync tag both ${s.slug}`, s.slug, 'target description');
  register(s, s.slug, 'master description');
  expect(readTag(s.targetSite, s.slug)?.description).toBe('target description');
});

Given('同期用に、マスター環境にだけあるタグと、両環境で説明文の異なるタグがある', async ({ ctx }) => {
  const s = state(ctx);
  s.missingSlug = uniqueTermSlug();
  s.differingSlug = uniqueTermSlug();
  createTag(s.masterSite, `Sync tag all missing ${s.missingSlug}`, s.missingSlug, 'missing on target');
  createTag(s.masterSite, `Sync tag all differing ${s.differingSlug}`, s.differingSlug, 'master side');
  createTag(s.targetSite, `Sync tag all differing ${s.differingSlug}`, s.differingSlug, 'target side');
  register(s, s.missingSlug, 'missing on target');
  register(s, s.differingSlug, 'master side');
  expect(readTag(s.targetSite, s.missingSlug), '前提: 対象環境に既に存在します').toBeNull();
});

When('そのタグを同期する', async ({ ctx, request }) => {
  const s = state(ctx);
  await postSync(request, s, 'sync', { slug: s.slug }, 'tags');
});

When('そのタグの説明文を編集して同期する', async ({ ctx, request }) => {
  const s = state(ctx);
  s.editedDescription = 'edited description';
  s.descriptions[s.slug as string] = s.editedDescription;
  await postSync(
    request,
    s,
    'edit-sync',
    {
      targetSlug: s.slug,
      value: `Sync tag both ${s.slug}`,
      slug: s.slug,
      description: s.editedDescription,
    },
    'tags'
  );
});

When('タグをすべて同期する', async ({ ctx, request }) => {
  await postSync(request, state(ctx), 'sync-all', undefined, 'tags');
});

Then('対象環境のWordPressにそのタグがマスター環境と同じ内容で作成されている', async ({ ctx }) => {
  const s = state(ctx);
  const slug = s.slug as string;
  const target = readTag(s.targetSite, slug);
  expect(target, 'WordPress の対象環境にタグが作成されていません').not.toBeNull();
  expect(target?.name).toBe(readTag(s.masterSite, slug)?.name);
  expect(target?.description).toBe('master only');
});

Then('マスター環境のWordPressのそのタグの説明文が編集後の内容になっている', async ({ ctx }) => {
  const s = state(ctx);
  expect(readTag(s.masterSite, s.slug as string)?.description).toBe(s.editedDescription);
});

Then('対象環境のWordPressのそのタグの説明文がマスター環境と一致している', async ({ ctx }) => {
  const s = state(ctx);
  const slug = s.slug as string;
  const target = readTag(s.targetSite, slug);
  expect(target, 'WordPress の対象環境にタグがありません').not.toBeNull();
  expect(target?.description).toBe(readTag(s.masterSite, slug)?.description);
  expect(target?.description).toBe(s.editedDescription);
});

Then('再比較すると、そのタグに環境間の差分は無い', async ({ ctx, request }) => {
  const s = state(ctx);
  const rows = await compareAll(request, s, 'tags');
  expectNoDiff(rows.get(s.slug as string), s.slug as string);
});

Then('対象環境のWordPressにマスター環境にだけあったタグが作成されている', async ({ ctx }) => {
  const s = state(ctx);
  const target = readTag(s.targetSite, s.missingSlug as string);
  expect(target, 'WordPress の対象環境にタグが作成されていません').not.toBeNull();
  expect(target?.description).toBe('missing on target');
});

Then('対象環境のWordPressの説明文の異なっていたタグがマスター環境と一致している', async ({ ctx }) => {
  const s = state(ctx);
  const slug = s.differingSlug as string;
  expect(readTag(s.targetSite, slug)?.description).toBe('master side');
  expect(readTag(s.masterSite, slug)?.description).toBe('master side');
});

Then('再比較すると、用意したすべてのタグに環境間の差分は無い', async ({ ctx, request }) => {
  const s = state(ctx);
  const rows = await compareAll(request, s, 'tags');
  for (const slug of s.slugs) {
    expectNoDiff(rows.get(slug), slug);
  }
});
