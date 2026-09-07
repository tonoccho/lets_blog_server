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

/**
 * 画像生成のバッチ回数(issue #1102)のステップ定義。
 *
 * ここだけは実際に `POST /api/ai/image` を叩く。batch count は「1回の要求で何回繰り返すか」
 * であり、要求を実際に投げなければ確かめようがないため。画像生成AIには ChatGPT スタブ
 * (`infra/e2e-stubs/openai-image/server.js`)を使う。ComfyUI はGPU必須の任意サービスで
 * 受け入れテスト環境に常在しないため、ここから到達できない。
 *
 * その帰結として、**seed に関する受入基準はここでは確かめられない**
 * (ChatGPT の画像生成APIは seed を受け付けない)。リピートごとの seed の変化は
 * services/media の ImageGenerationServiceTest が担当する。
 *
 * `/api/ai/image` は gateway の upload-endpoint 枠(プロセス全体で1時間に10回)に属する。
 * シナリオを足すときは枠を意識すること(docs/ACCEPTANCE_TESTING.md §11)。
 */

interface ImageGenerationOutcome {
  status: number;
  body: string;
  images: { id: number }[];
  /** 要求前に存在した生成画像のID集合。「1枚も増えていない」の比較に使う。 */
  idsBefore: number[];
}

async function listGeneratedImageIds(request: APIRequestContext): Promise<number[]> {
  const token = await adminToken(request);
  const response = await request.get('/api/generated-images', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    response.ok(),
    `生成画像一覧の取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return ((await response.json()) as { id: number }[]).map((image) => image.id);
}

async function requestImageGeneration(
  request: APIRequestContext,
  ctx: Record<string, unknown>,
  body: Record<string, unknown>
): Promise<void> {
  const token = await adminToken(request);
  const idsBefore = await listGeneratedImageIds(request);
  const response = await request.post('/api/ai/image', {
    headers: { Authorization: `Bearer ${token}` },
    data: { prompt: 'e2e batch count', ...body },
    timeout: 180_000,
  });
  const text = await response.text();
  let images: { id: number }[] = [];
  if (response.ok()) {
    images = (JSON.parse(text) as { images: { id: number }[] }).images;
  }
  ctx.imageGeneration = {
    status: response.status(),
    body: text,
    images,
    idsBefore,
  } satisfies ImageGenerationOutcome;
  ctx.mediaGeneratedIds = images.map((image) => image.id);
}

function outcome(ctx: Record<string, unknown>): ImageGenerationOutcome {
  const value = ctx.imageGeneration as ImageGenerationOutcome | undefined;
  expect(value, '画像生成の要求がまだ行われていません').toBeDefined();
  return value as ImageGenerationOutcome;
}

Given('画像生成にChatGPTを使うプロジェクトがある', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const suffix = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  const created = await request.post('/api/projects', {
    headers: { Authorization: `Bearer ${token}` },
    data: { name: `E2E 1102 ${suffix}`, slug: `e2e-1102-${suffix}` },
  });
  expect(
    created.ok(),
    `プロジェクトの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const projectId = ((await created.json()) as { id: number }).id;
  const selected = await request.put(`/api/projects/${projectId}/ai-models/image/provider/selection`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { provider: 'CHATGPT' },
  });
  expect(
    selected.ok(),
    `画像生成AIの選択に失敗しました (status=${selected.status()}): ${await selected.text()}`
  ).toBe(true);
  ctx.mediaProjectId = projectId;
});

When(
  /^そのプロジェクトで1回に「(\d+)」枚を「(\d+)」回繰り返す画像生成を要求する$/,
  async ({ ctx, request }, batchSize: string, batchCount: string) => {
    await requestImageGeneration(request, ctx, {
      projectId: ctx.mediaProjectId,
      batchSize: Number(batchSize),
      batchCount: Number(batchCount),
    });
  }
);

When(
  /^そのプロジェクトで1回に「(\d+)」枚の画像生成を繰り返し回数を指定せずに要求する$/,
  async ({ ctx, request }, batchSize: string) => {
    await requestImageGeneration(request, ctx, {
      projectId: ctx.mediaProjectId,
      batchSize: Number(batchSize),
    });
  }
);

When(
  /^そのプロジェクトで1回に「(\d+)」枚の画像生成を要求する$/,
  async ({ ctx, request }, batchSize: string) => {
    await requestImageGeneration(request, ctx, {
      projectId: ctx.mediaProjectId,
      batchSize: Number(batchSize),
    });
  }
);

When(/^1回に「(\d+)」枚の画像生成を要求する$/, async ({ ctx, request }, batchSize: string) => {
  await requestImageGeneration(request, ctx, { batchSize: Number(batchSize) });
});

When(
  /^1回に「(\d+)」枚を「(\d+)」回繰り返す画像生成を要求する$/,
  async ({ ctx, request }, batchSize: string, batchCount: string) => {
    await requestImageGeneration(request, ctx, {
      batchSize: Number(batchSize),
      batchCount: Number(batchCount),
    });
  }
);

Then(/^生成された画像が「(\d+)」枚返る$/, async ({ ctx }, expected: string) => {
  const result = outcome(ctx);
  expect(
    result.status,
    `画像生成に失敗しました (status=${result.status}): ${result.body}`
  ).toBe(200);
  expect(result.images).toHaveLength(Number(expected));
});

Then('返った画像がすべて生成画像の一覧に現れる', async ({ ctx, request }) => {
  const result = outcome(ctx);
  const ids = await listGeneratedImageIds(request);
  for (const image of result.images) {
    expect(ids, `生成画像 ${image.id} が一覧に現れていません`).toContain(image.id);
  }
});

Then(/^画像生成の要求が「(\d+)」で拒否される$/, async ({ ctx }, status: string) => {
  const result = outcome(ctx);
  expect(result.status, `応答本文: ${result.body}`).toBe(Number(status));
});

Then('生成画像は1枚も増えていない', async ({ ctx, request }) => {
  const result = outcome(ctx);
  const ids = await listGeneratedImageIds(request);
  expect(ids.filter((id) => !result.idsBefore.includes(id))).toEqual([]);
});

Then(
  /^拒否の理由に画像生成AIの名前「([^」]+)」と上限「(\d+)」が示される$/,
  async ({ ctx }, provider: string, limit: string) => {
    const result = outcome(ctx);
    expect(result.body).toContain(provider);
    expect(result.body).toContain(limit);
  }
);

After({ tags: '@media' }, async ({ ctx, request }) => {
  const token = await adminToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  for (const id of (ctx.mediaGeneratedIds as number[] | undefined) ?? []) {
    await request.delete(`/api/generated-images/${id}`, { headers });
  }
  const projectId = ctx.mediaProjectId as number | undefined;
  if (projectId !== undefined) {
    await request.delete(`/api/projects/${projectId}`, { headers });
  }
});
