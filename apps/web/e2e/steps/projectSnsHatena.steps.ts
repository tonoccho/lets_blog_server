import { execFileSync } from 'node:child_process';
import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * プロジェクト設定画面でのはてなブックマークの接続・切断と、プラグインからの告知(issue #1582)のステップ定義。
 * 「ステップ定義ファイルは相乗りしない」方針のため、フィクスチャのヘルパーはここに閉じて持つ。
 *
 * はてなブックマークの認可画面と API は hatena-stub(ホストからは 127.0.0.1:18093、コンテナからは http://hatena-stub:8080)。
 * スタブは OAuth 1.0a の署名を実際に検証するので、署名できていなければ接続も投稿も成功しない。
 * アプリ(project-service)は HATENA_* の環境変数 で、プラグインは wp-config.php の定数
 * LETSBLOG_HATENA_API_BASE_URL でスタブへ向ける。本番サイトの状態の確認と細工は wp-cli(docker exec)で行う。
 */

const WORDPRESS_CONTAINER = 'lbs-wordpress';
const MYSQL_CONTAINER = 'lbs-mysql';
const HATENA_STUB_URL = 'http://127.0.0.1:18093';
const HATENA_STUB_URL_FROM_WORDPRESS = 'http://hatena-stub:8080';
const CONSUMER_KEY = 'e2e-hatena-consumer-key';
const CONSUMER_SECRET = 'e2e-hatena-consumer-secret';
/** スタブが返す固定のトークンとアプリの秘密。アプリのどこにも、プラグインの DB にも平文で残ってはならない。 */
const SECRET_STRINGS = [
  CONSUMER_SECRET,
  'e2e-hatena-token',
  'e2e-hatena-token-secret',
  'e2e-hatena-request-token',
  'e2e-hatena-request-secret',
  'e2e-hatena-verifier',
  'e2e-hatena-revoked',
  'e2e-hatena-revoked-secret',
];
const POLL = { timeout: 120_000, intervals: [1_000, 2_000, 3_000] };

interface HatenaSite {
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

function site(ctx: Record<string, unknown>): HatenaSite {
  return ctx.hatenaSite as HatenaSite;
}

function projectId(ctx: Record<string, unknown>): number {
  return ctx.hatenaProjectId as number;
}

async function hatenaStub(path: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(`${HATENA_STUB_URL}${path}`, init);
  expect(response.ok, `hatena スタブ ${path} が失敗しました (status=${response.status})。hatena-stub が起動していますか`).toBe(true);
  return response;
}

interface StubBookmark {
  url: string;
  comment: string;
}

async function stubState(): Promise<{ bookmarks: StubBookmark[] }> {
  return (await (await hatenaStub('/__control/state')).json()) as { bookmarks: StubBookmark[] };
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
    (entry) => entry.sns === 'hatena'
  );
}

function hatenaStatusOf(siteKey: string): { status: string; account_name: string | null } {
  const out = wpCli(siteKey, ['letsblog', 'sns', 'status']);
  return (JSON.parse(out) as Record<string, { status: string; account_name: string | null }>).hatena;
}

async function buildProjectWithProductionSite(ctx: Record<string, unknown>, request: APIRequestContext): Promise<void> {
  const headers = await adminHeaders(request);
  const suffix = uniqueSuffix();
  await hatenaStub('/__control/reset', { method: 'POST' });

  const project = await request.post('/api/projects', {
    headers,
    data: { name: `E2E 1582 ${suffix}`, slug: `e2e1582-${suffix}` },
  });
  expect(project.ok(), `プロジェクトの作成に失敗しました (status=${project.status()}): ${await project.text()}`).toBe(true);
  ctx.hatenaProjectId = ((await project.json()) as { id: number }).id;

  const siteKey = `e2e1582${suffix}`.toLowerCase().replace(/[^a-z0-9]/g, '');
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: `E2E 1582 ${suffix}`,
      siteKey,
      title: `E2E 1582 ${suffix}`,
      adminUser: 'e2e1582admin',
      adminEmail: `e2e-1582-${suffix}@letsblog.local`,
      adminPassword: `E2e1582#Sns${suffix}`,
      locale: 'ja',
    },
    timeout: 120_000,
  });
  expect(created.ok(), `managedサイトの作成に失敗しました (status=${created.status()}): ${await created.text()}`).toBe(true);
  const id = ((await created.json()) as { id: number }).id;
  // プラグインのはてなブックマークの接続先をスタブにする(アプリから送る設定では変えられない)。
  wpCli(siteKey, ['config', 'set', 'LETSBLOG_HATENA_API_BASE_URL', HATENA_STUB_URL_FROM_WORDPRESS, '--type=constant']);
  ctx.hatenaSite = { id, siteKey, title: `E2E-1582-${suffix}`, suffix } satisfies HatenaSite;

  const bound = await request.post(`/api/projects/${ctx.hatenaProjectId as number}/environments`, {
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
  await expect(page.getByRole('heading', { name: 'はてなブックマーク', exact: true })).toBeVisible({ timeout: 30000 });
}

/** 画面から OAuth のアプリ情報(consumer key / secret)を入力してはてなブックマークと接続し、設定画面へ戻るまで待つ。 */
async function connectThroughUi(ctx: Record<string, unknown>, page: Page): Promise<void> {
  await openSnsPage(ctx, page);
  await page.locator('input[name="hatenaConsumerKey"]').fill(CONSUMER_KEY);
  await page.locator('input[name="hatenaConsumerSecret"]').fill(CONSUMER_SECRET);
  await page.getByRole('button', { name: 'はてなブックマーク と接続' }).click();
  await page.waitForURL(new RegExp(`/projects/${projectId(ctx)}/settings/sns\\?`), { timeout: 60000 });
  await expect(page.getByTestId('sns-hatena-status')).toContainText('接続済み', { timeout: 30000 });
}

/** プラグインのはてなブックマークの設定を、アクセストークンとその秘密を指定して書き直す(wp-cli の標準入力。アプリが送るのと同じ形)。 */
function setToken(siteKey: string, accessToken: string, accessTokenSecret: string): void {
  wpCli(
    siteKey,
    ['letsblog', 'sns', 'config', 'set'],
    JSON.stringify({
      sns: 'hatena',
      consumer_key: CONSUMER_KEY,
      consumer_secret: CONSUMER_SECRET,
      access_token: accessToken,
      access_token_secret: accessTokenSecret,
      account_name: "Let's Blog E2E",
    })
  );
}

/** 公開時の告知文のテンプレートを置き換える(wp-cli の標準入力)。 */
function setPublishTemplate(siteKey: string, publish: string): void {
  wpCli(siteKey, ['letsblog', 'sns', 'templates', 'set'], JSON.stringify({ publish, pv: '' }));
}

Given('はてなブックマーク接続検証用に本番サイトを持つプロジェクトを構築しておく', async ({ ctx, request }) => {
  await buildProjectWithProductionSite(ctx, request);
});

Given('はてなブックマーク接続検証のプロジェクトではてなブックマークを接続しておく', async ({ ctx, page }) => {
  await connectThroughUi(ctx, page);
});

Given('はてなブックマーク接続検証の本番サイトのアクセストークンははてなに失効させられている', async ({ ctx }) => {
  setToken(site(ctx).siteKey, 'e2e-hatena-revoked', 'e2e-hatena-revoked-secret');
});

Given('はてなブックマーク接続検証の本番サイトの公開時の告知文が300文字の本文と記事の URL になっている', async ({ ctx }) => {
  ctx.hatenaLongBody = 'あ'.repeat(300);
  setPublishTemplate(site(ctx).siteKey, `${ctx.hatenaLongBody as string}\n{url}`);
});

Given('はてなブックマーク接続検証の本番サイトの公開時の告知文が URL を含まない固定文になっている', async ({ ctx }) => {
  setPublishTemplate(site(ctx).siteKey, 'URL の無い固定の告知文です');
});

When(
  'はてなブックマーク接続検証のプロジェクトの SNS 告知設定画面で OAuth のアプリ情報を入力してはてなブックマークと接続する',
  async ({ ctx, page }) => {
    await connectThroughUi(ctx, page);
  }
);

When('SNS 告知設定画面ではてなブックマークを切断する', async ({ ctx, page }) => {
  await openSnsPage(ctx, page);
  await page.getByRole('button', { name: 'はてなブックマーク を切断' }).click();
  await expect(page.getByText('はてなブックマーク の接続を切断しました。')).toBeVisible({ timeout: 30000 });
});

When('SNS 告知設定画面ではてなブックマークの投稿テストを行う', async ({ ctx, page }) => {
  await openSnsPage(ctx, page);
  await page.getByRole('button', { name: 'はてなブックマーク 投稿テスト' }).click();
  // 成功・失敗のどちらでも、履歴に1件増えるまで待つ。
  await expect.poll(() => history(site(ctx).siteKey).length, POLL).toBeGreaterThanOrEqual(1);
  await page.goto(snsPage(ctx));
});

When("はてなブックマーク接続検証の本番サイトへ Let's Blog から記事を即時公開する", async ({ ctx, request }) => {
  const s = site(ctx);
  const response = await request.post('/api/posts/publish', {
    headers: await adminHeaders(request),
    multipart: {
      site: s.siteKey,
      title: s.title,
      slug: `e2e-1582-${s.suffix}`,
      status: 'publish',
      markdown: '本文です\n',
    },
    timeout: 120_000,
  });
  expect(response.ok(), `記事の投稿に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  ctx.hatenaPostId = ((await response.json()) as { wpPostId: string }).wpPostId;
});

Then('SNS 告知設定画面のはてなブックマークにアカウント{string}の接続済みが表示される', async ({ page }, account: string) => {
  const status = page.getByTestId('sns-hatena-status');
  await expect(status).toContainText('接続済み', { timeout: 30000 });
  await expect(status).toContainText(account);
});

Then('本番サイトの wp letsblog sns status がはてなブックマークの接続済みを返す', async ({ ctx }) => {
  const status = hatenaStatusOf(site(ctx).siteKey);
  expect(status.status).toBe('接続済み');
  expect(status.account_name).toBe("Let's Blog E2E");
});

Then('本番サイトの wp letsblog sns status がはてなブックマークの未設定を返す', async ({ ctx }) => {
  expect(hatenaStatusOf(site(ctx).siteKey).status).toBe('未設定');
});

Then('アプリの API の応答にはてなブックマークの秘密が含まれない', async ({ ctx, request }) => {
  const response = await request.get(`/api/projects/${projectId(ctx)}/sns/hatena`, { headers: await adminHeaders(request) });
  expect(response.ok()).toBe(true);
  const body = await response.text();
  for (const secret of SECRET_STRINGS) {
    expect(body, `API の応答に ${secret} が含まれています`).not.toContain(secret);
  }
});

Then('アプリの DB にはてなブックマークの秘密が保存されていない', async () => {
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

Then('本番サイトの DB にはてなブックマークの秘密が平文で保存されていない', async ({ ctx }) => {
  const stored = wpCli(site(ctx).siteKey, ['option', 'get', 'letsblog_sns_config', '--format=json']);
  expect(stored.length, 'プラグインの設定が保存されていません').toBeGreaterThan(0);
  const printedLog = wpCli(site(ctx).siteKey, ['letsblog', 'sns', 'log', '--format=json']);
  for (const secret of SECRET_STRINGS) {
    expect(stored.includes(secret), `プラグインの DB に ${secret} が平文で保存されています`).toBe(false);
    expect(printedLog.includes(secret), `告知履歴に ${secret} が含まれています`).toBe(false);
  }
});

Then('SNS 告知設定画面のはてなブックマークの接続状態は{string}と表示される', async ({ page }, text: string) => {
  await expect(page.getByTestId('sns-hatena-status')).toContainText(text, { timeout: 30000 });
});

Then('はてなブックマークのスタブにテスト投稿が1回だけ届いている', async ({ ctx }) => {
  await expect.poll(async () => (await stubState()).bookmarks.length, POLL).toBeGreaterThanOrEqual(1);
  const { bookmarks } = await stubState();
  expect(bookmarks).toHaveLength(1);
  expect(bookmarks[0].url).toMatch(/^https?:\/\//);
  expect(bookmarks[0].comment).toContain('接続テスト');
  expect(bookmarks[0].comment).not.toContain('http');
  void ctx;
});

Then('はてなブックマークのスタブに記事の URL とタイトルが1回だけ投稿されている', async ({ ctx }) => {
  const s = site(ctx);
  await expect.poll(async () => (await stubState()).bookmarks.length, POLL).toBeGreaterThanOrEqual(1);
  const { bookmarks } = await stubState();
  expect(bookmarks).toHaveLength(1);
  const permalink = wpCli(s.siteKey, ['eval', `echo get_permalink(${Number(ctx.hatenaPostId)});`]);
  expect(bookmarks[0]).toEqual({ url: permalink, comment: s.title });
});

Then('はてなブックマークのスタブに記事の URL と100文字のコメントが1回だけ投稿されている', async ({ ctx }) => {
  const s = site(ctx);
  await expect.poll(async () => (await stubState()).bookmarks.length, POLL).toBeGreaterThanOrEqual(1);
  const { bookmarks } = await stubState();
  expect(bookmarks).toHaveLength(1);
  const permalink = wpCli(s.siteKey, ['eval', `echo get_permalink(${Number(ctx.hatenaPostId)});`]);
  expect(bookmarks[0].url).toBe(permalink);
  expect([...bookmarks[0].comment]).toHaveLength(100);
  expect(bookmarks[0].comment.endsWith('…')).toBe(true);
  expect((ctx.hatenaLongBody as string).startsWith(bookmarks[0].comment.slice(0, -1))).toBe(true);
});

Then('はてなブックマークのスタブには何も投稿されていない', async ({ ctx }) => {
  // 履歴に失敗が残った後で確かめる(投稿の試みが終わっている)。
  await expect.poll(() => history(site(ctx).siteKey).length, POLL).toBeGreaterThanOrEqual(1);
  expect((await stubState()).bookmarks).toEqual([]);
});

Then('はてなブックマークの告知履歴にテスト投稿が成功として表示される', async ({ page }) => {
  const entry = page.getByTestId('sns-hatena-log-entry').first();
  await expect(entry).toContainText('テスト投稿', { timeout: 30000 });
  await expect(entry).toContainText('成功');
});

Then('はてなブックマークの告知履歴に記事の公開が成功として記録されている', async ({ ctx }) => {
  const s = site(ctx);
  await expect.poll(() => history(s.siteKey).length, POLL).toBe(1);
  expect(history(s.siteKey)[0]).toMatchObject({
    sns: 'hatena',
    kind: 'publish',
    post_id: Number(ctx.hatenaPostId),
    success: true,
    error: null,
  });
});

Then('はてなブックマークの告知履歴に失敗の理由として{string}が表示される', async ({ ctx, page }, text: string) => {
  const entry = history(site(ctx).siteKey).at(-1);
  expect(entry?.success).toBe(false);
  expect(entry?.error ?? '').toContain(text);
  await page.goto(snsPage(ctx));
  const row = page.getByTestId('sns-hatena-log-entry').first();
  await expect(row).toContainText('失敗', { timeout: 30000 });
  await expect(row).toContainText(text);
});

After({ tags: '@project' }, async ({ ctx, request }) => {
  if (ctx.hatenaProjectId === undefined) {
    return;
  }
  const headers = await adminHeaders(request);
  await hatenaStub('/__control/reset', { method: 'POST' }).catch(() => undefined);
  await request.delete(`/api/projects/${ctx.hatenaProjectId as number}`, { headers });
  const s = ctx.hatenaSite as HatenaSite | undefined;
  if (s) {
    await request.delete(`/api/sites/${s.id}`, { headers });
  }
});
