import crypto from 'node:crypto';
import type { APIRequestContext, Page } from '@playwright/test';
import { Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';
import { STUB_URLS } from '../support/stubs';
import { GPS_MARKER, LANDSCAPE_JPEG_BASE64, jpegWithGps } from './imageUpload.steps';

/**
 * issue #1600: アップロード画像のAIタグ自動付与のステップ定義。
 *
 * 後始末は `media.steps.ts` の `@media` After が `ctx.mediaProjectId` のプロジェクトごと行う。
 * タグ付けは応答の後に非同期で行われるため、「付く」は待ち合わせで、「付かない」は一定時間
 * 待ってから確かめる。LLMスタブへは並列シナリオも要求を送るので、モデル名を一意にして
 * 自分の要求だけを見分ける(`aiModelSelection.steps.ts` と同じ作法)。
 */

const STUB_TAGS = ['e2e-stub-vision-tag-a', 'e2e-stub-vision-tag-b', 'e2e-stub-vision-tag-c'];
const SETTLE_MS = 6_000;

interface StubImageRequest {
  model: string;
  mimeType: string | null;
  bytes: number;
  sha256: string | null;
  containsExif: boolean;
  containsGpsMarker: boolean;
}

function unique(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

async function createProjectWithLlm(
  request: APIRequestContext,
  ctx: Record<string, unknown>,
  provider: 'OLLAMA' | 'OPENAI',
  baseModel: string
): Promise<void> {
  const headers = await adminHeaders(request);
  const suffix = unique();
  const created = await request.post('/api/projects', {
    headers,
    data: { name: `E2E 1600 ${suffix}`, slug: `e2e-1600-${suffix}` },
  });
  expect(created.ok(), `プロジェクトの作成に失敗しました: ${await created.text()}`).toBe(true);
  const projectId = ((await created.json()) as { id: number }).id;
  ctx.mediaProjectId = projectId;

  const selectedProvider = await request.put(`/api/projects/${projectId}/ai-models/llm/provider/selection`, {
    headers,
    data: { provider },
  });
  expect(selectedProvider.ok(), `LLMプロバイダの選択に失敗しました: ${await selectedProvider.text()}`).toBe(true);

  // モデル名は一意にする。LLMスタブは受け取ったmodelを記録するので、自分の要求だけを見分けられる。
  const modelName = `${baseModel}.e2e${suffix}`;
  const selectedModel = await request.put(`/api/projects/${projectId}/ai-models/llm/models/selection`, {
    headers,
    data: { modelName },
  });
  expect(selectedModel.ok(), `LLMモデルの選択に失敗しました: ${await selectedModel.text()}`).toBe(true);
  ctx.llmModelName = modelName;
}

Given('画像入力に対応するAIモデルを選んだプロジェクトがある', async ({ ctx, request }) => {
  await createProjectWithLlm(request, ctx, 'OLLAMA', 'llava:7b');
});

Given('画像入力に対応しないAIモデルを選んだプロジェクトがある', async ({ ctx, request }) => {
  await createProjectWithLlm(request, ctx, 'OLLAMA', 'qwen2.5:7b-instruct');
});

Given(
  '画像入力に対応するChatGPTのモデルを選んだがAPIキーを設定していないプロジェクトがある',
  async ({ ctx, request }) => {
    await createProjectWithLlm(request, ctx, 'OPENAI', 'gpt-4o');
  }
);

async function uploadDirect(
  request: APIRequestContext,
  ctx: Record<string, unknown>,
  buffer: Buffer
): Promise<void> {
  const response = await request.post(`/api/generated-images/upload?projectId=${ctx.mediaProjectId}`, {
    headers: await adminHeaders(request),
    multipart: { file: { name: 'e2e-1600.jpg', mimeType: 'image/jpeg', buffer } },
  });
  ctx.uploadStatus = response.status();
  if (response.status() === 201) {
    ctx.uploadedImageId = ((await response.json()) as { id: number }).id;
  }
}

When('GPS情報付きのJPEG画像をプロジェクトへ直接アップロードする', async ({ ctx, request }) => {
  const original = jpegWithGps(Buffer.from(LANDSCAPE_JPEG_BASE64, 'base64'));
  expect(original.toString('latin1')).toContain(GPS_MARKER);
  await uploadDirect(request, ctx, original);
});

When('横長のJPEG画像をプロジェクトへ直接アップロードする', async ({ ctx, request }) => {
  await uploadDirect(request, ctx, Buffer.from(LANDSCAPE_JPEG_BASE64, 'base64'));
});

/** ギャラリーでアップロード画像の詳細を開く。 */
async function openDetail(page: Page, ctx: Record<string, unknown>): Promise<void> {
  const id = ctx.uploadedImageId as number;
  await page.goto('/image-gallery', { waitUntil: 'commit' });
  const thumbnail = page.locator(`img[src="/image-gallery/${id}/file"]`);
  await expect(thumbnail).toBeVisible({ timeout: 30_000 });
  // ハイドレーション前のクリックは効かないので、詳細が開くまで押し直す(media.steps.ts と同じ作法)。
  await expect(async () => {
    await thumbnail.click();
    await expect(page.getByText('生成画像の詳細')).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
}

When('生成画像ギャラリーでそのアップロード画像の詳細を開く', async ({ ctx, page }) => {
  expect(ctx.uploadStatus, 'アップロードに失敗しています').toBe(201);
  await openDetail(page, ctx);
});

function tagButton(page: Page, tag: string) {
  return page.getByRole('button', { name: `タグ「${tag}」を削除` });
}

async function expectStubTagsShown(page: Page, ctx: Record<string, unknown>): Promise<void> {
  // タグは非同期に付く。付いていなければ詳細を開き直して待つ。
  await expect(async () => {
    if ((await tagButton(page, STUB_TAGS[0]).count()) === 0) {
      await openDetail(page, ctx);
    }
    for (const tag of STUB_TAGS) {
      await expect(tagButton(page, tag)).toBeVisible({ timeout: 2_000 });
    }
  }).toPass({ timeout: 45_000, intervals: [2_000] });
}

Then('詳細のタグ一覧にスタブの画像タグ3件が表示される', async ({ ctx, page }) => {
  await expectStubTagsShown(page, ctx);
});

async function stubImageRequests(): Promise<StubImageRequest[]> {
  const response = await fetch(`${STUB_URLS.llm}/__control/state`);
  const state = (await response.json()) as { imageRequests?: StubImageRequest[] };
  return state.imageRequests ?? [];
}

Then('AIへ送られた画像は保存された画像と同一でEXIFもGPS情報も含まない', async ({ ctx, page }) => {
  const model = ctx.llmModelName as string;
  const received = (await stubImageRequests()).filter((r) => r.model === model);
  expect(received, `LLMスタブが画像入力付きの要求を受け取っていません(model=${model})`).toHaveLength(1);
  const stored = await page.request.get(`/image-gallery/${ctx.uploadedImageId as number}/file`);
  expect(stored.ok()).toBe(true);
  const storedBytes = Buffer.from(await stored.body());
  expect(received[0].sha256).toBe(crypto.createHash('sha256').update(storedBytes).digest('hex'));
  expect(received[0].containsExif).toBe(false);
  expect(received[0].containsGpsMarker).toBe(false);
});

// ---------------------------------------------------------------- 失敗しても成功する

Then('アップロードは成功しタグなしで登録される', async ({ ctx, request }) => {
  expect(ctx.uploadStatus, 'アップロードが成功していません').toBe(201);
  // タグ付けは非同期。付くとすれば応答の数秒後なので、待ってから確かめる。
  await new Promise((resolve) => setTimeout(resolve, SETTLE_MS));
  const response = await request.get(`/api/generated-images/${ctx.uploadedImageId as number}`, {
    headers: await adminHeaders(request),
  });
  expect(response.ok()).toBe(true);
  const detail = (await response.json()) as { provider: string; tags: string[] };
  expect(detail.provider).toBe('UPLOAD');
  expect(detail.tags).toEqual([]);
});

Then('そのモデルでAIへ画像入力付きの要求は送られていない', async ({ ctx }) => {
  const model = ctx.llmModelName as string;
  const received = (await stubImageRequests()).filter((r) => r.model === model);
  expect(received).toHaveLength(0);
});

// ---------------------------------------------------------------- 編集・絞り込み

When(/^詳細でタグ「([^」]+)」を追加する$/, async ({ page }, tag: string) => {
  await page.getByPlaceholder('タグを追加').fill(tag);
  await page.getByRole('button', { name: '追加', exact: true }).click();
  await expect(tagButton(page, tag)).toBeVisible({ timeout: 30_000 });
});

When(/^詳細でスタブの画像タグ「([^」]+)」を削除する$/, async ({ page }, tag: string) => {
  await tagButton(page, tag).click();
  await expect(tagButton(page, tag)).toHaveCount(0, { timeout: 30_000 });
});

Then(
  /^詳細のタグ一覧にスタブの画像タグは2件と「([^」]+)」だけが表示される$/,
  async ({ page }, manual: string) => {
    const expected = [STUB_TAGS[1], STUB_TAGS[2], manual];
    for (const tag of expected) {
      await expect(tagButton(page, tag)).toBeVisible();
    }
    await expect(page.getByRole('button', { name: /^タグ「.+」を削除$/ })).toHaveCount(expected.length);
  }
);

Then(
  /^ギャラリーを開き直すとタグ「([^」]+)」でそのアップロード画像を絞り込める$/,
  async ({ ctx, page }, tag: string) => {
    await page.goto('/image-gallery', { waitUntil: 'commit' });
    const filter = page.getByRole('button', { name: tag, exact: true });
    await expect(filter).toBeVisible({ timeout: 30_000 });
    await filter.click();
    await expect(
      page.locator(`img[src="/image-gallery/${ctx.uploadedImageId as number}/file"]`)
    ).toBeVisible({ timeout: 30_000 });
  }
);
