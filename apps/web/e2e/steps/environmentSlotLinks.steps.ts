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
 * 環境スロットのアイコンリンクのステップ定義(issue #1530)。
 *
 * 「ステップ定義ファイルは相乗りしない」方針(siteListLinks.steps.ts と同じ)のため、
 * フィクスチャ登録のヘルパーはこのファイル内に閉じて持つ。
 * 管理画面パスはシステム設定の既定値(`wp-admin`)を前提とし、設定は書き換えない。
 */

const FIXTURE_HOST = 'slotlinks1530.invalid';

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

function slot(page: Page) {
  return page
    .locator('div.rounded-lg', { has: page.getByRole('heading', { name: 'テスト環境' }) })
    .first();
}

Given(
  '環境スロットのリンク検証用のプロジェクトとサイトを用意し、テスト環境に紐付けてある',
  async ({ page, request, ctx }) => {
    const headers = await adminHeaders(request);
    const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
    const project = await createFixtureProject(request, token, 'at-1530-slot');
    ctx.slotLinkProjectId = project.id;

    const unique = uniqueSuffix();
    const siteKey = `e2e-1530-${unique}`;
    const name = `E2E 1530 ${unique}`;
    const response = await request.post('/api/sites', {
      headers,
      data: {
        name,
        siteKey,
        cmsType: 'WORDPRESS',
        credentials: {
          transport: 'SSH',
          baseUrl: `http://${unique}.${FIXTURE_HOST}`,
          sshHost: 'wrong.invalid',
          sshUser: 'nouser',
          wpPath: '/nowhere',
          sshPrivateKeyPem:
            '-----BEGIN OPENSSH PRIVATE KEY-----\ne2e-1530-fixture-not-a-real-key\n-----END OPENSSH PRIVATE KEY-----',
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
    ctx.slotLinkSiteId = created.id;
    ctx.slotLinkSiteName = name;
    ctx.slotLinkSiteBaseUrl = baseUrl;

    await loginAsAdmin(page);
    await page.goto(`/projects/${project.id}`);
    await page.locator('select[aria-label="テスト環境に紐付けるサイト"]').selectOption(String(created.id));
    await slot(page).locator('button:has-text("紐付ける")').click();
    await expect(slot(page).getByText(name, { exact: true })).toBeVisible({ timeout: 10000 });
  }
);

Then('テスト環境のスロットに公開URLの文字列は表示されない', async ({ page }) => {
  await expect(slot(page)).not.toContainText(FIXTURE_HOST);
});

Then('テスト環境のスロットの「サイトを開く」リンクは公開URLを新しいタブで開く', async ({ ctx, page }) => {
  const link = slot(page).getByRole('link', { name: `${ctx.slotLinkSiteName} のサイトを開く` });
  await expect(link).toHaveAttribute('href', ctx.slotLinkSiteBaseUrl as string);
  await expect(link).toHaveAttribute('target', '_blank');
});

Then(
  'テスト環境のスロットの「管理画面を開く」リンクは公開URLの wp-admin を新しいタブで開く',
  async ({ ctx, page }) => {
    const link = slot(page).getByRole('link', { name: `${ctx.slotLinkSiteName} の管理画面を開く` });
    const base = (ctx.slotLinkSiteBaseUrl as string).replace(/\/$/, '');
    await expect(link).toHaveAttribute('href', `${base}/wp-admin`);
    await expect(link).toHaveAttribute('target', '_blank');
  }
);

Then(
  'テスト環境のスロットにサイト名を含む「サイトを開く」と「管理画面を開く」のリンクがある',
  async ({ ctx, page }) => {
    await expect(slot(page).getByRole('link', { name: `${ctx.slotLinkSiteName} のサイトを開く` })).toHaveCount(1);
    await expect(slot(page).getByRole('link', { name: `${ctx.slotLinkSiteName} の管理画面を開く` })).toHaveCount(1);
  }
);

Then('テスト環境のスロットの両アイコンリンクのツールチップに遷移先のURLが入っている', async ({ ctx, page }) => {
  const base = (ctx.slotLinkSiteBaseUrl as string).replace(/\/$/, '');
  await expect(slot(page).getByRole('link', { name: `${ctx.slotLinkSiteName} のサイトを開く` })).toHaveAttribute(
    'title',
    ctx.slotLinkSiteBaseUrl as string
  );
  await expect(slot(page).getByRole('link', { name: `${ctx.slotLinkSiteName} の管理画面を開く` })).toHaveAttribute(
    'title',
    `${base}/wp-admin`
  );
});

Then(
  'テスト環境のスロットの両アイコンリンクにTabキーでフォーカスでき可視のフォーカスリングが出る',
  async ({ ctx, page }) => {
    for (const label of ['サイトを開く', '管理画面を開く']) {
      const link = slot(page).getByRole('link', { name: `${ctx.slotLinkSiteName} の${label}` });
      await link.focus();
      await expect(link).toBeFocused();
      await page.keyboard.press('Shift+Tab');
      await page.keyboard.press('Tab');
      await expect(link).toBeFocused();
      const outline = await link.evaluate((el) => {
        const cs = getComputedStyle(el);
        return { style: cs.outlineStyle, width: parseFloat(cs.outlineWidth) };
      });
      expect(outline.style).not.toBe('none');
      expect(outline.width).toBeGreaterThan(0);
    }
  }
);

When('テスト環境のスロットの「切離し」を押す', async ({ page }) => {
  await slot(page).getByRole('button', { name: '切離し' }).click();
});

Then('テスト環境のスロットが未設定に戻る', async ({ page }) => {
  await expect(slot(page).getByText('未設定')).toBeVisible({ timeout: 10000 });
});

After({ tags: '@project' }, async ({ ctx, request }) => {
  if (ctx.slotLinkProjectId !== undefined) {
    const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
    await deleteFixtureProject(request, token, ctx.slotLinkProjectId as number);
  }
  if (ctx.slotLinkSiteId !== undefined) {
    const headers = await adminHeaders(request);
    await request.delete(`/api/sites/${ctx.slotLinkSiteId}`, { headers });
  }
});
