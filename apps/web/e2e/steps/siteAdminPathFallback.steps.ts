import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  createFixtureProject,
  deleteFixtureProject,
  expect,
  fetchAccessToken,
  loginAsAdmin,
} from '../support';

/**
 * 管理画面リンクのサイト個別 → グローバル既定フォールバックのステップ定義(issue #1534)。
 *
 * 「ステップ定義ファイルは相乗りしない」方針のため、フィクスチャ登録のヘルパーはこのファイル内に閉じて持つ。
 * 上書きの設定・解除は編集フォームに依存せず PUT /api/sites/{id} を直接叩く。
 */

const FIXTURE_HOST = 'adminpathfallback1534.invalid';

type FixtureSite = { id: number; siteKey: string; name: string; baseUrl: string };

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

async function registerSite(request: APIRequestContext, label: string): Promise<FixtureSite> {
  const headers = await adminHeaders(request);
  const unique = uniqueSuffix();
  const siteKey = `e2e-1534-${label}-${unique}`;
  const name = `E2E 1534 ${label} ${unique}`;
  const response = await request.post('/api/sites', {
    headers,
    data: {
      name,
      siteKey,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'SSH',
        baseUrl: `http://${label}${unique}.${FIXTURE_HOST}`,
        sshHost: 'wrong.invalid',
        sshUser: 'nouser',
        wpPath: '/nowhere',
        sshPrivateKeyPem:
          '-----BEGIN OPENSSH PRIVATE KEY-----\ne2e-1534-fixture-not-a-real-key\n-----END OPENSSH PRIVATE KEY-----',
      },
    },
  });
  expect(
    response.ok(),
    `フィクスチャのサイト登録に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const created = (await response.json()) as { id: number };
  const detail = await request.get(`/api/sites/${created.id}`, { headers });
  const { baseUrl } = (await detail.json()) as { baseUrl: string };
  return { id: created.id, siteKey, name, baseUrl };
}

function slot(page: Page) {
  return page
    .locator('div.rounded-lg', { has: page.getByRole('heading', { name: 'テスト環境' }) })
    .first();
}

function siteOf(ctx: Record<string, unknown>, which: 'A' | 'B'): FixtureSite {
  return ctx[`adminPathFallbackSite${which}`] as FixtureSite;
}

function expectedHref(site: FixtureSite, path: string): string {
  return `${site.baseUrl.replace(/\/$/, '')}/${path}`;
}

Given(
  '管理画面パス解決の検証用に、サイトAとサイトBを登録し、サイトAをプロジェクトのテスト環境に紐付けてある',
  async ({ page, request, ctx }) => {
    const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
    const project = await createFixtureProject(request, token, 'at-1534-slot');
    ctx.adminPathFallbackProjectId = project.id;
    const siteA = await registerSite(request, 'a');
    ctx.adminPathFallbackSiteA = siteA;
    ctx.adminPathFallbackSiteB = await registerSite(request, 'b');

    await loginAsAdmin(page);
    await page.goto(`/projects/${project.id}`);
    await page.locator('select[aria-label="テスト環境に紐付けるサイト"]').selectOption(String(siteA.id));
    await slot(page).locator('button:has-text("紐付ける")').click();
    await expect(slot(page).getByText(siteA.name, { exact: true })).toBeVisible({ timeout: 10000 });
  }
);

async function putAdminPath(request: APIRequestContext, site: FixtureSite, value: string): Promise<void> {
  const response = await request.put(`/api/sites/${site.id}`, {
    headers: await adminHeaders(request),
    data: { adminPath: value },
  });
  expect(response.ok(), `管理画面パスの更新に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(
    true
  );
}

When('サイトAの管理画面パスを {string} に上書きする', async ({ request, ctx }, value: string) => {
  await putAdminPath(request, siteOf(ctx, 'A'), value);
});

When('サイトAの管理画面パスの上書きを解除する', async ({ request, ctx }) => {
  await putAdminPath(request, siteOf(ctx, 'A'), '');
});

When('サイト一覧を開く', async ({ page }) => {
  await page.goto('/sites');
});

When('サイトAが紐付いたプロジェクト詳細を開く', async ({ page, ctx }) => {
  await page.goto(`/projects/${ctx.adminPathFallbackProjectId}`);
});

async function expectListLink(page: Page, site: FixtureSite, path: string): Promise<void> {
  const link = page
    .locator(`tr:has-text("${site.siteKey}")`)
    .getByRole('link', { name: `${site.name} の管理画面を開く` });
  await expect(link).toHaveAttribute('href', expectedHref(site, path));
}

Then('サイトAの管理画面リンクは公開URLの {string} を指す', async ({ page, ctx }, path: string) => {
  await expectListLink(page, siteOf(ctx, 'A'), path);
});

Then('サイトBの管理画面リンクは公開URLの {string} を指す', async ({ page, ctx }, path: string) => {
  await expectListLink(page, siteOf(ctx, 'B'), path);
});

Then(
  'テスト環境のスロットのサイトAの管理画面リンクは公開URLの {string} を指す',
  async ({ page, ctx }, path: string) => {
    const site = siteOf(ctx, 'A');
    const link = slot(page).getByRole('link', { name: `${site.name} の管理画面を開く` });
    await expect(link).toHaveAttribute('href', expectedHref(site, path));
  }
);

After({ tags: '@project' }, async ({ ctx, request }) => {
  if (ctx.adminPathFallbackProjectId !== undefined) {
    const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
    await deleteFixtureProject(request, token, ctx.adminPathFallbackProjectId as number);
  }
  const headers = await adminHeaders(request);
  for (const which of ['A', 'B'] as const) {
    const site = ctx[`adminPathFallbackSite${which}`] as FixtureSite | undefined;
    if (site) {
      await request.delete(`/api/sites/${site.id}`, { headers });
    }
  }
});
