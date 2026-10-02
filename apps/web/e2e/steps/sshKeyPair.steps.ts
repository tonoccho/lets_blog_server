import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken, loginAsAdmin } from '../support';
import { clickUntilDone, withDialogAccepted } from '../support/retryClick';

/**
 * SSH鍵ペアの生成・秘密鍵の非開示・削除のステップ定義(issue #1170 / AT-5-6)。
 *
 * 兄弟issue(#1166〜#1169)と同じ「ステップ定義ファイルは相乗りしない」方針(issue本文の
 * Scope)のため、必要なヘルパーはこのファイル内に閉じて持つ。
 *
 * 削除時の「使用中(サイトから参照中)かどうか」チェックは`SshKeyPairService.java`のコメント
 * (issue #577 stage 3のTODO)の通り現行実装に存在しない。このファイルは現行の無条件削除
 * 挙動をそのまま固定し、そのチェックを追加する変更は行わない(issue本文のOut of Scope)。
 */

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await adminToken(request)}` };
}

/** issue #765と同じ理由(並列実行時の衝突対策)でフィクスチャ名をタイムスタンプ+乱数にする。 */
function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

interface SavedKeyPair {
  id: number;
  name: string;
  comment: string | null;
  publicKeyLine: string;
  createdAt: string;
}

async function listKeyPairs(request: APIRequestContext, headers: Record<string, string>): Promise<{
  status: number;
  bodyText: string;
  items: SavedKeyPair[];
}> {
  const response = await request.get('/api/ssh-key-pairs', { headers });
  const bodyText = await response.text();
  return { status: response.status(), bodyText, items: JSON.parse(bodyText) as SavedKeyPair[] };
}

async function deleteKeyPairIfPresent(
  request: APIRequestContext,
  headers: Record<string, string>,
  id: number | undefined
): Promise<void> {
  if (id === undefined) {
    return;
  }
  await request.delete(`/api/ssh-key-pairs/${id}`, { headers });
}

// ------------------------------------------------------- 背景

Given('SSH鍵管理ページを開いている', async ({ page }) => {
  await loginAsAdmin(page);
  await page.goto('/admin/ssh-keys');
});

// ------------------------------------------------------- シナリオ12: 生成・秘密鍵の非開示

When('SSH鍵管理ページで新しいSSH鍵ペアを生成する', async ({ ctx, page }) => {
  const unique = uniqueSuffix();
  const name = `e2e-1170-generate-${unique}`;
  ctx.generatedKeyName = name;

  await page.locator('input[type="text"]').first().fill(name);
  await page.locator('button:has-text("SSH鍵ペアを生成")').click();
  await expect(page.getByText(`「${name}」を生成しました。`)).toBeVisible({ timeout: 10000 });

  const publicKeyLine = await page
    .locator('p:has-text("公開鍵") + textarea')
    .inputValue();
  const privateKeyPem = await page
    .locator('p:has-text("秘密鍵") + textarea')
    .inputValue();
  ctx.generatedPublicKeyLine = publicKeyLine;
  ctx.generatedPrivateKeyPem = privateKeyPem;
});

Then('生成直後の画面に公開鍵と秘密鍵が表示される', async ({ ctx }) => {
  const publicKeyLine = ctx.generatedPublicKeyLine as string;
  const privateKeyPem = ctx.generatedPrivateKeyPem as string;
  expect(publicKeyLine.startsWith('ssh-ed25519 '), `公開鍵の形式が不正です: "${publicKeyLine}"`).toBe(true);
  expect(privateKeyPem.includes('PRIVATE KEY'), `秘密鍵の形式が不正です: "${privateKeyPem}"`).toBe(true);
});

Then('鍵ペア一覧のAPIレスポンスに秘密鍵は含まれない', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const { items, bodyText } = await listKeyPairs(request, headers);

  const created = items.find((item) => item.name === ctx.generatedKeyName);
  expect(created, `生成したSSH鍵ペア「${ctx.generatedKeyName}」が一覧に見つかりません`).toBeTruthy();
  ctx.generatedKeyId = created?.id;

  // フィールド名としての秘密鍵(privateKeyPem)が一覧のレスポンスに存在しないこと。
  expect('privateKeyPem' in (created as unknown as Record<string, unknown>)).toBe(false);

  // 秘密鍵の実際の値そのものもレスポンス本文に含まれないこと(非開示の直接確認)。
  const privateKeyPem = ctx.generatedPrivateKeyPem as string;
  expect(
    bodyText.includes(privateKeyPem),
    '一覧のAPIレスポンスに生成時の秘密鍵の値が含まれています'
  ).toBe(false);
});

// ------------------------------------------------------- シナリオ: 削除(現行の無条件削除挙動)

Given('削除検証用のSSH鍵ペアを生成しておく', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  const unique = uniqueSuffix();
  const name = `e2e-1170-delete-${unique}`;
  const response = await request.post('/api/ssh-key-pairs', {
    headers,
    data: { name },
  });
  expect(
    response.ok(),
    `フィクスチャのSSH鍵ペア生成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const body = (await response.json()) as { id: number; name: string };
  ctx.deleteKeyPairId = body.id;
  ctx.deleteKeyPairName = body.name;
});

When('SSH鍵管理ページでそのSSH鍵ペアを削除する', async ({ ctx, page }) => {
  await page.reload();
  const row = page.locator(`tr:has-text("${ctx.deleteKeyPairName}")`);
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
  ctx.deleteKeyPairAlreadyDeleted = true;
});

Then('そのSSH鍵ペアが一覧とAPIの両方から消えている', async ({ ctx, page, request }) => {
  await expect(page.locator(`tr:has-text("${ctx.deleteKeyPairName}")`)).toHaveCount(0);

  const headers = await adminHeaders(request);
  const { items } = await listKeyPairs(request, headers);
  const stillPresent = items.some((item) => item.id === ctx.deleteKeyPairId);
  expect(stillPresent, `削除したはずのSSH鍵ペア「${ctx.deleteKeyPairName}」がAPIの一覧にまだ存在します`).toBe(
    false
  );
});

// ------------------------------------------------------- 後片付け

After({ tags: '@project' }, async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  await deleteKeyPairIfPresent(request, headers, ctx.generatedKeyId as number | undefined);
  if (!ctx.deleteKeyPairAlreadyDeleted) {
    await deleteKeyPairIfPresent(request, headers, ctx.deleteKeyPairId as number | undefined);
  }
});
