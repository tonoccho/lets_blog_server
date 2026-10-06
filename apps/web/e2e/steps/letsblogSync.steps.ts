import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * カスタムタグ等の letsblog プラグインへの同期(issue #1558)のステップ定義。
 * 「ステップ定義ファイルは相乗りしない」方針のため、フィクスチャのヘルパーはここに閉じて持つ。
 */

const WORDPRESS_CONTAINER = 'lbs-wordpress';

interface SyncSite {
  id: number;
  siteKey: string;
  projectId: number;
}

interface SyncState {
  status: string;
  error: string | null;
  hash: string | null;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

function wpCli(siteKey: string, args: string[]): string {
  return execFileSync(
    'docker',
    ['exec', WORDPRESS_CONTAINER, 'wp', '--allow-root', `--path=/var/www/html/sites/${siteKey}`, ...args],
    { encoding: 'utf8', timeout: 120_000 }
  ).trim();
}

function inSite(siteKey: string, command: string): string {
  return execFileSync(
    'docker',
    ['exec', WORDPRESS_CONTAINER, 'sh', '-c', `cd /var/www/html/sites/${siteKey} && ${command}`],
    { encoding: 'utf8', timeout: 120_000 }
  ).trim();
}

function sites(ctx: Record<string, unknown>): SyncSite[] {
  return (ctx.syncSites as SyncSite[] | undefined) ?? [];
}

async function createProject(request: APIRequestContext, headers: Record<string, string>): Promise<number> {
  const suffix = uniqueSuffix();
  const response = await request.post('/api/projects', {
    headers,
    data: { name: `E2E 1558 ${suffix}`, slug: `e2e1558-${suffix}` },
  });
  expect(response.ok(), `プロジェクトの作成に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  return ((await response.json()) as { id: number }).id;
}

async function createManagedSite(
  request: APIRequestContext,
  headers: Record<string, string>,
  projectId: number,
  environment: 'local' | 'test'
): Promise<SyncSite> {
  const suffix = uniqueSuffix();
  const siteKey = `e2e1558${suffix}`.toLowerCase().replace(/[^a-z0-9]/g, '');
  const response = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: `E2E 1558 ${suffix}`,
      siteKey,
      title: `E2E 1558 ${suffix}`,
      adminUser: 'e2e1558admin',
      adminEmail: `e2e-1558-${suffix}@letsblog.local`,
      adminPassword: `E2e1558#Sync${suffix}`,
      locale: 'ja',
    },
    timeout: 120_000,
  });
  expect(response.ok(), `managedサイトの作成に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  const id = ((await response.json()) as { id: number }).id;
  const bound = await request.post(`/api/projects/${projectId}/environments`, {
    headers,
    data: { environment, siteId: id },
  });
  expect(bound.ok(), `環境への紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`).toBe(true);
  return { id, siteKey, projectId };
}

async function build(
  ctx: Record<string, unknown>,
  request: APIRequestContext,
  perProject: number[]
): Promise<void> {
  const headers = await adminHeaders(request);
  const built: SyncSite[] = [];
  const projectIds: number[] = [];
  for (const count of perProject) {
    const projectId = await createProject(request, headers);
    projectIds.push(projectId);
    const environments: ('local' | 'test')[] = ['local', 'test'];
    for (let i = 0; i < count; i++) {
      built.push(await createManagedSite(request, headers, projectId, environments[i]));
    }
  }
  ctx.syncSites = built;
  ctx.syncProjectIds = projectIds;
}

Given('同期検証用に1つのプロジェクトへ紐付けたマネージドWordPressサイトを{int}件構築しておく', async ({ ctx, request }, count: number) => {
  await build(ctx, request, [count]);
});

Given('同期検証用に2つのプロジェクトへそれぞれマネージドWordPressサイトを紐付けて構築しておく', async ({ ctx, request }) => {
  await build(ctx, request, [1, 1]);
});

Given('同期検証のサイトのプラグインの status が壊れた出力を返すようにしておく', async ({ ctx }) => {
  for (const site of sites(ctx)) {
    inSite(
      site.siteKey,
      `sed -i 's/WP_CLI::line(json_encode(\\[/WP_CLI::line("garbage"); WP_CLI::line(json_encode([/' wp-content/plugins/letsblog/letsblog.php`
    );
  }
});

Given('同期検証のサイトのプラグインを停止しておく', async ({ ctx }) => {
  for (const site of sites(ctx)) {
    wpCli(site.siteKey, ['plugin', 'deactivate', 'letsblog']);
  }
});

async function saveTag(
  ctx: Record<string, unknown>,
  request: APIRequestContext,
  projectId: number | null
): Promise<void> {
  const tagName = `e2e1558t${uniqueSuffix()}`;
  const response = await request.post('/api/custom-tags', {
    headers: await adminHeaders(request),
    data: {
      tagName,
      htmlTemplate: '<div class="e2e1558">{{content}}</div>',
      cssContent: '.e2e1558 { color: #123456; }',
      description: 'e2e1558 の検証用タグ',
      tagFormat: 'BLOCK',
      ...(projectId === null ? {} : { projectId }),
    },
  });
  expect(response.ok(), `カスタムタグの作成に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  const tag = (await response.json()) as { id: number };
  ctx.syncTagName = tagName;
  ctx.syncTagIds = [...((ctx.syncTagIds as number[] | undefined) ?? []), tag.id];
}

When('同期検証のプロジェクトにカスタムタグを保存する', async ({ ctx, request }) => {
  await saveTag(ctx, request, (ctx.syncProjectIds as number[])[0]);
});

When('同期検証のグローバルのカスタムタグを保存する', async ({ ctx, request }) => {
  await saveTag(ctx, request, null);
});

async function appState(request: APIRequestContext, siteId: number): Promise<SyncState | null> {
  const response = await request.get(`/api/sites/${siteId}/letsblog-sync`, { headers: await adminHeaders(request) });
  if (!response.ok()) {
    return null;
  }
  const text = await response.text();
  return text === '' ? null : (JSON.parse(text) as SyncState | null);
}

function pluginStatus(siteKey: string): { sync_hash: string | null } {
  return JSON.parse(wpCli(siteKey, ['letsblog', 'status'])) as { sync_hash: string | null };
}

Then('同期検証のすべてのサイトのプラグインの status のハッシュがアプリ側のハッシュと一致する', async ({ ctx, request }) => {
  for (const site of sites(ctx)) {
    await expect
      .poll(
        async () => {
          const state = await appState(request, site.id);
          if (!state || state.status !== 'SYNCED' || !state.hash) {
            return `アプリ側が同期済みでない(${JSON.stringify(state)})`;
          }
          const pluginHash = pluginStatus(site.siteKey).sync_hash;
          return pluginHash === state.hash ? 'ok' : `プラグインのハッシュ ${pluginHash} がアプリ側 ${state.hash} と違う`;
        },
        { timeout: 180_000, intervals: [2_000, 3_000, 5_000] }
      )
      .toBe('ok');
    // 保存されている内容そのもののハッシュとも一致する(ハッシュだけ合わせて内容を保存していない、を防ぐ)
    const payload = wpCli(site.siteKey, ['option', 'get', 'letsblog_sync_payload']);
    expect(createHash('sha256').update(payload, 'utf8').digest('hex')).toBe(pluginStatus(site.siteKey).sync_hash);
  }
});

Then('同期検証のすべてのサイトのプラグインに保存したタグの定義が入っている', async ({ ctx }) => {
  for (const site of sites(ctx)) {
    const payload = JSON.parse(wpCli(site.siteKey, ['option', 'get', 'letsblog_sync_payload'])) as {
      customTags: { tagName: string; cssContent: string | null }[];
      cssBundle: string;
    };
    const tag = payload.customTags.find((t) => t.tagName === (ctx.syncTagName as string));
    expect(tag, `${site.siteKey} に ${ctx.syncTagName as string} の定義がありません`).toBeDefined();
    expect(payload.cssBundle).toContain('.e2e1558');
  }
});

Then('サイト一覧に同期検証のサイトの同期失敗が表示される', async ({ ctx, page }) => {
  const [site] = sites(ctx);
  await expect
    .poll(
      async () => {
        await page.goto('/sites');
        return await page.locator(`tr:has-text("${site.siteKey}") [data-testid="letsblog-sync-failed"]`).count();
      },
      { timeout: 180_000, intervals: [3_000, 5_000] }
    )
    .toBe(1);
});

When('同期検証のサイトの編集画面でプラグインを再導入して再同期する', async ({ ctx, page, request }) => {
  const [site] = sites(ctx);
  // 壊れた出力の間は導入状態を取得できず、画面に再導入ボタンは出ない。再導入はアプリの API で行い、
  // 壊れたプラグインを配置し直した上で、画面の「再同期」で回復させる。
  const reinstall = await request.post(`/api/sites/${site.id}/letsblog-plugin/install`, {
    headers: await adminHeaders(request),
    timeout: 120_000,
  });
  expect(reinstall.ok(), `再導入に失敗しました (status=${reinstall.status()}): ${await reinstall.text()}`).toBe(true);
  await page.goto(`/sites/${site.id}/edit`);
  await expect(page.getByTestId('letsblog-plugin-status')).toContainText('導入済み', { timeout: 60_000 });
  await page.getByRole('button', { name: '再同期' }).click();
});

Then('同期検証のサイトの同期状態が同期済みと表示される', async ({ ctx, page }) => {
  const [site] = sites(ctx);
  await page.goto(`/sites/${site.id}/edit`);
  await expect(page.getByTestId('letsblog-sync-status')).toContainText('同期済み', { timeout: 60_000 });
});

Then('同期検証のサイトの編集画面で同期状態が未同期と表示される', async ({ ctx, page }) => {
  const [site] = sites(ctx);
  await page.goto(`/sites/${site.id}/edit`);
  await expect(page.getByTestId('letsblog-sync-status')).toContainText('未同期', { timeout: 60_000 });
});

Then('同期検証のサイトの REST API に letsblog のルートが存在しない', async ({ ctx }) => {
  for (const site of sites(ctx)) {
    const routes = wpCli(site.siteKey, [
      'eval',
      'echo json_encode(array_values(array_filter(array_keys(rest_get_server()->get_routes()), fn($r) => stripos($r, "letsblog") !== false)));',
    ]);
    expect(JSON.parse(routes)).toEqual([]);
  }
});

Then('同期検証のサイトの同期状態が見送りになる', async ({ ctx, request }) => {
  const [site] = sites(ctx);
  await expect
    .poll(async () => (await appState(request, site.id))?.status ?? null, { timeout: 180_000, intervals: [2_000, 3_000, 5_000] })
    .toBe('SKIPPED');
});

Then('同期検証のサイトには内容が送られていない', async ({ ctx }) => {
  for (const site of sites(ctx)) {
    expect(() => wpCli(site.siteKey, ['option', 'get', 'letsblog_sync_hash'])).toThrow();
  }
});

After({ tags: '@project' }, async ({ ctx, request }) => {
  if (ctx.syncSites === undefined && ctx.syncTagIds === undefined) {
    return;
  }
  const headers = await adminHeaders(request);
  for (const id of (ctx.syncTagIds as number[] | undefined) ?? []) {
    await request.delete(`/api/custom-tags/${id}`, { headers });
  }
  for (const site of sites(ctx)) {
    await request.delete(`/api/sites/${site.id}`, { headers });
  }
  for (const projectId of (ctx.syncProjectIds as number[] | undefined) ?? []) {
    await request.delete(`/api/projects/${projectId}`, { headers });
  }
});
