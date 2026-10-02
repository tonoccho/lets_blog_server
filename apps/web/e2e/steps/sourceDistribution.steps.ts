import { expect } from '@playwright/test';
import { Then, When } from './fixtures';

/**
 * Penpotプラグイン / MCPサーバーのソースZip配布(source-distribution.feature、issue #1491)。
 *
 * 「管理者としてログインする」「ビューポート幅NNNpxでダッシュボードを開く」
 * 「ヘッダーのダウンロードメニューを開く」は既存の共通ステップを再利用する。
 */

const EOCD_SIGNATURE = 0x06054b50;
const CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50;

/** zipのセントラルディレクトリからエントリ名だけを読む(vscodeExtension.steps.ts と同じ方式)。 */
function listZipEntries(bytes: Buffer): string[] {
  const eocdOffset = bytes.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  if (eocdOffset === -1 || bytes.readUInt32LE(eocdOffset) !== EOCD_SIGNATURE) {
    throw new Error('zipのEnd Of Central Directoryレコードが見つかりませんでした');
  }
  const count = bytes.readUInt16LE(eocdOffset + 10);
  let cursor = bytes.readUInt32LE(eocdOffset + 16);
  const entries: string[] = [];
  for (let i = 0; i < count; i += 1) {
    if (bytes.readUInt32LE(cursor) !== CENTRAL_DIRECTORY_SIGNATURE) {
      throw new Error(`zipのセントラルディレクトリのヘッダが不正です(offset=${cursor})`);
    }
    const nameLength = bytes.readUInt16LE(cursor + 28);
    const extraLength = bytes.readUInt16LE(cursor + 30);
    const commentLength = bytes.readUInt16LE(cursor + 32);
    const nameStart = cursor + 46;
    entries.push(bytes.toString('utf-8', nameStart, nameStart + nameLength));
    cursor = nameStart + nameLength + extraLength + commentLength;
  }
  return entries;
}

Then('ダウンロードメニューにPenpotプラグインとMCPサーバーの項目が表示される', async ({ page }) => {
  await expect(page.getByRole('menuitem', { name: /Penpotプラグイン/ })).toBeVisible();
  await expect(page.getByRole('menuitem', { name: /MCPサーバー/ })).toBeVisible();
});

When('ダウンロードメニューのPenpotプラグインを選ぶ', async ({ page, ctx }) => {
  const downloadPromise = page.waitForEvent('download', { timeout: 85_000 });
  await page.getByRole('menuitem', { name: /Penpotプラグイン/ }).click();
  ctx.headerDownload = await downloadPromise;
});

When('ダウンロードメニューのMCPサーバーを選ぶ', async ({ page, ctx }) => {
  const downloadPromise = page.waitForEvent('download', { timeout: 85_000 });
  await page.getByRole('menuitem', { name: /MCPサーバー/ }).click();
  ctx.headerDownload = await downloadPromise;
});

Then('.zipファイルがダウンロードされる', async ({ ctx }) => {
  const download = ctx.headerDownload as { suggestedFilename(): string };
  expect(download.suggestedFilename()).toMatch(/\.zip$/);
});

When('Penpotプラグインをダウンロードする', async ({ page, ctx }) => {
  const response = await page.request.get('/downloads/penpot-plugin');
  expect(response.ok(), `Penpotプラグインの取得に失敗 (status=${response.status()}): ${await response.text()}`).toBe(true);
  ctx.distributionZipBytes = await response.body();
});

When('MCPサーバーをダウンロードする', async ({ page, ctx }) => {
  const response = await page.request.get('/downloads/mcp-server');
  expect(response.ok(), `MCPサーバーの取得に失敗 (status=${response.status()}): ${await response.text()}`).toBe(true);
  ctx.distributionZipBytes = await response.body();
});

Then('ダウンロードしたZipにsetup.shとpackage-lock.jsonが含まれる', async ({ ctx }) => {
  const entries = listZipEntries(ctx.distributionZipBytes as Buffer);
  expect(entries.some((n) => /^[^/]+\/setup\.sh$/.test(n)), 'setup.sh がありません').toBe(true);
  expect(entries.some((n) => /^[^/]+\/package-lock\.json$/.test(n)), 'package-lock.json がありません').toBe(true);
});

Then('ダウンロードしたZipにnode_modulesが含まれない', async ({ ctx }) => {
  const offending = listZipEntries(ctx.distributionZipBytes as Buffer).filter((n) => n.includes('node_modules'));
  expect(offending, `node_modules が含まれています: ${offending.join(', ')}`).toEqual([]);
});

Then('ダウンロードしたZipにPenpotプラグインのビルド生成物が含まれない', async ({ ctx }) => {
  const offending = listZipEntries(ctx.distributionZipBytes as Buffer).filter((n) =>
    /^[^/]+\/(plugin|ui)\.js$/.test(n)
  );
  expect(offending, `ビルド生成物が含まれています: ${offending.join(', ')}`).toEqual([]);
});

When('認証なしでPenpotプラグインとMCPサーバーの配布経路にアクセスする', async ({ request, ctx }) => {
  ctx.distributionStatuses = [
    (await request.get('/api/system/penpot-plugin')).status(),
    (await request.get('/api/system/mcp-server')).status(),
  ];
});

Then('どちらも401で拒否される', async ({ ctx }) => {
  expect(ctx.distributionStatuses).toEqual([401, 401]);
});
