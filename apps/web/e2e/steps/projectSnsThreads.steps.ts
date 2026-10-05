import { execFileSync } from 'node:child_process';
import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * プロジェクト設定画面での Threads アカウントの接続・切断と、プラグインからの告知(issue #1579)のステップ定義。
 * 「ステップ定義ファイルは相乗りしない」方針のため、フィクスチャのヘルパーはここに閉じて持つ。
 *
 * Threads の認可画面と API は threads-stub(ホストからは 127.0.0.1:18090、コンテナからは http://threads-stub:8080)。
 * アプリ(project-service)は THREADS_API_BASE_URL / THREADS_AUTHORIZE_URL で、プラグインは wp-config.php の定数
 * LETSBLOG_THREADS_API_BASE_URL でスタブへ向ける。本番サイトの状態の確認と細工は wp-cli(docker exec)で行う。
 */

const WORDPRESS_CONTAINER = 'lbs-wordpress';
const MYSQL_CONTAINER = 'lbs-mysql';
const THREADS_STUB_URL = 'http://127.0.0.1:18090';
const THREADS_STUB_URL_FROM_WORDPRESS = 'http://threads-stub:8080';
const APP_ID = 'e2e-threads-app-id';
const APP_SECRET = 'e2e-threads-app-secret';
/** スタブが返す固定のトークンとアプリの秘密。アプリのどこにも、プラグインの DB にも平文で残ってはならない。 */
const SECRET_STRINGS = ['e2e-threads-short', 'e2e-threads-long', 'e2e-threads-refreshed', APP_SECRET];
/** スタブの認可で得られる Threads のユーザー ID(プラグインの設定を直接書き換えるときに使う)。 */
const THREADS_USER_ID = '17841400000000001';
const POLL = { timeout: 120_000, intervals: [1_000, 2_000, 3_000] };

interface ThreadsSite {
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

function site(ctx: Record<string, unknown>): ThreadsSite {
  return ctx.threadsSite as ThreadsSite;
}

function projectId(ctx: Record<string, unknown>): number {
  return ctx.threadsProjectId as number;
}

async function threadsStub(path: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(`${THREADS_STUB_URL}${path}`, init);
  expect(response.ok, `threads スタブ ${path} が失敗しました (status=${response.status})。threads-stub が起動していますか`).toBe(true);
  return response;
}

async function stubState(): Promise<{ posts: string[]; refreshes: string[] }> {
  return (await (await threadsStub('/__control/state')).json()) as { posts: string[]; refreshes: string[] };
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
    (entry) => entry.sns === 'threads'
  );
}

function threadsStatusOf(siteKey: string): { status: string; account_name: string | null } {
  const out = wpCli(siteKey, ['letsblog', 'sns', 'status']);
  return (JSON.parse(out) as Record<string, { status: string; account_name: string | null }>).threads;
}

async function buildProjectWithProductionSite(ctx: Record<string, unknown>, request: APIRequestContext): Promise<void> {
  const headers = await adminHeaders(request);
  const suffix = uniqueSuffix();
  await threadsStub('/__control/reset', { method: 'POST' });

  const project = await request.post('/api/projects', {
    headers,
    data: { name: `E2E 1579 ${suffix}`, slug: `e2e1579-${suffix}` },
  });
  expect(project.ok(), `プロジェクトの作成に失敗しました (status=${project.status()}): ${await project.text()}`).toBe(true);
  ctx.threadsProjectId = ((await project.json()) as { id: number }).id;

  const siteKey = `e2e1579${suffix}`.toLowerCase().replace(/[^a-z0-9]/g, '');
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: `E2E 1579 ${suffix}`,
      siteKey,
      title: `E2E 1579 ${suffix}`,
      adminUser: 'e2e1579admin',
      adminEmail: `e2e-1579-${suffix}@letsblog.local`,
      adminPassword: `E2e1579#Sns${suffix}`,
      locale: 'ja',
    },
    timeout: 120_000,
  });
  expect(created.ok(), `managedサイトの作成に失敗しました (status=${created.status()}): ${await created.text()}`).toBe(true);
  const id = ((await created.json()) as { id: number }).id;
  // プラグインの Threads の接続先をスタブにする(アプリから送る設定では変えられない)。
  wpCli(siteKey, ['config', 'set', 'LETSBLOG_THREADS_API_BASE_URL', THREADS_STUB_URL_FROM_WORDPRESS, '--type=constant']);
  ctx.threadsSite = { id, siteKey, title: `E2E-1579-${suffix}`, suffix } satisfies ThreadsSite;

  const bound = await request.post(`/api/projects/${ctx.threadsProjectId as number}/environments`, {
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
  await expect(page.getByRole('heading', { name: 'Threads', exact: true })).toBeVisible({ timeout: 30000 });
}

/** 画面から OAuth のアプリ情報を入力して Threads と接続し、設定画面へ戻るまで待つ。 */
async function connectThroughUi(ctx: Record<string, unknown>, page: Page): Promise<void> {
  await openSnsPage(ctx, page);
  await page.locator('input[name="threadsAppId"]').fill(APP_ID);
  await page.locator('input[name="threadsAppSecret"]').fill(APP_SECRET);
  await page.getByRole('button', { name: 'Threads と接続' }).click();
  await page.waitForURL(new RegExp(`/projects/${projectId(ctx)}/settings/sns\\?`), { timeout: 60000 });
  await expect(page.getByTestId('sns-threads-status')).toContainText('接続済み', { timeout: 30000 });
}

/** プラグインの Threads の設定を、発行・期限の時刻を指定して書き直す(wp-cli の標準入力。アプリが送るのと同じ形)。 */
function setTokenTimes(siteKey: string, issuedAgoSeconds: number, expiresInSeconds: number): void {
  const now = Math.floor(Date.now() / 1000);
  wpCli(
    siteKey,
    ['letsblog', 'sns', 'config', 'set'],
    JSON.stringify({
      sns: 'threads',
      access_token: 'e2e-threads-long',
      user_id: THREADS_USER_ID,
      issued_at: now - issuedAgoSeconds,
      expires_at: now + expiresInSeconds,
      account_name: 'lets_blog_e2e',
    })
  );
}

const DAY = 24 * 3600;

Given('Threads 接続検証用に本番サイトを持つプロジェクトを構築しておく', async ({ ctx, request }) => {
  await buildProjectWithProductionSite(ctx, request);
});

Given('Threads 接続検証のプロジェクトで Threads を接続しておく', async ({ ctx, page }) => {
  await connectThroughUi(ctx, page);
});

Given('Threads 接続検証の本番サイトのトークンが発行から55日で期限まで5日になっている', async ({ ctx }) => {
  setTokenTimes(site(ctx).siteKey, 55 * DAY, 5 * DAY);
});

Given('Threads 接続検証の本番サイトのトークンが発行から1時間で期限まで3日になっている', async ({ ctx }) => {
  setTokenTimes(site(ctx).siteKey, 3600, 3 * DAY);
});

Given('Threads 接続検証の本番サイトのトークンが期限切れになっている', async ({ ctx }) => {
  setTokenTimes(site(ctx).siteKey, 61 * DAY, -60);
});

When(
  'Threads 接続検証のプロジェクトの SNS 告知設定画面で OAuth のアプリ情報を入力して Threads と接続する',
  async ({ ctx, page }) => {
    await connectThroughUi(ctx, page);
  }
);

When('SNS 告知設定画面で Threads を切断する', async ({ ctx, page }) => {
  await openSnsPage(ctx, page);
  await page.getByRole('button', { name: 'Threads を切断' }).click();
  await expect(page.getByText('Threads の接続を切断しました。')).toBeVisible({ timeout: 30000 });
});

When('SNS 告知設定画面で Threads の投稿テストを行う', async ({ ctx, page }) => {
  await openSnsPage(ctx, page);
  await page.getByRole('button', { name: 'Threads 投稿テスト' }).click();
  // 成功・失敗のどちらでも、履歴に1件増えるまで待つ。
  await expect.poll(() => history(site(ctx).siteKey).length, POLL).toBeGreaterThanOrEqual(1);
  await page.goto(snsPage(ctx));
});

When("Threads 接続検証の本番サイトへ Let's Blog から記事を即時公開する", async ({ ctx, request }) => {
  const s = site(ctx);
  const response = await request.post('/api/posts/publish', {
    headers: await adminHeaders(request),
    multipart: {
      site: s.siteKey,
      title: s.title,
      slug: `e2e-1579-${s.suffix}`,
      status: 'publish',
      markdown: '本文です\n',
    },
    timeout: 120_000,
  });
  expect(response.ok(), `記事の投稿に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  ctx.threadsPostId = ((await response.json()) as { wpPostId: string }).wpPostId;
});

Then('SNS 告知設定画面の Threads にアカウント{string}の接続済みが表示される', async ({ page }, account: string) => {
  const status = page.getByTestId('sns-threads-status');
  await expect(status).toContainText('接続済み', { timeout: 30000 });
  await expect(status).toContainText(account);
});

Then('本番サイトの wp letsblog sns status が Threads の接続済みを返す', async ({ ctx }) => {
  const status = threadsStatusOf(site(ctx).siteKey);
  expect(status.status).toBe('接続済み');
  expect(status.account_name).toBe('lets_blog_e2e');
});

Then('本番サイトの wp letsblog sns status が Threads の未設定を返す', async ({ ctx }) => {
  expect(threadsStatusOf(site(ctx).siteKey).status).toBe('未設定');
});

Then('アプリの API の応答に Threads のトークンが含まれない', async ({ ctx, request }) => {
  const response = await request.get(`/api/projects/${projectId(ctx)}/sns/threads`, { headers: await adminHeaders(request) });
  expect(response.ok()).toBe(true);
  const body = await response.text();
  for (const secret of SECRET_STRINGS) {
    expect(body, `API の応答に ${secret} が含まれています`).not.toContain(secret);
  }
});

Then('アプリの DB に Threads のトークンが保存されていない', async () => {
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

Then('本番サイトの DB に Threads のトークンが平文で保存されていない', async ({ ctx }) => {
  const stored = wpCli(site(ctx).siteKey, ['option', 'get', 'letsblog_sns_config', '--format=json']);
  expect(stored.length, 'プラグインの設定が保存されていません').toBeGreaterThan(0);
  const printedLog = wpCli(site(ctx).siteKey, ['letsblog', 'sns', 'log', '--format=json']);
  for (const secret of SECRET_STRINGS) {
    expect(stored.includes(secret), `プラグインの DB に ${secret} が平文で保存されています`).toBe(false);
    expect(printedLog.includes(secret), `告知履歴に ${secret} が含まれています`).toBe(false);
  }
});

Then('SNS 告知設定画面の Threads の接続状態は{string}と表示される', async ({ page }, text: string) => {
  await expect(page.getByTestId('sns-threads-status')).toContainText(text, { timeout: 30000 });
});

Then('Threads のスタブにテスト投稿が1回だけ届いている', async () => {
  await expect.poll(async () => (await stubState()).posts.length, POLL).toBeGreaterThanOrEqual(1);
  const { posts } = await stubState();
  expect(posts).toHaveLength(1);
  expect(posts[0]).toContain('接続テスト');
});

Then('Threads のスタブにタイトルと URL が1回だけ投稿されている', async ({ ctx }) => {
  const s = site(ctx);
  await expect.poll(async () => (await stubState()).posts.length, POLL).toBeGreaterThanOrEqual(1);
  const { posts } = await stubState();
  expect(posts).toHaveLength(1);
  const permalink = wpCli(s.siteKey, ['eval', `echo get_permalink(${Number(ctx.threadsPostId)});`]);
  expect(posts[0]).toBe(`${s.title}\n${permalink}`);
});

Then('Threads のスタブでトークンが1回更新されている', async () => {
  expect((await stubState()).refreshes).toEqual(['e2e-threads-long']);
});

Then('Threads のスタブには何も投稿されず、更新もされていない', async ({ ctx }) => {
  // 履歴に失敗が残った後で確かめる(投稿の試みが終わっている)。
  await expect.poll(() => history(site(ctx).siteKey).length, POLL).toBeGreaterThanOrEqual(1);
  const state = await stubState();
  expect(state.posts).toEqual([]);
  expect(state.refreshes).toEqual([]);
});

Then('Threads の告知履歴にテスト投稿が成功として表示される', async ({ page }) => {
  const entry = page.getByTestId('sns-threads-log-entry').first();
  await expect(entry).toContainText('テスト投稿', { timeout: 30000 });
  await expect(entry).toContainText('成功');
});

Then('Threads の告知履歴に記事の公開が成功として記録されている', async ({ ctx }) => {
  const s = site(ctx);
  await expect.poll(() => history(s.siteKey).length, POLL).toBe(1);
  expect(history(s.siteKey)[0]).toMatchObject({
    sns: 'threads',
    kind: 'publish',
    post_id: Number(ctx.threadsPostId),
    success: true,
    error: null,
  });
});

Then('Threads の告知履歴に失敗の理由として{string}が表示される', async ({ ctx, page }, text: string) => {
  const entry = history(site(ctx).siteKey).at(-1);
  expect(entry?.success).toBe(false);
  expect(entry?.error ?? '').toContain(text);
  await page.goto(snsPage(ctx));
  const row = page.getByTestId('sns-threads-log-entry').first();
  await expect(row).toContainText('失敗', { timeout: 30000 });
  await expect(row).toContainText(text);
});

After({ tags: '@project' }, async ({ ctx, request }) => {
  if (ctx.threadsProjectId === undefined) {
    return;
  }
  const headers = await adminHeaders(request);
  await threadsStub('/__control/reset', { method: 'POST' }).catch(() => undefined);
  await request.delete(`/api/projects/${ctx.threadsProjectId as number}`, { headers });
  const s = ctx.threadsSite as ThreadsSite | undefined;
  if (s) {
    await request.delete(`/api/sites/${s.id}`, { headers });
  }
});
