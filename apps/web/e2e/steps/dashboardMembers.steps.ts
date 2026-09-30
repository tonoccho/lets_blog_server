import type { Locator, Page } from '@playwright/test';
import { Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken, loginAsAdmin } from '../support';

/**
 * ダッシュボードのメンバーウィジェットのステップ定義(issue #1502)。
 * 背景と「管理者がそのメンバーをプロジェクトへauthorとして追加している」は
 * `projectMember.steps.ts`(ctx の pm* を読む)を再利用する。
 */

interface ProjectUserItem {
  userId: number;
  email: string | null;
  displayName: string | null;
  wpRole: string;
}

function widget(page: Page): Locator {
  return page.locator('div.rounded-lg').filter({ has: page.locator('h2', { hasText: /^メンバー$/ }) }).first();
}

async function adminHeaders(request: import('@playwright/test').APIRequestContext) {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

async function fetchMember(request: import('@playwright/test').APIRequestContext, projectId: number, userId: number) {
  const res = await request.get(`/api/projects/${projectId}/users`, { headers: await adminHeaders(request) });
  expect(res.ok(), `メンバー一覧の取得に失敗しました (status=${res.status()})`).toBe(true);
  const member = ((await res.json()) as ProjectUserItem[]).find((m) => m.userId === userId);
  expect(member, `メンバー一覧にuserId=${userId}が見つかりません`).toBeTruthy();
  return member as ProjectUserItem;
}

Given('そのプロジェクトのメンバーが全員外れている', async ({ request, ctx }) => {
  const projectId = ctx.pmProjectId as number;
  const headers = await adminHeaders(request);
  const res = await request.get(`/api/projects/${projectId}/users`, { headers });
  expect(res.ok(), `メンバー一覧の取得に失敗しました (status=${res.status()})`).toBe(true);
  for (const member of (await res.json()) as ProjectUserItem[]) {
    const del = await request.delete(`/api/projects/${projectId}/users/${member.userId}`, { headers });
    expect(del.ok(), `メンバー除外に失敗しました (status=${del.status()})`).toBe(true);
  }
});

When('メンバーウィジェットを見るためにダッシュボードを開く', async ({ page, ctx }) => {
  await loginAsAdmin(page);
  await page.goto(`/projects/${ctx.pmProjectId as number}/dashboard`);
  await expect(widget(page)).toBeVisible({ timeout: 30_000 });
});

When('メンバーウィジェットのメンバー管理リンクを押す', async ({ page }) => {
  await widget(page).getByRole('link', { name: 'メンバーを管理' }).click();
});

Then('メンバーウィジェットにそのメンバーがauthorロールで一覧される', async ({ page, request, ctx }) => {
  const member = await fetchMember(request, ctx.pmProjectId as number, ctx.pmMemberId as number);
  const name = member.displayName ?? member.email ?? `ユーザー#${member.userId}`;
  const row = widget(page).getByRole('listitem').filter({ hasText: name });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText('author');
});

Then('メンバーウィジェットにはロール変更や削除の操作がない', async ({ page }) => {
  const scope = widget(page);
  await expect(scope.locator('select, input, form, button')).toHaveCount(0);
});

Then('メンバーウィジェットに0人と表示される', async ({ page }) => {
  await expect(widget(page).getByText('0人', { exact: true })).toBeVisible();
});

Then('プロジェクト詳細のメンバータブが選択されている', async ({ page, ctx }) => {
  await expect(page).toHaveURL(new RegExp(`/projects/${ctx.pmProjectId as number}\\?tab=members`));
  await expect(page.getByRole('button', { name: 'メンバー', exact: true })).toHaveAttribute('aria-pressed', 'true');
});

Then('メンバータブでそのメンバーの役割をeditorへ変更できる', async ({ page, request, ctx }) => {
  const select = page.locator('table select').first();
  await expect(select).toHaveValue('author');
  await select.selectOption('editor');
  await expect
    .poll(async () => (await fetchMember(request, ctx.pmProjectId as number, ctx.pmMemberId as number)).wpRole)
    .toBe('editor');
});
