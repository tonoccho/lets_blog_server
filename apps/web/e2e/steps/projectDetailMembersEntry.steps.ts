import type { Page } from '@playwright/test';
import { Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken, loginAsAdmin } from '../support';

/**
 * プロジェクト詳細のメンバー追加の入口(issue #1670)のステップ定義。
 * 背景(検証用のプロジェクト・メンバー検証用のアカウント)と After の後片付け、
 * 「プロジェクト詳細のメンバータブが選択されている」は他のステップ定義(ctx の pm*)を再利用する。
 */

async function adminHeaders(request: import('@playwright/test').APIRequestContext) {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

function tabButton(page: Page, name: string) {
  return page.getByRole('button', { name, exact: true });
}

/** タブを開く。ハイドレーション前のクリック取りこぼしに備え、選択状態になるまでクリックを繰り返す。 */
async function selectTab(page: Page, name: string): Promise<void> {
  const tab = tabButton(page, name);
  await expect(tab).toBeVisible({ timeout: 30_000 });
  await expect(async () => {
    await tab.click();
    await expect(tab).toHaveAttribute('aria-pressed', 'true', { timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
}

async function openDetail(page: Page, projectId: number): Promise<void> {
  await loginAsAdmin(page);
  await page.goto(`/projects/${projectId}`, { waitUntil: 'commit' });
}

Given('メンバー入口検証用にそのプロジェクトのサイトが紐付いている', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  const suffix = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
  const siteKey = `e2e-1670-site-${suffix}`;
  const created = await request.post('/api/sites', {
    headers,
    data: {
      name: `E2E 1670 site ${suffix}`,
      siteKey,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'SSH',
        baseUrl: 'http://wrong.invalid',
        sshHost: 'wrong.invalid',
        sshUser: 'nouser',
        wpPath: '/nowhere',
        sshPrivateKeyPem:
          '-----BEGIN OPENSSH PRIVATE KEY-----\ne2e-1670-fixture-not-a-real-key\n-----END OPENSSH PRIVATE KEY-----',
      },
    },
  });
  expect(created.ok(), `サイトの登録に失敗しました (status=${created.status()}): ${await created.text()}`).toBe(true);
  const siteId = ((await created.json()) as { id: number }).id;
  // projectMember.steps.ts の After('@identity') が pmUnreachableSiteId を後片付けする。
  ctx.pmUnreachableSiteId = siteId;
  const bound = await request.post(`/api/projects/${ctx.pmProjectId as number}/environments`, {
    headers,
    data: { environment: 'test', siteId },
  });
  expect(bound.ok(), `環境紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`).toBe(true);
});

When('メンバー入口検証用に管理者としてプロジェクト詳細を開く', async ({ ctx, page }) => {
  await openDetail(page, ctx.pmProjectId as number);
});

When('メンバー入口検証用に管理者としてプロジェクト詳細のメンバータブを開く', async ({ ctx, page }) => {
  await openDetail(page, ctx.pmProjectId as number);
  await selectTab(page, 'メンバー');
});

When('メンバー入口検証用に管理者としてプロジェクト詳細の設定タブを開く', async ({ ctx, page }) => {
  await openDetail(page, ctx.pmProjectId as number);
  await selectTab(page, '設定');
});

When('概要タブの「メンバータブ」リンクを押す', async ({ page }) => {
  await page.getByRole('link', { name: 'メンバータブ', exact: true }).click();
});

When('メンバータブでそのアカウントをauthorとして追加する', async ({ ctx, page }) => {
  const form = page.locator('form').filter({ hasText: 'ユーザーを追加' });
  await form.locator('select[name="userId"]').selectOption({ label: ctx.pmMemberEmail as string });
  await form.locator('select[name="wpRole"]').selectOption('author');
  await form.getByRole('button', { name: '追加', exact: true }).click();
});

Then('概要タブに「ユーザーを追加」のフォームは表示されていない', async ({ page }) => {
  await expect(page.getByText('このプロジェクトにはメンバーがいません', { exact: false })).toBeVisible({ timeout: 30_000 });
  await expect(page.locator('form').filter({ hasText: 'ユーザーを追加' })).toHaveCount(0);
});

Then('概要タブに「メンバーがいません」の案内が表示されている', async ({ page }) => {
  await expect(page.getByText('メンバーがいません', { exact: false })).toBeVisible({ timeout: 30_000 });
});

Then('メンバータブのメンバー一覧にそのアカウントが表示される', async ({ ctx, page }) => {
  await expect(page.locator('tr', { hasText: ctx.pmMemberEmail as string })).toBeVisible({ timeout: 60_000 });
});

Then('設定タブに「モデル設定」の見出しは表示されていない', async ({ page }) => {
  // 設定タブ自身の内容が出てから、案内カードが無いことを確かめる。
  await expect(page.getByRole('heading', { name: '外部サービス連携' })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('heading', { name: 'モデル設定', exact: true })).toHaveCount(0);
});
