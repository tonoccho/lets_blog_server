import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';
import { clickUntilDone, withDialogAccepted } from '../support/retryClick';

/**
 * サイトの登録・編集・登録解除と疎通失敗時の表示のステップ定義(issue #1168 / AT-5-4)。
 *
 * 兄弟issue(#1166 / #1167)と同じ「ステップ定義ファイルは相乗りしない」方針(issue本文の
 * Scope)のため、必要なヘルパーはこのファイル内に閉じて持つ。
 *
 * フィクスチャのサイトは意図的に到達不能なSSHホストを使う。理由は2つ:
 *   - シナリオ8(疎通確認の失敗)がそのまま成立する
 *   - シナリオ9・10・16は疎通の成否を問わないため、同じ資格情報パターンを使い回せる
 * `POST /api/sites` はプロジェクトへの紐付けを一切行わないため、ここで作るサイトは
 * すべて「プロジェクトに紐付いていないサイト」でもある(シナリオ16の前提を兼ねる)。
 */

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await adminToken(request)}` };
}

/** issue #765: フィクスチャ名は並列実行時の衝突・孤児サイト対策でタイムスタンプ+乱数にする。 */
function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

interface FixtureSite {
  id: number;
  siteKey: string;
  name: string;
}

/**
 * `POST /api/sites` で、到達不能なSSHホストを指す既存WordPressサイトを登録する。
 * 登録自体は疎通確認の成否によらず完了する(`SiteForm.tsx`の説明文と同じ挙動)。
 */
async function registerFixtureSite(
  request: APIRequestContext,
  headers: Record<string, string>,
  prefix: string
): Promise<FixtureSite> {
  const unique = uniqueSuffix();
  const siteKey = `e2e-1168-${prefix}-${unique}`;
  const name = `E2E 1168 ${prefix} ${unique}`;
  const response = await request.post('/api/sites', {
    headers,
    data: {
      name,
      siteKey,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'SSH',
        baseUrl: 'http://wrong.invalid',
        sshHost: 'wrong.invalid',
        sshUser: 'nouser',
        wpPath: '/nowhere',
        sshPrivateKeyPem:
          '-----BEGIN OPENSSH PRIVATE KEY-----\ne2e-1168-fixture-not-a-real-key\n-----END OPENSSH PRIVATE KEY-----',
      },
    },
  });
  expect(
    response.ok(),
    `フィクスチャのサイト登録に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const body = (await response.json()) as { id: number };
  return { id: body.id, siteKey, name };
}

/** 登録済みかどうかによらず安全に呼べる後片付け用の削除(既に削除済みなら404になるだけ)。 */
async function deleteFixtureSiteIfPresent(
  request: APIRequestContext,
  headers: Record<string, string>,
  id: number | undefined
): Promise<void> {
  if (id === undefined) {
    return;
  }
  await request.delete(`/api/sites/${id}`, { headers });
}

// ------------------------------------------------------- 背景

// siteProvisioning.steps.ts(#1167)で定義済みの共通Stepを再利用する(loginAsAdmin + /sites遷移)。
// ここでは再定義しない。

// ------------------------------------------------------- シナリオ8: 疎通確認の失敗表示

Given('資格情報が誤っているサイトを登録しておく', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  const site = await registerFixtureSite(request, headers, 'connfail');
  ctx.connFailSiteId = site.id;
  ctx.connFailSiteKey = site.siteKey;
});

When('サイト一覧でそのサイトの疎通確認を実行する', async ({ ctx, page }) => {
  // 前提のサイト登録は背景のページ表示より後に起きるため、一覧を読み直してから探す。
  await page.reload();
  const row = page.locator(`tr:has-text("${ctx.connFailSiteKey}")`);
  await expect(row).toBeVisible();
  await row.locator('button:has-text("疎通確認")').click();
});

Then('疎通確認の結果が失敗と表示される', async ({ ctx, page }) => {
  const row = page.locator(`tr:has-text("${ctx.connFailSiteKey}")`);
  await expect(row.getByText('FAILED', { exact: true })).toBeVisible({ timeout: 15000 });
});

Then('失敗理由のメッセージが画面に表示される', async ({ ctx, page }) => {
  // CheckConnectionButton.tsx: ステータスラベル(「FAILED」)と失敗理由のspanはどちらも
  // text-red-700で、失敗理由は理由文字列があるときだけ追加で描画される。2つ目の要素として
  // 出現することを、空でない・「FAILED」そのものではないことで確認する。
  const row = page.locator(`tr:has-text("${ctx.connFailSiteKey}")`);
  const redSpans = row.locator('span.text-red-700');
  await expect(redSpans).toHaveCount(2, { timeout: 15000 });
  const reasonText = (await redSpans.nth(1).textContent())?.trim() ?? '';
  expect(reasonText.length > 0 && reasonText !== 'FAILED', `失敗理由のメッセージが見つかりません: "${reasonText}"`).toBe(
    true
  );
});

// ------------------------------------------------------- シナリオ9: サイト編集

Given('編集検証用のサイトを登録しておく', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  const site = await registerFixtureSite(request, headers, 'edit');
  ctx.editSiteId = site.id;
  ctx.editSiteKey = site.siteKey;
});

When('サイト編集画面で表示名を変更して保存する', async ({ ctx, page }) => {
  const editedName = `E2E 1168 edited ${uniqueSuffix()}`;
  ctx.editedSiteName = editedName;
  await page.goto(`/sites/${ctx.editSiteId}/edit`);
  await page.locator('input[name="name"]').fill(editedName);
  await page.locator('button[type="submit"]').click();
  await expect(page.getByText('保存しました。')).toBeVisible({ timeout: 10000 });
});

Then('変更後の表示名が画面とAPIの両方で確認できる', async ({ ctx, page, request }) => {
  await page.goto('/sites');
  const row = page.locator(`tr:has-text("${ctx.editSiteKey}")`);
  await expect(row.getByText(ctx.editedSiteName as string)).toBeVisible();

  const headers = await adminHeaders(request);
  const response = await request.get(`/api/sites/${ctx.editSiteId}`, { headers });
  expect(
    response.ok(),
    `サイト詳細の取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const detail = (await response.json()) as { name: string };
  expect(detail.name).toBe(ctx.editedSiteName);
});

// ------------------------------------------------------- シナリオ10: 登録解除

Given('削除検証用のサイトを登録しておく', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  const site = await registerFixtureSite(request, headers, 'delete');
  ctx.deleteSiteId = site.id;
  ctx.deleteSiteKey = site.siteKey;
});

When('サイト一覧からそのサイトを削除する', async ({ ctx, page }) => {
  await page.reload();
  const row = page.locator(`tr:has-text("${ctx.deleteSiteKey}")`);
  await expect(row).toBeVisible();
  // issue #1386: reload直後はハイドレーション未完了でクリックが空振りしうるため、行が消えるまで
  // クリックし直す。確認ダイアログは再試行のたびに出るので毎回acceptする。
  await withDialogAccepted(page, () =>
    clickUntilDone(row.locator('button:has-text("削除")'), {
      isDone: async () => (await row.count()) === 0,
      waitDone: (timeout) => row.waitFor({ state: 'detached', timeout }),
    })
  );
  await expect(row).toHaveCount(0, { timeout: 10000 });
  ctx.deleteSiteAlreadyDeleted = true;
});

Then('そのサイトが一覧とAPIの両方から消えている', async ({ ctx, page, request }) => {
  await expect(page.locator(`tr:has-text("${ctx.deleteSiteKey}")`)).toHaveCount(0);

  const headers = await adminHeaders(request);
  const response = await request.get(`/api/sites/${ctx.deleteSiteId}`, { headers });
  expect(response.status()).toBe(404);
});

// ------------------------------------------------------- シナリオ16: #759の退行検知

Given('プロジェクトに紐付いていないサイトを登録しておく', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  const site = await registerFixtureSite(request, headers, 'unlinked');
  ctx.unlinkedSiteId = site.id;
  ctx.unlinkedSiteKey = site.siteKey;
});

Then('そのサイトの疎通確認は500にならず結果が返る', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const response = await request.post(`/api/sites/${ctx.unlinkedSiteId}/test-connection`, { headers });
  expect(
    response.status(),
    `未紐付けサイトの疎通確認が500になりました (#759退行, status=${response.status()}): ${await response.text()}`
  ).toBeLessThan(500);
  expect(response.ok()).toBe(true);
});

Then('そのサイトの編集は500にならず保存できる', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const response = await request.put(`/api/sites/${ctx.unlinkedSiteId}`, {
    headers,
    data: { name: 'E2E 1168 unlinked renamed' },
  });
  expect(
    response.status(),
    `未紐付けサイトの編集が500になりました (#759退行, status=${response.status()}): ${await response.text()}`
  ).toBeLessThan(500);
  expect(response.ok()).toBe(true);
});

Then('そのサイトの削除は500にならず完了する', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const response = await request.delete(`/api/sites/${ctx.unlinkedSiteId}`, { headers });
  expect(
    response.status(),
    `未紐付けサイトの削除が500になりました (#759退行, status=${response.status()}): ${await response.text()}`
  ).toBeLessThan(500);
  expect([200, 204]).toContain(response.status());
  ctx.unlinkedSiteAlreadyDeleted = true;
});

// ------------------------------------------------------- 後片付け

After({ tags: '@project' }, async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  await deleteFixtureSiteIfPresent(request, headers, ctx.connFailSiteId as number | undefined);
  await deleteFixtureSiteIfPresent(request, headers, ctx.editSiteId as number | undefined);
  if (!ctx.deleteSiteAlreadyDeleted) {
    await deleteFixtureSiteIfPresent(request, headers, ctx.deleteSiteId as number | undefined);
  }
  if (!ctx.unlinkedSiteAlreadyDeleted) {
    await deleteFixtureSiteIfPresent(request, headers, ctx.unlinkedSiteId as number | undefined);
  }
});
