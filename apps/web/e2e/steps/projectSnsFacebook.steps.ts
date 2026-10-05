import { execFileSync } from 'node:child_process';
import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * プロジェクト設定画面での Facebook ページの接続・切断と、プラグインからの告知(issue #1580)のステップ定義。
 * 「ステップ定義ファイルは相乗りしない」方針のため、フィクスチャのヘルパーはここに閉じて持つ。
 *
 * Facebook の認可画面と API は facebook-stub(ホストからは 127.0.0.1:18091、コンテナからは http://facebook-stub:8080)。
 * アプリ(project-service)は FACEBOOK_API_BASE_URL / FACEBOOK_AUTHORIZE_URL で、プラグインは wp-config.php の定数
 * LETSBLOG_FACEBOOK_API_BASE_URL でスタブへ向ける。本番サイトの状態の確認と細工は wp-cli(docker exec)で行う。
 */

const WORDPRESS_CONTAINER = 'lbs-wordpress';
const MYSQL_CONTAINER = 'lbs-mysql';
const FACEBOOK_STUB_URL = 'http://127.0.0.1:18091';
const FACEBOOK_STUB_URL_FROM_WORDPRESS = 'http://facebook-stub:8080';
const APP_ID = 'e2e-facebook-app-id';
const APP_SECRET = 'e2e-facebook-app-secret';
/** スタブが返す固定のトークンとアプリの秘密。アプリのどこにも、プラグインの DB にも平文で残ってはならない。 */
const SECRET_STRINGS = [
  'e2e-facebook-user-short',
  'e2e-facebook-user-long',
  'e2e-facebook-page-token',
  'e2e-facebook-page-token-noperm',
  APP_SECRET,
];
/** スタブの「管理しているページ」のうち、投稿できるページの ID(プラグインの設定を直接書き換えるときに使う)。 */
const FACEBOOK_PAGE_ID = '100000000000001';
const POLL = { timeout: 120_000, intervals: [1_000, 2_000, 3_000] };

interface FacebookSite {
  id: number;
  siteKey: string;
  title: string;
  suffix: string;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

function docker(args: string[], input?: string): string {
  // 共有の開発 DB は mysqldump が既定の maxBuffer(1MB)を超える(ENOBUFS)ため、上限を引き上げる。
  return execFileSync('docker', args, {
    encoding: 'utf8',
    timeout: 180_000,
    input: input ?? '',
    maxBuffer: 256 * 1024 * 1024,
  }).trim();
}

function wpCli(siteKey: string, args: string[], input?: string): string {
  return docker(
    ['exec', '-i', WORDPRESS_CONTAINER, 'wp', '--allow-root', `--path=/var/www/html/sites/${siteKey}`, ...args],
    input
  );
}

function site(ctx: Record<string, unknown>): FacebookSite {
  return ctx.facebookSite as FacebookSite;
}

function projectId(ctx: Record<string, unknown>): number {
  return ctx.facebookProjectId as number;
}

async function facebookStub(path: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(`${FACEBOOK_STUB_URL}${path}`, init);
  expect(response.ok, `facebook スタブ ${path} が失敗しました (status=${response.status})。facebook-stub が起動していますか`).toBe(true);
  return response;
}

interface FeedPost {
  page_id: string;
  message: string | null;
  link: string | null;
}

async function stubPosts(): Promise<FeedPost[]> {
  return ((await (await facebookStub('/__control/state')).json()) as { posts: FeedPost[] }).posts;
}

interface LogEntry {
  sns: string;
  kind: string;
  post_id: number | null;
  success: boolean;
  error: string | null;
}

function history(siteKey: string): LogEntry[] {
  return (JSON.parse(wpCli(siteKey, ['letsblog', 'sns', 'log', '--format=json'])) as LogEntry[]).filter(
    (entry) => entry.sns === 'facebook'
  );
}

function facebookStatusOf(siteKey: string): { status: string; account_name: string | null } {
  const out = wpCli(siteKey, ['letsblog', 'sns', 'status']);
  return (JSON.parse(out) as Record<string, { status: string; account_name: string | null }>).facebook;
}

async function buildProjectWithProductionSite(ctx: Record<string, unknown>, request: APIRequestContext): Promise<void> {
  const headers = await adminHeaders(request);
  const suffix = uniqueSuffix();
  await facebookStub('/__control/reset', { method: 'POST' });

  const project = await request.post('/api/projects', {
    headers,
    data: { name: `E2E 1580 ${suffix}`, slug: `e2e1580-${suffix}` },
  });
  expect(project.ok(), `プロジェクトの作成に失敗しました (status=${project.status()}): ${await project.text()}`).toBe(true);
  ctx.facebookProjectId = ((await project.json()) as { id: number }).id;

  const siteKey = `e2e1580${suffix}`.toLowerCase().replace(/[^a-z0-9]/g, '');
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: `E2E 1580 ${suffix}`,
      siteKey,
      title: `E2E 1580 ${suffix}`,
      adminUser: 'e2e1580admin',
      adminEmail: `e2e-1580-${suffix}@letsblog.local`,
      adminPassword: `E2e1580#Sns${suffix}`,
      locale: 'ja',
    },
    timeout: 120_000,
  });
  expect(created.ok(), `managedサイトの作成に失敗しました (status=${created.status()}): ${await created.text()}`).toBe(true);
  const id = ((await created.json()) as { id: number }).id;
  // プラグインの Facebook の接続先をスタブにする(アプリから送る設定では変えられない)。
  wpCli(siteKey, ['config', 'set', 'LETSBLOG_FACEBOOK_API_BASE_URL', FACEBOOK_STUB_URL_FROM_WORDPRESS, '--type=constant']);
  ctx.facebookSite = { id, siteKey, title: `E2E-1580-${suffix}`, suffix } satisfies FacebookSite;

  const bound = await request.post(`/api/projects/${ctx.facebookProjectId as number}/environments`, {
    headers,
    data: { environment: 'production', siteId: id },
  });
  expect(bound.ok(), `本番環境への紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`).toBe(true);
}

function snsPage(ctx: Record<string, unknown>): string {
  return `/projects/${projectId(ctx)}/settings/sns`;
}

async function openSnsPage(ctx: Record<string, unknown>, page: Page): Promise<void> {
  await page.goto(snsPage(ctx));
  await expect(page.getByRole('heading', { name: 'Facebook', exact: true })).toBeVisible({ timeout: 30000 });
}

/** 画面から OAuth のアプリ情報を入力し、認可のあとに投稿先のページを選んで接続し、設定画面へ戻るまで待つ。 */
async function connectThroughUi(ctx: Record<string, unknown>, page: Page, pageName: string): Promise<void> {
  await openSnsPage(ctx, page);
  await page.locator('input[name="facebookAppId"]').fill(APP_ID);
  await page.locator('input[name="facebookAppSecret"]').fill(APP_SECRET);
  await page.getByRole('button', { name: 'Facebook と接続' }).click();
  // 認可画面(スタブ)は利用者の操作なしでコールバックへ戻す。戻った設定画面に、管理しているページの選択欄が出る。
  const select = page.getByTestId('sns-facebook-page-select');
  await expect(select).toBeVisible({ timeout: 60000 });
  await select.getByLabel(pageName).check();
  await page.getByRole('button', { name: 'このページを接続' }).click();
  await page.waitForURL(new RegExp(`/projects/${projectId(ctx)}/settings/sns\\?connected=facebook`), { timeout: 60000 });
  await expect(page.getByTestId('sns-facebook-status')).toContainText('接続済み', { timeout: 30000 });
}

Given('Facebook 接続検証用に本番サイトを持つプロジェクトを構築しておく', async ({ ctx, request }) => {
  await buildProjectWithProductionSite(ctx, request);
});

Given('Facebook 接続検証のプロジェクトで Facebook ページ{string}を接続しておく', async ({ ctx, page }, pageName: string) => {
  await connectThroughUi(ctx, page, pageName);
});

Given('Facebook 接続検証の本番サイトのページのトークンが失効している', async ({ ctx }) => {
  // アプリが送るのと同じ形(wp-cli の標準入力)で、スタブが知らないトークンに書き直す。
  wpCli(
    site(ctx).siteKey,
    ['letsblog', 'sns', 'config', 'set'],
    JSON.stringify({
      sns: 'facebook',
      access_token: 'e2e-facebook-page-token-expired',
      page_id: FACEBOOK_PAGE_ID,
      account_name: "Let's Blog E2E ページ",
    })
  );
});

When(
  'Facebook 接続検証のプロジェクトの SNS 告知設定画面で OAuth のアプリ情報を入力し、ページ{string}を選んで Facebook と接続する',
  async ({ ctx, page }, pageName: string) => {
    await connectThroughUi(ctx, page, pageName);
  }
);

When('SNS 告知設定画面で Facebook を切断する', async ({ ctx, page }) => {
  await openSnsPage(ctx, page);
  await page.getByRole('button', { name: 'Facebook を切断' }).click();
  await expect(page.getByText('Facebook の接続を切断しました。')).toBeVisible({ timeout: 30000 });
});

When('SNS 告知設定画面で Facebook の投稿テストを行う', async ({ ctx, page }) => {
  await openSnsPage(ctx, page);
  await page.getByRole('button', { name: 'Facebook 投稿テスト' }).click();
  // 成功・失敗のどちらでも、履歴に1件増えるまで待つ。
  await expect.poll(() => history(site(ctx).siteKey).length, POLL).toBeGreaterThanOrEqual(1);
  await page.goto(snsPage(ctx));
});

When("Facebook 接続検証の本番サイトへ Let's Blog から記事を即時公開する", async ({ ctx, request }) => {
  const s = site(ctx);
  const response = await request.post('/api/posts/publish', {
    headers: await adminHeaders(request),
    multipart: {
      site: s.siteKey,
      title: s.title,
      slug: `e2e-1580-${s.suffix}`,
      status: 'publish',
      markdown: '本文です\n',
    },
    timeout: 120_000,
  });
  expect(response.ok(), `記事の投稿に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  ctx.facebookPostId = ((await response.json()) as { wpPostId: string }).wpPostId;
});

Then('SNS 告知設定画面の Facebook にページ{string}の接続済みが表示される', async ({ page }, pageName: string) => {
  const status = page.getByTestId('sns-facebook-status');
  await expect(status).toContainText('接続済み', { timeout: 30000 });
  await expect(status).toContainText(pageName);
});

Then('本番サイトの wp letsblog sns status が Facebook の接続済みを返す', async ({ ctx }) => {
  const status = facebookStatusOf(site(ctx).siteKey);
  expect(status.status).toBe('接続済み');
  expect(status.account_name).toBe("Let's Blog E2E ページ");
});

Then('本番サイトの wp letsblog sns status が Facebook の未設定を返す', async ({ ctx }) => {
  expect(facebookStatusOf(site(ctx).siteKey).status).toBe('未設定');
});

Then('アプリの API の応答に Facebook のトークンが含まれない', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const response = await request.get(`/api/projects/${projectId(ctx)}/sns/facebook`, { headers });
  expect(response.ok()).toBe(true);
  const body = await response.text();
  for (const secret of SECRET_STRINGS) {
    expect(body, `API の応答に ${secret} が含まれています`).not.toContain(secret);
  }
});

Then('アプリの DB に Facebook のトークンが保存されていない', async () => {
  const databases = docker([
    'exec', MYSQL_CONTAINER, 'sh', '-c',
    `mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SHOW DATABASES LIKE 'lbs\\\\_%'" 2>/dev/null`,
  ])
    .split('\n')
    .filter((name) => name.length > 0);
  expect(databases.length, 'lbs_* のスキーマが見つかりません').toBeGreaterThan(0);
  const dump = docker([
    'exec', MYSQL_CONTAINER, 'sh', '-c',
    `mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --no-tablespaces --databases ${databases.join(' ')} 2>/dev/null`,
  ]);
  for (const secret of SECRET_STRINGS) {
    expect(dump.includes(secret), `アプリの DB に ${secret} が保存されています`).toBe(false);
  }
});

Then('本番サイトの DB に Facebook のトークンが平文で保存されていない', async ({ ctx }) => {
  const stored = wpCli(site(ctx).siteKey, ['option', 'get', 'letsblog_sns_config', '--format=json']);
  expect(stored.length, 'プラグインの設定が保存されていません').toBeGreaterThan(0);
  const printedLog = wpCli(site(ctx).siteKey, ['letsblog', 'sns', 'log', '--format=json']);
  for (const secret of SECRET_STRINGS) {
    expect(stored.includes(secret), `プラグインの DB に ${secret} が平文で保存されています`).toBe(false);
    expect(printedLog.includes(secret), `告知履歴に ${secret} が含まれています`).toBe(false);
  }
});

Then('SNS 告知設定画面の Facebook の接続状態は{string}と表示される', async ({ page }, text: string) => {
  await expect(page.getByTestId('sns-facebook-status')).toContainText(text, { timeout: 30000 });
});

Then('Facebook のスタブの選んだページのフィードにテスト投稿が1回だけ届いている', async () => {
  await expect.poll(async () => (await stubPosts()).length, POLL).toBeGreaterThanOrEqual(1);
  const posts = await stubPosts();
  expect(posts).toHaveLength(1);
  // 投稿先は選んだページ。個人アカウント(/me)ではない。
  expect(posts[0].page_id).toBe(FACEBOOK_PAGE_ID);
  expect(posts[0].message).toContain('接続テスト');
});

Then('Facebook のスタブの選んだページのフィードにタイトルとリンクが1回だけ投稿されている', async ({ ctx }) => {
  const s = site(ctx);
  await expect.poll(async () => (await stubPosts()).length, POLL).toBeGreaterThanOrEqual(1);
  const posts = await stubPosts();
  expect(posts).toHaveLength(1);
  const permalink = wpCli(s.siteKey, ['eval', `echo get_permalink(${Number(ctx.facebookPostId)});`]);
  expect(posts[0]).toEqual({ page_id: FACEBOOK_PAGE_ID, message: s.title, link: permalink });
});

Then('Facebook のスタブには何も投稿されていない', async ({ ctx }) => {
  // 履歴に失敗が残った後で確かめる(投稿の試みが終わっている)。
  await expect.poll(() => history(site(ctx).siteKey).length, POLL).toBeGreaterThanOrEqual(1);
  expect(await stubPosts()).toEqual([]);
});

Then('Facebook の告知履歴にテスト投稿が成功として表示される', async ({ page }) => {
  const entry = page.getByTestId('sns-facebook-log-entry').first();
  await expect(entry).toContainText('テスト投稿', { timeout: 30000 });
  await expect(entry).toContainText('成功');
});

Then('Facebook の告知履歴に記事の公開が成功として記録されている', async ({ ctx }) => {
  const s = site(ctx);
  await expect.poll(() => history(s.siteKey).length, POLL).toBe(1);
  expect(history(s.siteKey)[0]).toMatchObject({
    sns: 'facebook',
    kind: 'publish',
    post_id: Number(ctx.facebookPostId),
    success: true,
    error: null,
  });
});

Then('Facebook の告知履歴に失敗の理由として{string}が表示される', async ({ ctx, page }, text: string) => {
  const entry = history(site(ctx).siteKey).at(-1);
  expect(entry?.success).toBe(false);
  expect(entry?.error ?? '').toContain(text);
  await page.goto(snsPage(ctx));
  const row = page.getByTestId('sns-facebook-log-entry').first();
  await expect(row).toContainText('失敗', { timeout: 30000 });
  await expect(row).toContainText(text);
});

After({ tags: '@project' }, async ({ ctx, request }) => {
  if (ctx.facebookProjectId === undefined) {
    return;
  }
  const headers = await adminHeaders(request);
  await facebookStub('/__control/reset', { method: 'POST' }).catch(() => undefined);
  await request.delete(`/api/projects/${ctx.facebookProjectId as number}`, { headers });
  const s = ctx.facebookSite as FacebookSite | undefined;
  if (s) {
    await request.delete(`/api/sites/${s.id}`, { headers });
  }
});
