import type { APIResponse } from '@playwright/test';
import { Then, When } from './fixtures';
import { expect } from '../support';

/**
 * ログイン必須の添付ファイル配信3経路のCache-Control(issue #1263、attachment-cache-control.feature)。
 *
 * `page.request` は `page` と同じBrowserContextのCookieを共有するため、ログイン済みのブラウザの
 * セッションでBFFのルートハンドラ(`/downloads/...`等。Spring Bootの`/api/`ではない)を呼べる。
 */

When('ブラウザのセッションで「{word}」を取得する', async ({ ctx, page }, path: string) => {
  ctx.attachmentResponse = await page.request.get(path, { timeout: 170_000 });
});

When('ブラウザのセッションでそのプロジェクトの統合CSS配信経路を取得する', async ({ ctx, page }) => {
  ctx.attachmentResponse = await page.request.get(
    `/projects/${ctx.tagProjectId as number}/custom-tags/css-bundle`
  );
});

Then('取得した添付ファイルの応答のCache-Controlは「no-store」を含む', async ({ ctx }) => {
  const response = ctx.attachmentResponse as APIResponse;
  expect(response.status(), '添付ファイルの取得に失敗しました').toBe(200);
  const cacheControl = response.headers()['cache-control'] ?? '';
  expect(cacheControl, `Cache-Control: ${cacheControl}`).toContain('no-store');
});
