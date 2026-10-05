import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  expect,
  fetchAccessToken,
} from '../support';
import { STUB_URLS } from '../support/stubs';
import { magnifierOf } from '../support/galleryCard';

/**
 * 参照画像付き(img2img)生成(issue #1601)のステップ定義。
 *
 * ComfyUI は `infra/e2e-stubs/comfyui` のスタブ。スタブは投入されたワークフローと、
 * `/upload/image` で受け取った参照画像を `/__control/state` に記録するので、プロンプトの印
 * (要求ごとに一意)で自分の投入を特定して中身を検査する。
 *
 * 画像生成のジョブ状態(`ctx.imageJob`)は `media.steps.ts` の「画像生成のジョブが終わるまで待つ」と
 * 共有する。
 */

/** 1x1のPNG。参照画像の中身は問わない(スタブは画像を処理しない)。 */
const PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

interface ReferenceRun {
  /** 要求ごとに一意のプロンプトの印。スタブの記録・ジョブ一覧から自分の投入を特定する。 */
  marker: string;
  status: number;
  body: string;
  uploadCountBefore: number;
}

interface StubPrompt {
  prompt: string | null;
  referenceImage: string | null;
  denoise: number | null;
  hasEmptyLatent: boolean;
  scale: { width: number | null; height: number | null } | null;
}

interface StubState {
  prompts: StubPrompt[];
  uploadCount: number;
  uploads: { name: string }[];
}

async function token(request: APIRequestContext, email: string, password: string): Promise<string> {
  return fetchAccessToken(request, email, password);
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return token(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function stubState(): Promise<StubState> {
  const response = await fetch(`${STUB_URLS.comfyui}/__control/state`);
  expect(response.ok, 'ComfyUIスタブの状態を取得できません').toBe(true);
  return (await response.json()) as StubState;
}

function run(ctx: Record<string, unknown>): ReferenceRun {
  const value = ctx.referenceRun as ReferenceRun | undefined;
  expect(value, '参照画像付きの生成がまだ要求されていません').toBeDefined();
  return value as ReferenceRun;
}

function referenceImageId(ctx: Record<string, unknown>): number {
  const id = ctx.refGenReferenceImageId as number | undefined;
  expect(id, '参照にする画像がまだ用意されていません').toBeDefined();
  return id as number;
}

async function createImageInProject(request: APIRequestContext, projectId: number, label: string): Promise<number> {
  const response = await request.post('/api/generated-images', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: {
      projectId,
      prompt: `e2e 1601 reference fixture ${label}`,
      negativePrompt: 'blurry',
      steps: 20,
      cfgScale: 7.0,
      samplerName: 'euler',
      scheduler: 'normal',
      seed: 1_601_000,
      width: 512,
      height: 512,
      batchSize: 1,
      batchIndex: 0,
      checkpoint: 'e2e.safetensors',
      mimeType: 'image/png',
      provider: 'COMFYUI',
      imageData: PNG_BASE64,
    },
  });
  expect(
    response.ok(),
    `参照用の画像の登録に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return ((await response.json()) as { id: number }).id;
}

Given('そのプロジェクトのギャラリーに参照にできる画像がある', async ({ ctx, request }) => {
  const id = await createImageInProject(request, ctx.mediaProjectId as number, 'own');
  ctx.refGenReferenceImageId = id;
  // 後始末(media.steps.ts の @media After)が消す。
  ctx.mediaImageId = id;
});

Given('その参照画像を削除する', async ({ ctx, request }) => {
  const response = await request.delete(`/api/generated-images/${referenceImageId(ctx)}`, {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
  });
  expect(response.ok(), `参照画像の削除に失敗しました (status=${response.status()})`).toBe(true);
});

Given('別のプロジェクトのギャラリーに画像がある', async ({ ctx, request }) => {
  const headers = { Authorization: `Bearer ${await adminToken(request)}` };
  const suffix = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  const created = await request.post('/api/projects', {
    headers,
    data: { name: `E2E 1601 other ${suffix}`, slug: `e2e-1601-other-${suffix}` },
  });
  expect(
    created.ok(),
    `別プロジェクトの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const otherProjectId = ((await created.json()) as { id: number }).id;
  ctx.refGenOtherProjectId = otherProjectId;
  ctx.refGenOtherImageId = await createImageInProject(request, otherProjectId, 'other');
});

async function requestJob(
  request: APIRequestContext,
  ctx: Record<string, unknown>,
  options: { bearer: string; referenceImageId?: number; denoise?: number }
): Promise<void> {
  const marker = `e2e 1601 ${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
  const uploadCountBefore = (await stubState()).uploadCount;
  const data: Record<string, unknown> = {
    prompt: marker,
    projectId: ctx.mediaProjectId,
    width: 512,
    height: 512,
    batchSize: 1,
    steps: 4,
  };
  if (options.referenceImageId !== undefined) data.referenceImageId = options.referenceImageId;
  if (options.denoise !== undefined) data.denoise = options.denoise;
  const response = await request.post('/api/ai/image/jobs', {
    headers: { Authorization: `Bearer ${options.bearer}` },
    data,
    timeout: 30_000,
  });
  const text = await response.text();
  let parsed: { id?: number; status?: string } = {};
  try {
    parsed = JSON.parse(text) as { id?: number; status?: string };
  } catch {
    // 本文がJSONでなければ後続の検証がステータスと本文を出して失敗する。
  }
  ctx.referenceRun = { marker, status: response.status(), body: text, uploadCountBefore } satisfies ReferenceRun;
  // 「画像生成のジョブが終わるまで待つ」(media.steps.ts)が読む状態。
  ctx.imageJob = {
    acceptStatus: response.status(),
    acceptBody: text,
    jobId: parsed.id ?? null,
    jobStatus: parsed.status ?? null,
    final: null,
  };
}

When(
  /^そのプロジェクトで、その画像を参照にdenoise「([\d.]+)」を指定して画像生成をジョブとして要求する$/,
  async ({ ctx, request }, denoise: string) => {
    await requestJob(request, ctx, {
      bearer: await adminToken(request),
      referenceImageId: referenceImageId(ctx),
      denoise: Number(denoise),
    });
  }
);

When(
  'そのプロジェクトで、その画像を参照にdenoiseを指定せず画像生成をジョブとして要求する',
  async ({ ctx, request }) => {
    await requestJob(request, ctx, {
      bearer: await adminToken(request),
      referenceImageId: referenceImageId(ctx),
    });
  }
);

When('そのプロジェクトで参照画像を付けずに画像生成をジョブとして要求する', async ({ ctx, request }) => {
  await requestJob(request, ctx, { bearer: await adminToken(request) });
});

When(
  'そのプロジェクトで、別のプロジェクトの画像を参照にdenoiseを指定せず画像生成をジョブとして要求する',
  async ({ ctx, request }) => {
    await requestJob(request, ctx, {
      bearer: await adminToken(request),
      referenceImageId: ctx.refGenOtherImageId as number,
    });
  }
);

When(
  'メンバーでない一般利用者が、そのプロジェクトで、その画像を参照にdenoiseを指定せず画像生成をジョブとして要求する',
  async ({ ctx, request }) => {
    await requestJob(request, ctx, {
      bearer: await token(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD),
      referenceImageId: referenceImageId(ctx),
    });
  }
);

/** スタブが記録した、このシナリオの投入。ジョブは背景で進むので現れるまで待つ。 */
async function awaitStubPrompt(marker: string): Promise<{ entry: StubPrompt; state: StubState }> {
  const deadline = Date.now() + 60_000;
  let state = await stubState();
  while (Date.now() < deadline) {
    const entry = state.prompts.find((p) => p.prompt?.includes(marker));
    if (entry) return { entry, state };
    await new Promise((resolve) => setTimeout(resolve, 500));
    state = await stubState();
  }
  throw new Error(`ComfyUIスタブに印(${marker})を持つ投入が記録されていません`);
}

Then(
  /^ComfyUIスタブが受け取ったその生成のワークフローは、参照画像を読み込み、denoiseは「([\d.]+)」である$/,
  async ({ ctx }, denoise: string) => {
    const { entry, state } = await awaitStubPrompt(run(ctx).marker);
    expect(entry.referenceImage, '参照画像のLoadImageがワークフローに含まれていません').toBeTruthy();
    expect(
      state.uploads.map((upload) => upload.name),
      '参照画像がComfyUIの /upload/image に送られていません'
    ).toContain(entry.referenceImage);
    expect(entry.denoise).toBeCloseTo(Number(denoise), 6);
  }
);

Then(
  /^ComfyUIスタブは参照画像を生成サイズ「(\d+)」x「(\d+)」へリサイズするワークフローを受け取っている$/,
  async ({ ctx }, width: string, height: string) => {
    const { entry } = await awaitStubPrompt(run(ctx).marker);
    expect(entry.scale, '参照画像のリサイズ(ImageScale)がワークフローに含まれていません').not.toBeNull();
    expect(entry.scale?.width).toBe(Number(width));
    expect(entry.scale?.height).toBe(Number(height));
    expect(entry.hasEmptyLatent, 'img2imgでは空の潜在画像を使わない').toBe(false);
  }
);

Then(
  /^ComfyUIスタブが受け取ったその生成のワークフローは、参照画像の読み込みを含まず、denoiseは「([\d.]+)」である$/,
  async ({ ctx }, denoise: string) => {
    const { entry } = await awaitStubPrompt(run(ctx).marker);
    expect(entry.referenceImage).toBeNull();
    expect(entry.hasEmptyLatent, '従来のtxt2imgは空の潜在画像から始める').toBe(true);
    expect(entry.denoise).toBeCloseTo(Number(denoise), 6);
  }
);

Then('ComfyUIスタブは、その生成で画像をアップロードされていない', async ({ ctx }) => {
  await awaitStubPrompt(run(ctx).marker);
  expect((await stubState()).uploadCount).toBe(run(ctx).uploadCountBefore);
});

Then('参照画像付きの画像生成の要求は4xxで拒否される', async ({ ctx }) => {
  const { status, body } = run(ctx);
  expect(status, `応答本文: ${body}`).toBeGreaterThanOrEqual(400);
  expect(status, `応答本文: ${body}`).toBeLessThan(500);
});

Then('その要求のジョブは作られていない', async ({ ctx, request }) => {
  const headers = { Authorization: `Bearer ${await adminToken(request)}` };
  const list = await request.get('/api/generation-jobs', { headers });
  expect(list.ok(), `ジョブ一覧の取得に失敗しました (status=${list.status()})`).toBe(true);
  const jobs = (await list.json()) as Array<{ id: number; type: string }>;
  const marker = run(ctx).marker;
  for (const job of jobs.filter((j) => j.type === 'image_generation')) {
    const detail = await request.get(`/api/generation-jobs/${job.id}`, { headers });
    if (!detail.ok()) continue;
    const body = (await detail.json()) as { requestPayload: string | null };
    expect(
      body.requestPayload?.includes(marker) ?? false,
      `拒否されたはずの要求(印 ${marker})のジョブ ${job.id} が作られています`
    ).toBe(false);
  }
});

// ---- ギャラリーの詳細(参照元の表示) ----

When('画像ギャラリーで参照付きで生成された画像の詳細を開く', async ({ ctx, page }) => {
  const generatedId = (ctx.mediaGeneratedIds as number[])[0];
  ctx.refGenGeneratedImageId = generatedId;
  await page.goto('/image-gallery', { waitUntil: 'commit' });
  const thumbnail = page.locator(`img[src="/image-gallery/${generatedId}/file"]`);
  await expect(thumbnail).toBeVisible({ timeout: 30_000 });
  // ハイドレーション前のクリックは取りこぼされるので、詳細が開くまで再試行する(#1284)。
  await expect(async () => {
    await magnifierOf(thumbnail).click();
    await expect(page.getByText('生成画像の詳細')).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
});

Then('詳細に参照元の画像のIDと、そのサムネイルが表示される', async ({ ctx, page }) => {
  const id = referenceImageId(ctx);
  const value = page.locator('dt:text-is("参照元の画像") + dd');
  await expect(value).toBeVisible({ timeout: 15_000 });
  await expect(value).toContainText(`ID ${id}`);
  await expect(value.locator(`img[src="/image-gallery/${id}/file"]`)).toBeVisible();
});

// ---- アセット画像生成パネル ----

function referenceSection(page: import('@playwright/test').Page) {
  return page.getByTestId('reference-image-section');
}

async function chooseReference(
  page: import('@playwright/test').Page,
  ctx: Record<string, unknown>
): Promise<void> {
  const section = referenceSection(page);
  await expect(section).toBeVisible({ timeout: 15_000 });
  await section.getByRole('button', { name: '参照画像を選ぶ' }).click();
  const id = referenceImageId(ctx);
  const thumbnail = section.locator(`img[src="/image-gallery/${id}/file"]`);
  await expect(thumbnail).toBeVisible({ timeout: 30_000 });
  await thumbnail.click();
  await expect(section.getByText(`選択中の参照画像: ID ${id}`)).toBeVisible();
}

async function fillPromptMarkerAndGenerate(
  page: import('@playwright/test').Page,
  ctx: Record<string, unknown>
): Promise<void> {
  const marker = `e2e 1601 panel ${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
  const uploadCountBefore = (await stubState()).uploadCount;
  const promptInput = page.getByPlaceholder('生成したい画像の説明');
  await promptInput.fill(marker);
  await expect(promptInput).toHaveValue(marker);
  await page.getByLabel('batch size(最大16)').fill('1');
  const generate = page.getByRole('button', { name: '生成', exact: true });
  await expect(generate).toBeEnabled();
  await generate.click();
  await expect(page.getByText(/生成を要求しました。処理キューに追加されました/)).toBeVisible({ timeout: 30_000 });
  ctx.referenceRun = { marker, status: 202, body: '', uploadCountBefore } satisfies ReferenceRun;
}

When(
  /^パネルで参照画像としてその画像を選び、denoiseに「([\d.]+)」を入力して生成を要求する$/,
  async ({ ctx, page }, denoise: string) => {
    await chooseReference(page, ctx);
    await page.getByLabel(/denoise/).fill(String(denoise));
    await fillPromptMarkerAndGenerate(page, ctx);
  }
);

When('パネルで参照画像としてその画像を選んでから解除し、生成を要求する', async ({ ctx, page }) => {
  await chooseReference(page, ctx);
  const section = referenceSection(page);
  await section.getByRole('button', { name: '参照画像を解除' }).click();
  await expect(section.getByText(/選択中の参照画像/)).toHaveCount(0);
  await fillPromptMarkerAndGenerate(page, ctx);
});

After({ tags: '@media' }, async ({ ctx, request }) => {
  const otherProjectId = ctx.refGenOtherProjectId as number | undefined;
  if (otherProjectId === undefined) {
    return;
  }
  const headers = { Authorization: `Bearer ${await adminToken(request)}` };
  const otherImageId = ctx.refGenOtherImageId as number | undefined;
  if (otherImageId !== undefined) {
    await request.delete(`/api/generated-images/${otherImageId}`, { headers });
  }
  await request.delete(`/api/projects/${otherProjectId}`, { headers });
});
