import zlib from 'node:zlib';
import type { APIRequestContext, Locator } from '@playwright/test';
import { kcadm, kcadmLogin, KEYCLOAK_REALM } from '../kcadm';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from './index';

/**
 * Server Action の3秒予算シナリオ(issue #1477、`features/response-budget/server-action-*.feature`)が
 * 使う検証用フィクスチャと小さな補助。計測そのものは `./responseBudget.ts` の共通ヘルパーが担い、
 * ここには**測る前の準備と後片付け**だけを置く(画面ごとに独自の計測方法を作らない)。
 *
 * ステップを登録しない(プレーンなモジュール)。後片付けは `registerCleanup` で積み、
 * `steps/responseBudgetCleanup.steps.ts` の After が `@response-budget` の各シナリオ後に流す。
 */

export const CLEANUPS_KEY = 'responseBudgetCleanups';

type Cleanup = () => Promise<void>;

/** シナリオ終了時に流す後片付けを積む。新しいものから先に実行される。 */
export function registerCleanup(ctx: Record<string, unknown>, cleanup: Cleanup): void {
  const list = (ctx[CLEANUPS_KEY] as Cleanup[] | undefined) ?? [];
  list.push(cleanup);
  ctx[CLEANUPS_KEY] = list;
}

export function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

export async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD)}` };
}

export interface ThrowawayUser {
  id: number;
  email: string;
  password: string;
}

/**
 * 使い捨ての一般利用者を作る(共有の E2E 固定アカウントには触れない)。
 * `loginable` を指定すると Keycloak 側の資格情報も整え、そのアカウントでログインできる状態にする
 * (`avatarUpload.steps.ts` と同じ手順)。後片付けは自動で積まれる。
 */
export async function createThrowawayUser(
  request: APIRequestContext,
  ctx: Record<string, unknown>,
  options: { loginable?: boolean } = {}
): Promise<ThrowawayUser> {
  const suffix = uniqueSuffix();
  const email = `e2e-1477-budget-${suffix}@example.com`;
  const password = `E2e1477Budget!${suffix}`;
  const created = await request.post('/api/users', {
    headers: await adminHeaders(request),
    data: { email, password, role: 'user' },
  });
  expect(
    created.ok(),
    `検証用利用者の登録に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const id = ((await created.json()) as { id: number }).id;
  registerCleanup(ctx, async () => {
    await request.delete(`/api/users/${id}`, { headers: await adminHeaders(request) });
  });
  if (options.loginable) {
    provisionLoginableKeycloakCredential(email, password);
  }
  return { id, email, password };
}

/** その email の利用者を後片付けの対象にする(画面から作られた利用者は id を知らないため)。 */
export function registerUserCleanupByEmail(
  request: APIRequestContext,
  ctx: Record<string, unknown>,
  email: string
): void {
  registerCleanup(ctx, async () => {
    const headers = await adminHeaders(request);
    const response = await request.get('/api/users', { headers });
    if (!response.ok()) return;
    const body = (await response.json()) as { id: number; email: string }[] | { content: { id: number; email: string }[] };
    const users = Array.isArray(body) ? body : body.content;
    for (const user of users.filter((u) => u.email === email)) {
      await request.delete(`/api/users/${user.id}`, { headers });
    }
  });
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
    '-s', 'lastName=Budget',
  ]);
}

/**
 * 対象の要素が React にハイドレートされるまで待つ。
 *
 * フォームの `action={formAction}` は、ハイドレーション前に送信するとブラウザの通常の
 * フルページ POST になり、`next-action` ヘッダを持たない(Server Action の往復として数えられない。
 * `responseBudget.ts` の§10.3 の注意)。React は DOM 要素に `__reactProps$...` を付けるので、
 * それが現れるまで待てば、計測の対象になる送信だけを行える。
 */
export async function waitForHydrated(target: Locator): Promise<void> {
  await expect
    .poll(
      () => target.first().evaluate((el) => Object.keys(el).some((key) => key.startsWith('__reactProps$'))),
      { timeout: 30_000 }
    )
    .toBe(true);
}

/** 単色の RGB PNG を組み立てる(外部ライブラリを使わない)。 */
export function createSolidPng(width: number, height: number, rgb: [number, number, number]): Buffer {
  const crc32 = (buf: Buffer): number => {
    let crc = 0xffffffff;
    for (const byte of buf) {
      crc ^= byte;
      for (let i = 0; i < 8; i++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
    }
    return (crc ^ 0xffffffff) >>> 0;
  };
  const chunk = (type: string, data: Buffer): Buffer => {
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length, 0);
    const typeBuf = Buffer.from(type, 'ascii');
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
    return Buffer.concat([length, typeBuf, data, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;
  const rowBytes = width * 3;
  const raw = Buffer.alloc((rowBytes + 1) * height);
  for (let y = 0; y < height; y++) {
    const start = y * (rowBytes + 1);
    for (let x = 0; x < width; x++) {
      raw[start + 1 + x * 3] = rgb[0];
      raw[start + 2 + x * 3] = rgb[1];
      raw[start + 3 + x * 3] = rgb[2];
    }
  }
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}
