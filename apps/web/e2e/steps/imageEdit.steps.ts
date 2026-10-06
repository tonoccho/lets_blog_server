import zlib from 'node:zlib';
import type { APIRequestContext, Page } from '@playwright/test';
import { Given, Then, When } from './fixtures';
import { magnifierOf } from '../support/galleryCard';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  expect,
  fetchAccessToken,
} from '../support';

/**
 * issue #1655: 画像ギャラリーの詳細から画像を回転・反転・切り抜きし、新しい画像として保存する、のステップ定義。
 *
 * 後始末は `media.steps.ts` の `@media` After が `ctx.mediaProjectId` のプロジェクトごと行う
 * (編集で増えた画像もプロジェクトに紐づく)。
 */

// ---------------------------------------------------------------- 画像フィクスチャ

function crc32(buf: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of buf) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) {
      crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function pngChunk(type: string, data: Buffer): Buffer {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([length, typeBuf, data, crc]);
}

/** 左半分が赤・右半分が青の RGB PNG を、テスト実行時に組み立てる。 */
function createRedBluePng(width: number, height: number): Buffer {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;
  const rowBytes = width * 3;
  const raw = Buffer.alloc((rowBytes + 1) * height);
  for (let y = 0; y < height; y++) {
    const rowStart = y * (rowBytes + 1);
    for (let x = 0; x < width; x++) {
      const red = x < width / 2;
      raw[rowStart + 1 + x * 3] = red ? 220 : 20;
      raw[rowStart + 2 + x * 3] = 20;
      raw[rowStart + 3 + x * 3] = red ? 20 : 220;
    }
  }
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw)),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

function readPngDimensions(bytes: Buffer): { width: number; height: number } {
  expect(bytes[0] === 0x89 && bytes[1] === 0x50, '保存画像がPNGではありません').toBe(true);
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
}

// ---------------------------------------------------------------- API

interface ImageDto {
  id: number;
  provider: string;
  folderId: number | null;
  tags: string[];
  width: number;
  height: number;
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

async function createProject(request: APIRequestContext, ctx: Record<string, unknown>): Promise<number> {
  const suffix = uniqueSuffix();
  const created = await request.post('/api/projects', {
    headers: await adminHeaders(request),
    data: { name: `E2E 1655 ${suffix}`, slug: `e2e-1655-${suffix}` },
  });
  expect(
    created.ok(),
    `プロジェクトの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const projectId = ((await created.json()) as { id: number }).id;
  ctx.mediaProjectId = projectId;
  return projectId;
}

async function createSourceImage(
  request: APIRequestContext,
  ctx: Record<string, unknown>,
  options: { provider: string; tags?: string[] }
): Promise<number> {
  const projectId = await createProject(request, ctx);
  const headers = await adminHeaders(request);
  const response = await request.post('/api/generated-images', {
    headers,
    data: {
      projectId,
      prompt: `E2E 1655 ${uniqueSuffix()}`,
      width: 400,
      height: 200,
      mimeType: 'image/png',
      provider: options.provider,
      tagsJson: options.tags ? JSON.stringify(options.tags) : null,
      imageData: createRedBluePng(400, 200).toString('base64'),
    },
  });
  expect(
    response.status(),
    `編集元の画像の登録に失敗しました: ${await response.text()}`
  ).toBe(201);
  const id = ((await response.json()) as { id: number }).id;
  ctx.editSourceId = id;
  return id;
}

async function getImage(request: APIRequestContext, id: number): Promise<ImageDto> {
  const response = await request.get(`/api/generated-images/${id}`, { headers: await adminHeaders(request) });
  expect(response.ok(), `画像の取得に失敗しました (status=${response.status()})`).toBe(true);
  return (await response.json()) as ImageDto;
}

async function listProjectImages(request: APIRequestContext, ctx: Record<string, unknown>): Promise<ImageDto[]> {
  const response = await request.get(`/api/generated-images?projectId=${ctx.mediaProjectId}`, {
    headers: await adminHeaders(request),
  });
  expect(response.ok()).toBe(true);
  return (await response.json()) as ImageDto[];
}

async function fetchFileDimensions(request: APIRequestContext, id: number) {
  const response = await request.get(`/api/generated-images/${id}/file`, { headers: await adminHeaders(request) });
  expect(response.ok(), `画像ファイルの取得に失敗しました (status=${response.status()})`).toBe(true);
  return readPngDimensions(Buffer.from(await response.body()));
}

// ---------------------------------------------------------------- 前提

Given('編集用の横長400x200のアップロード画像がある', async ({ ctx, request }) => {
  await createSourceImage(request, ctx, { provider: 'UPLOAD' });
});

Given('編集用の横長400x200のAI生成画像がタグ「風景」とフォルダ付きである', async ({ ctx, request }) => {
  const id = await createSourceImage(request, ctx, { provider: 'COMFYUI', tags: ['風景'] });
  const headers = await adminHeaders(request);
  const folder = await request.post('/api/generated-images/folders', {
    headers,
    data: { name: `E2E 1655 ${uniqueSuffix()}`, parentId: null },
  });
  expect(folder.status(), `フォルダの作成に失敗しました: ${await folder.text()}`).toBe(201);
  const folderId = ((await folder.json()) as { id: number }).id;
  const moved = await request.put(`/api/generated-images/${id}/folder`, { headers, data: { folderId } });
  expect(moved.ok(), `フォルダへの登録に失敗しました: ${await moved.text()}`).toBe(true);
  ctx.editSourceFolderId = folderId;
});

Given('一般利用者がメンバーではないプロジェクトに編集用の画像がある', async ({ ctx, request }) => {
  await createSourceImage(request, ctx, { provider: 'UPLOAD' });
});

// ---------------------------------------------------------------- 画面操作

When('画像ギャラリーでその画像の編集画面を開く', async ({ page, ctx }) => {
  const id = ctx.editSourceId as number;
  // issue #1664: 既定の並列度(4ワーカー)では、一覧の24枚のサムネイルを各ワーカーが取りに行く分で
  // 共有スタックのゲートウェイのレート制限(429)に当たり、一覧や詳細の取得が一時的に失敗していた
  // (「生成画像を取得できませんでした」)。このシナリオが使うのは編集元の1枚だけなので、ほかの画像の
  // ファイル取得は止めて、スタックへの負荷を減らす(アサーションは変えない)。
  await page.route(/\/image-gallery\/\d+\/file/, (route) => {
    const requested = Number(/\/image-gallery\/(\d+)\/file/.exec(route.request().url())?.[1]);
    // `<img>` の取得だけを止める(後続のステップが `fetch` で保存結果のファイルを読むため)。
    return requested === id || route.request().resourceType() !== 'image' ? route.continue() : route.abort();
  });
  const thumbnail = page.locator(`img[src="/image-gallery/${id}/file"]`);
  // 一覧の取得自体が429で失敗した画面は待っても直らないので、開き直す。
  await expect(async () => {
    await page.goto('/image-gallery', { waitUntil: 'commit' });
    await expect(thumbnail).toBeVisible({ timeout: 10_000 });
  }).toPass({ timeout: 40_000, intervals: [0, 2_000, 4_000] });
  // ハイドレーション前のクリックでは詳細ダイアログが開かない(再試行する)。ただし、クリックのたびに
  // 詳細の取得(サーバーアクション)が走って詳細が空に戻るため、1回の押下には取得が終わるのに足りる待ちを与える。
  // 詳細の取得が429で失敗すると、エラー表示のまま詳細ダイアログが開いたままになり、背後の虫眼鏡は
  // 押せなくなる。その場合は閉じてから押し直す。
  await expect(async () => {
    const close = page.getByRole('button', { name: '閉じる', exact: true });
    if (await close.isVisible()) await close.click();
    await magnifierOf(thumbnail).click();
    await expect(page.getByRole('button', { name: '編集', exact: true })).toBeVisible({ timeout: 10_000 });
  }).toPass({ timeout: 60_000 });
  // 後続のステップは、保存した別の画像を `<img>` で読む。止めるのは編集画面を開くまで。
  await page.unroute(/\/image-gallery\/\d+\/file/);
  await page.getByRole('button', { name: '編集', exact: true }).click();
  await expect(page.getByRole('heading', { name: '画像を編集' })).toBeVisible();
});

When(/^編集画面で「([^」]+)」を押す$/, async ({ page }, name: string) => {
  await page.getByRole('button', { name, exact: true }).click();
});

When(/^編集画面で比率「([^」]+)」を選ぶ$/, async ({ page }, name: string) => {
  await page.getByRole('group', { name: '切り抜きの比率' }).getByRole('button', { name, exact: true }).click();
});

When(
  /^プレビュー上で左上から右へ(\d+)画素、下へ(\d+)画素ドラッグする$/,
  async ({ page }, dx: string, dy: string) => {
    const box = await page.getByTestId('image-edit-preview').boundingBox();
    expect(box, 'プレビューが表示されていません').not.toBeNull();
    const startX = box!.x + 10;
    const startY = box!.y + 10;
    await page.mouse.move(startX, startY);
    await page.mouse.down();
    await page.mouse.move(startX + Number(dx) / 2, startY + Number(dy) / 2);
    await page.mouse.move(startX + Number(dx), startY + Number(dy));
    await page.mouse.up();
  }
);

Then(/^編集後のサイズは(\d+ × \d+ px)と表示される$/, async ({ page }, size: string) => {
  await expect(page.getByText(`編集後のサイズ: ${size}`, { exact: true })).toBeVisible();
});

Then(/^切り抜き範囲は(\d+ × \d+ px)と表示される$/, async ({ page }, size: string) => {
  await expect(page.getByText(`切り抜き範囲: ${size}`, { exact: true })).toBeVisible();
});

Then('プレビューは右に90°回転している', async ({ page }) => {
  await expect(page.getByAltText('編集プレビュー')).toHaveAttribute('style', /rotate\(90deg\)/);
});

Then('プレビューの変形に左右反転と上下反転と左回転が含まれる', async ({ page }) => {
  const style = page.getByAltText('編集プレビュー');
  await expect(style).toHaveAttribute('style', /scaleX\(-1\)/);
  await expect(style).toHaveAttribute('style', /scaleY\(-1\)/);
  await expect(style).toHaveAttribute('style', /rotate\(-90deg\)/);
});

Then('プレビュー上に切り抜き範囲の枠が表示される', async ({ page }) => {
  await expect(page.getByTestId('image-edit-crop')).toBeVisible();
});

Then('編集画面は閉じている', async ({ page }) => {
  await expect(page.getByRole('heading', { name: '画像を編集' })).toHaveCount(0);
});

// ---------------------------------------------------------------- 保存の結果

Then(/^新しい画像として保存した旨が表示される$/, async ({ page, ctx }) => {
  const message = page.getByText(/新しい画像として保存しました\(ID \d+\)/);
  await expect(message).toBeVisible({ timeout: 60_000 });
  const match = ((await message.textContent()) ?? '').match(/ID (\d+)/);
  expect(match).not.toBeNull();
  ctx.editedImageId = Number(match![1]);
});

Then(/^新しい画像は(\d+)x(\d+)のPNGである$/, async ({ request, ctx }, width: string, height: string) => {
  const id = ctx.editedImageId as number;
  expect(id).not.toBe(ctx.editSourceId);
  expect(await fetchFileDimensions(request, id)).toEqual({ width: Number(width), height: Number(height) });
  const detail = await getImage(request, id);
  expect({ width: detail.width, height: detail.height }).toEqual({ width: Number(width), height: Number(height) });
});

Then(/^元の画像は(\d+)x(\d+)のPNGのままである$/, async ({ request, ctx }, width: string, height: string) => {
  const id = ctx.editSourceId as number;
  expect(await fetchFileDimensions(request, id)).toEqual({ width: Number(width), height: Number(height) });
  const detail = await getImage(request, id);
  expect({ width: detail.width, height: detail.height }).toEqual({ width: Number(width), height: Number(height) });
});

Then(/^このプロジェクトの画像は(\d+)枚(?:になっている|のままである)$/, async ({ request, ctx }, count: string) => {
  expect(await listProjectImages(request, ctx)).toHaveLength(Number(count));
});

Then('新しい画像のタグ・フォルダ・種別は元の画像と同じである', async ({ request, ctx }) => {
  const source = await getImage(request, ctx.editSourceId as number);
  const edited = await getImage(request, ctx.editedImageId as number);
  expect(source.tags).toEqual(['風景']);
  expect(source.folderId).toBe(ctx.editSourceFolderId);
  expect(edited.tags).toEqual(source.tags);
  expect(edited.folderId).toBe(source.folderId);
  expect(edited.provider).toBe('COMFYUI');
  expect(edited.provider).toBe(source.provider);
});

// ---------------------------------------------------------------- 認可

When('一般利用者がその画像の編集を直接要求する', async ({ ctx, request }) => {
  const token = await fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
  const response = await request.post(`/api/generated-images/${ctx.editSourceId}/edit`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { operations: ['ROTATE_CW'] },
  });
  ctx.editStatus = response.status();
});

When('存在しない画像の編集を直接要求する', async ({ request, ctx }) => {
  const response = await request.post('/api/generated-images/2147483646/edit', {
    headers: await adminHeaders(request),
    data: { operations: ['ROTATE_CW'] },
  });
  ctx.editStatus = response.status();
});

Then('画像の編集は権限不足として403で拒否される', async ({ ctx }) => {
  expect(ctx.editStatus).toBe(403);
});

Then('画像の編集は存在しない画像として404で拒否される', async ({ ctx }) => {
  expect(ctx.editStatus).toBe(404);
});

// ---------------------------------------------------------------- 明るさ・コントラスト(issue #1656)

interface LuminanceStats {
  mean: number;
  variance: number;
}

/** フィクスチャ(左半分 (220,20,20)・右半分 (20,20,220))の輝度(Rec.601)の統計。 */
const ORIGINAL_STATS: LuminanceStats = (() => {
  const red = 0.299 * 220 + 0.587 * 20 + 0.114 * 20;
  const blue = 0.299 * 20 + 0.587 * 20 + 0.114 * 220;
  const mean = (red + blue) / 2;
  return { mean, variance: ((red - mean) ** 2 + (blue - mean) ** 2) / 2 };
})();

/** ブラウザで画像を読み込み、画素の輝度の平均と分散を求める(PNG は可逆なので回転しても変わらない)。 */
async function luminanceStats(page: Page, imageId: number): Promise<LuminanceStats> {
  return page.evaluate(async (url) => {
    // ゲートウェイのレート制限(429)の応答は画像ではなくデコードできないので、取れるまで取り直す(issue #1664)。
    let response = await fetch(url);
    for (let attempt = 0; !response.ok && attempt < 5; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 3_000));
      response = await fetch(url);
    }
    const blob = await response.blob();
    const bitmap = await createImageBitmap(blob);
    const canvas = document.createElement('canvas');
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext('2d')!;
    context.drawImage(bitmap, 0, 0);
    const { data } = context.getImageData(0, 0, canvas.width, canvas.height);
    const count = data.length / 4;
    let sum = 0;
    let sumSquares = 0;
    for (let i = 0; i < data.length; i += 4) {
      const luminance = 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2];
      sum += luminance;
      sumSquares += luminance * luminance;
    }
    const mean = sum / count;
    return { mean, variance: sumSquares / count - mean * mean };
  }, `/image-gallery/${imageId}/file`);
}

When(/^編集画面で(明るさ|コントラスト)のスライダーを(-?\d+)にする$/, async ({ page }, name: string, value: number) => {
  await page.getByRole('slider', { name, exact: true }).fill(String(value));
});

Then('プレビューにフィルタは掛かっていない', async ({ page }) => {
  await expect(page.getByAltText('編集プレビュー')).toHaveCSS('filter', 'none');
});

Then(/^プレビューのフィルタは「([^」]+)」である$/, async ({ page }, filter: string) => {
  await expect(page.getByAltText('編集プレビュー')).toHaveCSS('filter', filter);
});

Then(/^明るさのスライダーは(-?\d+)である$/, async ({ page }, value: number) => {
  await expect(page.getByRole('slider', { name: '明るさ', exact: true })).toHaveValue(String(value));
});

Then('新しい画像の平均輝度は元の画像より高い', async ({ page, ctx }) => {
  const edited = await luminanceStats(page, ctx.editedImageId as number);
  expect(edited.mean).toBeGreaterThan(ORIGINAL_STATS.mean + 5);
});

Then('新しい画像の輝度の分散は元の画像より大きい', async ({ page, ctx }) => {
  const edited = await luminanceStats(page, ctx.editedImageId as number);
  expect(edited.variance).toBeGreaterThan(ORIGINAL_STATS.variance * 1.1);
});

Then('元の画像の明るさとコントラストは変わっていない', async ({ page, ctx }) => {
  const original = await luminanceStats(page, ctx.editSourceId as number);
  expect(original.mean).toBeCloseTo(ORIGINAL_STATS.mean, 0);
  expect(original.variance).toBeCloseTo(ORIGINAL_STATS.variance, -1);
});

Then('新しい画像の明るさとコントラストは元の画像と同じである', async ({ page, ctx }) => {
  const edited = await luminanceStats(page, ctx.editedImageId as number);
  expect(edited.mean).toBeCloseTo(ORIGINAL_STATS.mean, 0);
  expect(edited.variance).toBeCloseTo(ORIGINAL_STATS.variance, -1);
});
