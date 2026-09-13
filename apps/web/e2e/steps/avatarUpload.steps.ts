import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  expect,
  fetchAccessToken,
  loginViaKeycloak,
} from '../support';

/**
 * issue #1241: プロフィール編集画面のアバターアップロード・切り抜きのステップ定義。
 *
 * `userManagement.steps.ts`と同様、Keycloak側の資格情報を`kcadm.sh`で直接整える
 * (ステップ定義ファイルは兄弟issueと相乗りしない方針)。
 */

const KEYCLOAK_CONTAINER = 'lbs-keycloak';
const KEYCLOAK_REALM = 'letsblog';
const KCADM_BIN = '/opt/keycloak/bin/kcadm.sh';

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

function readEnvValue(key: string): string {
  const envPath = path.join(REPO_ROOT, '.env');
  const content = fs.readFileSync(envPath, 'utf-8');
  const match = content.match(new RegExp(`^${key}=(.*)$`, 'm'));
  if (!match) {
    throw new Error(`.env に ${key} が見つかりません`);
  }
  return match[1].trim();
}

function kcadm(args: string[]): string {
  return execFileSync('docker', ['exec', KEYCLOAK_CONTAINER, KCADM_BIN, ...args], {
    encoding: 'utf-8',
    timeout: 30_000,
  });
}

function kcadmLogin(): void {
  const username = readEnvValue('KEYCLOAK_ADMIN_USERNAME');
  const password = readEnvValue('KEYCLOAK_ADMIN_PASSWORD');
  kcadm([
    'config', 'credentials',
    '--server', 'http://localhost:8080/auth',
    '--realm', 'master',
    '--user', username,
    '--password', password,
  ]);
}

function provisionLoginableKeycloakCredential(email: string, password: string): void {
  kcadmLogin();
  const usersJson = kcadm(['get', 'users', '-r', KEYCLOAK_REALM, '-q', `email=${email}`, '--fields', 'id']);
  const users = JSON.parse(usersJson) as { id: string }[];
  if (users.length === 0) {
    throw new Error(`Keycloakに ${email} が見つかりません`);
  }
  const keycloakUserId = users[0].id;

  kcadm(['set-password', '-r', KEYCLOAK_REALM, '--userid', keycloakUserId, '--new-password', password]);
  kcadm([
    'update', `users/${keycloakUserId}`, '-r', KEYCLOAK_REALM,
    '-s', 'requiredActions=[]',
    '-s', 'emailVerified=true',
    '-s', 'firstName=E2E',
    '-s', 'lastName=Avatar',
  ]);
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

// --------------------------------------------------------------- PNGフィクスチャの組み立て

/** 標準的なCRC32(PNGチャンクの検証に使う多項式0xEDB88320)。 */
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

/** 単色のRGB PNGを生成する(外部ライブラリを使わず、テスト実行時に組み立てる)。 */
function createSolidPng(width: number, height: number, rgb: [number, number, number]): Buffer {
  const signature = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

  const ihdrData = Buffer.alloc(13);
  ihdrData.writeUInt32BE(width, 0);
  ihdrData.writeUInt32BE(height, 4);
  ihdrData[8] = 8; // bit depth
  ihdrData[9] = 2; // color type: RGB
  ihdrData[10] = 0;
  ihdrData[11] = 0;
  ihdrData[12] = 0;
  const ihdr = pngChunk('IHDR', ihdrData);

  const rowBytes = width * 3;
  const raw = Buffer.alloc((rowBytes + 1) * height);
  const [r, g, b] = rgb;
  for (let y = 0; y < height; y++) {
    const rowStart = y * (rowBytes + 1);
    raw[rowStart] = 0; // フィルタなし
    for (let x = 0; x < width; x++) {
      const px = rowStart + 1 + x * 3;
      raw[px] = r;
      raw[px + 1] = g;
      raw[px + 2] = b;
    }
  }
  const idat = pngChunk('IDAT', zlib.deflateSync(raw));
  const iend = pngChunk('IEND', Buffer.alloc(0));
  return Buffer.concat([signature, ihdr, idat, iend]);
}

// --------------------------------------------------------------- フィクスチャの用意

interface AvatarFixture {
  userId: number;
  email: string;
  password: string;
}

async function createAvatarFixture(request: APIRequestContext, ctx: Record<string, unknown>): Promise<void> {
  const suffix = uniqueSuffix();
  const email = `e2e-1241-avatar-${suffix}@example.com`;
  const password = `E2e1241Avatar!${suffix}`;

  const headers = await adminHeaders(request);
  const created = await request.post('/api/users', {
    headers,
    data: { email, password, role: 'user' },
  });
  expect(
    created.ok(),
    `検証用メンバーの登録に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const userId = ((await created.json()) as { id: number }).id;

  provisionLoginableKeycloakCredential(email, password);

  ctx.avatarUploadUserId = userId;
  ctx.avatarUploadEmail = email;
  ctx.avatarUploadPassword = password;
}

function fixture(ctx: Record<string, unknown>): AvatarFixture {
  return {
    userId: ctx.avatarUploadUserId as number,
    email: ctx.avatarUploadEmail as string,
    password: ctx.avatarUploadPassword as string,
  };
}

// --------------------------------------------------------------- ステップ定義

Given('アバターアップロード検証用のログイン可能なメンバーが登録されている', async ({ request, ctx }) => {
  await createAvatarFixture(request, ctx);
});

Given('そのメンバーは切り抜き済みのアバターを保存済みである', async ({ request, ctx }) => {
  const { userId, email, password } = fixture(ctx);
  const token = await fetchAccessToken(request, email, password);
  const png = createSolidPng(600, 600, [10, 20, 30]);
  const response = await request.post(`/api/users/${userId}/avatar`, {
    headers: { Authorization: `Bearer ${token}` },
    multipart: { file: { name: 'initial-avatar.png', mimeType: 'image/png', buffer: png } },
  });
  expect(response.ok(), `事前アバター保存に失敗しました (status=${response.status()})`).toBe(true);
  ctx.avatarUploadPreviousBytes = png;
});

When('そのメンバーでプロフィール編集画面を開く', async ({ page, ctx }) => {
  const { userId, email, password } = fixture(ctx);
  await loginViaKeycloak(page, email, password);
  await page.goto(`/users/${userId}/edit`);
});

When('{int}x{int}の画像ファイルを選択する', async ({ page }, width: number, height: number) => {
  const png = createSolidPng(width, height, [200, 50, 50]);
  await page.locator('[data-testid="avatar-file-input"]').setInputFiles({
    name: 'avatar-source.png',
    mimeType: 'image/png',
    buffer: png,
  });
});

When('GIF形式のファイルを選択する', async ({ page }) => {
  await page.locator('[data-testid="avatar-file-input"]').setInputFiles({
    name: 'avatar.gif',
    mimeType: 'image/gif',
    buffer: Buffer.from('GIF89a-not-a-real-gif'),
  });
});

When('{int}MBのPNGファイルを選択する', async ({ page }, megabytes: number) => {
  const buffer = Buffer.alloc(megabytes * 1024 * 1024, 1);
  await page.locator('[data-testid="avatar-file-input"]').setInputFiles({
    name: 'oversized.png',
    mimeType: 'image/png',
    buffer,
  });
});

When('切り抜きを確定して保存する', async ({ page }) => {
  await page.locator('[data-testid="avatar-crop-confirm"]').click();
});

When('別の{int}x{int}の画像ファイルを選択し切り抜きを確定して保存する', async ({ page, ctx }, width: number, height: number) => {
  const png = createSolidPng(width, height, [30, 200, 30]);
  await page.locator('[data-testid="avatar-file-input"]').setInputFiles({
    name: 'avatar-replacement.png',
    mimeType: 'image/png',
    buffer: png,
  });
  await page.locator('[data-testid="avatar-crop-confirm"]').click();
  ctx.avatarUploadReplacementBytes = png;
});

Then('中央の{int}x{int}が初期選択された切り抜きUIが表示される', async ({ page }, size: number, sizeAgain: number) => {
  const frame = page.locator('[data-testid="avatar-crop-frame"]');
  await expect(frame).toBeVisible();
  await expect(frame).toHaveAttribute('data-crop-size', String(size));
  expect(size).toBe(sizeAgain);
});

Then('アバターとして表示される', async ({ page }) => {
  const preview = page.locator('[data-testid="avatar-preview"]');
  await expect(preview).toBeVisible({ timeout: 15000 });
  await expect(async () => {
    const natural = await preview.evaluate((img: HTMLImageElement) => img.naturalWidth);
    expect(natural).toBeGreaterThan(0);
  }).toPass({ timeout: 15000 });
});

Then('画面を再読み込みしてもアバターが表示される', async ({ page }) => {
  await page.reload();
  const preview = page.locator('[data-testid="avatar-preview"]');
  await expect(preview).toBeVisible({ timeout: 15000 });
  await expect(async () => {
    const natural = await preview.evaluate((img: HTMLImageElement) => img.naturalWidth);
    expect(natural).toBeGreaterThan(0);
  }).toPass({ timeout: 15000 });
});

Then('アバターURL入力欄が引き続き表示され利用できる', async ({ page }) => {
  const urlInput = page.locator('input[name="avatarUrl"]');
  await expect(urlInput).toBeVisible();
  await expect(urlInput).toBeEditable();
});

Then('対応形式エラーが表示される', async ({ page }) => {
  await expect(page.locator('[data-testid="avatar-upload-error"]')).toBeVisible();
});

Then('サイズ超過エラーが表示される', async ({ page }) => {
  await expect(page.locator('[data-testid="avatar-upload-error"]')).toBeVisible();
});

Then('アバターは変更されていない', async ({ request, ctx }) => {
  const { userId, email, password } = fixture(ctx);
  const token = await fetchAccessToken(request, email, password);
  const response = await request.get(`/api/users/${userId}/avatar`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(response.ok(), `アバター取得に失敗しました (status=${response.status()})`).toBe(true);
  const body = await response.body();
  const previous = ctx.avatarUploadPreviousBytes as Buffer;
  expect(Buffer.compare(body, previous)).toBe(0);
});

Then('配信されるアバターの内容が新しい画像に置き換わっている', async ({ request, ctx }) => {
  const { userId, email, password } = fixture(ctx);
  const token = await fetchAccessToken(request, email, password);
  await expect(async () => {
    const response = await request.get(`/api/users/${userId}/avatar`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(response.ok()).toBe(true);
    const body = await response.body();
    const previous = ctx.avatarUploadPreviousBytes as Buffer;
    expect(Buffer.compare(body, previous)).not.toBe(0);
  }).toPass({ timeout: 15000 });
});

After({ tags: '@identity' }, async ({ ctx, request }) => {
  const userId = ctx.avatarUploadUserId as number | undefined;
  if (userId === undefined) {
    return;
  }
  const headers = await adminHeaders(request);
  await request.delete(`/api/users/${userId}`, { headers });
});
