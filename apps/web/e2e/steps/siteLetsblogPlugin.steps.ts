import { execFileSync } from 'node:child_process';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';
import { waitForSiteCreationPanel } from '../support/siteCreationPanel';
import { waitForProvisionedSiteRow } from '../support/provisionedSiteRow';

/**
 * サイト編集画面の letsblog プラグインの導入状態(issue #1557)のステップ定義。
 * 「ステップ定義ファイルは相乗りしない」方針のため、フィクスチャのヘルパーはここに閉じて持つ。
 */

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

/** `WordPressSiteProvisioningService#normalizeSlug` と同じ正規化。 */
function slugOf(siteKey: string): string {
  return siteKey.toLowerCase().replace(/[^a-z0-9-]/g, '-');
}

/** WordPress コンテナのサイトディレクトリで任意のシェルコマンドを実行する。 */
function inSite(slug: string, command: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'wordpress', 'sh', '-c', `cd /var/www/html/sites/${slug} && ${command}`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 180_000 }
  ).trim();
}

Given('プラグイン状態検証用のマネージドWordPressサイトを構築しておく', async ({ ctx, page, request }) => {
  const unique = uniqueSuffix();
  const siteKey = `e2e1557-${unique}`;
  await waitForSiteCreationPanel(page);
  await page.locator('button:has-text("WordPressを新規構築")').click();
  await page.locator('input[name="managedName"]').fill(`E2E 1557 ${unique}`);
  await page.locator('input[name="managedSiteKey"]').fill(siteKey);
  await page.locator('input[name="managedTitle"]').fill(`E2E 1557 ${unique}`);
  await page
    .locator('input[name="managedAdminUser"]')
    .fill(`e2eadmin${unique}`.replace(/[^a-zA-Z0-9._-]/g, '').slice(0, 30));
  await page.locator('input[name="managedAdminEmail"]').fill(`e2e-${unique}@letsblog.local`);
  await page.locator('input[name="managedAdminPassword"]').fill('E2eProvision#Passw0rd1');
  await page.locator('button:has-text("構築する")').click();
  await waitForProvisionedSiteRow(page, siteKey);
  const list = await request.get('/api/sites', { headers: await adminHeaders(request) });
  const sites = (await list.json()) as { id: number; siteKey: string }[];
  const found = sites.find((s) => s.siteKey === siteKey);
  expect(found, `構築したサイト ${siteKey} が一覧APIに見つかりません`).toBeDefined();
  ctx.pluginSiteId = found!.id;
  ctx.pluginSiteKey = siteKey;
});

Given('そのサイトのプラグインを停止しておく', async ({ ctx }) => {
  inSite(slugOf(ctx.pluginSiteKey as string), 'wp --allow-root plugin deactivate letsblog');
});

Given('そのサイトのプラグインのプロトコルのバージョンを非互換にしておく', async ({ ctx }) => {
  inSite(
    slugOf(ctx.pluginSiteKey as string),
    "sed -i 's/const LETSBLOG_PROTOCOL_VERSION = 1;/const LETSBLOG_PROTOCOL_VERSION = 99;/' wp-content/plugins/letsblog/letsblog.php"
  );
});

When('サイト編集画面を開く', async ({ ctx, page }) => {
  await page.goto(`/sites/${ctx.pluginSiteId}/edit`);
});

When('プラグインを再導入する', async ({ page }) => {
  await page.getByRole('button', { name: 'プラグインを再導入' }).click();
});

Then('プラグインの導入状態に{string}が表示される', async ({ page }, text: string) => {
  await expect(page.getByTestId('letsblog-plugin-status')).toContainText(text, { timeout: 60000 });
});

Then('プラグインの再導入ボタンは表示されない', async ({ page }) => {
  await expect(page.getByTestId('letsblog-plugin-status')).toContainText('導入済み', { timeout: 60000 });
  await expect(page.getByRole('button', { name: 'プラグインを再導入' })).toHaveCount(0);
});

When('そのサイトへ記事の投稿を試みる', async ({ ctx, request }) => {
  const unique = uniqueSuffix();
  const slug = `e2e-1557-${unique}`;
  const response = await request.post('/api/posts/publish', {
    headers: await adminHeaders(request),
    multipart: {
      site: ctx.pluginSiteKey as string,
      title: `E2E-1557-${unique}`,
      slug,
      status: 'draft',
      markdown: `# E2E 1557\n\n拒否されるはずの記事です。\n`,
    },
    timeout: 120_000,
  });
  ctx.pluginPublishSlug = slug;
  ctx.pluginPublishStatus = response.status();
  ctx.pluginPublishBody = await response.text();
});

Then('投稿は拒否され{string}を案内するメッセージが返る', async ({ ctx }, guidance: string) => {
  expect(ctx.pluginPublishStatus, '拒否(4xx)されていません').toBe(409);
  expect(ctx.pluginPublishBody as string).toContain(guidance);
});

Then('そのサイトには記事が作成されていない', async ({ ctx }) => {
  const found = inSite(
    slugOf(ctx.pluginSiteKey as string),
    `wp --allow-root post list --name=${ctx.pluginPublishSlug as string} --post_status=any --format=ids`
  );
  expect(found, '拒否されたはずの記事がWordPressに作成されています').toBe('');
});

After({ tags: '@project' }, async ({ ctx, request }) => {
  if (ctx.pluginSiteId === undefined) {
    return;
  }
  await request.delete(`/api/sites/${ctx.pluginSiteId}`, { headers: await adminHeaders(request) });
});
