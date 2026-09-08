import { execFileSync } from 'node:child_process';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * カテゴリ・タグの解決(issue #1174 / AT-6-4)のステップ定義。
 *
 * 兄弟issue(#932系列の子issue群)とステップ定義ファイルを共有しない方針
 * (`site-adoption.steps.ts`と同様。相乗りしない)のため、必要なヘルパーはこのファイル内に
 * 閉じて持つ。
 *
 * ## 専用サイトを冪等に用意する理由
 *
 * `POST /api/taxonomy/resolve` はWordPress側の分類(カテゴリ/タグ)を直接操作するAPIで、
 * その正しさは実際のWordPressの状態(wp-cli)を見ないと確かめられない。#1167のプロビジョニング
 * 済みサイト共有フィクスチャ(`site-provisioning.steps.ts`)はサイトの識別子だけを永続化し
 * WordPress管理者の認証情報は残さないため、後始末(作成したタームの削除)にwp-cliで直接
 * アクセスする本ファイルには使えない。そこで`media-garbage-collection`
 * (`mediaGarbageCollection.steps.ts`)と同じ「固定siteKeyで冪等に用意し、実行をまたいで
 * 再利用する」パターンを踏襲する。
 */

/** リポジトリルート(apps/web/e2e/steps から4階層上)。 */
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

/** 分類解決の検証用サイト。冪等に用意し、実行をまたいで再利用する。 */
const TAXONOMY_SITE_KEY = 'at64taxonomyprobe';
const TAXONOMY_SITE_ADMIN_USER = 'at64taxadmin';

/** WordPress自動構築の待ち上限。分単位でかかりうる。 */
const PROVISION_TIMEOUT_MS = 600_000;

interface SiteFixture {
  id: number;
  siteKey: string;
}

interface ResolveResult {
  categoryIds: string[];
  tagIds: string[];
}

interface WpTerm {
  name: string;
  term_id: string;
}

/** `WordPressSiteProvisioningService#normalizeSlug` と同じ正規化。 */
function wpSlug(siteKey: string): string {
  return siteKey.toLowerCase().replace(/[^a-z0-9-]/g, '-');
}

/**
 * WordPress コンテナで wp-cli を実行し、標準出力を返す。
 *
 * `docker compose exec` の cwd はコンテナの `WorkingDir` になるため、
 * サイトディレクトリへは `sh -c 'cd ... && ...'` で自分で移動する
 * (docs/ACCEPTANCE_TESTING.md §9「`working_dir` はマウント先にしない」)。
 */
function wpCli(slug: string, command: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'wordpress', 'sh', '-c', `cd /var/www/html/sites/${slug} && wp --allow-root ${command}`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 180_000 }
  ).trim();
}

function shellQuote(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`;
}

/** name完全一致(大文字小文字無視)のtermをwp-cliで検索する(本番側の解決ロジックと同じ基準)。 */
function findTermsByName(slug: string, taxonomy: string, name: string): WpTerm[] {
  const output = wpCli(
    slug,
    `term list ${taxonomy} --search=${shellQuote(name)} --fields=name,term_id --format=json`
  );
  // wp-cliのJSON出力はterm_idを数値として返す。APIのcategoryIds/tagIdsは文字列(record
  // TaxonomyResolveResponseのList<String>)なので、比較できるよう文字列へ揃える。
  const terms = (JSON.parse(output || '[]') as { name: string; term_id: number | string }[]) ?? [];
  return terms
    .filter((term) => term.name.toLowerCase() === name.toLowerCase())
    .map((term) => ({ name: term.name, term_id: String(term.term_id) }));
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

/** issue #765と同じ理由(並列実行時の衝突対策)でユニークな名前を作る。 */
function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

async function ensureManagedSite(request: APIRequestContext): Promise<SiteFixture> {
  const token = await adminToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const list = await request.get('/api/sites', { headers });
  expect(
    list.ok(),
    `サイト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`
  ).toBe(true);
  const existing = ((await list.json()) as SiteFixture[]).find((site) => site.siteKey === TAXONOMY_SITE_KEY);
  if (existing) {
    return existing;
  }
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: 'AT6-4 taxonomy probe site',
      siteKey: TAXONOMY_SITE_KEY,
      title: 'AT6-4 Taxonomy Probe',
      adminUser: TAXONOMY_SITE_ADMIN_USER,
      adminEmail: 'at64-taxonomy-probe@letsblog.local',
      adminPassword: 'At64Taxonomy#Passw0rd1',
    },
    timeout: PROVISION_TIMEOUT_MS,
  });
  if (created.status() === 409) {
    // provision-agent側には既に実体があるがDBには未登録(中断した前回実行の取り残し、または
    // このシナリオ自体を@mode:serialなしで並列実行してしまった場合)。
    // site-adoption.steps.tsと同じ「取り込み」で救う(site-adoption.feature参照)。
    return adoptExistingManagedSite(request, headers);
  }
  expect(
    created.ok(),
    `マネージドWordPressの構築に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  return (await created.json()) as SiteFixture;
}

async function adoptExistingManagedSite(
  request: APIRequestContext,
  headers: Record<string, string>
): Promise<SiteFixture> {
  const adopted = await request.post('/api/sites/managed-wordpress/adopt', {
    headers,
    data: {
      name: 'AT6-4 taxonomy probe site',
      siteKey: TAXONOMY_SITE_KEY,
      adminUser: TAXONOMY_SITE_ADMIN_USER,
    },
  });
  expect(
    adopted.ok(),
    `既存WordPressの取り込みに失敗しました (status=${adopted.status()}): ${await adopted.text()}`
  ).toBe(true);
  return (await adopted.json()) as SiteFixture;
}

async function resolveTaxonomy(
  request: APIRequestContext,
  siteKey: string,
  categories: string[],
  tags: string[]
): Promise<ResolveResult> {
  const token = await adminToken(request);
  const response = await request.post('/api/taxonomy/resolve', {
    headers: { Authorization: `Bearer ${token}` },
    data: { site: siteKey, categories, tags },
  });
  expect(
    response.ok(),
    `分類の解決に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as ResolveResult;
}

// ------------------------------------------------------- 前提

Given('分類解決用のWordPressサイトがある', async ({ ctx, request }) => {
  const site = await ensureManagedSite(request);
  ctx.taxonomySiteKey = site.siteKey;
  ctx.taxonomySiteSlug = wpSlug(site.siteKey);
  ctx.taxonomyCreatedCategoryIds = [] as string[];
  ctx.taxonomyCreatedTagIds = [] as string[];
});

// ------------------------------------------------------- シナリオ1: 既存分類への解決(AC1)

When('新しい名前でカテゴリとタグを解決する', async ({ ctx, request }) => {
  const unique = uniqueSuffix();
  const categoryName = `E2E-1174-Category-${unique}`;
  const tagName = `E2E-1174-Tag-${unique}`;
  const result = await resolveTaxonomy(request, ctx.taxonomySiteKey as string, [categoryName], [tagName]);
  ctx.taxonomyFirstCategoryName = categoryName;
  ctx.taxonomyFirstTagName = tagName;
  ctx.taxonomyFirstCategoryId = result.categoryIds[0];
  ctx.taxonomyFirstTagId = result.tagIds[0];
  (ctx.taxonomyCreatedCategoryIds as string[]).push(result.categoryIds[0]);
  (ctx.taxonomyCreatedTagIds as string[]).push(result.tagIds[0]);
});

When('同じ名前を大文字小文字だけ変えて再度カテゴリとタグを解決する', async ({ ctx, request }) => {
  const categoryName = (ctx.taxonomyFirstCategoryName as string).toUpperCase();
  const tagName = (ctx.taxonomyFirstTagName as string).toUpperCase();
  const result = await resolveTaxonomy(request, ctx.taxonomySiteKey as string, [categoryName], [tagName]);
  ctx.taxonomySecondCategoryId = result.categoryIds[0];
  ctx.taxonomySecondTagId = result.tagIds[0];
});

Then('1回目と2回目で同じIDが返る', async ({ ctx }) => {
  expect(
    ctx.taxonomySecondCategoryId,
    'カテゴリのIDが1回目と2回目で異なります(重複作成の疑い)'
  ).toBe(ctx.taxonomyFirstCategoryId);
  expect(
    ctx.taxonomySecondTagId,
    'タグのIDが1回目と2回目で異なります(重複作成の疑い)'
  ).toBe(ctx.taxonomyFirstTagId);
});

Then('WordPress側にそのカテゴリとタグがそれぞれ1件だけ存在する', async ({ ctx }) => {
  const slug = ctx.taxonomySiteSlug as string;
  const categoryTerms = findTermsByName(slug, 'category', ctx.taxonomyFirstCategoryName as string);
  const tagTerms = findTermsByName(slug, 'post_tag', ctx.taxonomyFirstTagName as string);
  expect(
    categoryTerms,
    `WordPress側のカテゴリが1件ではありません: ${JSON.stringify(categoryTerms)}`
  ).toHaveLength(1);
  expect(
    tagTerms,
    `WordPress側のタグが1件ではありません: ${JSON.stringify(tagTerms)}`
  ).toHaveLength(1);
  expect(categoryTerms[0].term_id).toBe(ctx.taxonomyFirstCategoryId);
  expect(tagTerms[0].term_id).toBe(ctx.taxonomyFirstTagId);
});

// ------------------------------------------------------- シナリオ2: 存在しない分類の新規作成(AC2)

When('存在しないカテゴリとタグを解決する', async ({ ctx, request }) => {
  const unique = uniqueSuffix();
  const categoryName = `E2E-1174-New-Category-${unique}`;
  const tagName = `E2E-1174-New-Tag-${unique}`;
  const result = await resolveTaxonomy(request, ctx.taxonomySiteKey as string, [categoryName], [tagName]);
  ctx.taxonomyNewCategoryName = categoryName;
  ctx.taxonomyNewTagName = tagName;
  ctx.taxonomyNewCategoryId = result.categoryIds[0];
  ctx.taxonomyNewTagId = result.tagIds[0];
  (ctx.taxonomyCreatedCategoryIds as string[]).push(result.categoryIds[0]);
  (ctx.taxonomyCreatedTagIds as string[]).push(result.tagIds[0]);
});

Then('WordPress側にそのカテゴリとタグが新規作成されている', async ({ ctx }) => {
  const slug = ctx.taxonomySiteSlug as string;
  const categoryTerms = findTermsByName(slug, 'category', ctx.taxonomyNewCategoryName as string);
  const tagTerms = findTermsByName(slug, 'post_tag', ctx.taxonomyNewTagName as string);
  expect(
    categoryTerms,
    `WordPress側に新規カテゴリが見つかりません: ${JSON.stringify(categoryTerms)}`
  ).toHaveLength(1);
  expect(
    tagTerms,
    `WordPress側に新規タグが見つかりません: ${JSON.stringify(tagTerms)}`
  ).toHaveLength(1);
  expect(categoryTerms[0].name).toBe(ctx.taxonomyNewCategoryName);
  expect(tagTerms[0].name).toBe(ctx.taxonomyNewTagName);
  expect(categoryTerms[0].term_id).toBe(ctx.taxonomyNewCategoryId);
  expect(tagTerms[0].term_id).toBe(ctx.taxonomyNewTagId);
});

// ------------------------------------------------------- 後片付け

After({ tags: '@publishing' }, async ({ ctx }) => {
  const slug = ctx.taxonomySiteSlug as string | undefined;
  if (!slug) {
    return;
  }
  const categoryIds = Array.from(new Set((ctx.taxonomyCreatedCategoryIds as string[] | undefined) ?? []));
  const tagIds = Array.from(new Set((ctx.taxonomyCreatedTagIds as string[] | undefined) ?? []));
  for (const id of categoryIds) {
    try {
      wpCli(slug, `term delete category ${id}`);
    } catch {
      // 既に消えている等は後片付けの失敗としては扱わない。
    }
  }
  for (const id of tagIds) {
    try {
      wpCli(slug, `term delete post_tag ${id}`);
    } catch {
      // 同上。
    }
  }
});
