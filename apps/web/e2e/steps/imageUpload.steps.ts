import zlib from 'node:zlib';
import type { APIRequestContext, Page } from '@playwright/test';
import { Given, Then, When } from './fixtures';
import { galleryCardOf } from '../support/galleryCard';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  expect,
  fetchAccessToken,
} from '../support';

/**
 * issue #1599: 手元の画像を1920x1080に変換して画像ギャラリーへ登録する、のステップ定義。
 *
 * 後始末は `media.steps.ts` の `@media` After が `ctx.mediaProjectId` のプロジェクトごと行う
 * (画面・API から作ったアップロード画像はプロジェクトに紐づく)。
 */

const MAX_BYTES = 20 * 1024 * 1024;

/** 64x36 の赤い JPEG(sharp で生成した最小の実画像)。EXIF は持たない。 */
export const LANDSCAPE_JPEG_BASE64 =
  '/9j/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAAkAEADASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAf/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFgEBAQEAAAAAAAAAAAAAAAAAAAcI/8QAFBEBAAAAAAAAAAAAAAAAAAAAAP/aAAwDAQACEQMRAD8Ak4CeNkAAAAAAAAAAAAAAAAAAAAAAP//Z';

/** 出力に残っていてはならない、GPS位置情報とみなす目印。 */
export const GPS_MARKER = 'GPS-35.6586N-139.7454E';

// ---------------------------------------------------------------- 画像フィクスチャ

function crc32(buf: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of buf) {
    crc ^= byte;
    for (let i = 0; i < 8; i++) {
      const mask = -(crc & 1);
      crc = (crc >>> 1) ^ (0xedb88320 & mask);
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

/** 単色のRGB PNGを、テスト実行時に組み立てる。 */
function createSolidPng(width: number, height: number): Buffer {
  const ihdrData = Buffer.alloc(13);
  ihdrData.writeUInt32BE(width, 0);
  ihdrData.writeUInt32BE(height, 4);
  ihdrData[8] = 8;
  ihdrData[9] = 2;
  const rowBytes = width * 3;
  const raw = Buffer.alloc((rowBytes + 1) * height);
  for (let y = 0; y < height; y++) {
    const rowStart = y * (rowBytes + 1);
    for (let x = 0; x < width; x++) {
      raw[rowStart + 1 + x * 3] = 200;
      raw[rowStart + 2 + x * 3] = 50;
      raw[rowStart + 3 + x * 3] = 50;
    }
  }
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    pngChunk('IHDR', ihdrData),
    pngChunk('IDAT', zlib.deflateSync(raw)),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

/** JPEG の SOI 直後に、GPS の目印を持つ APP1(Exif) セグメントを差し込む。 */
export function jpegWithGps(jpeg: Buffer): Buffer {
  const tiff = Buffer.concat([
    Buffer.from([0x49, 0x49, 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]),
    Buffer.from(GPS_MARKER, 'ascii'),
  ]);
  const body = Buffer.concat([Buffer.from('Exif\0\0', 'binary'), tiff]);
  const header = Buffer.from([0xff, 0xe1, (body.length + 2) >> 8, (body.length + 2) & 0xff]);
  return Buffer.concat([jpeg.subarray(0, 2), header, body, jpeg.subarray(2)]);
}

/** JPEG(SOF)またはPNG(IHDR)のヘッダーから寸法を読む。 */
function readDimensions(bytes: Buffer): { width: number; height: number } {
  if (bytes[0] === 0x89 && bytes[1] === 0x50) {
    return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
  }
  let offset = 2;
  while (offset + 9 < bytes.length) {
    if (bytes[offset] !== 0xff) {
      throw new Error('JPEGのマーカーを読めません');
    }
    const marker = bytes[offset + 1];
    if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
      return { height: bytes.readUInt16BE(offset + 5), width: bytes.readUInt16BE(offset + 7) };
    }
    offset += 2 + bytes.readUInt16BE(offset + 2);
  }
  throw new Error('JPEGの寸法(SOF)が見つかりません');
}

// ---------------------------------------------------------------- 画面操作

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

/** 画面のファイル入力へ画像を渡して「ギャラリーへ登録」を押し、登録された画像のIDを返す。 */
async function uploadViaPanel(
  page: Page,
  ctx: Record<string, unknown>,
  file: { name: string; mimeType: string; buffer: Buffer }
): Promise<void> {
  await page.getByLabel('アップロードする画像ファイル').setInputFiles(file);
  await page.getByRole('button', { name: 'ギャラリーへ登録' }).click();
  const success = page.getByText(/画像ギャラリーに登録しました\(画像ID: \d+\)/);
  await expect(success).toBeVisible({ timeout: 120_000 });
  const text = (await success.textContent()) ?? '';
  const match = text.match(/画像ID: (\d+)/);
  expect(match, `成功表示から画像IDを読めません: ${text}`).not.toBeNull();
  ctx.uploadedImageId = Number(match![1]);
}

async function fetchStoredImage(page: Page, ctx: Record<string, unknown>): Promise<Buffer> {
  const id = ctx.uploadedImageId as number;
  const response = await page.request.get(`/image-gallery/${id}/file`);
  expect(response.ok(), `保存画像の取得に失敗しました (status=${response.status()})`).toBe(true);
  return Buffer.from(await response.body());
}

When('横長のJPEG画像をギャラリーへアップロードする', async ({ page, ctx }) => {
  await uploadViaPanel(page, ctx, {
    name: 'landscape.jpg',
    mimeType: 'image/jpeg',
    buffer: Buffer.from(LANDSCAPE_JPEG_BASE64, 'base64'),
  });
});

When('GPS情報付きのJPEG画像をギャラリーへアップロードする', async ({ page, ctx }) => {
  await uploadViaPanel(page, ctx, {
    name: 'gps.jpg',
    mimeType: 'image/jpeg',
    buffer: jpegWithGps(Buffer.from(LANDSCAPE_JPEG_BASE64, 'base64')),
  });
});

When('{int}x{int}のPNG画像をギャラリーへアップロードする', async ({ page, ctx }, width: number, height: number) => {
  await uploadViaPanel(page, ctx, {
    name: `${width}x${height}.png`,
    mimeType: 'image/png',
    buffer: createSolidPng(width, height),
  });
});

When('GIF形式の画像ファイルをアップロード対象に選ぶ', async ({ page }) => {
  await page.getByLabel('アップロードする画像ファイル').setInputFiles({
    name: 'animation.gif',
    mimeType: 'image/gif',
    buffer: Buffer.from('GIF89a-not-a-real-gif'),
  });
});

When('21MBのPNG画像ファイルをアップロード対象に選ぶ', async ({ page }) => {
  const buffer = Buffer.alloc(MAX_BYTES + 1024 * 1024);
  Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]).copy(buffer);
  await page.getByLabel('アップロードする画像ファイル').setInputFiles({
    name: 'too-large.png',
    mimeType: 'image/png',
    buffer,
  });
});

Then('その画像は画像ギャラリーにアップロード画像として表示される', async ({ page, ctx }) => {
  const id = ctx.uploadedImageId as number;
  await page.goto('/image-gallery', { waitUntil: 'commit' });
  const card = galleryCardOf(page.locator(`img[src="/image-gallery/${id}/file"]`));
  await expect(card).toBeVisible({ timeout: 30_000 });
  await expect(card).toContainText('アップロード画像');
});

Then(/^保存された画像は(\d+)x(\d+)である$/, async ({ page, ctx }, width: string, height: string) => {
  const dimensions = readDimensions(await fetchStoredImage(page, ctx));
  expect(dimensions).toEqual({ width: Number(width), height: Number(height) });
});

Then('保存された画像にEXIFもGPS情報も残っていない', async ({ page, ctx }) => {
  const text = (await fetchStoredImage(page, ctx)).toString('latin1');
  expect(text).not.toContain(GPS_MARKER);
  expect(text).not.toContain('Exif');
});

Then('画像アップロードの対応形式エラーが表示される', async ({ page }) => {
  await expect(
    page.getByText('対応していない画像形式です。JPEGまたはPNGを選択してください。')
  ).toBeVisible();
});

Then('画像アップロードのサイズ超過エラーが表示される', async ({ page }) => {
  await expect(page.getByText('ファイルサイズが上限(20MB)を超えています。')).toBeVisible();
});

// ---------------------------------------------------------------- 非メンバー・登録の不在

Given('一般利用者がメンバーではない画像アップロード検証用のプロジェクトがある', async ({ ctx, request }) => {
  const suffix = uniqueSuffix();
  const created = await request.post('/api/projects', {
    headers: await adminHeaders(request),
    data: { name: `E2E 1599 ${suffix}`, slug: `e2e-1599-${suffix}` },
  });
  expect(
    created.ok(),
    `プロジェクトの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  ctx.mediaProjectId = ((await created.json()) as { id: number }).id;
});

When('一般利用者がそのプロジェクトへ画像を直接アップロードする', async ({ ctx, request }) => {
  const token = await fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
  const response = await request.post(`/api/generated-images/upload?projectId=${ctx.mediaProjectId}`, {
    headers: { Authorization: `Bearer ${token}` },
    multipart: { file: { name: 'a.png', mimeType: 'image/png', buffer: createSolidPng(32, 18) } },
  });
  ctx.uploadStatus = response.status();
});

Then('画像のアップロードは権限不足として403で拒否される', async ({ ctx }) => {
  expect(ctx.uploadStatus).toBe(403);
});

Then('このプロジェクトのギャラリーにアップロード画像は登録されていない', async ({ ctx, request }) => {
  const response = await request.get(`/api/generated-images?projectId=${ctx.mediaProjectId}`, {
    headers: await adminHeaders(request),
  });
  expect(response.ok()).toBe(true);
  const images = (await response.json()) as { provider: string }[];
  expect(images.filter((image) => image.provider === 'UPLOAD')).toHaveLength(0);
});

// ---------------------------------------------------------------- アセットとして追加

When('パネルのギャラリーからそのアップロード画像を選んでアセットとして追加する', async ({ page, ctx }) => {
  const id = ctx.uploadedImageId as number;
  const section = page
    .getByRole('heading', { name: '画像ギャラリーから選択してアップロード' })
    .locator('xpath=ancestor::div[contains(@class,"space-y-3")][1]');
  await section.getByRole('button', { name: '開く' }).click();
  await section.locator(`button:has(img[src="/image-gallery/${id}/file"])`).click();
  await section.getByRole('button', { name: '選択した画像をアセットとして追加(全環境へアップロード)' }).click();
});

Then('アセットとしての追加が完了した旨が表示される', async ({ page }) => {
  await expect(page.getByText(/環境へアップロードしました。|環境でアップロードに失敗しました。/)).toBeVisible({
    timeout: 120_000,
  });
});
