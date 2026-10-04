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

/**
 * システム設定(アプリ設定・LLM接続・Brave Searchキー・認可)の受け入れシナリオを支える
 * ステップ定義(issue #1152 / AT-14-1)。
 *
 * ## platform.steps.ts と分けている理由
 *
 * 親issue #940の分割で、ダッシュボード状態・バックアップ・VSCode拡張配布は兄弟issueが
 * 引き取る。これらは同じ `apps/web/e2e/steps/platform.steps.ts` に相乗りさせると
 * ファイル衝突が起きるため(前例: `mediaGarbageCollection.steps.ts` は `media.steps.ts` と
 * 別ファイル)、この issue の範囲だけを新規ファイルへ切り出す。
 */

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function userToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
}

// ---- システム設定(アプリ設定の保存反映) ----

const WEB_BASE_URL_LABEL = 'Webフロントの公開URL';

Given(
  'システム設定画面でWebフロントの公開URLを保存する',
  async ({ ctx, page }) => {
    await page.goto('/admin/system-settings', { waitUntil: 'commit' });
    await expect(page.getByRole('heading', { name: 'システム設定' })).toBeVisible({ timeout: 30_000 });

    const value = `https://e2e1152-${Date.now()}.example.com`;
    ctx.systemSettingsWebBaseUrl = value;

    const field = page
      .locator('label')
      .filter({ hasText: WEB_BASE_URL_LABEL })
      .locator('input[name="app_web_base_url"]');
    await expect(field).toBeVisible({ timeout: 30_000 });
    await field.fill(value);

    await page.getByRole('button', { name: 'まとめて保存' }).click();
    await expect(page.getByText('保存しました。')).toBeVisible({ timeout: 30_000 });
  }
);

Then(
  'ページを開き直しても、保存したWebフロントの公開URLが表示される',
  async ({ ctx, page }) => {
    await page.goto('/admin/system-settings', { waitUntil: 'commit' });
    const field = page
      .locator('label')
      .filter({ hasText: WEB_BASE_URL_LABEL })
      .locator('input[name="app_web_base_url"]');
    await expect(field).toHaveValue(ctx.systemSettingsWebBaseUrl as string, { timeout: 30_000 });
  }
);

// ---- ChatGPT / ClaudeのAPIキー入力欄が無い(issue #1568) ----

Then('システム設定画面にChatGPTとClaudeのAPIキー入力欄が表示されない', async ({ page, request }) => {
  await expect(page.locator('[name="llm_api_key"]')).toHaveCount(0);
  await expect(page.locator('[name="llm_claude_api_key"]')).toHaveCount(0);
  const keys = (await fetchAppSettings(request)).map((s) => s.key);
  expect(keys, 'アプリ設定の応答にChatGPT / ClaudeのAPIキーの項目が残っています').not.toContain('llm_api_key');
  expect(keys, 'アプリ設定の応答にChatGPT / ClaudeのAPIキーの項目が残っています').not.toContain('llm_claude_api_key');
});

Then('システム設定画面にはキー以外のLLM設定が表示される', async ({ page }) => {
  await expect(page.locator('[name="llm_claude_model"]')).toBeVisible();
  await expect(page.locator('[name="llm_base_url"]')).toBeVisible();
});

// ---- LLM接続設定(DB側優先の確認) ----

/**
 * 到達不能なURL(TCPの1番ポートには何も待ち受けていない前提)。接続がすぐrefusedになるため、
 * タイムアウト待ちで無駄にシナリオを遅くしない。
 */
const UNREACHABLE_LLM_BASE_URL = 'http://127.0.0.1:1/v1';

interface AppSettingStatus {
  key: string;
  value: string | null;
  source: 'DATABASE' | 'ENVIRONMENT' | 'NONE';
}

async function fetchAppSettings(request: APIRequestContext): Promise<AppSettingStatus[]> {
  const response = await request.get('/api/system-settings/app-settings', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
  });
  expect(
    response.ok(),
    `アプリ設定の取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as AppSettingStatus[];
}

async function putAppSettings(request: APIRequestContext, updates: Record<string, string>): Promise<void> {
  const response = await request.put('/api/system-settings/app-settings', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: updates,
  });
  expect(
    response.ok(),
    `アプリ設定の更新に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

/**
 * `/api/ai/tags` はプロジェクトを指定しない呼び出しであり、ai-serviceの
 * `RemoteLlmConfigProvider#resolveDefault()` を経由してシステム全体既定のLLM接続設定
 * (platform-serviceの `AppSettingService`、DB優先・未設定時は環境変数)を使う。
 * プロジェクト個別のLLMプロバイダー上書きの影響を受けないため、「DB側優先」の検証に使える。
 */
async function requestTagSuggestion(request: APIRequestContext) {
  return request.post('/api/ai/tags', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: { text: 'e2e1152のLLM接続設定を確かめるための本文です。' },
  });
}

When('システム全体のLLM接続設定を到達不能なURLへ変更する', async ({ ctx, request }) => {
  const settings = await fetchAppSettings(request);
  const provider = settings.find((s) => s.key === 'llm_provider');
  const baseUrl = settings.find((s) => s.key === 'llm_ollama_base_url');
  expect(provider, 'llm_providerの設定項目が見つかりません').toBeDefined();
  expect(baseUrl, 'llm_ollama_base_urlの設定項目が見つかりません').toBeDefined();
  // DB由来でなければ(=環境変数フォールバック)、復元時は空文字を送ってDB設定を削除し、
  // 環境変数へのフォールバックへ戻す。
  ctx.systemSettingsLlmOriginalProvider = provider!.source === 'DATABASE' ? provider!.value ?? '' : '';
  ctx.systemSettingsLlmOriginalBaseUrl = baseUrl!.source === 'DATABASE' ? baseUrl!.value ?? '' : '';

  // issue #1568: ChatGPT / Claudeはプロジェクトのキーが無いとLLM呼び出し前に失敗するため、
  // プロジェクト指定の無い呼び出しで接続先を確かめられるOLLAMAの接続先を書き換える。
  await putAppSettings(request, { llm_provider: 'OLLAMA', llm_ollama_base_url: UNREACHABLE_LLM_BASE_URL });
});

When('システム全体のLLM接続設定を元に戻す', async ({ ctx, request }) => {
  await putAppSettings(request, {
    llm_provider: (ctx.systemSettingsLlmOriginalProvider as string) ?? '',
    llm_ollama_base_url: (ctx.systemSettingsLlmOriginalBaseUrl as string) ?? '',
  });
});

// ---- LLMプロバイダーの一時切替(issue #1397: 連携サービスの状況のOllama行) ----

/**
 * システム全体のLLMプロバイダーをOLLAMAへ切り替える。ATスタックは環境変数でOPENAIに固定されているが、
 * DB設定は環境変数より優先される(AppSettingService)ため、これでplatform-serviceの
 * ConnectedServiceStatusService#checkLlm がOllama経路(LLM_OLLAMA_BASE_URL=llm-stub)を通る。
 * 復元値はPUTより前にctxへ記録し、PUTが失敗しても After で戻せるようにする。
 */
Given('システム全体のLLMプロバイダーをOllamaへ変更する', async ({ ctx, request }) => {
  const provider = (await fetchAppSettings(request)).find((s) => s.key === 'llm_provider');
  expect(provider, 'llm_providerの設定項目が見つかりません').toBeDefined();
  // DB由来でなければ空文字を送ってDB設定を削除し、環境変数へのフォールバックへ戻す。
  ctx.systemSettingsOllamaSwitchOriginal = provider!.source === 'DATABASE' ? provider!.value ?? '' : '';
  await putAppSettings(request, { llm_provider: 'OLLAMA' });
});

After(async ({ ctx, request }) => {
  if (ctx.systemSettingsOllamaSwitchOriginal === undefined) return;
  await putAppSettings(request, { llm_provider: ctx.systemSettingsOllamaSwitchOriginal as string });
});

Then('タグ提案の呼び出しは失敗する', async ({ request }) => {
  const response = await requestTagSuggestion(request);
  expect(
    response.ok(),
    'タグ提案が成功しました。LLM接続設定の変更が実際のAI生成経路へ反映されていません(環境変数側が優先されている疑い)。'
  ).toBe(false);
});

Then('タグ提案の呼び出しは成功する', async ({ request }) => {
  const response = await requestTagSuggestion(request);
  expect(
    response.ok(),
    `タグ提案の呼び出しに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

// ---- Brave Search APIキー(システム全体) ----

When('システム全体のBrave Search APIキーを保存する', async ({ ctx, request }) => {
  ctx.systemSettingsBraveApiKey = `e2e1152-brave-${Date.now()}`;
  const response = await request.put('/api/system-settings/brave-search-api-key', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: { apiKey: ctx.systemSettingsBraveApiKey },
  });
  expect(
    response.ok(),
    `Brave Search APIキーの保存に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

async function fetchBraveKeyStatus(
  request: APIRequestContext
): Promise<{ configured: boolean; source: string; body: string }> {
  const response = await request.get('/api/system-settings/brave-search-api-key', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
  });
  expect(
    response.ok(),
    `Brave Search APIキーの状態取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const body = await response.text();
  const parsed = JSON.parse(body) as { configured: boolean; source: string };
  return { configured: parsed.configured, source: parsed.source, body };
}

Then(
  '状態取得APIは「設定済み」を返すが、キーの値そのものは含まない',
  async ({ ctx, request }) => {
    const { configured, source, body } = await fetchBraveKeyStatus(request);
    expect(configured, `応答本文: ${body}`).toBe(true);
    expect(source, `応答本文: ${body}`).toBe('DATABASE');
    expect(body, '状態取得APIの応答に平文のAPIキーが含まれています').not.toContain(
      ctx.systemSettingsBraveApiKey as string
    );
  }
);

When('システム全体のBrave Search APIキーを削除する', async ({ request }) => {
  const response = await request.delete('/api/system-settings/brave-search-api-key', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
  });
  expect(
    response.ok(),
    `Brave Search APIキーの削除に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

/**
 * 削除後は「DB設定を使用中」ではなくなる、という判定にする。この環境は
 * BRAVE_SEARCH_API_KEY(環境変数)を設定済みのため、削除後も `configured` はtrue
 * (環境変数へフォールバック)のままになりうる。「未設定」を一律に期待すると
 * 環境変数を設定した開発環境で常に失敗するため、DB由来ではなくなったことで判定する。
 */
Then('状態取得APIは「未設定」を返す', async ({ request }) => {
  const { source, body } = await fetchBraveKeyStatus(request);
  expect(source, `応答本文: ${body}`).not.toBe('DATABASE');
});

// ---- 一般ユーザーによる変更拒否 ----

Then('一般ユーザーとしてアプリ設定の更新を要求すると拒否される', async ({ request }) => {
  const response = await request.put('/api/system-settings/app-settings', {
    headers: { Authorization: `Bearer ${await userToken(request)}` },
    data: { app_web_base_url: 'https://should-not-be-applied.example.com' },
  });
  expect(response.status(), `応答本文: ${await response.text()}`).toBe(403);
});

Then(
  '一般ユーザーとしてシステム全体のBrave Search APIキーの保存を要求すると拒否される',
  async ({ request }) => {
    const response = await request.put('/api/system-settings/brave-search-api-key', {
      headers: { Authorization: `Bearer ${await userToken(request)}` },
      data: { apiKey: 'should-not-be-applied' },
    });
    expect(response.status(), `応答本文: ${await response.text()}`).toBe(403);
  }
);

// ---- 管理画面パス(グローバル既定値、issue #1079) ----

const SITE_ADMIN_PATH_INPUT = 'input[name="site_admin_path"]';
const SITE_ADMIN_PATH_LABEL = '管理画面パス';

When('システム設定画面を開く', async ({ page }) => {
  await page.goto('/admin/system-settings', { waitUntil: 'commit' });
  await expect(page.getByRole('heading', { name: 'システム設定' })).toBeVisible({ timeout: 30_000 });
  await expect(page.locator(SITE_ADMIN_PATH_INPUT)).toBeVisible({ timeout: 30_000 });
});

Then(
  '管理画面パスの設定項目に値{string}と設定元{string}が表示される',
  async ({ page }, value: string, source: string) => {
    const input = page.locator(SITE_ADMIN_PATH_INPUT);
    await expect(input).toHaveValue(value, { timeout: 30_000 });
    await expect(page.locator('label').filter({ has: input })).toContainText(source);
  }
);

async function saveSiteAdminPath(page: import('@playwright/test').Page, value: string) {
  await page.locator(SITE_ADMIN_PATH_INPUT).fill(value);
  await page.getByRole('button', { name: 'まとめて保存' }).click();
}

When('管理画面パスに{string}を入力してまとめて保存する', async ({ page }, value: string) => {
  await saveSiteAdminPath(page, value);
});

When('管理画面パスに{int}文字の値を入力してまとめて保存する', async ({ page }, length: number) => {
  await saveSiteAdminPath(page, 'a'.repeat(length));
});

When(
  'Webフロントの公開URLを変更し、管理画面パスに{string}を入力してまとめて保存する',
  async ({ ctx, page }, value: string) => {
    const webBaseUrl = page.locator('input[name="app_web_base_url"]');
    ctx.systemSettingsWebBaseUrlBefore = await webBaseUrl.inputValue();
    await webBaseUrl.fill(`https://e2e1079-${Date.now()}.example.com`);
    await saveSiteAdminPath(page, value);
  }
);

Then('保存に成功する', async ({ page }) => {
  await expect(page.getByText('保存しました。')).toBeVisible({ timeout: 30_000 });
});

Then('保存が拒否されエラーが表示される', async ({ page }) => {
  await expect(page.getByText('この保存操作での変更は反映されていません')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText('保存しました。')).toHaveCount(0);
  await expect(page.getByText(new RegExp(`site_admin_path|${SITE_ADMIN_PATH_LABEL}`)).first()).toBeVisible();
});

Then('Webフロントの公開URLは保存前の値のままである', async ({ ctx, page }) => {
  await expect(page.locator('input[name="app_web_base_url"]')).toHaveValue(
    ctx.systemSettingsWebBaseUrlBefore as string,
    { timeout: 30_000 }
  );
});

Then(
  '一般ユーザーとして管理画面パスを取得すると、管理者の設定画面と同じ解決済みの値が返る',
  async ({ request }) => {
    const expected = (await fetchAppSettings(request)).find((s) => s.key === 'site_admin_path');
    expect(expected, 'site_admin_pathの設定項目が見つかりません').toBeDefined();
    const response = await request.get('/api/system-settings/site-admin-path', {
      headers: { Authorization: `Bearer ${await userToken(request)}` },
    });
    expect(response.status(), `応答本文: ${await response.text()}`).toBe(200);
    expect(((await response.json()) as { path: string }).path).toBe(expected!.value);
  }
);

Then('認証なしで管理画面パスを取得すると401で拒否される', async ({ request }) => {
  const response = await request.get('/api/system-settings/site-admin-path');
  expect(response.status(), `応答本文: ${await response.text()}`).toBe(401);
});

Then('一般ユーザーとしてアプリ設定の一覧を要求すると403で拒否される', async ({ request }) => {
  const response = await request.get('/api/system-settings/app-settings', {
    headers: { Authorization: `Bearer ${await userToken(request)}` },
  });
  expect(response.status(), `応答本文: ${await response.text()}`).toBe(403);
});
