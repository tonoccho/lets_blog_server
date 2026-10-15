import type { APIRequestContext, Locator, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * システム全体のプラグインの登録(issue #1807)の受け入れシナリオのステップ定義。
 * 登録は全員で共有する1つの表なので @destructive で単独実行し、Given で空にして After で空に戻す。
 * 接続先の「docker ネットワーク内に解決されるホスト」には、受け入れテスト用のスタブ lbx-portal(#1782)を使う。
 */

const API = '/api/system-settings/plugin-registrations';
const SETTINGS_PATH = '/admin/system-settings';

interface RegistrationView {
  id: number;
  kind: string;
  name: string;
  url: string;
  inUse: boolean;
  drawioUrl: string | null;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD)}` };
}

async function listAll(request: APIRequestContext): Promise<RegistrationView[]> {
  const response = await request.get(API, { headers: await adminHeaders(request) });
  expect(response.ok(), `登録の一覧の取得に失敗しました (status=${response.status()})`).toBe(true);
  return (await response.json()) as RegistrationView[];
}

async function deleteAll(request: APIRequestContext): Promise<void> {
  const headers = await adminHeaders(request);
  const response = await request.get(API, { headers });
  if (!response.ok()) return;
  for (const registration of (await response.json()) as RegistrationView[]) {
    await request.delete(`${API}/${registration.id}`, { headers });
  }
}

Given('プラグインの登録が1件も無い', async ({ ctx, request }) => {
  ctx.pluginRegistrationsTouched = true;
  await deleteAll(request);
  expect(await listAll(request)).toEqual([]);
});

After(async ({ ctx, request }) => {
  if (ctx.pluginRegistrationsTouched !== true) return;
  await deleteAll(request);
});

// ---- 画面 ----

function region(page: Page, kind: string): Locator {
  return page.getByRole('region', { name: `${kind} の登録` });
}

function entry(page: Page, kind: string, name: string): Locator {
  return region(page, kind).getByRole('listitem').filter({ hasText: name });
}

async function openSettings(page: Page): Promise<void> {
  if (new URL(page.url()).pathname === SETTINGS_PATH) return;
  await page.goto(SETTINGS_PATH, { waitUntil: 'commit' });
  await expect(page.getByRole('heading', { name: 'プラグインの登録' })).toBeVisible({ timeout: 30_000 });
}

/** Server Action の応答(POST)を待ってからクリックする。直前の結果表示を取り違えないため。 */
async function clickAndWaitForAction(page: Page, button: Locator): Promise<void> {
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes(SETTINGS_PATH), { timeout: 30_000 }),
    button.click(),
  ]);
}

async function addRegistration(
  page: Page,
  kind: string,
  name: string,
  url: string,
  drawioUrl?: string,
): Promise<void> {
  await openSettings(page);
  const section = region(page, kind);
  await section.getByLabel('名前').fill(name);
  await section.getByLabel('URL', { exact: true }).fill(url);
  if (drawioUrl !== undefined) {
    await section.getByLabel('drawioUrl(任意)').fill(drawioUrl);
  }
  const addButton = section.getByRole('button', { name: '追加' });
  await clickAndWaitForAction(page, addButton);
  // 応答の直後はまだ送信の処理中で、フォームは React が処理の完了時に空へ戻す(受理でも拒否でも)。
  // 戻る前に次の追加を入力すると値が消えるため、ボタンが再び押せて名前欄が空になるまで待つ。
  await expect(addButton).toBeEnabled({ timeout: 30_000 });
  await expect(section.getByLabel('名前').last()).toHaveValue('', { timeout: 30_000 });
}

When(
  /^システム設定画面の「([^」]+)」欄で、名前「([^」]+)」、URL「([^」]+)」を追加する$/,
  async ({ page }, kind: string, name: string, url: string) => {
    await addRegistration(page, kind, name, url);
  }
);

When(
  /^システム設定画面の「([^」]+)」欄で、名前「([^」]+)」、URL「([^」]+)」、drawioUrl「([^」]+)」を追加する$/,
  async ({ page }, kind: string, name: string, url: string, drawioUrl: string) => {
    await addRegistration(page, kind, name, url, drawioUrl);
  }
);

When(/^「([^」]+)」欄の「([^」]+)」を使用中にする$/, async ({ page }, kind: string, name: string) => {
  await clickAndWaitForAction(page, entry(page, kind, name).getByRole('button', { name: '使用中にする' }));
});

When(
  /^「([^」]+)」欄の「([^」]+)」の名前を「([^」]+)」に編集する$/,
  async ({ page }, kind: string, name: string, newName: string) => {
    await entry(page, kind, name).getByRole('button', { name: '編集' }).click();
    const form = region(page, kind).getByRole('form', { name: `${name} を編集` });
    await form.getByLabel('名前').fill(newName);
    await clickAndWaitForAction(page, form.getByRole('button', { name: '更新' }));
  }
);

When(/^「([^」]+)」欄の「([^」]+)」を削除する$/, async ({ page }, kind: string, name: string) => {
  await clickAndWaitForAction(page, entry(page, kind, name).getByRole('button', { name: '削除' }));
});

Then(/^「([^」]+)」欄に「([^」]+)」と「([^」]+)」が並ぶ$/, async ({ page }, kind: string, a: string, b: string) => {
  await expect(entry(page, kind, a)).toBeVisible({ timeout: 30_000 });
  await expect(entry(page, kind, b)).toBeVisible({ timeout: 30_000 });
});

Then(/^「([^」]+)」欄に「([^」]+)」が表示される$/, async ({ page }, kind: string, name: string) => {
  await expect(entry(page, kind, name)).toBeVisible({ timeout: 30_000 });
});

Then(/^「([^」]+)」欄に「([^」]+)」は表示されない$/, async ({ page }, kind: string, name: string) => {
  await expect(region(page, kind)).toBeVisible({ timeout: 30_000 });
  await expect(entry(page, kind, name)).toHaveCount(0);
});

Then(/^「([^」]+)」欄のどの登録も使用中ではない$/, async ({ page }, kind: string) => {
  await expect(region(page, kind).getByText('使用中', { exact: true })).toHaveCount(0);
});

Then(/^「([^」]+)」欄の使用中は「([^」]+)」だけである$/, async ({ page }, kind: string, name: string) => {
  const inUse = region(page, kind).getByRole('listitem').filter({ has: page.getByText('使用中', { exact: true }) });
  await expect(inUse).toHaveCount(1, { timeout: 30_000 });
  await expect(inUse).toContainText(name);
});

Then(
  /^「([^」]+)」欄の「([^」]+)」にdrawioUrl「([^」]+)」が表示される$/,
  async ({ page }, kind: string, name: string, drawioUrl: string) => {
    await expect(entry(page, kind, name)).toContainText(drawioUrl, { timeout: 30_000 });
  }
);

Then(/^「([^」]+)」欄に「使用中にする」ボタンは無い$/, async ({ page }, kind: string) => {
  await expect(region(page, kind)).toBeVisible({ timeout: 30_000 });
  await expect(region(page, kind).getByRole('button', { name: '使用中にする' })).toHaveCount(0);
});

Then(/^「([^」]+)」欄に保存できなかった理由「(.+)」が表示される$/, async ({ page }, kind: string, reason: string) => {
  await expect(region(page, kind).getByRole('alert')).toContainText(reason, { timeout: 30_000 });
});

Then(
  /^プラグインの登録の欄に「(.+)」という注記がある$/,
  async ({ page }, note: string) => {
    await expect(page.getByRole('heading', { name: 'プラグインの登録' })).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(note)).toBeVisible();
  }
);

// ---- API ----

Given(/^管理者がAPIでLLMの登録「([^」]+)」を追加しておく$/, async ({ ctx, request }, name: string) => {
  const response = await request.post(API, {
    headers: await adminHeaders(request),
    data: { kind: 'LLM', name, url: 'http://lbx-portal:3100', drawioUrl: null },
  });
  expect(response.ok(), `登録の追加に失敗しました (status=${response.status()})`).toBe(true);
  ctx.pluginRegistrationId = ((await response.json()) as RegistrationView).id;
});

function userHeaders(ctx: Record<string, unknown>): Record<string, string> {
  return { Authorization: `Bearer ${ctx.userToken as string}` };
}

When('一般ユーザーがプラグインの登録の一覧を要求する', async ({ ctx, request }) => {
  ctx.pluginRegistrationStatus = (await request.get(API, { headers: userHeaders(ctx) })).status();
});

When(/^一般ユーザーがLLMの登録「([^」]+)」を追加するよう要求する$/, async ({ ctx, request }, name: string) => {
  ctx.pluginRegistrationStatus = (
    await request.post(API, {
      headers: userHeaders(ctx),
      data: { kind: 'LLM', name, url: 'http://lbx-portal:3100', drawioUrl: null },
    })
  ).status();
});

When('一般ユーザーが既存の登録を編集するよう要求する', async ({ ctx, request }) => {
  ctx.pluginRegistrationStatus = (
    await request.put(`${API}/${ctx.pluginRegistrationId as number}`, {
      headers: userHeaders(ctx),
      data: { name: '不正な編集', url: 'http://lbx-portal:3100', drawioUrl: null },
    })
  ).status();
});

When('一般ユーザーが既存の登録を使用中にするよう要求する', async ({ ctx, request }) => {
  ctx.pluginRegistrationStatus = (
    await request.put(`${API}/${ctx.pluginRegistrationId as number}/in-use`, { headers: userHeaders(ctx) })
  ).status();
});

When('一般ユーザーが既存の登録を削除するよう要求する', async ({ ctx, request }) => {
  ctx.pluginRegistrationStatus = (
    await request.delete(`${API}/${ctx.pluginRegistrationId as number}`, { headers: userHeaders(ctx) })
  ).status();
});

Then('プラグインの登録の要求は403で拒否される', async ({ ctx }) => {
  expect(ctx.pluginRegistrationStatus).toBe(403);
});

Then(/^管理者のAPIで見るとプラグインの登録は「([^」]+)」の1件だけである$/, async ({ request }, name: string) => {
  const all = await listAll(request);
  expect(all.map((r) => r.name)).toEqual([name]);
  expect(all[0].inUse).toBe(false);
});
