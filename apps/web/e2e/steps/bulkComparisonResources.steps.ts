import { execFileSync } from 'node:child_process';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * タグ・プラグイン・テーマ・投稿の環境間比較(issue #1178 / AT-7-2、AC-BULK-002〜005)の
 * ステップ定義。
 *
 * 兄弟 issue(#1177 の `bulkComparison.steps.ts` ほか)と同じファイルに相乗りしないため、
 * 新規ファイルとして独立させている。あちらのヘルパーは非公開なので、サイトの用意と
 * wp-cli の呼び出しは必要な分だけをここへ持つ。ステップ文言は兄弟と重複させない
 * (playwright-bdd のステップ登録は全ファイル共通)。
 *
 * 比較は読み取りのみ。差分は wp-cli で各サイトへ直接作って用意し、比較APIの応答を
 * WordPress の実状態(wp-cli で読み直した値)と突き合わせる。
 */

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

/** #1177 と同じ固定キーの比較用プロジェクトとサイト。冪等に再利用する。 */
const PROJECT_SLUG = 'e2e-at7-cmp';
const MASTER_SITE_KEY = 'at7cmpmaster';
const TARGET_SITE_KEY = 'at7cmptarget';

const PROVISION_TIMEOUT_MS = 600_000;

interface SiteFixture {
  id: number;
  siteKey: string;
}

interface TermValue {
  available: boolean;
  error: boolean;
  slug: string | null;
  description: string | null;
}

interface StatusValue {
  available: boolean;
  error: boolean;
  status: string | null;
}

interface PostValue {
  available: boolean;
  error: boolean;
  postId: string | null;
  title: string | null;
  status: string | null;
}

interface Row<V> {
  slug: string;
  local: V;
  test: V;
  production: V;
}

interface Page<V> {
  items: Row<V>[];
  totalCount: number;
}

/** シナリオの後片付けを、種類ごとに登録しておく。 */
type Cleanup = () => void;

function wpSlug(siteKey: string): string {
  return siteKey.toLowerCase().replace(/[^a-z0-9-]/g, '-');
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

/** `docker compose exec` の cwd はコンテナの WorkingDir になるので、サイトディレクトリへは自分で移る。 */
function wpShell(siteSlug: string, script: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'wordpress', 'sh', '-c', `cd /var/www/html/sites/${siteSlug} && ${script}`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 180_000 }
  ).trim();
}

function wpCli(siteSlug: string, command: string): string {
  return wpShell(siteSlug, `wp --allow-root ${command}`);
}

function unique(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
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
    data: { name: 'E2E AT7 Category Comparison', slug: PROJECT_SLUG },
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

/** 比較APIの全ページを辿って、指定スラッグの行を集める(1回分)。 */
async function fetchRowsOnce<V>(
  request: APIRequestContext,
  projectId: number,
  resource: 'tags' | 'plugins' | 'themes' | 'posts',
  slugs: string[]
): Promise<Record<string, Row<V> | undefined>> {
  const headers = { Authorization: `Bearer ${await adminToken(request)}` };
  const found: Record<string, Row<V> | undefined> = {};
  for (let page = 0; ; page++) {
    const response = await request.get(
      `/api/projects/${projectId}/bulk-management/${resource}/comparison?page=${page}`,
      { headers, timeout: 120_000 }
    );
    expect(
      response.ok(),
      `${resource}の比較に失敗しました (status=${response.status()}): ${await response.text()}`
    ).toBe(true);
    const body = (await response.json()) as Page<V>;
    for (const item of body.items) {
      if (slugs.includes(item.slug)) {
        found[item.slug] = item;
      }
    }
    if (body.items.length === 0 || (page + 1) * body.items.length >= body.totalCount) {
      return found;
    }
  }
}

/**
 * 比較APIの応答から指定スラッグの行を集める。
 *
 * プラグイン・テーマの一覧は provision-agent への問い合わせで、負荷が高いときは取得が
 * タイムアウトして環境が「エラー」として返ることがある(そのとき行も欠ける)。それは比較の
 * ふるまいではなく取得の一時失敗なので、両環境とも取得できた応答が得られるまで数回やり直す。
 * 取得できたうえで差分が期待と違うなら、やり直さずそのまま検証で落ちる。
 */
async function fetchRows<V extends { error: boolean }>(
  request: APIRequestContext,
  projectId: number,
  resource: 'tags' | 'plugins' | 'themes' | 'posts',
  slugs: string[]
): Promise<Record<string, Row<V> | undefined>> {
  const attempts = 6;
  let found: Record<string, Row<V> | undefined> = {};
  for (let attempt = 1; attempt <= attempts; attempt++) {
    found = await fetchRowsOnce<V>(request, projectId, resource, slugs);
    const complete = slugs.every((slug) => {
      const row = found[slug];
      return row !== undefined && !row.local.error && !row.test.error;
    });
    if (complete) {
      return found;
    }
    await new Promise((resolve) => setTimeout(resolve, 5_000));
  }
  return found;
}

function need<V>(ctx: Record<string, unknown>, key: string): Row<V> {
  const row = (ctx.resRows as Record<string, Row<V> | undefined>)[ctx[key] as string];
  expect(row, `比較結果に用意したリソース(${ctx[key]})の行がありません`).toBeTruthy();
  return row as Row<V>;
}

function cleanups(ctx: Record<string, unknown>): Cleanup[] {
  return ctx.resCleanups as Cleanup[];
}

function pluginDir(slug: string): string {
  return `$(wp --allow-root plugin path)/${slug}`;
}

function createPlugin(siteSlug: string, slug: string, activate: boolean): void {
  wpShell(
    siteSlug,
    `mkdir -p ${pluginDir(slug)} && printf '<?php\\n/*\\nPlugin Name: ${slug}\\n*/\\n' > ${pluginDir(slug)}/${slug}.php`
  );
  if (activate) {
    wpCli(siteSlug, `plugin activate ${slug}`);
  }
}

function removePlugin(siteSlug: string, slug: string): void {
  try {
    wpCli(siteSlug, `plugin deactivate ${slug}`);
  } catch {
    // 未インストール・無効のものを無効化しようとした場合は、後片付けの目的を満たしている。
  }
  wpShell(siteSlug, `rm -rf ${pluginDir(slug)}`);
}

function themeDir(slug: string): string {
  return `$(wp --allow-root theme path)/${slug}`;
}

function createTheme(siteSlug: string, slug: string): void {
  wpShell(
    siteSlug,
    `mkdir -p ${themeDir(slug)} && printf '/*\\nTheme Name: ${slug}\\n*/\\n' > ${themeDir(slug)}/style.css && printf '<?php\\n' > ${themeDir(slug)}/index.php`
  );
}

function removeTheme(siteSlug: string, slug: string): void {
  wpShell(siteSlug, `rm -rf ${themeDir(slug)}`);
}

/** WordPress 側の実状態: タグの説明文。存在しなければ null。 */
function readTagDescription(siteSlug: string, slug: string): string | null {
  const found = wpCli(siteSlug, `term list post_tag --slug='${slug}' --field=description`);
  return found === '' ? null : found;
}

function createPost(siteSlug: string, slug: string, status: string): string {
  return wpCli(
    siteSlug,
    `post create --post_type=post --post_title='Post ${slug}' --post_name='${slug}' --post_status=${status} --porcelain`
  );
}

/** WordPress 側の実状態: 投稿の公開状態。存在しなければ null。 */
function readPostStatus(siteSlug: string, slug: string): string | null {
  // `--name` は下書きを引けないので、全件を読んでスラッグで絞る。
  const listing = wpCli(
    siteSlug,
    'post list --post_type=post --post_status=any --fields=post_name,post_status --format=csv'
  );
  const line = listing.split('\n').find((l) => l.startsWith(`${slug},`));
  return line ? line.slice(slug.length + 1) : null;
}

Given(
  'testをマスター環境、localを対象環境とする2つのWordPressサイトを持つリソース比較用プロジェクトがある',
  async ({ ctx, request }) => {
    const projectId = await ensureProject(request);
    const master = await ensureManagedSite(request, MASTER_SITE_KEY);
    const target = await ensureManagedSite(request, TARGET_SITE_KEY);
    await bindEnvironment(request, projectId, 'test', master.id);
    await bindEnvironment(request, projectId, 'local', target.id);
    ctx.resProjectId = projectId;
    ctx.resMaster = wpSlug(MASTER_SITE_KEY);
    ctx.resTarget = wpSlug(TARGET_SITE_KEY);
    ctx.resCleanups = [] as Cleanup[];
  }
);

// ---- タグ ----

Given('マスター環境にだけあるタグと、両環境で説明文の異なるタグと、対象環境にだけあるタグがある', async ({ ctx }) => {
  const master = ctx.resMaster as string;
  const target = ctx.resTarget as string;
  ctx.resMissing = unique('at7res-tm');
  ctx.resDiff = unique('at7res-td');
  ctx.resExtra = unique('at7res-te');
  const make = (site: string, slug: string, description: string) => {
    const id = wpCli(
      site,
      `term create post_tag 'Tag ${slug}' --slug='${slug}' --description='${description}' --porcelain`
    );
    cleanups(ctx).push(() => wpCli(site, `term delete post_tag ${id}`));
  };
  make(master, ctx.resMissing as string, 'master only');
  make(master, ctx.resDiff as string, 'master description');
  make(target, ctx.resDiff as string, 'target description');
  make(target, ctx.resExtra as string, 'target only');
});

When('そのプロジェクトのタグを環境間で比較する', async ({ ctx, request }) => {
  ctx.resRows = await fetchRows<TermValue>(request, ctx.resProjectId as number, 'tags', [
    ctx.resMissing as string,
    ctx.resDiff as string,
    ctx.resExtra as string,
  ]);
});

Then('比較結果でマスター環境にだけあるタグは対象環境に不足している', async ({ ctx }) => {
  const row = need<TermValue>(ctx, 'resMissing');
  expect(row.test.slug, 'マスター(test)に存在しません').toBe(ctx.resMissing);
  expect(row.local.error, '対象環境の取得がエラーになっています').toBe(false);
  expect(row.local.available, '対象環境が比較対象になっていません').toBe(true);
  expect(row.local.slug, '対象環境に存在しないはずのタグが存在します').toBeNull();
  expect(readTagDescription(ctx.resMaster as string, ctx.resMissing as string)).toBe('master only');
  expect(
    readTagDescription(ctx.resTarget as string, ctx.resMissing as string),
    'WordPress の対象環境に実際は存在します'
  ).toBeNull();
});

Then('比較結果で両環境にあるタグの説明文は環境間で異なる', async ({ ctx }) => {
  const row = need<TermValue>(ctx, 'resDiff');
  expect(row.test.description).toBe('master description');
  expect(row.local.description).toBe('target description');
  expect(readTagDescription(ctx.resMaster as string, ctx.resDiff as string)).toBe('master description');
  expect(readTagDescription(ctx.resTarget as string, ctx.resDiff as string)).toBe('target description');
});

Then('比較結果で対象環境にだけあるタグはマスター環境に存在しない', async ({ ctx }) => {
  const row = need<TermValue>(ctx, 'resExtra');
  expect(row.local.slug, '対象環境(local)に存在しません').toBe(ctx.resExtra);
  expect(row.test.error, 'マスター環境の取得がエラーになっています').toBe(false);
  expect(row.test.available, 'マスター環境が比較対象になっていません').toBe(true);
  expect(row.test.slug, 'マスター環境に存在しないはずのタグが存在します').toBeNull();
  expect(readTagDescription(ctx.resTarget as string, ctx.resExtra as string)).toBe('target only');
  expect(
    readTagDescription(ctx.resMaster as string, ctx.resExtra as string),
    'WordPress のマスター環境に実際は存在します'
  ).toBeNull();
});

// ---- プラグイン ----

/** WordPress 側の実状態: プラグインの状態(active / inactive)。未インストールなら null。 */
function readPluginStatus(siteSlug: string, slug: string): string | null {
  const found = wpCli(siteSlug, `plugin list --name='${slug}' --field=status`);
  return found === '' ? null : found;
}

Given('マスター環境にだけ有効なプラグインと、有効状態が環境間で異なるプラグインがある', async ({ ctx }) => {
  const master = ctx.resMaster as string;
  const target = ctx.resTarget as string;
  ctx.resMissing = unique('at7res-pm');
  ctx.resDiff = unique('at7res-pd');
  createPlugin(master, ctx.resMissing as string, true);
  cleanups(ctx).push(() => removePlugin(master, ctx.resMissing as string));
  createPlugin(master, ctx.resDiff as string, true);
  cleanups(ctx).push(() => removePlugin(master, ctx.resDiff as string));
  createPlugin(target, ctx.resDiff as string, false);
  cleanups(ctx).push(() => removePlugin(target, ctx.resDiff as string));
});

When('そのプロジェクトのプラグインを環境間で比較する', async ({ ctx, request }) => {
  ctx.resRows = await fetchRows<StatusValue>(request, ctx.resProjectId as number, 'plugins', [
    ctx.resMissing as string,
    ctx.resDiff as string,
  ]);
});

Then('比較結果でマスター環境にだけあるプラグインは対象環境に未インストールである', async ({ ctx }) => {
  const row = need<StatusValue>(ctx, 'resMissing');
  expect(row.test.status).toBe('ACTIVE');
  expect(row.local.error, '対象環境の取得がエラーになっています').toBe(false);
  expect(row.local.status).toBe('NOT_INSTALLED');
  expect(readPluginStatus(ctx.resMaster as string, ctx.resMissing as string)).toBe('active');
  expect(
    readPluginStatus(ctx.resTarget as string, ctx.resMissing as string),
    'WordPress の対象環境に実際は存在します'
  ).toBeNull();
});

Then('比較結果で有効状態が異なるプラグインはマスター環境で有効、対象環境で無効である', async ({ ctx }) => {
  const row = need<StatusValue>(ctx, 'resDiff');
  expect(row.test.status).toBe('ACTIVE');
  expect(row.local.status).toBe('INACTIVE');
  expect(readPluginStatus(ctx.resMaster as string, ctx.resDiff as string)).toBe('active');
  expect(readPluginStatus(ctx.resTarget as string, ctx.resDiff as string)).toBe('inactive');
});

// ---- テーマ ----

/** WordPress 側の実状態: テーマの状態。未インストールなら null。 */
function readThemeStatus(siteSlug: string, slug: string): string | null {
  const found = wpCli(siteSlug, `theme list --name='${slug}' --field=status`);
  return found === '' ? null : found;
}

Given('マスター環境にだけあるテーマと、対象環境にだけあるテーマがある', async ({ ctx }) => {
  const master = ctx.resMaster as string;
  const target = ctx.resTarget as string;
  ctx.resMissing = unique('at7res-hm');
  ctx.resExtra = unique('at7res-he');
  createTheme(master, ctx.resMissing as string);
  cleanups(ctx).push(() => removeTheme(master, ctx.resMissing as string));
  createTheme(target, ctx.resExtra as string);
  cleanups(ctx).push(() => removeTheme(target, ctx.resExtra as string));
});

When('そのプロジェクトのテーマを環境間で比較する', async ({ ctx, request }) => {
  ctx.resRows = await fetchRows<StatusValue>(request, ctx.resProjectId as number, 'themes', [
    ctx.resMissing as string,
    ctx.resExtra as string,
  ]);
});

Then('比較結果でマスター環境にだけあるテーマは対象環境に未インストールである', async ({ ctx }) => {
  const row = need<StatusValue>(ctx, 'resMissing');
  expect(row.test.status).toBe('INACTIVE');
  expect(row.local.error, '対象環境の取得がエラーになっています').toBe(false);
  expect(row.local.status).toBe('NOT_INSTALLED');
  expect(readThemeStatus(ctx.resMaster as string, ctx.resMissing as string)).toBe('inactive');
  expect(
    readThemeStatus(ctx.resTarget as string, ctx.resMissing as string),
    'WordPress の対象環境に実際は存在します'
  ).toBeNull();
});

Then('比較結果で対象環境にだけあるテーマはマスター環境に未インストールである', async ({ ctx }) => {
  const row = need<StatusValue>(ctx, 'resExtra');
  expect(row.local.status).toBe('INACTIVE');
  expect(row.test.error, 'マスター環境の取得がエラーになっています').toBe(false);
  expect(row.test.status).toBe('NOT_INSTALLED');
  expect(readThemeStatus(ctx.resTarget as string, ctx.resExtra as string)).toBe('inactive');
  expect(
    readThemeStatus(ctx.resMaster as string, ctx.resExtra as string),
    'WordPress のマスター環境に実際は存在します'
  ).toBeNull();
});

// ---- 投稿 ----

Given('マスター環境にだけある投稿と、両環境にあり公開状態が異なる投稿がある', async ({ ctx }) => {
  const master = ctx.resMaster as string;
  const target = ctx.resTarget as string;
  ctx.resMissing = unique('at7res-qm');
  ctx.resDiff = unique('at7res-qd');
  const make = (site: string, slug: string, status: string) => {
    const id = createPost(site, slug, status);
    cleanups(ctx).push(() => wpCli(site, `post delete ${id} --force`));
  };
  make(master, ctx.resMissing as string, 'publish');
  make(master, ctx.resDiff as string, 'publish');
  make(target, ctx.resDiff as string, 'draft');
});

When('そのプロジェクトの投稿を環境間で比較する', async ({ ctx, request }) => {
  ctx.resRows = await fetchRows<PostValue>(request, ctx.resProjectId as number, 'posts', [
    ctx.resMissing as string,
    ctx.resDiff as string,
  ]);
});

Then('比較結果でマスター環境にだけある投稿は対象環境に存在しない', async ({ ctx }) => {
  const row = need<PostValue>(ctx, 'resMissing');
  expect(row.test.postId, 'マスター(test)に存在しません').not.toBeNull();
  expect(row.local.error, '対象環境の取得がエラーになっています').toBe(false);
  expect(row.local.available, '対象環境が比較対象になっていません').toBe(true);
  expect(row.local.postId, '対象環境に存在しないはずの投稿が存在します').toBeNull();
  expect(readPostStatus(ctx.resMaster as string, ctx.resMissing as string)).toBe('publish');
  expect(
    readPostStatus(ctx.resTarget as string, ctx.resMissing as string),
    'WordPress の対象環境に実際は存在します'
  ).toBeNull();
});

Then('比較結果で両環境にある投稿は公開状態が環境間で異なる', async ({ ctx }) => {
  const row = need<PostValue>(ctx, 'resDiff');
  expect(row.test.postId).not.toBeNull();
  expect(row.local.postId).not.toBeNull();
  expect(row.test.status).toBe('publish');
  expect(row.local.status).toBe('draft');
  expect(readPostStatus(ctx.resMaster as string, ctx.resDiff as string)).toBe('publish');
  expect(readPostStatus(ctx.resTarget as string, ctx.resDiff as string)).toBe('draft');
});

/** シナリオが作ったリソースを消す。サイトそのものは残す(構築に分単位かかるため)。 */
After({ tags: '@bulk' }, async ({ ctx }) => {
  for (const cleanup of ((ctx.resCleanups as Cleanup[] | undefined) ?? []).reverse()) {
    try {
      cleanup();
    } catch {
      // 既に無いものを消そうとした場合は、後片付けの目的(残さない)を満たしている。
    }
  }
});
