import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * 生成画像ギャラリーのseed表示のステップ定義(issue #1101)。
 *
 * 画像そのものの生成は行わない。ComfyUIはGPU必須の任意サービスで受け入れテスト環境に
 * 常在せず、OpenAI画像スタブはseedの概念を持たないため、「どのseedで生成されたか」を
 * 生成経由で作り分けられない。ここで確かめたいのは**保存済みの画像の詳細画面が
 * seedをどう見せるか**なので、`POST /api/generated-images`(VSCode拡張が実際に使う
 * 保存経路)で行を作ってからギャラリーを開く。
 *
 * seedの実値決定そのもの(seed未指定でも非nullになる・バッチ内位置が0起点で振られる)は
 * サービスレベルのテストが担う(services/media の ImageGenerationServiceTest)。
 */

/** 1x1の透明PNG。中身は問わないので固定バイト列で足りる。 */
const PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

interface CreatedImage {
  id: number;
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function createGeneratedImage(
  request: APIRequestContext,
  overrides: Record<string, unknown>
): Promise<number> {
  const token = await adminToken(request);
  const response = await request.post('/api/generated-images', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      prompt: 'e2e seed fixture',
      negativePrompt: 'blurry',
      steps: 20,
      cfgScale: 7.0,
      samplerName: 'euler',
      scheduler: 'normal',
      width: 512,
      height: 512,
      batchSize: 1,
      checkpoint: 'e2e.safetensors',
      mimeType: 'image/png',
      imageData: PNG_BASE64,
      ...overrides,
    },
  });
  expect(
    response.ok(),
    `生成画像の保存に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return ((await response.json()) as CreatedImage).id;
}

/** 詳細ダイアログの、指定した項目名に対応する値。 */
function detailValue(page: Page, label: string) {
  return page.locator(`dt:text-is("${label}") + dd`);
}

Given(
  /^seed「(\d+)」・バッチ内位置「(\d+)」のComfyUI画像がギャラリーにある$/,
  async ({ ctx, request }, seed: string, batchIndex: string) => {
    ctx.mediaImageId = await createGeneratedImage(request, {
      provider: 'COMFYUI',
      seed: Number(seed),
      batchSize: 2,
      batchIndex: Number(batchIndex),
    });
  }
);

Given('seedを持たないChatGPT画像がギャラリーにある', async ({ ctx, request }) => {
  ctx.mediaImageId = await createGeneratedImage(request, {
    provider: 'CHATGPT',
    seed: null,
    batchIndex: null,
  });
});

When('生成画像ギャラリーでその画像の詳細を開く', async ({ ctx, page }) => {
  await page.goto('/image-gallery', { waitUntil: 'commit' });
  await page.locator(`img[src="/image-gallery/${ctx.mediaImageId}/file"]`).click();
  await expect(page.getByText('生成画像の詳細')).toBeVisible({ timeout: 15_000 });
  await expect(detailValue(page, 'prompt')).toBeVisible({ timeout: 15_000 });
});

Then(/^詳細にseed「(\d+)」が表示される$/, async ({ page }, seed: string) => {
  await expect(detailValue(page, 'seed')).toHaveText(seed);
});

Then(/^詳細にバッチ内位置「(\d+)」が表示される$/, async ({ page }, batchIndex: string) => {
  await expect(detailValue(page, 'batch index')).toHaveText(batchIndex);
});

Then('詳細にseedの値は表示されず、再現不可と分かる表示になる', async ({ page }) => {
  await expect(detailValue(page, 'seed')).toContainText('再現不可');
});

After({ tags: '@media' }, async ({ ctx, request }) => {
  const id = ctx.mediaImageId as number | undefined;
  if (id === undefined) {
    return;
  }
  const token = await adminToken(request);
  await request.delete(`/api/generated-images/${id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
});
