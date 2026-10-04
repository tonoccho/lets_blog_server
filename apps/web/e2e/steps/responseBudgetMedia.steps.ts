import { Given, When } from './fixtures';
import { expect } from '../support';
import { measureServerActionRoundTrip, recordResponseTime } from '../support/responseBudget';
import {
  adminHeaders,
  createSolidPng,
  registerCleanup,
  uniqueSuffix,
  waitForHydrated,
} from '../support/responseBudgetFixtures';

/**
 * 生成画像ギャラリー(`app/image-gallery/actions.ts`)の Server Action の3秒予算シナリオ(issue #1477、
 * `features/response-budget/server-action-media.feature`)のステップ定義。
 * 計測は共通の `measureServerActionRoundTrip`、判定は共通ステップ(`responseBudget.steps.ts`)。
 */

const IMAGE_ID_KEY = 'responseBudgetImageId';

Given('応答時間予算の検証用の生成画像がギャラリーにある', async ({ request, ctx }) => {
  const response = await request.post('/api/generated-images', {
    headers: await adminHeaders(request),
    data: {
      prompt: `E2E 1477 budget ${uniqueSuffix()}`,
      negativePrompt: 'blurry',
      steps: 20,
      cfgScale: 7.0,
      samplerName: 'euler',
      scheduler: 'normal',
      width: 64,
      height: 64,
      batchSize: 1,
      checkpoint: 'e2e.safetensors',
      provider: 'COMFYUI',
      seed: 1_477_000,
      mimeType: 'image/png',
      imageData: createSolidPng(64, 64, [90, 90, 90]).toString('base64'),
    },
  });
  expect(response.ok(), `生成画像の保存に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  const id = ((await response.json()) as { id: number }).id;
  ctx[IMAGE_ID_KEY] = id;
  registerCleanup(ctx, async () => {
    await request.delete(`/api/generated-images/${id}`, { headers: await adminHeaders(request) });
  });
});

/** ギャラリーを開き、検証用の画像のサムネイルが描画・ハイドレートされるまで待つ。 */
async function openGallery(page: import('@playwright/test').Page, ctx: Record<string, unknown>) {
  await page.goto('/image-gallery');
  const thumbnail = page.locator(`img[src="/image-gallery/${ctx[IMAGE_ID_KEY]}/file"]`);
  await expect(thumbnail).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(thumbnail);
  return thumbnail;
}

When('生成画像ギャラリーでその画像を開いて Server Action の往復を計測する', async ({ page, ctx }) => {
  const thumbnail = await openGallery(page, ctx);
  const timing = await measureServerActionRoundTrip(page, async () => {
    await thumbnail.click();
    await expect(page.locator('dt:text-is("prompt") + dd')).toBeVisible({ timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, '生成画像の詳細の取得(Server Action)の往復');
});

When('生成画像ギャラリーでその画像の詳細を開いてタグを追加し Server Action の往復を計測する', async ({ page, ctx }) => {
  const thumbnail = await openGallery(page, ctx);
  await thumbnail.click();
  await expect(page.locator('dt:text-is("prompt") + dd')).toBeVisible({ timeout: 30_000 });
  const tag = `e2e1477${uniqueSuffix()}`;
  await page.getByPlaceholder('タグを追加').fill(tag);
  const timing = await measureServerActionRoundTrip(page, async () => {
    await page.getByRole('button', { name: '追加', exact: true }).click();
    await expect(page.getByRole('button', { name: `タグ「${tag}」を削除` })).toBeVisible({ timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, '生成画像のタグ保存(Server Action)の往復');
});

When('生成画像ギャラリーでその画像の詳細を開いて削除し Server Action の往復を計測する', async ({ page, ctx }) => {
  const thumbnail = await openGallery(page, ctx);
  await thumbnail.click();
  await expect(page.locator('dt:text-is("prompt") + dd')).toBeVisible({ timeout: 30_000 });
  page.once('dialog', (dialog) => void dialog.accept());
  const timing = await measureServerActionRoundTrip(page, async () => {
    await page.getByRole('button', { name: '削除', exact: true }).click();
    await expect(page.getByText('生成画像の詳細')).toHaveCount(0, { timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, '生成画像の削除(Server Action)の往復');
});

When('生成画像ギャラリーでその画像を選択して一括削除し Server Action の往復を計測する', async ({ page, ctx }) => {
  const thumbnail = await openGallery(page, ctx);
  // 検証用の1枚だけを選ぶ(全選択すると他の画像まで消える)。チェックボックスは画像と同じカードにある。
  await thumbnail.locator('xpath=ancestor::div[contains(@class,"relative")][1]').getByRole('checkbox').check();
  page.once('dialog', (dialog) => void dialog.accept());
  const timing = await measureServerActionRoundTrip(page, async () => {
    await page.getByRole('button', { name: '選択した1件を削除' }).click();
    await expect(page.locator(`img[src="/image-gallery/${ctx[IMAGE_ID_KEY]}/file"]`)).toHaveCount(0, { timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, '生成画像の一括削除(Server Action)の往復');
});
