import { execFileSync } from 'node:child_process';
import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * 公開時の SNS 告知(issue #1575)のステップ定義。
 * 「ステップ定義ファイルは相乗りしない」方針のため、フィクスチャのヘルパーはここに閉じて持つ。
 * X の API は x スタブ(ホストからは 127.0.0.1:18088、WordPress からは http://x-stub:8080)。
 * 他のシナリオの `ALL_STUBS` に x を加えないよう、スタブへの入口もここに閉じて持つ。
 */

const WORDPRESS_CONTAINER = 'lbs-wordpress';
const X_STUB_HOST_URL = 'http://127.0.0.1:18088';
const X_STUB_NETWORK_URL = 'http://x-stub:8080';
const POLL = { timeout: 120_000, intervals: [1_000, 2_000, 3_000] };

interface AnnounceFixture {
  siteId: number;
  siteKey: string;
  projectId: number;
  adminUser: string;
  adminPassword: string;
  title: string;
  postId: string;
  suffix: string;
}

function fixture(ctx: Record<string, unknown>): AnnounceFixture {
  return ctx.announceFixture as AnnounceFixture;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

function wpCli(siteKey: string, args: string[], input?: string): string {
  return execFileSync(
    'docker',
    ['exec', '-i', WORDPRESS_CONTAINER, 'wp', '--allow-root', `--path=/var/www/html/sites/${siteKey}`, ...args],
    { encoding: 'utf8', timeout: 120_000, input: input ?? '' }
  ).trim();
}

async function xStub(path: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(`${X_STUB_HOST_URL}${path}`, init);
  expect(response.ok, `x スタブ ${path} が失敗しました (status=${response.status})。x-stub が起動していますか`).toBe(true);
  return response;
}

async function tweets(): Promise<string[]> {
  const state = (await (await xStub('/__control/state')).json()) as { tweets: string[] };
  return state.tweets;
}

interface LogEntry {
  sns: string;
  kind: string;
  post_id: number | null;
  success: boolean;
  error: string | null;
}

function history(siteKey: string): LogEntry[] {
  return JSON.parse(wpCli(siteKey, ['letsblog', 'sns', 'log', '--format=json'])) as LogEntry[];
}

function permalink(siteKey: string, postId: string): string {
  return wpCli(siteKey, ['eval', `echo get_permalink(${Number(postId)});`]);
}

async function createSite(ctx: Record<string, unknown>, request: APIRequestContext): Promise<AnnounceFixture> {
  const headers = await adminHeaders(request);
  const suffix = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  await xStub('/__control/reset', { method: 'POST' });

  const project = await request.post('/api/projects', {
    headers,
    data: { name: `E2E 1575 ${suffix}`, slug: `e2e1575-${suffix}` },
  });
  expect(project.ok(), `プロジェクトの作成に失敗しました: ${await project.text()}`).toBe(true);
  const projectId = ((await project.json()) as { id: number }).id;

  const siteKey = `e2e1575${suffix}`.toLowerCase().replace(/[^a-z0-9]/g, '');
  const adminUser = 'e2e1575admin';
  const adminPassword = `E2e1575#Sns${suffix}`;
  const site = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: `E2E 1575 ${suffix}`,
      siteKey,
      title: `E2E 1575 ${suffix}`,
      adminUser,
      adminEmail: `e2e-1575-${suffix}@letsblog.local`,
      adminPassword,
      locale: 'ja',
    },
    timeout: 120_000,
  });
  expect(site.ok(), `managedサイトの作成に失敗しました: ${await site.text()}`).toBe(true);
  const siteId = ((await site.json()) as { id: number }).id;
  const bound = await request.post(`/api/projects/${projectId}/environments`, {
    headers,
    data: { environment: 'production', siteId },
  });
  expect(bound.ok(), `環境への紐付けに失敗しました: ${await bound.text()}`).toBe(true);

  // 本番サイトに紐付ける(Let's Blog の予約投稿 publish_scheduled_at は本番サイトにだけ効き、SNS の設定も本番サイトにだけ送られる)。
  // X の向き先を x スタブへ。接続はアプリが本番サイトへ送るのと同じ wp letsblog sns config set。
  wpCli(siteKey, ['config', 'set', 'LETSBLOG_X_API_BASE_URL', X_STUB_NETWORK_URL, '--type=constant']);
  wpCli(
    siteKey,
    ['letsblog', 'sns', 'config', 'set'],
    JSON.stringify({
      sns: 'x',
      client_id: 'e2e-client-id',
      client_secret: 'e2e-client-secret',
      access_token: 'e2e-x-access-valid',
      refresh_token: 'e2e-x-refresh-valid',
      expires_at: Math.floor(Date.now() / 1000) + 3600,
      account_name: 'LetsBlogOfficial',
    })
  );

  const f: AnnounceFixture = { siteId, siteKey, projectId, adminUser, adminPassword, title: `E2E-1575-${suffix}`, postId: '', suffix };
  ctx.announceFixture = f;
  return f;
}

async function publishFromLetsBlog(
  request: APIRequestContext,
  f: AnnounceFixture,
  options: { status: string; scheduledAt?: string; wpPostId?: string; markdown?: string }
): Promise<string> {
  const response = await request.post('/api/posts/publish', {
    headers: await adminHeaders(request),
    multipart: {
      site: f.siteKey,
      title: f.title,
      slug: `e2e-1575-${f.suffix}`,
      status: options.status,
      markdown: options.markdown ?? '本文です\n',
      ...(options.scheduledAt ? { publishScheduledAt: options.scheduledAt } : {}),
      ...(options.wpPostId ? { wpPostId: options.wpPostId } : {}),
    },
    timeout: 120_000,
  });
  expect(response.ok(), `記事の投稿に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  return ((await response.json()) as { wpPostId: string }).wpPostId;
}

async function loginToWordPressAdmin(page: Page, f: AnnounceFixture): Promise<void> {
  await page.goto(`/sites/${f.siteKey}/wp-login.php`);
  await expect(async () => {
    await page.locator('#user_login').fill(f.adminUser);
    await page.locator('#user_pass').fill(f.adminPassword);
  }).toPass({ timeout: 30000 });
  await page.locator('#wp-submit').click();
  await page.waitForURL(`**/sites/${f.siteKey}/wp-admin/**`, { timeout: 30000 });
}

/** wp-admin のログイン済みセッションから、WP REST API で記事の状態を変える(Web のリクエストとして処理される)。 */
async function restPost(page: Page, f: AnnounceFixture, method: 'post' | 'put', path: string, data: object): Promise<{ id: number }> {
  await page.goto(`/sites/${f.siteKey}/wp-admin/post-new.php`);
  const nonce = (await (
    await page.waitForFunction(
      () => (window as unknown as { wpApiSettings?: { nonce?: string } }).wpApiSettings?.nonce ?? null,
      { timeout: 30000 }
    )
  ).jsonValue()) as string;
  const response = await page.request[method](`/sites/${f.siteKey}/wp-json/wp/v2/posts${path}`, {
    headers: { 'X-WP-Nonce': nonce },
    data,
  });
  expect(response.ok(), `REST API が失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  return (await response.json()) as { id: number };
}

Given('告知検証用に、X を接続したサイトを用意する', async ({ ctx, request }) => {
  await createSite(ctx, request);
});

Given('告知検証用に、X を接続したサイトに下書きの記事を作成しておく', async ({ ctx, request, page }) => {
  const f = await createSite(ctx, request);
  await loginToWordPressAdmin(page, f);
  const created = await restPost(page, f, 'post', '', { title: f.title, content: '本文です', status: 'draft' });
  f.postId = String(created.id);
  expect(await tweets()).toEqual([]);
});

Given('告知検証の X のスタブが次の投稿に 429 を返すようにしておく', async () => {
  await xStub('/__control/force', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ status: 429, count: 1 }),
  });
});

When('告知検証の下書きを wp-admin から公開する', async ({ ctx, page }) => {
  const f = fixture(ctx);
  await restPost(page, f, 'post', `/${f.postId}`, { status: 'publish' });
});

When('告知検証の記事を Let\'s Blog から即時公開する', async ({ ctx, request }) => {
  const f = fixture(ctx);
  f.postId = await publishFromLetsBlog(request, f, { status: 'publish' });
});

When('告知検証の記事を Let\'s Blog から予約投稿する', async ({ ctx, request }) => {
  const f = fixture(ctx);
  const scheduledAt = new Date(Date.now() + 7 * 24 * 3600 * 1000).toISOString();
  f.postId = await publishFromLetsBlog(request, f, { status: 'publish', scheduledAt });
  expect(wpCli(f.siteKey, ['post', 'get', f.postId, '--field=post_status'])).toBe('future');
});

When('告知検証の予約投稿が予約時刻に公開される', async ({ ctx }) => {
  const f = fixture(ctx);
  // 予約時刻を過去へ動かし、WordPress が予約投稿を公開する処理(publish_future_post)を実行する。
  wpCli(f.siteKey, [
    'eval',
    `global $wpdb; $id = ${Number(f.postId)}; $past = gmdate('Y-m-d H:i:s', time() - 60);`
      + ` $wpdb->update($wpdb->posts, ['post_date' => $past, 'post_date_gmt' => $past], ['ID' => $id]); clean_post_cache($id);`
      + ` check_and_publish_future_post($id);`,
  ]);
  expect(wpCli(f.siteKey, ['post', 'get', f.postId, '--field=post_status'])).toBe('publish');
});

When('告知検証の公開済みの記事の本文を更新する', async ({ ctx, request }) => {
  const f = fixture(ctx);
  await publishFromLetsBlog(request, f, { status: 'publish', wpPostId: f.postId, markdown: '更新した本文です\n' });
});

When('告知検証の記事を非公開にしてから再公開する', async ({ ctx, page }) => {
  const f = fixture(ctx);
  await loginToWordPressAdmin(page, f);
  await restPost(page, f, 'post', `/${f.postId}`, { status: 'draft' });
  await restPost(page, f, 'post', `/${f.postId}`, { status: 'publish' });
});

When('告知検証のサイトで WP-Cron を実行する', async ({ ctx }) => {
  wpCli(fixture(ctx).siteKey, ['cron', 'event', 'run', '--due-now']);
});

Then('告知検証の X のスタブにタイトルと URL が1回だけ投稿されている', async ({ ctx }) => {
  const f = fixture(ctx);
  await expect.poll(async () => (await tweets()).length, POLL).toBeGreaterThanOrEqual(1);
  const posted = await tweets();
  expect(posted).toHaveLength(1);
  expect(posted[0]).toBe(`${f.title}\n${permalink(f.siteKey, f.postId)}`);
});

Then('告知検証の X のスタブにはまだ1回だけ投稿されている', async () => {
  expect(await tweets()).toHaveLength(1);
});

Then('告知検証の X のスタブには何も投稿されていない', async () => {
  expect(await tweets()).toEqual([]);
});

Then('告知検証の告知履歴に成功として記録されている', async ({ ctx }) => {
  const f = fixture(ctx);
  await expect.poll(() => history(f.siteKey).length, POLL).toBe(1);
  const [entry] = history(f.siteKey);
  expect(entry).toMatchObject({ sns: 'x', kind: 'publish', post_id: Number(f.postId), success: true, error: null });
});

Then('告知検証の記事は公開状態になっている', async ({ ctx }) => {
  const f = fixture(ctx);
  expect(wpCli(f.siteKey, ['post', 'get', f.postId, '--field=post_status'])).toBe('publish');
});

Then('告知検証の告知履歴に失敗とその理由が記録されている', async ({ ctx }) => {
  const f = fixture(ctx);
  await expect.poll(() => history(f.siteKey).length, POLL).toBe(1);
  const [entry] = history(f.siteKey);
  expect(entry).toMatchObject({ sns: 'x', kind: 'publish', post_id: Number(f.postId), success: false });
  expect(entry.error ?? '').toContain('429');
  expect(await tweets()).toEqual([]);
});

After({ tags: '@project' }, async ({ ctx, request }) => {
  const f = ctx.announceFixture as AnnounceFixture | undefined;
  if (f === undefined) {
    return;
  }
  const headers = await adminHeaders(request);
  await xStub('/__control/reset', { method: 'POST' }).catch(() => undefined);
  await request.delete(`/api/sites/${f.siteId}`, { headers });
  await request.delete(`/api/projects/${f.projectId}`, { headers });
});
