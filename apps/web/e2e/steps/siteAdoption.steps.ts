import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * 既存WordPressの取り込み・再プロビジョニング(issue #1169 / AT-5-5)のステップ定義。
 *
 * 兄弟issue(#1166 / #1167 / #1168)と同じ「ステップ定義ファイルは相乗りしない」方針
 * (issue本文のScope)のため、必要なヘルパーはこのファイル内に閉じて持つ。
 *
 * wp-cli導入(親issue #931のシナリオ14)はこのファイルにない。理由は
 * `site-adoption.feature`冒頭のコメントと#1197を参照。
 */

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await adminToken(request)}` };
}

/** issue #765: フィクスチャ名は並列実行時の衝突・孤児サイト対策でタイムスタンプ+乱数にする。 */
function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

interface ManagedWordPressFixture {
  siteId: number;
  siteKey: string;
  name: string;
  adminUser: string;
  adminEmail: string;
  adminPassword: string;
  /** provision-agent側の実体を指すディレクトリ名(`normalizeSlug(siteKey)`と同じ規則)。 */
  slug: string;
}

/**
 * `POST /api/sites/managed-wordpress` でManagedWordPressサイトを構築する(API直叩き。
 * UIのフォームを経由しない。adminUser/adminPasswordを自分で把握しておく必要があるため)。
 */
async function createManagedWordPress(
  request: APIRequestContext,
  headers: Record<string, string>,
  prefix: string
): Promise<ManagedWordPressFixture> {
  const unique = uniqueSuffix();
  // siteKeyは英数字とハイフンのみ(normalizeSlugと同じ文字種)にしておく。
  const siteKey = `e2e-1169-${prefix}-${unique}`;
  const name = `E2E 1169 ${prefix} ${unique}`;
  const adminUser = `e2e1169${prefix}${unique}`.replace(/[^a-zA-Z0-9]/g, '').slice(0, 30);
  const adminEmail = `e2e-1169-${prefix}-${unique}@letsblog.local`;
  const adminPassword = 'E2eAdoption#Passw0rd1';

  const response = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name,
      siteKey,
      title: name,
      adminUser,
      adminEmail,
      adminPassword,
      locale: 'ja',
    },
  });
  expect(
    response.ok(),
    `ManagedWordPressの構築に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const body = (await response.json()) as { id: number };
  return { siteId: body.id, siteKey, name, adminUser, adminEmail, adminPassword, slug: siteKey };
}

/** 登録済みかどうかによらず安全に呼べる後片付け用の削除(既に削除済みなら404になるだけ)。 */
async function deleteFixtureSiteIfPresent(
  request: APIRequestContext,
  headers: Record<string, string>,
  id: number | undefined
): Promise<void> {
  if (id === undefined) {
    return;
  }
  await request.delete(`/api/sites/${id}`, { headers });
}

// ------------------------------------------------------- シナリオ1: adoptによる取り込み(親シナリオ7)

Given('provision-agent上に構築済みだが未取り込みのWordPressがある', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const fixture = await createManagedWordPress(request, headers, 'adoptsrc');
  ctx.adoptSourceSiteId = fixture.siteId;
  ctx.adoptSourceAdminUser = fixture.adminUser;
  // adopt先のsiteKeyは、元のsiteKeyのハイフンをアンダースコアへ置き換えたもの。
  // normalizeSlug()はどちらも同じslug(元のsiteKeyそのもの)へ正規化するため、
  // provision-agent側は同じディレクトリ・同じ管理者ユーザーを見つけて取り込みに成功する
  // (site_keyのDB一意制約には抵触しない。文字として異なる値のため。詳細は
  // site-adoption.featureの冒頭コメント参照)。
  ctx.adoptTargetSiteKey = fixture.siteKey.replace(/-/g, '_');
  ctx.adoptTargetName = `${fixture.name} adopted`;
});

When('そのWordPressをadoptで取り込む', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const response = await request.post('/api/sites/managed-wordpress/adopt', {
    headers,
    data: {
      name: ctx.adoptTargetName,
      siteKey: ctx.adoptTargetSiteKey,
      adminUser: ctx.adoptSourceAdminUser,
    },
  });
  expect(
    response.ok(),
    `既存WordPressのadoptに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const body = (await response.json()) as { id: number; siteKey: string };
  ctx.adoptedSiteId = body.id;
  expect(body.siteKey).toBe(ctx.adoptTargetSiteKey);
});

Then('取り込みが完了し取り込んだサイトが一覧に含まれる', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const response = await request.get('/api/sites', { headers });
  expect(
    response.ok(),
    `サイト一覧の取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const sites = (await response.json()) as { id: number; siteKey: string }[];
  expect(sites.some((site) => site.id === ctx.adoptedSiteId)).toBe(true);
});

Then('取り込んだサイトの疎通確認が成功する', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const response = await request.post(`/api/sites/${ctx.adoptedSiteId}/test-connection`, { headers });
  expect(
    response.ok(),
    `取り込んだサイトの疎通確認に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const body = (await response.json()) as { connectionCheckStatus: string };
  expect(body.connectionCheckStatus).toBe('SUCCESS');
});

// ------------------------------------------------------- シナリオ2: 再プロビジョニング(親シナリオ15)

/** WordPress自身のログイン画面からCookie認証する(project-serviceの認証とは独立)。 */
async function loginToWordPressAdmin(page: Page, slug: string, adminUser: string, adminPassword: string): Promise<void> {
  await page.goto(`/sites/${slug}/wp-login.php`);
  const user = page.locator('#user_login');
  const pass = page.locator('#user_pass');

  // wp-login.php は読み込み完了後に `#user_login` へ自動でフォーカスを移す。
  // その割り込みが2つの fill の間に入ると、2つ目の入力がユーザー名欄へ流れ込み、
  // 「ユーザー名欄にパスワードが入り、パスワード欄が空のまま送信される」形で落ちる。
  // 送信前に両欄の値を確認し、崩れていたら入れ直す。復旧できなければ waitForURL の
  // タイムアウトではなく、どちらの欄が不正かを示す toHaveValue で落ちる。
  await expect(async () => {
    await user.fill(adminUser);
    await pass.fill(adminPassword);
    await expect(user).toHaveValue(adminUser);
    await expect(pass).toHaveValue(adminPassword);
  }).toPass({ timeout: 30000 });

  await page.locator('#wp-submit').click();
  await page.waitForURL(`**/sites/${slug}/wp-admin/**`, { timeout: 30000 });
}

/**
 * 投稿編集画面(ブロックエディタ)はwp-json REST APIを介して保存するため、
 * `wpApiSettings.nonce`をページから読み取って直接REST APIへ投稿する
 * (project-serviceはアプリケーションパスワードを平文で返さないため、Basic認証は使えない。
 * 詳細はsite-adoption.featureの冒頭コメント参照)。
 */
async function createRealPost(page: Page, slug: string, title: string): Promise<number> {
  await page.goto(`/sites/${slug}/wp-admin/post-new.php`);
  const nonce = await page.waitForFunction(
    () => (window as unknown as { wpApiSettings?: { nonce?: string } }).wpApiSettings?.nonce ?? null,
    { timeout: 30000 }
  );
  const nonceValue = (await nonce.jsonValue()) as string;

  const response = await page.request.post(`/sites/${slug}/wp-json/wp/v2/posts`, {
    headers: { 'X-WP-Nonce': nonceValue },
    data: { title, content: 'E2E 1169 reprovision fixture body', status: 'publish' },
  });
  expect(
    response.ok(),
    `テスト用投稿の作成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const body = (await response.json()) as { id: number };
  return body.id;
}

Given('投稿がある状態のManagedWordPressサイトがある', async ({ ctx, page, request }) => {
  const headers = await adminHeaders(request);
  const fixture = await createManagedWordPress(request, headers, 'reprov');
  ctx.reprovisionSiteId = fixture.siteId;
  ctx.reprovisionSlug = fixture.slug;

  await loginToWordPressAdmin(page, fixture.slug, fixture.adminUser, fixture.adminPassword);
  ctx.reprovisionPostTitle = `E2E 1169 reprovision fixture ${uniqueSuffix()}`;
  ctx.reprovisionPostId = await createRealPost(page, fixture.slug, ctx.reprovisionPostTitle as string);
});

When('そのサイトを再プロビジョニングする', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const response = await request.post(`/api/sites/${ctx.reprovisionSiteId}/reprovision`, { headers });
  ctx.reprovisionResponseStatus = response.status();
  ctx.reprovisionResponseBody = await response.json();
});

Then('再プロビジョニングが成功したことが応答でわかる', async ({ ctx }) => {
  expect(
    ctx.reprovisionResponseStatus,
    `再プロビジョニングが失敗しました: ${JSON.stringify(ctx.reprovisionResponseBody)}`
  ).toBe(200);
  const body = ctx.reprovisionResponseBody as { message: string };
  expect(body.message).toContain('プロビジョニングを再実行しました');
});

Then('投稿はそのまま取得できる', async ({ ctx, page }) => {
  const response = await page.request.get(`/sites/${ctx.reprovisionSlug}/wp-json/wp/v2/posts/${ctx.reprovisionPostId}`);
  expect(
    response.ok(),
    `再プロビジョニング後に投稿が取得できませんでした (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const body = (await response.json()) as { title: { rendered: string } };
  expect(body.title.rendered).toBe(ctx.reprovisionPostTitle);
});

// ------------------------------------------------------- 後片付け

After({ tags: '@project' }, async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  await deleteFixtureSiteIfPresent(request, headers, ctx.adoptSourceSiteId as number | undefined);
  await deleteFixtureSiteIfPresent(request, headers, ctx.adoptedSiteId as number | undefined);
  await deleteFixtureSiteIfPresent(request, headers, ctx.reprovisionSiteId as number | undefined);
});
