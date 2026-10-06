import { execFileSync } from 'node:child_process';
import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * プロジェクト設定画面での X アカウントの接続(issue #1574)のステップ定義。
 * 「ステップ定義ファイルは相乗りしない」方針のため、フィクスチャのヘルパーはここに閉じて持つ。
 *
 * X の認可画面と API は x-stub(ホストからは 127.0.0.1:18088、コンテナからは http://x-stub:8080)。
 * アプリ(project-service)は X_API_BASE_URL / X_AUTHORIZE_URL で、プラグインは wp-config.php の定数
 * LETSBLOG_X_API_BASE_URL でスタブへ向ける。本番サイトの状態の確認と細工は wp-cli(docker exec)で行う。
 */

const WORDPRESS_CONTAINER = 'lbs-wordpress';
const MYSQL_CONTAINER = 'lbs-mysql';
const X_STUB_URL = 'http://127.0.0.1:18088';
const X_STUB_URL_FROM_WORDPRESS = 'http://x-stub:8080';
const CLIENT_ID = 'e2e-client-id';
const CLIENT_SECRET = 'e2e-client-secret';
/** スタブが返す固定のトークン。アプリのどこにも残ってはならない。 */
const SECRET_STRINGS = ['e2e-x-access-valid', 'e2e-x-refresh-valid', CLIENT_SECRET];

interface XSite {
  id: number;
  siteKey: string;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

function docker(args: string[]): string {
  // 共有の開発 DB は mysqldump が既定の maxBuffer(1MB)を超える(ENOBUFS)ため、上限を引き上げる。
  return execFileSync('docker', args, {
    encoding: 'utf8',
    timeout: 180_000,
    maxBuffer: 256 * 1024 * 1024,
  }).trim();
}

function wpCli(siteKey: string, args: string[]): string {
  return docker(['exec', WORDPRESS_CONTAINER, 'wp', '--allow-root', `--path=/var/www/html/sites/${siteKey}`, ...args]);
}

function inSite(siteKey: string, command: string): string {
  return docker(['exec', WORDPRESS_CONTAINER, 'sh', '-c', `cd /var/www/html/sites/${siteKey} && ${command}`]);
}

function sites(ctx: Record<string, unknown>): XSite[] {
  return (ctx.xSites as XSite[] | undefined) ?? [];
}

function productionSite(ctx: Record<string, unknown>): XSite {
  return ctx.xProductionSite as XSite;
}

function projectId(ctx: Record<string, unknown>): number {
  return ctx.xProjectId as number;
}

async function createProject(request: APIRequestContext, headers: Record<string, string>): Promise<number> {
  const suffix = uniqueSuffix();
  const response = await request.post('/api/projects', {
    headers,
    data: { name: `E2E 1574 ${suffix}`, slug: `e2e1574-${suffix}` },
  });
  expect(response.ok(), `プロジェクトの作成に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  return ((await response.json()) as { id: number }).id;
}

async function createManagedSite(request: APIRequestContext, headers: Record<string, string>): Promise<XSite> {
  const suffix = uniqueSuffix();
  const siteKey = `e2e1574${suffix}`.toLowerCase().replace(/[^a-z0-9]/g, '');
  const response = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: `E2E 1574 ${suffix}`,
      siteKey,
      title: `E2E 1574 ${suffix}`,
      adminUser: 'e2e1574admin',
      adminEmail: `e2e-1574-${suffix}@letsblog.local`,
      adminPassword: `E2e1574#Sns${suffix}`,
      locale: 'ja',
    },
    timeout: 120_000,
  });
  expect(response.ok(), `managedサイトの作成に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  const id = ((await response.json()) as { id: number }).id;
  // プラグインの X の接続先をスタブにする(アプリから送る設定では変えられない)。
  wpCli(siteKey, ['config', 'set', 'LETSBLOG_X_API_BASE_URL', X_STUB_URL_FROM_WORDPRESS, '--type=constant']);
  return { id, siteKey };
}

async function bindProduction(
  request: APIRequestContext,
  headers: Record<string, string>,
  project: number,
  siteId: number
): Promise<void> {
  const bound = await request.post(`/api/projects/${project}/environments`, {
    headers,
    data: { environment: 'production', siteId },
  });
  expect(bound.ok(), `本番環境への紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`).toBe(true);
}

function snsPage(ctx: Record<string, unknown>): string {
  return `/projects/${projectId(ctx)}/settings/sns`;
}

async function openSnsPage(ctx: Record<string, unknown>, page: Page): Promise<void> {
  await page.goto(snsPage(ctx));
  await expect(page.getByRole('heading', { name: 'SNS 告知', exact: true })).toBeVisible({ timeout: 30000 });
}

/** 画面から OAuth クライアントを入力して X と接続し、設定画面へ戻るまで待つ。 */
async function connectThroughUi(ctx: Record<string, unknown>, page: Page): Promise<void> {
  await openSnsPage(ctx, page);
  await page.locator('input[name="clientId"]').fill(CLIENT_ID);
  await page.locator('input[name="clientSecret"]').fill(CLIENT_SECRET);
  await page.getByRole('button', { name: 'X と接続' }).click();
  await page.waitForURL(new RegExp(`/projects/${projectId(ctx)}/settings/sns\\?`), { timeout: 60000 });
  await expect(page.getByTestId('sns-x-status')).toContainText('接続済み', { timeout: 30000 });
}

function snsStatusOf(siteKey: string): { status: string; account_name: string | null } {
  const out = wpCli(siteKey, ['letsblog', 'sns', 'status']);
  return (JSON.parse(out) as Record<string, { status: string; account_name: string | null }>).x;
}

async function xStubState(): Promise<{ tweets: string[] }> {
  const response = await fetch(`${X_STUB_URL}/__control/state`);
  return (await response.json()) as { tweets: string[] };
}

async function buildProjectWithProductionSite(ctx: Record<string, unknown>, request: APIRequestContext): Promise<void> {
  const headers = await adminHeaders(request);
  const project = await createProject(request, headers);
  ctx.xProjectId = project;
  const site = await createManagedSite(request, headers);
  ctx.xSites = [site];
  ctx.xProductionSite = site;
  await bindProduction(request, headers, project, site.id);
}

Given('X 接続検証用に本番サイトを持つプロジェクトを構築しておく', async ({ ctx, request }) => {
  await buildProjectWithProductionSite(ctx, request);
});

Given('X 接続検証用に本番サイトを持たないプロジェクトを用意しておく', async ({ ctx, request }) => {
  ctx.xProjectId = await createProject(request, await adminHeaders(request));
});

Given('X 接続検証用に別のマネージドWordPressサイトを構築しておく', async ({ ctx, request }) => {
  const site = await createManagedSite(request, await adminHeaders(request));
  ctx.xSites = [...sites(ctx), site];
  ctx.xOtherSite = site;
});

Given('X 接続検証の本番サイトのプラグインを停止しておく', async ({ ctx }) => {
  wpCli(productionSite(ctx).siteKey, ['plugin', 'deactivate', 'letsblog']);
});

Given('X 接続検証の本番サイトのプラグインの status が壊れた出力を返すようにしておく', async ({ ctx }) => {
  inSite(
    productionSite(ctx).siteKey,
    `sed -i 's/WP_CLI::line(json_encode(\\[/WP_CLI::line("garbage"); WP_CLI::line(json_encode([/' wp-content/plugins/letsblog/letsblog.php`
  );
});

Given('X 接続検証のプロジェクトで X を接続しておく', async ({ ctx, page }) => {
  await connectThroughUi(ctx, page);
});

When(
  'X 接続検証のプロジェクトの SNS 告知設定画面で OAuth クライアントを入力して X と接続する',
  async ({ ctx, page }) => {
    await connectThroughUi(ctx, page);
  }
);

When('X 接続検証のプロジェクトの SNS 告知設定画面を開く', async ({ ctx, page }) => {
  await openSnsPage(ctx, page);
});

When('SNS 告知設定画面でテスト投稿を行う', async ({ ctx, page }) => {
  await openSnsPage(ctx, page);
  ctx.xTweetsBefore = (await xStubState()).tweets.length;
  await page.getByRole('button', { name: 'テスト投稿' }).click();
});

When('X 接続検証のプロジェクトの本番サイトを別のサイトへ変更する', async ({ ctx, request }) => {
  const other = ctx.xOtherSite as XSite;
  await bindProduction(request, await adminHeaders(request), projectId(ctx), other.id);
});

Then('SNS 告知設定画面にアカウント{string}の接続済みが表示される', async ({ page }, account: string) => {
  const status = page.getByTestId('sns-x-status');
  await expect(status).toContainText('接続済み', { timeout: 30000 });
  await expect(status).toContainText(account);
});

Then('本番サイトの wp letsblog sns status が接続済みを返す', async ({ ctx }) => {
  const status = snsStatusOf(productionSite(ctx).siteKey);
  expect(status.status).toBe('接続済み');
  expect(status.account_name).toBe('lets_blog_e2e');
});

Then('アプリの API の応答にトークンが含まれない', async ({ ctx, request }) => {
  const response = await request.get(`/api/projects/${projectId(ctx)}/sns/x`, { headers: await adminHeaders(request) });
  expect(response.ok()).toBe(true);
  const body = await response.text();
  for (const secret of SECRET_STRINGS) {
    expect(body, `API の応答に ${secret} が含まれています`).not.toContain(secret);
  }
});

Then('アプリの DB にトークンが保存されていない', async () => {
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

Then('SNS 告知設定画面に接続できない理由として{string}が表示される', async ({ page }, text: string) => {
  await expect(page.getByTestId('sns-x-reason')).toContainText(text, { timeout: 30000 });
});

Then('X と接続するボタンは押せない', async ({ page }) => {
  await expect(page.getByRole('button', { name: 'X と接続' })).toBeDisabled();
});

Then('本番サイトに X の認証情報が送られていない', async ({ ctx }) => {
  let stored = '';
  try {
    stored = wpCli(productionSite(ctx).siteKey, ['option', 'get', 'letsblog_sns_config', '--format=json']);
  } catch {
    stored = '';
  }
  expect(stored, '本番サイトに SNS の設定が保存されています').toBe('');
});

Then('X のスタブにテスト投稿が届いている', async ({ ctx }) => {
  await expect
    .poll(async () => (await xStubState()).tweets.length, { timeout: 30000 })
    .toBeGreaterThan(ctx.xTweetsBefore as number);
  const tweets = (await xStubState()).tweets;
  expect(tweets.some((text) => text.includes('接続テスト'))).toBe(true);
});

Then('SNS 告知の履歴にテスト投稿が成功として表示される', async ({ page }) => {
  const entry = page.getByTestId('sns-x-log-entry').first();
  await expect(entry).toContainText('テスト投稿', { timeout: 30000 });
  await expect(entry).toContainText('成功');
});

Then('SNS 告知設定画面の接続状態は{string}と表示される', async ({ page }, text: string) => {
  await expect(page.getByTestId('sns-x-status')).toContainText(text, { timeout: 30000 });
});

Then('SNS 告知設定画面の告知履歴は{string}と表示される', async ({ page }, text: string) => {
  await expect(page.getByTestId('sns-x-log')).toContainText(text, { timeout: 30000 });
});

Then('SNS 告知設定画面の見出しが表示されている', async ({ page }) => {
  await expect(page.getByRole('heading', { name: 'SNS 告知', exact: true })).toBeVisible();
});

Then('旧い本番サイトの wp letsblog sns status が未設定を返す', async ({ ctx }) => {
  expect(snsStatusOf(productionSite(ctx).siteKey).status).toBe('未設定');
});

After({ tags: '@project' }, async ({ ctx, request }) => {
  if (ctx.xProjectId === undefined) {
    return;
  }
  const headers = await adminHeaders(request);
  await request.delete(`/api/projects/${ctx.xProjectId as number}`, { headers });
  for (const site of sites(ctx)) {
    await request.delete(`/api/sites/${site.id}`, { headers });
  }
});
