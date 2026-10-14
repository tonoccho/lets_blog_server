import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';
import { PORTAL_STUB_URL, requirePortalStub } from '../support/stubs';

/**
 * extensions のポータルから接続先をシステム設定へ取り込む(issue #1783)の受け入れシナリオのステップ定義。
 * ポータルはスタブ(#1782)。システム設定の4キーを書き換えるので @destructive で単独実行し、
 * 元の値はPUTより前に ctx へ記録して、途中で失敗しても After で戻す。
 */

const SETTING_KEYS = ['llm_ollama_base_url', 'comfyui_base_url', 'penpot_base_url', 'extensions_portal_url'] as const;
type SettingKey = (typeof SETTING_KEYS)[number];

const KNOWN_VALUES: Record<'llm_ollama_base_url' | 'comfyui_base_url' | 'penpot_base_url', string> = {
  llm_ollama_base_url: 'http://before.example:11434/v1',
  comfyui_base_url: 'http://before.example:8188',
  penpot_base_url: 'http://192.168.77.10:3000',
};

const IMPORT_API = '/api/system-settings/app-settings/extensions-import';

interface AppSettingStatus {
  key: string;
  value: string | null;
  source: 'DATABASE' | 'ENVIRONMENT' | 'NONE';
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD)}` };
}

async function fetchSettings(request: APIRequestContext): Promise<AppSettingStatus[]> {
  const response = await request.get('/api/system-settings/app-settings', { headers: await adminHeaders(request) });
  expect(response.ok(), `アプリ設定の取得に失敗しました (status=${response.status()})`).toBe(true);
  return (await response.json()) as AppSettingStatus[];
}

async function putSettings(request: APIRequestContext, values: Record<string, string>): Promise<number> {
  const response = await request.put('/api/system-settings/app-settings', {
    headers: await adminHeaders(request),
    data: values,
  });
  return response.status();
}

async function currentValue(request: APIRequestContext, key: string): Promise<string | null> {
  const setting = (await fetchSettings(request)).find((s) => s.key === key);
  expect(setting, `${key} の設定項目が見つかりません`).toBeDefined();
  return setting!.source === 'DATABASE' ? setting!.value : null;
}

async function settingKeysPresent(request: APIRequestContext): Promise<Set<string>> {
  return new Set((await fetchSettings(request)).map((s) => s.key));
}

async function postPortalControl(path: string, payload?: unknown): Promise<void> {
  const res = await fetch(`${PORTAL_STUB_URL}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: payload === undefined ? undefined : JSON.stringify(payload),
  });
  expect(res.ok, `ポータルスタブの ${path} が失敗した(${res.status})`).toBe(true);
}

Given('システム設定のOllama・ComfyUI・PenpotのURLを既知の値にして、元の値を控える', async ({ ctx, request }) => {
  // ポータルのURLのキーが無い実装(取り込み機能の前)でも、画面の振る舞いで落とすためここでは落とさない。
  const present = await settingKeysPresent(request);
  const originals: Record<string, string> = {};
  for (const key of SETTING_KEYS.filter((k) => present.has(k))) {
    originals[key] = (await currentValue(request, key)) ?? '';
  }
  ctx.extensionsImportOriginals = originals;
  const reset: Record<string, string> = { ...KNOWN_VALUES };
  if (present.has('extensions_portal_url')) reset.extensions_portal_url = '';
  expect(await putSettings(request, reset)).toBe(204);
});

After(async ({ ctx, request }) => {
  const originals = ctx.extensionsImportOriginals as Record<SettingKey, string> | undefined;
  if (originals === undefined) return;
  await putSettings(request, originals);
  await fetch(`${PORTAL_STUB_URL}/__control/reset`, { method: 'POST' }).catch(() => undefined);
});

Given('ポータルスタブの応答は既定である', async () => {
  await requirePortalStub();
  await postPortalControl('/__control/reset');
});

Given(/^ポータルスタブの応答は、Penpotの公開URLが「(.+)」である$/, async ({}, url: string) => {
  await requirePortalStub();
  await postPortalControl('/__control/reset');
  const connections = (await (await fetch(`${PORTAL_STUB_URL}/api/connections`)).json()) as Array<{
    id: string;
    publicUrl: string;
  }>;
  const changed = connections.map((c) => (c.id === 'penpot' ? { ...c, publicUrl: url } : c));
  await postPortalControl('/__control/response', { body: changed });
});

Given(/^ポータルスタブは「(.+)」になっている$/, async ({}, situation: string) => {
  await requirePortalStub();
  await postPortalControl('/__control/reset');
  if (situation === '500を返す') {
    await postPortalControl('/__control/force', { status: 500, count: 1 });
  } else if (situation === '応答に5秒かかる') {
    await postPortalControl('/__control/force', { delayMs: 5000, count: 1 });
  } else if (situation === 'JSONでない本文を返す') {
    await postPortalControl('/__control/response', { body: '<html>not json</html>', contentType: 'text/html' });
  } else {
    throw new Error(`未知のポータルの状況: ${situation}`);
  }
});

// ---- 画面 ----

const SECTION_HEADING = 'extensions から取り込む';

function importSection(page: Page) {
  return page.locator('section').filter({ has: page.getByRole('heading', { name: SECTION_HEADING }) });
}

async function openSettings(page: Page): Promise<void> {
  await page.goto('/admin/system-settings', { waitUntil: 'commit' });
  await expect(page.getByRole('heading', { name: 'システム設定' })).toBeVisible({ timeout: 30_000 });
}

When(
  /^システム設定画面の「extensions から取り込む」欄で、ポータルのURLを「(.+)」、経路を「(.+)」にして取得する$/,
  async ({ page }, portalUrl: string, route: string) => {
    await openSettings(page);
    const section = importSection(page);
    await expect(section).toBeVisible({ timeout: 30_000 });
    await section.getByLabel('ポータルのURL').fill(portalUrl);
    await section.getByLabel(route).check();
    await section.getByRole('button', { name: '取得' }).click();
  }
);

function candidateRow(page: Page, key: string) {
  return importSection(page).getByRole('table', { name: '取り込み候補' }).locator('tbody tr').filter({
    has: page.getByRole('cell', { name: key, exact: true }),
  });
}

Then(/^取り込み候補の「(.+)」の取り込む値は「(.+)」である$/, async ({ page }, key: string, value: string) => {
  await expect(candidateRow(page, key).getByRole('cell').nth(2)).toHaveText(value, { timeout: 30_000 });
});

Then(/^取り込み候補の「(.+)」の現在の値は「(.+)」である$/, async ({ page }, key: string, value: string) => {
  await expect(candidateRow(page, key).getByRole('cell').nth(1)).toHaveText(value, { timeout: 30_000 });
});

When('取り込み候補を反映する', async ({ page }) => {
  await importSection(page).getByRole('button', { name: '反映' }).click();
  await expect(importSection(page).getByRole('status').filter({ hasText: '反映しました' })).toBeVisible({
    timeout: 30_000,
  });
});

Then(
  /^反映結果に、保存されなかった「(.+)」とその理由が表示される$/,
  async ({ page }, key: string) => {
    const rejected = importSection(page).getByRole('list', { name: '保存されなかった項目' }).getByRole('listitem');
    const item = rejected.filter({ hasText: key });
    await expect(item).toBeVisible({ timeout: 30_000 });
    // 理由(キー名だけでなく「なぜ」)が続くこと。
    await expect(item).toContainText('指定できない接続先');
  }
);

Then(/^取り込みのエラーとして「(.+)」が表示される$/, async ({ page }, message: string) => {
  await expect(importSection(page).getByRole('alert')).toContainText(message, { timeout: 30_000 });
});

Then(/^システム設定画面を開き直すと「(.+)」は「(.+)」と表示される$/, async ({ page }, key: string, value: string) => {
  await openSettings(page);
  await expect(page.locator(`input[name="${key}"]`)).toHaveValue(value, { timeout: 30_000 });
});

Then(/^システム設定画面を開き直すと、ポータルのURLに「(.+)」が入力済みである$/, async ({ page }, url: string) => {
  await openSettings(page);
  await expect(importSection(page).getByLabel('ポータルのURL')).toHaveValue(url, { timeout: 30_000 });
});

// ---- API ----

Then(/^システム設定の「(.+)」は「(.+)」である$/, async ({ request }, key: string, value: string) => {
  expect(await currentValue(request, key)).toBe(value);
});

Then(/^システム設定の「(.+)」はまだ「(.+)」のままである$/, async ({ request }, key: string, value: string) => {
  expect(await currentValue(request, key)).toBe(value);
});

When(/^管理者がポータルのURL「(.+)」で取り込みの取得を要求する$/, async ({ ctx, request }, url: string) => {
  const response = await request.post(`${IMPORT_API}/fetch`, {
    headers: await adminHeaders(request),
    data: { portalUrl: url, route: 'SAME_PC' },
  });
  ctx.importStatus = response.status();
});

When(/^一般ユーザーがポータルのURL「(.+)」で取り込みの取得を要求する$/, async ({ ctx, request }, url: string) => {
  const response = await request.post(`${IMPORT_API}/fetch`, {
    headers: { Authorization: `Bearer ${ctx.userToken as string}` },
    data: { portalUrl: url, route: 'SAME_PC' },
  });
  ctx.importStatus = response.status();
});

When(
  /^一般ユーザーがOllamaの接続先「(.+)」の取り込みの反映を要求する$/,
  async ({ ctx, request }, url: string) => {
    const response = await request.post(`${IMPORT_API}/apply`, {
      headers: { Authorization: `Bearer ${ctx.userToken as string}` },
      data: { portalUrl: 'http://lbx-portal:3100', values: { llm_ollama_base_url: url } },
    });
    ctx.importStatus = response.status();
  }
);

Then('取り込みの要求は403で拒否される', async ({ ctx }) => {
  expect(ctx.importStatus).toBe(403);
});

// platform-service は不正な設定値(IllegalArgumentException)を409 CONFLICTで返す(GlobalExceptionHandler)。
Then(/^取り込みの要求はポータルのURLの検証エラー\(409\)で拒否される$/, async ({ ctx }) => {
  expect(ctx.importStatus).toBe(409);
});
