import type { APIRequestContext, Locator, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  expect,
  fetchAccessToken,
} from '../support';
import { forceStubDelay, forceStubStatus, resetStub } from '../support/stubs';

/**
 * Analytics(Google Analytics / AdSense)の受け入れシナリオを支えるステップ定義
 * (issue #939 / AT-13)。
 *
 * ## 外部SaaSは呼ばない
 *
 * 向き先は `docker-compose.e2e-stubs.yml` が analytics-service の
 * `GOOGLE_ANALYTICS_DATA_API_BASE_URL` / `GOOGLE_ANALYTICS_OAUTH_TOKEN_URI` /
 * `ADSENSE_DATA_API_BASE_URL` / `GOOGLE_OAUTH_TOKEN_URI` をスタブへ差し替える。
 * したがってここが登録する資格情報は**スタブに通ればよい**だけの作り物である。
 *
 * サービスアカウントJSONに `token_uri` を入れてはならない。入れると環境変数より
 * 優先され、実 Google のトークンエンドポイントへ出ていく
 * (docs/ACCEPTANCE_TESTING.md §9)。
 *
 * ## フィクスチャに本番サイトが要る
 *
 * `GoogleAnalyticsReportService` / `AdSenseReportService` は、資格情報があっても
 * **本番サイトが紐付いていなければ** 外部APIを呼ばずに `eligible=false` を返す
 * (`ProjectBridgeClient.ProjectEligibility#hasProductionSite`)。レポート表示の
 * シナリオを成立させるため、フィクスチャのプロジェクトにはサイトを1件登録して
 * 本番環境へ紐付ける。WordPress のプロビジョニングはしない — 紐付いていることが
 * 判定のすべてで、サイトの中身は使われないため(実プロビジョニングは AT-5 / #931)。
 *
 * ## 後片付け
 *
 * `After({ tags: '@analytics' })` が、資格情報 → プロジェクトメンバー → プロジェクト →
 * サイトの順に戻し、スタブへの注入も解除する。資格情報を先に消すのは、
 * `analytics_credentials` が `lbs_analytics` にあり `projects` への外部キーを持たない
 * (ADR-0004)ため、プロジェクトを先に消すと行が孤児として残るからである。
 */

// ------------------------------------------------------------------ 共通

type ScenarioState = Record<string, unknown>;

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

/** 「一般利用者」= 非adminの合成アカウント。プロジェクト単位の仕切りの検証に使う。 */
async function memberToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await adminToken(request)}` };
}

async function memberHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await memberToken(request)}` };
}

// --------------------------------------------------------- 資格情報の作り物

/**
 * 受け入れテスト専用のRSA秘密鍵。**実在のGoogleアカウントとは無関係**で、
 * `services/analytics/src/test/java/.../GoogleServiceAccountJwtSignerTest.java` が使っている
 * ものと同じ鍵である(同じ用途の値を2つ持たない)。
 *
 * 本物である必要はないが、**RSAの鍵として妥当**である必要はある。analytics-service は
 * これで自己署名JWTを組み立ててからスタブへ投げる(`GoogleServiceAccountJwtSigner`)。
 * 壊れた鍵を入れると署名の段で落ち、「スタブが何を返したか」を確かめる前に終わる。
 */
const TEST_PRIVATE_KEY_PEM = [
  '-----BEGIN PRIVATE KEY-----',
  'MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC2mlclKKyllzeV',
  'Cx3Qgwdk7Jrrpk6lA77G6TbnXjmNB9EDbn0IqYa/eqmbixUKywLHPRi2ZZ/4pjcf',
  'XgCPsJo72/yVmlA0pHSFsxhS61RZqfJRtZx+jW/isty30OimWvwJtCHgMTdsWUFt',
  'LJ264s30CiQ1ZNVtPCzCoYJ/qNOR+rvoEr/CMCU9ZU8TGQD08csdurBW2+SY7Buw',
  'o2NnypgGH98gVLdF9BY6xVRGvCvWHfM3kfo8gzQz4amVyKfXw8GllPScn1wcpu9m',
  '2EU7Eoct34GVITVzD7qvY+jHvtsZgBoxUvL6H3MMmaxtzh0nrA3Xtw7FX32f0ZH3',
  'snBQch+PAgMBAAECggEAJokKxAJB8Q4pAjCe4Z6NRGy0Qu/NYACa1bJozknxvkP8',
  'hYtfIqFYGPejbHpc/fKayv4nRXLL4Db/ogR9/NTpr6E8vDudGobsOjzx8KnOGsAF',
  'Ld40QPbLOl3Bu58AQf8oeknD7mKkjh6F8qq8PLDZgttTCduWON++mHJqLlOsFn2m',
  'hV3sdIJOvFxFEAEz/+wS1bWmYcCkDYyiSAlvCAAECWBAsxG3QumH0AuvJKTETEw5',
  'm18W9w9PcdSvIk5y3SLp7zXZPDoDBVnNq/22VM50XP3UhG+uwvnslVQUdMt9Inn9',
  '6syXxmQqqKEW8jSpoIII5RJ20xqdD5VKPpz7Q1MjgQKBgQDoSo/zXdSQfZN4MboM',
  '0bQlQFh3Aw8Ygx3gsAkhB6oNZH5kH4cj1D/io5CiF2luu8g+AJLCmNMbJk5aVLQV',
  'B+s/ikd4qCU/Ld1bLTAvdaUis2DbXh8CVId/8Fe1/buQ+MP6gvpuBASvLgiCV7Ih',
  '3QX5Lv5puls1pcAqT6VOAkRCgQKBgQDJPX4Gsv3oWIQscjbtFUYXxzftZIORFOUo',
  'c82+OdRWAC7NMKSpw75GpSTPpXt2G3al2ZMDEZpzV18mSsvmmrCNsQX4+AkbI82D',
  'DUprXpc7FRENcG582aT5X8sJ4B+0FCVV5BIBPuxZz9s9Qc8YTXWRemATv70LB5U5',
  'ynmyROE6DwKBgQDn6JDgoju2aXiSFesuIypbymrHnokyqqxohrcGf9VZe4vnv8Y2',
  'kg+Z4DxkZ0U+ZUFcDUx39QVF5K9y5X/IQ1is3gvOvOg6tDp7bZjeuPA9vaIkQEpr',
  'FCMXKscWjZP1/zYBY0RME7zte+LI5m6T+kqdZTpgKconvCwm0c8yG3c0gQKBgQDE',
  '+Z+lxwWoqxuUtab1oOEe3Szs/HmbRKyZT+CO1eP02fD1fyttz98rHvJNHVkfXfpg',
  'k/rGAjD/vQGxZXz3l2pBBokmDQI8wmqiYBv7xHaaqiAq22YKZq6IOS9v1ySxCxcQ',
  'X1EQTxrhPgcGiqe+zfLKFtJ8Ai1z4lQ6YOmFiM48GQKBgCexCI5KqOZyqHTiY771',
  '7qyurXk4LWahFSZDGIH2KZDp/pi6yyvjdWDkx9lTDFgVCwN1Tqv7PEgLhJMsiVds',
  'doXjpGoqdYqqPDRvBuui5cx3j4tmJX+lidWmays+JCk54vyvJ0rm4JxCAZgN6XoS',
  'nlG1wg12bvzlmVtRVjDv67mH',
  '-----END PRIVATE KEY-----',
].join('\n');

/**
 * 「保存した秘密情報が後から読み出せない」ことを探すための目印(#939 受け入れ基準3)。
 * 秘密鍵そのものの一部を使う。別の文字列を目印に混ぜても「その文字列が出ない」ことしか
 * 言えず、**鍵が漏れていない**ことの証拠にはならない。
 */
const PRIVATE_KEY_FINGERPRINT = 'MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC2mlclKKyllzeV';

/** ga-stub は `client_email` が `invalid@` で始まるときトークン交換を401にする。 */
const EXPIRED_CLIENT_EMAIL = 'invalid@at13-expired.iam.gserviceaccount.com';

/** adsense-stub が認可コードフローで返すリフレッシュトークン。漏れていないことの確認に使う。 */
const STUB_REFRESH_TOKEN = 'e2e-stub-adsense-refresh-token';

const GA_PROPERTY_ID = '987654321';
const ADSENSE_ACCOUNT_ID = 'pub-1234567890123456';
const ADSENSE_CLIENT_ID = 'at13-acceptance.apps.googleusercontent.com';

function serviceAccountJson(clientEmail: string): string {
  // token_uri は入れない(入れると環境変数より優先され実 Google へ出ていく)。
  return JSON.stringify({
    type: 'service_account',
    project_id: 'at13-acceptance',
    private_key_id: 'at13',
    client_email: clientEmail,
    client_id: '100000000000000000001',
    private_key: TEST_PRIVATE_KEY_PEM,
  });
}

// ------------------------------------------------------------ フィクスチャ

interface AnalyticsProject {
  id: number;
  name: string;
  /** 本番サイトを紐付けた場合のサイトID。認可のシナリオは紐付けない(下記)。 */
  siteId?: number;
}

function trackedProjects(ctx: ScenarioState): AnalyticsProject[] {
  ctx.analyticsProjects ??= [];
  return ctx.analyticsProjects as AnalyticsProject[];
}

function currentProject(ctx: ScenarioState): AnalyticsProject {
  const project = ctx.analyticsProject as AnalyticsProject | undefined;
  if (!project) {
    throw new Error('先にプロジェクトを用意するステップを実行すること');
  }
  return project;
}

/** このシナリオ限りのクライアントシークレット。漏れていないことを目印で確かめる。 */
function clientSecret(ctx: ScenarioState): string {
  ctx.analyticsClientSecret ??= `at13-client-secret-${uniqueSuffix()}`;
  return ctx.analyticsClientSecret as string;
}

/**
 * サイトを持たないプロジェクト。認可のシナリオはこちらを使う。
 *
 * <p><b>認可の検証にサイトを紐付けてはならない。</b>`POST /api/projects/{id}/users` は
 * identity-service から publishing-service の著者プロビジョニング
 * (`/api/internal/publishing/sites/{siteKey}/authors`)を呼ぶ。フィクスチャのサイトは
 * 実体の無い WordPress なのでこれが500になり、メンバー追加そのものが失敗する
 * (実測: `publishing-serviceの著者プロビジョニング呼び出しに失敗しました: 500`)。
 * 認可判定はレポートの取得可否より手前で効くので、サイトは要らない。
 */
async function createBareProject(
  request: APIRequestContext,
  ctx: ScenarioState
): Promise<AnalyticsProject> {
  const suffix = uniqueSuffix();
  const name = `E2E 939 ${suffix}`;
  const created = await request.post('/api/projects', {
    headers: await adminHeaders(request),
    data: { name, slug: `e2e-939-${suffix}` },
  });
  expect(
    created.ok(),
    `プロジェクトの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const project: AnalyticsProject = { id: ((await created.json()) as { id: number }).id, name };
  trackedProjects(ctx).push(project);
  return project;
}

async function createAnalyticsProject(
  request: APIRequestContext,
  ctx: ScenarioState
): Promise<AnalyticsProject> {
  const headers = await adminHeaders(request);
  const project = await createBareProject(request, ctx);
  const projectId = project.id;
  const suffix = uniqueSuffix();

  // 本番サイトが紐付いていないとレポートは eligible=false になる。中身は使われないので
  // WordPress のプロビジョニングはしない(登録だけ)。
  const siteKey = `e2e939${suffix}`.toLowerCase().replace(/[^a-z0-9]/g, '');
  const site = await request.post('/api/sites', {
    headers,
    data: {
      name: `E2E 939 site ${suffix}`,
      siteKey,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'AGENT',
        baseUrl: 'http://wordpress',
        username: 'at13-fixture',
        appPassword: 'at13 fixture app password',
      },
    },
  });
  expect(
    site.ok(),
    `サイトの登録に失敗しました (status=${site.status()}): ${await site.text()}`
  ).toBe(true);
  const siteId = ((await site.json()) as { id: number }).id;

  const bound = await request.post(`/api/projects/${projectId}/environments`, {
    headers,
    data: { environment: 'production', siteId },
  });
  expect(
    bound.ok(),
    `本番サイトの紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`
  ).toBe(true);

  project.siteId = siteId;
  return project;
}

async function putGoogleAnalyticsCredentials(
  request: APIRequestContext,
  projectId: number,
  clientEmail: string
): Promise<void> {
  const response = await request.put(`/api/projects/${projectId}/api-keys/google-analytics`, {
    headers: await adminHeaders(request),
    data: { propertyId: GA_PROPERTY_ID, serviceAccountJson: serviceAccountJson(clientEmail) },
  });
  expect(
    response.ok(),
    `GAの資格情報の登録に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

async function putAdSenseClient(
  request: APIRequestContext,
  projectId: number,
  secret: string
): Promise<void> {
  const headers = await adminHeaders(request);
  const settings = await request.put(`/api/projects/${projectId}/api-keys/adsense`, {
    headers,
    data: { accountId: ADSENSE_ACCOUNT_ID, clientId: ADSENSE_CLIENT_ID },
  });
  expect(
    settings.ok(),
    `AdSenseの設定に失敗しました (status=${settings.status()}): ${await settings.text()}`
  ).toBe(true);

  const stored = await request.put(`/api/projects/${projectId}/api-keys/adsense/client-secret`, {
    headers,
    data: { clientSecret: secret },
  });
  expect(
    stored.ok(),
    `AdSenseのクライアントシークレット保存に失敗しました (status=${stored.status()}): ${await stored.text()}`
  ).toBe(true);
}

async function completeAdSenseOAuth(
  request: APIRequestContext,
  projectId: number,
  code: string
): Promise<{ status: number; body: string }> {
  const response = await request.post(`/api/projects/${projectId}/api-keys/adsense/oauth-callback`, {
    headers: await adminHeaders(request),
    data: { code, redirectUri: 'https://localhost/connect/adsense/callback' },
  });
  return { status: response.status(), body: await response.text() };
}

// ------------------------------------------------------------ 前提ステップ

Given('Analytics を確かめるためのプロジェクトがある', async ({ ctx, request }) => {
  ctx.analyticsProject = await createAnalyticsProject(request, ctx);
});

Given('そのプロジェクトにGoogle Analyticsの資格情報が登録されている', async ({ ctx, request }) => {
  await putGoogleAnalyticsCredentials(
    request,
    currentProject(ctx).id,
    'at13-acceptance@at13.iam.gserviceaccount.com'
  );
});

Given('そのプロジェクトに失効したGoogle Analyticsの資格情報が登録されている', async ({ ctx, request }) => {
  await putGoogleAnalyticsCredentials(request, currentProject(ctx).id, EXPIRED_CLIENT_EMAIL);
});

Given('そのプロジェクトにAdSenseのパブリッシャーIDとOAuthクライアントが登録されている', async ({ ctx, request }) => {
  await putAdSenseClient(request, currentProject(ctx).id, clientSecret(ctx));
});

Given('そのプロジェクトにAdSenseの資格情報が登録されている', async ({ ctx, request }) => {
  const projectId = currentProject(ctx).id;
  await putAdSenseClient(request, projectId, clientSecret(ctx));
  const outcome = await completeAdSenseOAuth(request, projectId, 'at13-authorization-code');
  expect(
    outcome.status,
    `AdSenseのOAuth連携に失敗しました: ${outcome.body}`
  ).toBe(204);
});

Given('そのプロジェクトにGoogle AnalyticsとAdSenseの資格情報が登録されている', async ({ ctx, request }) => {
  const projectId = currentProject(ctx).id;
  await putGoogleAnalyticsCredentials(request, projectId, 'at13-acceptance@at13.iam.gserviceaccount.com');
  await putAdSenseClient(request, projectId, clientSecret(ctx));
  const outcome = await completeAdSenseOAuth(request, projectId, 'at13-authorization-code');
  expect(
    outcome.status,
    `AdSenseのOAuth連携に失敗しました: ${outcome.body}`
  ).toBe(204);
});

// ------------------------------------------------------ 設定画面(GA)

function gaSettingsPath(projectId: number): string {
  return `/projects/${projectId}/settings/google-analytics`;
}

function adSenseSettingsPath(projectId: number): string {
  return `/projects/${projectId}/settings/adsense`;
}

async function openSettings(page: Page, path: string, heading: RegExp): Promise<void> {
  await page.goto(path, { waitUntil: 'commit' });
  await expect(page.getByRole('heading', { name: heading })).toBeVisible({ timeout: 30_000 });
}

When(
  /^そのプロジェクトのGoogle Analytics設定でプロパティID「([^」]+)」とサービスアカウントのJSON鍵を保存する$/,
  async ({ ctx, page }, propertyId: string) => {
    const project = currentProject(ctx);
    await openSettings(page, gaSettingsPath(project.id), /Google Analytics設定$/);
    await page.locator('input[name="propertyId"]').fill(propertyId);
    await page
      .locator('textarea[name="serviceAccountJson"]')
      .fill(serviceAccountJson('at13-acceptance@at13.iam.gserviceaccount.com'));
    await page.getByRole('button', { name: '保存', exact: true }).click();
  }
);

Then(
  /^Google Analytics設定の状態に「([^」]+)」と表示される$/,
  async ({ page }, expected: string) => {
    await expect(page.getByText(expected, { exact: true })).toBeVisible({ timeout: 30_000 });
  }
);

When('そのプロジェクトのGoogle Analytics設定を削除する', async ({ ctx, page }) => {
  const project = currentProject(ctx);
  await openSettings(page, gaSettingsPath(project.id), /Google Analytics設定$/);
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: '設定を削除', exact: true }).click();
});

Then('Google Analytics設定のAPI応答は未設定を示す', async ({ ctx, request }) => {
  const response = await request.get(
    `/api/projects/${currentProject(ctx).id}/api-keys/google-analytics`,
    { headers: await adminHeaders(request) }
  );
  expect(response.status(), 'GAの設定状態を取得できない').toBe(200);
  const status = (await response.json()) as { configured: boolean; propertyId: string | null };
  expect(status.configured, '削除したのに設定済みのままである').toBe(false);
  expect(status.propertyId, '削除したのにプロパティIDが残っている').toBeNull();
});

// ------------------------------------------------- 設定画面(AdSense)

When('そのプロジェクトのGoogle AdSense設定でパブリッシャーIDとOAuthクライアントを保存する', async ({ ctx, page }) => {
  const project = currentProject(ctx);
  await openSettings(page, adSenseSettingsPath(project.id), /Google AdSense設定$/);
  await page.locator('input[name="accountId"]').fill(ADSENSE_ACCOUNT_ID);
  await page.locator('input[name="clientId"]').fill(ADSENSE_CLIENT_ID);
  await page.locator('input[name="clientSecret"]').fill(clientSecret(ctx));
  await page.getByRole('button', { name: 'まとめて保存', exact: true }).click();
});

Then('Google AdSense設定にクライアントシークレットが設定済みとして表示される', async ({ page }) => {
  // 値は返らない。フォームは「設定済み」であることだけを placeholder で示す。
  await expect(page.locator('input[name="clientSecret"]')).toHaveAttribute(
    'placeholder',
    '設定済み(変更する場合のみ入力)',
    { timeout: 30_000 }
  );
});

Then('Googleアカウントとの連携を始めるリンクが現れる', async ({ ctx, page }) => {
  const link = page.getByRole('link', { name: 'Google AdSenseと連携' });
  await expect(link).toBeVisible({ timeout: 30_000 });
  await expect(link).toHaveAttribute(
    'href',
    `/connect/adsense/start?projectId=${currentProject(ctx).id}`
  );
});

// ------------------------------------------------ 秘密情報の再表示(シナリオ2)

When('Google Analytics設定の画面を開き直す', async ({ ctx, page }) => {
  await openSettings(page, gaSettingsPath(currentProject(ctx).id), /Google Analytics設定$/);
});

When('Google AdSense設定の画面を開き直す', async ({ ctx, page }) => {
  await openSettings(page, adSenseSettingsPath(currentProject(ctx).id), /Google AdSense設定$/);
});

Then('画面にサービスアカウントの秘密鍵は現れない', async ({ page }) => {
  const html = await page.content();
  expect(html, '保存したサービスアカウントの秘密鍵が画面に再表示されている').not.toContain(
    PRIVATE_KEY_FINGERPRINT
  );
  expect(html, '保存した秘密鍵のPEMヘッダが画面に再表示されている').not.toContain(
    'BEGIN PRIVATE KEY'
  );
});

Then('Google Analytics設定のAPI応答にサービスアカウントの秘密鍵は含まれない', async ({ ctx, request }) => {
  const response = await request.get(
    `/api/projects/${currentProject(ctx).id}/api-keys/google-analytics`,
    { headers: await adminHeaders(request) }
  );
  expect(response.status(), 'GAの設定状態を取得できない').toBe(200);
  const body = await response.text();
  expect(body, 'API応答にサービスアカウントの秘密鍵が含まれている').not.toContain(
    PRIVATE_KEY_FINGERPRINT
  );
  expect(body, 'API応答にサービスアカウントJSONが含まれている').not.toContain('private_key');
  // 設定済みであること自体は返る(それが無いと画面が状態を出せない)。
  expect((await response.json()) as { configured: boolean }).toMatchObject({ configured: true });
});

Then('画面にクライアントシークレットは現れない', async ({ ctx, page }) => {
  const html = await page.content();
  expect(html, '保存したクライアントシークレットが画面に再表示されている').not.toContain(
    clientSecret(ctx)
  );
  expect(html, '保存したリフレッシュトークンが画面に再表示されている').not.toContain(
    STUB_REFRESH_TOKEN
  );
});

Then('Google AdSense設定のAPI応答にクライアントシークレットもリフレッシュトークンも含まれない', async ({ ctx, request }) => {
  const response = await request.get(`/api/projects/${currentProject(ctx).id}/api-keys/adsense`, {
    headers: await adminHeaders(request),
  });
  expect(response.status(), 'AdSenseの設定状態を取得できない').toBe(200);
  const body = await response.text();
  expect(body, 'API応答にクライアントシークレットが含まれている').not.toContain(clientSecret(ctx));
  expect(body, 'API応答にリフレッシュトークンが含まれている').not.toContain(STUB_REFRESH_TOKEN);
  expect((await response.json()) as { hasClientSecret: boolean }).toMatchObject({
    hasClientSecret: true,
  });
});

// --------------------------------------------------- OAuth コールバック(@api)

function seenOAuth(ctx: ScenarioState): { status: number; body: string } {
  const outcome = ctx.analyticsOAuth as { status: number; body: string } | undefined;
  if (!outcome) {
    throw new Error('先にOAuth連携を要求するステップを実行すること');
  }
  return outcome;
}

When(/^認可コード「([^」]+)」でAdSenseのOAuth連携を完了する$/, async ({ ctx, request }, code: string) => {
  const outcome = await completeAdSenseOAuth(request, currentProject(ctx).id, code);
  expect(outcome.status, `OAuth連携が成功しない: ${outcome.body}`).toBe(204);
  ctx.analyticsOAuth = outcome;
});

When(/^認可コード「([^」]+)」でAdSenseのOAuth連携を完了しようとする$/, async ({ ctx, request }, code: string) => {
  ctx.analyticsOAuth = await completeAdSenseOAuth(request, currentProject(ctx).id, code);
});

Then('AdSenseは連携済みになる', async ({ ctx, request }) => {
  const response = await request.get(`/api/projects/${currentProject(ctx).id}/api-keys/adsense`, {
    headers: await adminHeaders(request),
  });
  expect(response.status(), 'AdSenseの設定状態を取得できない').toBe(200);
  expect((await response.json()) as { configured: boolean }).toMatchObject({ configured: true });
});

Then('AdSenseは連携済みにならない', async ({ ctx, request }) => {
  const response = await request.get(`/api/projects/${currentProject(ctx).id}/api-keys/adsense`, {
    headers: await adminHeaders(request),
  });
  expect(response.status(), 'AdSenseの設定状態を取得できない').toBe(200);
  expect(
    ((await response.json()) as { configured: boolean }).configured,
    '拒否されたはずの認可コードで連携済みになっている'
  ).toBe(false);
});

Then('保存されたリフレッシュトークンでAdSenseのレポートを取得できる', async ({ ctx, request }) => {
  const response = await request.get(`/api/projects/${currentProject(ctx).id}/dashboard/adsense`, {
    headers: await adminHeaders(request),
  });
  expect(response.status(), 'AdSenseのレポートを取得できない').toBe(200);
  const report = (await response.json()) as {
    eligible: boolean;
    estimatedEarnings: string | null;
    errorMessage: string | null;
  };
  expect(report.errorMessage, 'AdSenseのレポート取得が失敗している').toBeNull();
  expect(report.eligible, 'AdSenseのレポートが対象外扱いになっている').toBe(true);
  expect(report.estimatedEarnings, 'スタブが返す推定収益が返っていない').toBe('12.34');
});

Then('OAuth連携は拒否される', async ({ ctx }) => {
  const outcome = seenOAuth(ctx);
  expect(outcome.status, '不正な認可コードでOAuth連携が通っている').toBeGreaterThanOrEqual(400);
  const error = (JSON.parse(outcome.body) as { error?: string }).error ?? '';
  expect(error.length, '拒否の理由が示されていない').toBeGreaterThan(0);
  for (const marker of ['\tat com.letsblog', 'java.lang.', 'Caused by:']) {
    expect(error, `拒否の理由がスタックトレースを外へ出している(${marker})`).not.toContain(marker);
  }
});

When('認証なしでAdSenseのOAuthコールバックURLを開く', async ({ ctx, request }) => {
  const project = currentProject(ctx);
  const response = await request.get(
    `/connect/adsense/callback?code=at13-forged-code&state=${project.id}.forged-nonce`,
    { maxRedirects: 0 }
  );
  ctx.analyticsCallback = { status: response.status(), location: response.headers().location ?? '' };
});

Then('認可コードは処理されず、ログイン画面へ戻される', async ({ ctx, request }) => {
  const outcome = ctx.analyticsCallback as { status: number; location: string };
  expect(
    outcome.status,
    `未認証のコールバックがリダイレクトで拒否されていない (status=${outcome.status})`
  ).toBeGreaterThanOrEqual(300);
  expect(outcome.status, '未認証のコールバックが成功扱いになっている').toBeLessThan(400);
  expect(outcome.location, `ログイン画面へ戻されていない: ${outcome.location}`).toContain('/login');

  const status = await request.get(`/api/projects/${currentProject(ctx).id}/api-keys/adsense`, {
    headers: await adminHeaders(request),
  });
  expect(
    ((await status.json()) as { configured: boolean }).configured,
    '未認証のコールバックで連携が完了してしまっている'
  ).toBe(false);
});

// ------------------------------------------------------------ ダッシュボード

function dashboardPanel(page: Page, title: 'Google Analytics' | 'Google AdSense'): Locator {
  return page
    .locator('div.rounded-lg')
    .filter({ has: page.locator('h2', { hasText: new RegExp(`^${title}$`) }) });
}

/** 指標の値。`<p>{値}</p><p>{ラベル}</p>` という並びをラベルから辿る。 */
function metricValue(panel: Locator, label: string): Locator {
  return panel.locator(`xpath=.//p[normalize-space(text())="${label}"]/preceding-sibling::p[1]`);
}

When('そのプロジェクトのダッシュボードを開く', async ({ ctx, page }) => {
  const project = currentProject(ctx);
  // 外部APIを遅延させるシナリオがあるため、既定より長く待つ(スタブの遅延は8秒)。
  await page.goto(`/projects/${project.id}/dashboard`, { waitUntil: 'commit', timeout: 60_000 });
  await expect(page.getByRole('heading', { name: /ダッシュボード$/ })).toBeVisible({
    timeout: 60_000,
  });
});

Then(
  /^Google (Analytics|AdSense)のパネルに期間「([^」]+)」が表示される$/,
  async ({ page }, which: string, period: string) => {
    const panel = dashboardPanel(page, `Google ${which}` as 'Google Analytics' | 'Google AdSense');
    await expect(panel.getByText(period, { exact: true })).toBeVisible({ timeout: 30_000 });
  }
);

Then(
  /^Google (Analytics|AdSense)のパネルに「([^」]+)」として「([^」]+)」が表示される$/,
  async ({ page }, which: string, label: string, value: string) => {
    const panel = dashboardPanel(page, `Google ${which}` as 'Google Analytics' | 'Google AdSense');
    await expect(metricValue(panel, label)).toHaveText(value, { timeout: 30_000 });
  }
);

Then('Google Analyticsのパネルに日次推移とトラフィックソース別内訳が表示される', async ({ page }) => {
  const panel = dashboardPanel(page, 'Google Analytics');
  await expect(panel.getByTestId('ga-daily-chart')).toBeVisible({ timeout: 30_000 });
  await expect(panel.getByTestId('ga-channel-breakdown')).toBeVisible({ timeout: 30_000 });
});

Then('Google AdSenseのパネルに日次推移とプラットフォーム別内訳が表示される', async ({ page }) => {
  const panel = dashboardPanel(page, 'Google AdSense');
  await expect(panel.getByTestId('adsense-daily-chart')).toBeVisible({ timeout: 30_000 });
  await expect(panel.getByTestId('adsense-platform-breakdown')).toBeVisible({ timeout: 30_000 });
});

Then('Google AnalyticsのパネルとGoogle AdSenseのパネルが両方とも表示される', async ({ page }) => {
  await expect(dashboardPanel(page, 'Google Analytics')).toBeVisible({ timeout: 30_000 });
  await expect(dashboardPanel(page, 'Google AdSense')).toBeVisible({ timeout: 30_000 });
});

Then(
  /^Google (Analytics|AdSense)のパネルに設定画面への導線が表示される$/,
  async ({ ctx, page }, which: string) => {
    const title = `Google ${which}` as 'Google Analytics' | 'Google AdSense';
    const link = dashboardPanel(page, title).getByRole('link', { name: `${title}を設定` });
    await expect(link).toBeVisible({ timeout: 30_000 });
    const path = which === 'Analytics'
      ? gaSettingsPath(currentProject(ctx).id)
      : adSenseSettingsPath(currentProject(ctx).id);
    await expect(link).toHaveAttribute('href', path);
  }
);

Then('どちらのパネルにも取得失敗のエラーは表示されない', async ({ page }) => {
  await expect(
    page.getByText('取得に失敗しました:', { exact: false }),
    '未設定のはずのパネルに取得失敗が表示されている'
  ).toHaveCount(0);
});

Then('ダッシュボードの見出しは表示され続けている', async ({ page }) => {
  await expect(
    page.getByRole('heading', { name: /ダッシュボード$/ }),
    'パネルの失敗でページ全体が壊れている'
  ).toBeVisible();
});

// ------------------------------------------------------------ 異常系

Given(
  /^Google Analyticsの外部APIが次の1回だけ「(\d+)」を返す$/,
  async ({}, status: string) => {
    await forceStubStatus('google-analytics', Number(status), 1);
  }
);

Given(
  /^Google Analyticsの外部APIが次の1回だけ「(\d+)」ミリ秒応答しない$/,
  async ({}, delayMs: string) => {
    await forceStubDelay('google-analytics', Number(delayMs), 1);
  }
);

Then('Google Analyticsのパネルに取得失敗が表示される', async ({ ctx, page }) => {
  const message = dashboardPanel(page, 'Google Analytics').getByText('取得に失敗しました:', {
    exact: false,
  });
  await expect(message, 'GAの取得失敗が画面に出ていない').toBeVisible({ timeout: 60_000 });
  ctx.analyticsErrorText = (await message.textContent()) ?? '';
});

function seenErrorText(ctx: ScenarioState): string {
  const text = ctx.analyticsErrorText as string | undefined;
  if (text === undefined) {
    throw new Error('先に取得失敗を確認するステップを実行すること');
  }
  return text;
}

Then('取得失敗の説明から再認証が必要だと分かる', async ({ ctx }) => {
  const text = seenErrorText(ctx);
  expect(text, `再認証が必要だと分かる説明になっていない: ${text}`).toMatch(/再認証|連携し直/);
});

Then('取得失敗の説明にGoogleからの生の応答本文は含まれない', async ({ ctx }) => {
  const text = seenErrorText(ctx);
  for (const marker of ['invalid_grant', 'error_description', '[stub]', '{"error"']) {
    expect(text, `Googleからの生の応答本文が利用者へ出ている(${marker}): ${text}`).not.toContain(
      marker
    );
  }
});

Then('取得失敗の説明から呼び出し回数の制限に達したと分かる', async ({ ctx }) => {
  const text = seenErrorText(ctx);
  expect(text, `呼び出し回数の制限に達したと分かる説明になっていない: ${text}`).toMatch(
    /回数制限|レート制限|しばらく/
  );
});

// ------------------------------------------------------------ 認可(@api)

interface DeniedOutcome {
  what: string;
  status: number;
  body: string;
}

/** 資格情報のエンドポイント8本。1本でも外れれば秘密情報が漏れるのでまとめて叩く。 */
async function requestAllCredentialEndpoints(
  request: APIRequestContext,
  projectId: number,
  headers: Record<string, string>
): Promise<DeniedOutcome[]> {
  const base = `/api/projects/${projectId}/api-keys`;
  const calls: { what: string; run: () => Promise<{ status: number; body: string }> }[] = [
    { what: 'GET google-analytics', run: () => send(request.get(`${base}/google-analytics`, { headers })) },
    {
      what: 'PUT google-analytics',
      run: () => send(request.put(`${base}/google-analytics`, {
        headers,
        data: { propertyId: '111', serviceAccountJson: serviceAccountJson('intruder@example.com') },
      })),
    },
    { what: 'DELETE google-analytics', run: () => send(request.delete(`${base}/google-analytics`, { headers })) },
    { what: 'GET adsense', run: () => send(request.get(`${base}/adsense`, { headers })) },
    {
      what: 'PUT adsense',
      run: () => send(request.put(`${base}/adsense`, {
        headers,
        data: { accountId: 'pub-9999999999999999', clientId: 'intruder.apps.googleusercontent.com' },
      })),
    },
    {
      what: 'PUT adsense/client-secret',
      run: () => send(request.put(`${base}/adsense/client-secret`, {
        headers,
        data: { clientSecret: 'intruder-secret' },
      })),
    },
    {
      what: 'POST adsense/oauth-callback',
      run: () => send(request.post(`${base}/adsense/oauth-callback`, {
        headers,
        data: { code: 'intruder-code', redirectUri: 'https://localhost/connect/adsense/callback' },
      })),
    },
    { what: 'DELETE adsense', run: () => send(request.delete(`${base}/adsense`, { headers })) },
  ];

  const outcomes: DeniedOutcome[] = [];
  for (const call of calls) {
    outcomes.push({ what: call.what, ...(await call.run()) });
  }
  return outcomes;
}

async function requestBothDashboards(
  request: APIRequestContext,
  projectId: number,
  headers: Record<string, string>
): Promise<DeniedOutcome[]> {
  const base = `/api/projects/${projectId}/dashboard`;
  return [
    { what: 'GET dashboard/google-analytics', ...(await send(request.get(`${base}/google-analytics`, { headers }))) },
    { what: 'GET dashboard/adsense', ...(await send(request.get(`${base}/adsense`, { headers }))) },
  ];
}

async function send(
  pending: ReturnType<APIRequestContext['get']>
): Promise<{ status: number; body: string }> {
  const response = await pending;
  return { status: response.status(), body: await response.text() };
}

function seenDenied(ctx: ScenarioState): DeniedOutcome[] {
  const outcomes = ctx.analyticsDenied as DeniedOutcome[] | undefined;
  if (!outcomes || outcomes.length === 0) {
    throw new Error('先に要求を送るステップを実行すること');
  }
  return outcomes;
}

/** 未認証の検証はプロジェクトの実在を要しない。認証ゲートはハンドラの手前で効く。 */
const UNAUTHENTICATED_PROJECT_ID = 1;

When('認証なしでAnalyticsの資格情報エンドポイントをすべて要求する', async ({ ctx, request }) => {
  ctx.analyticsDenied = await requestAllCredentialEndpoints(request, UNAUTHENTICATED_PROJECT_ID, {});
});

When('認証なしでAnalyticsのダッシュボードを要求する', async ({ ctx, request }) => {
  ctx.analyticsDenied = await requestBothDashboards(request, UNAUTHENTICATED_PROJECT_ID, {});
});

Then('すべて認証が必要だとして拒否される', async ({ ctx }) => {
  for (const outcome of seenDenied(ctx)) {
    // 「通っている」と断定しない。gateway のレート制限(api-global、既定100回/分)に
    // 掛かると 429 が返り、それは認可が外れたことを意味しない。実際のステータスは
    // Playwright が Expected/Received として併記するので、そちらで区別できる。
    expect(outcome.status, `${outcome.what} が未認証の401で拒否されていない`).toBe(401);
  }
});

Given('Analyticsを確かめるプロジェクトが2つあり、一般利用者は片方だけのメンバーである', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const memberProject = await createBareProject(request, ctx);
  const otherProject = await createBareProject(request, ctx);

  const me = await request.get('/api/identity/me', { headers: await memberHeaders(request) });
  expect(me.ok(), `一般利用者の情報を取得できませんでした (status=${me.status()})`).toBe(true);
  const memberUserId = ((await me.json()) as { id: number }).id;

  const added = await request.post(`/api/projects/${memberProject.id}/users`, {
    headers,
    data: { userId: memberUserId, wpRole: 'editor' },
  });
  expect(
    added.ok(),
    `プロジェクトメンバーの追加に失敗しました (status=${added.status()}): ${await added.text()}`
  ).toBe(true);

  // 他プロジェクト側には既知の資格情報を入れておく。書き換えられていないことを後で見る。
  await putAdSenseClient(request, otherProject.id, clientSecret(ctx));
  await putGoogleAnalyticsCredentials(
    request,
    otherProject.id,
    'at13-owner@at13.iam.gserviceaccount.com'
  );

  ctx.analyticsMemberProject = memberProject;
  ctx.analyticsOtherProject = otherProject;
  ctx.analyticsMemberUserId = memberUserId;
});

Then('一般利用者は自分のプロジェクトの資格情報を読み書きできる', async ({ ctx, request }) => {
  const project = ctx.analyticsMemberProject as AnalyticsProject;
  const headers = await memberHeaders(request);

  const read = await request.get(`/api/projects/${project.id}/api-keys/adsense`, { headers });
  expect(
    read.status(),
    `メンバーなのに自分のプロジェクトの資格情報を読めない: ${await read.text()}`
  ).toBe(200);

  const written = await request.put(`/api/projects/${project.id}/api-keys/adsense`, {
    headers,
    data: { accountId: ADSENSE_ACCOUNT_ID, clientId: ADSENSE_CLIENT_ID },
  });
  expect(
    written.status(),
    `メンバーなのに自分のプロジェクトの資格情報を書けない: ${await written.text()}`
  ).toBe(204);

  const after = await request.get(`/api/projects/${project.id}/api-keys/adsense`, { headers });
  expect((await after.json()) as { accountId: string }).toMatchObject({
    accountId: ADSENSE_ACCOUNT_ID,
  });
});

When('一般利用者が他プロジェクトのAnalyticsの資格情報エンドポイントをすべて要求する', async ({ ctx, request }) => {
  const project = ctx.analyticsOtherProject as AnalyticsProject;
  ctx.analyticsDenied = await requestAllCredentialEndpoints(
    request,
    project.id,
    await memberHeaders(request)
  );
});

When('一般利用者が他プロジェクトのAnalyticsのダッシュボードを要求する', async ({ ctx, request }) => {
  const project = ctx.analyticsOtherProject as AnalyticsProject;
  ctx.analyticsDenied = await requestBothDashboards(request, project.id, await memberHeaders(request));
});

Then('すべてプロジェクトメンバーではないとして拒否される', async ({ ctx }) => {
  for (const outcome of seenDenied(ctx)) {
    expect(outcome.status, `${outcome.what} が非メンバーへの403で拒否されていない`).toBe(403);
    expect(outcome.body, `${outcome.what} の拒否理由が示されていない`).toContain('プロジェクトメンバー');
  }
});

Then('他プロジェクトの資格情報は書き換えられていない', async ({ ctx, request }) => {
  const project = ctx.analyticsOtherProject as AnalyticsProject;
  const headers = await adminHeaders(request);

  const adsense = await request.get(`/api/projects/${project.id}/api-keys/adsense`, { headers });
  expect((await adsense.json()) as Record<string, unknown>).toMatchObject({
    accountId: ADSENSE_ACCOUNT_ID,
    clientId: ADSENSE_CLIENT_ID,
    hasClientSecret: true,
  });

  const ga = await request.get(`/api/projects/${project.id}/api-keys/google-analytics`, { headers });
  expect((await ga.json()) as Record<string, unknown>).toMatchObject({
    configured: true,
    propertyId: GA_PROPERTY_ID,
  });
});

// ------------------------------------------------------------ 後片付け

After({ tags: '@analytics' }, async ({ ctx, request }) => {
  const headers = await adminHeaders(request);

  const memberUserId = ctx.analyticsMemberUserId as number | undefined;
  const memberProject = ctx.analyticsMemberProject as AnalyticsProject | undefined;
  if (memberUserId !== undefined && memberProject !== undefined) {
    await request.delete(`/api/projects/${memberProject.id}/users/${memberUserId}`, { headers });
  }

  for (const project of trackedProjects(ctx)) {
    // analytics_credentials は projects への外部キーを持たない(ADR-0004)。
    // プロジェクトを先に消すと行が孤児として残るため、資格情報を先に消す。
    await request.delete(`/api/projects/${project.id}/api-keys/google-analytics`, { headers });
    await request.delete(`/api/projects/${project.id}/api-keys/adsense`, { headers });
    await request.delete(`/api/projects/${project.id}`, { headers });
    if (project.siteId !== undefined) {
      await request.delete(`/api/sites/${project.siteId}`, { headers });
    }
  }

  // 注入が残ると、同じスタブを使う後続のシナリオを巻き添えにする。
  await resetStub('google-analytics');
  await resetStub('adsense');
});
