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
import {
  clickUntilVisible,
  DEFAULT_VISIBLE_TIMEOUT_MS,
  retryUntilPass,
  withDialogAccepted,
} from '../support/retryClick';

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
 * ga-stub は、認可コード `e2e-stub-invalid-code` のトークン交換を401にする。
 * また、認可コード `e2e-stub-ga-expired-code` は「後で失効するリフレッシュトークン」を返し、
 * そのリフレッシュトークンでのアクセストークン取得は401になる(失効の再現に制御エンドポイントを使わない)。
 */
const EXPIRED_AUTHORIZATION_CODE = 'e2e-stub-ga-expired-code';

/** ga-stub が認可コードフローで返すリフレッシュトークン。漏れていないことの確認に使う。 */
const STUB_GA_REFRESH_TOKEN = 'e2e-stub-ga-refresh-token';

const GA_CLIENT_ID = 'at1231-ga.apps.googleusercontent.com';

/** adsense-stub が認可コードフローで返すリフレッシュトークン。漏れていないことの確認に使う。 */
const STUB_REFRESH_TOKEN = 'e2e-stub-adsense-refresh-token';

// adsense-stub は accounts.list を認可コードごとに切り替えて返す(issue #1232)。既定(どの認可コードでも)は1件、
// `e2e-stub-adsense-multi-accounts-code` は2件、`e2e-stub-adsense-accounts-error-code` は一覧の取得失敗(403)。

const GA_PROPERTY_ID = '987654321';
const ADSENSE_ACCOUNT_ID = 'pub-1234567890123456';
const ADSENSE_CLIENT_ID = 'at13-acceptance.apps.googleusercontent.com';

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

/** このシナリオ限りのGA用クライアントシークレット。漏れていないことを目印で確かめる。 */
function gaClientSecret(ctx: ScenarioState): string {
  ctx.analyticsGaClientSecret ??= `at1231-ga-client-secret-${uniqueSuffix()}`;
  return ctx.analyticsGaClientSecret as string;
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

async function putGoogleAnalyticsClient(
  request: APIRequestContext,
  projectId: number,
  secret: string
): Promise<void> {
  const response = await request.put(`/api/projects/${projectId}/api-keys/google-analytics/client`, {
    headers: await adminHeaders(request),
    data: { clientId: GA_CLIENT_ID, clientSecret: secret },
  });
  expect(
    response.ok(),
    `GAのOAuthクライアント保存に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

async function completeGoogleAnalyticsOAuth(
  request: APIRequestContext,
  projectId: number,
  code: string
): Promise<{ status: number; body: string }> {
  const response = await request.post(`/api/projects/${projectId}/api-keys/google-analytics/oauth-callback`, {
    headers: await adminHeaders(request),
    data: { code, redirectUri: 'https://localhost/connect/google-analytics/callback' },
  });
  return { status: response.status(), body: await response.text() };
}

async function selectGoogleAnalyticsProperty(
  request: APIRequestContext,
  projectId: number,
  propertyId: string
): Promise<void> {
  const response = await request.put(`/api/projects/${projectId}/api-keys/google-analytics/property`, {
    headers: await adminHeaders(request),
    data: { propertyId },
  });
  expect(
    response.ok(),
    `GAのプロパティ選択に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

/** クライアント保存 → 認可コード交換 → プロパティ選択 まで済ませる(ダッシュボード表示の前提)。 */
async function connectGoogleAnalyticsFor(
  request: APIRequestContext,
  projectId: number,
  secret: string,
  code: string
): Promise<void> {
  await putGoogleAnalyticsClient(request, projectId, secret);
  const outcome = await completeGoogleAnalyticsOAuth(request, projectId, code);
  expect(outcome.status, `GAのOAuth連携に失敗しました: ${outcome.body}`).toBe(204);
  await selectGoogleAnalyticsProperty(request, projectId, GA_PROPERTY_ID);
}

async function connectGoogleAnalytics(
  request: APIRequestContext,
  ctx: ScenarioState,
  code: string
): Promise<void> {
  await connectGoogleAnalyticsFor(request, currentProject(ctx).id, gaClientSecret(ctx), code);
}

/**
 * @param accountId パブリッシャーID。`null`なら送らない(#1232: 任意入力で、連携後に自動取得される)。
 */
async function putAdSenseClient(
  request: APIRequestContext,
  projectId: number,
  secret: string,
  accountId: string | null = ADSENSE_ACCOUNT_ID
): Promise<void> {
  const headers = await adminHeaders(request);
  const settings = await request.put(`/api/projects/${projectId}/api-keys/adsense`, {
    headers,
    data: accountId === null ? { clientId: ADSENSE_CLIENT_ID } : { accountId, clientId: ADSENSE_CLIENT_ID },
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

interface AdSenseStatusBody {
  configured: boolean;
  connected: boolean;
  accountId: string | null;
}

async function adSenseStatus(request: APIRequestContext, projectId: number): Promise<AdSenseStatusBody> {
  const response = await request.get(`/api/projects/${projectId}/api-keys/adsense`, {
    headers: await adminHeaders(request),
  });
  expect(response.status(), 'AdSenseの設定状態を取得できない').toBe(200);
  return (await response.json()) as AdSenseStatusBody;
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
  await connectGoogleAnalytics(request, ctx, 'at1231-authorization-code');
});

Given('そのプロジェクトに失効したGoogle Analyticsの資格情報が登録されている', async ({ ctx, request }) => {
  await connectGoogleAnalytics(request, ctx, EXPIRED_AUTHORIZATION_CODE);
});

Given('そのプロジェクトにGoogle AnalyticsのOAuthクライアントが登録されている', async ({ ctx, request }) => {
  await putGoogleAnalyticsClient(request, currentProject(ctx).id, gaClientSecret(ctx));
});

Given('そのプロジェクトにGoogle Analyticsが連携済みでプロパティは未選択である', async ({ ctx, request }) => {
  const projectId = currentProject(ctx).id;
  await putGoogleAnalyticsClient(request, projectId, gaClientSecret(ctx));
  const outcome = await completeGoogleAnalyticsOAuth(request, projectId, 'at1231-authorization-code');
  expect(outcome.status, `GAのOAuth連携に失敗しました: ${outcome.body}`).toBe(204);
});

Given('そのプロジェクトにAdSenseのパブリッシャーIDとOAuthクライアントが登録されている', async ({ ctx, request }) => {
  await putAdSenseClient(request, currentProject(ctx).id, clientSecret(ctx));
});

Given('そのプロジェクトにパブリッシャーIDなしでAdSenseのOAuthクライアントが登録されている', async ({ ctx, request }) => {
  await putAdSenseClient(request, currentProject(ctx).id, clientSecret(ctx), null);
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
  await connectGoogleAnalytics(request, ctx, 'at1231-authorization-code');
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

/**
 * `heading`の出現は**サーバ描画**で満たされるため、この待ちはReactのハイドレーション
 * 完了より前に解決しうる。Next.js(このプロジェクトが使うバージョン。
 * `node_modules/next/dist/docs/`参照)はハイドレーション完了を検出できる標識を公開して
 * いない — `instrumentation-client.js`はハイドレーション**開始前**に実行される専用の
 * フックであり(`01-app/03-api-reference/03-file-conventions/instrumentation-client.md`
 * 「Execution timing」)完了後のフックは無く、`preventing-flash-before-hydration.md`が
 * 扱う`suppressHydrationWarning`+インラインスクリプトの手法もハイドレーション前に
 * DOMを直接書き換えて見た目のズレを消すためのもので、ハイドレーション完了そのものを
 * 通知する仕組みではない。製品コード側にも標識となる`data-*`属性やクライアント専用の
 * 目印は存在しない(`apps/web/src`調査済み)。したがって`openSettings`自体を「完了まで
 * 待つ」形に直す手段は無く、#1381/#1360と同じ「クリックし直す」方針を続ける(#1385)。
 */
async function openSettings(page: Page, path: string, heading: RegExp): Promise<void> {
  await page.goto(path, { waitUntil: 'commit' });
  await expect(page.getByRole('heading', { name: heading })).toBeVisible({ timeout: 30_000 });
}

When('そのプロジェクトのGoogle Analytics設定でOAuthクライアントを保存する', async ({ ctx, page }) => {
  const project = currentProject(ctx);
  await openSettings(page, gaSettingsPath(project.id), /Google Analytics設定$/);
  const secret = gaClientSecret(ctx);

  // AdSenseの保存ステップと同じ理由(#1385): goto直後のfill()はハイドレーション完了時の
  // 最初のレンダリングでReactのstateへ戻されうるため、fill()から含めて再試行する。
  // 同じ値による再送信でありべき等なので安全。
  await retryUntilPass(async () => {
    await page.locator('input[name="clientId"]').fill(GA_CLIENT_ID);
    await page.locator('input[name="clientSecret"]').fill(secret);
    await page.getByRole('button', { name: 'クライアントを保存', exact: true }).click();
    await page
      .getByText('保存しました。')
      .first()
      .waitFor({ state: 'visible', timeout: DEFAULT_VISIBLE_TIMEOUT_MS });
  });
});

Then('Google Analytics設定にクライアントシークレットが設定済みとして表示される', async ({ page }) => {
  await expect(page.locator('input[name="clientSecret"]')).toHaveAttribute(
    'placeholder',
    '設定済み(変更する場合のみ入力)',
    { timeout: 30_000 }
  );
});

Then('Google Analytics設定にサービスアカウントの入力欄は無い', async ({ page }) => {
  await expect(page.locator('textarea[name="serviceAccountJson"]')).toHaveCount(0);
  await expect(page.locator('input[name="propertyId"]')).toHaveCount(0);
});

Then(
  'Google Analyticsの連携リンクの遷移先はanalytics.readonlyだけを要求するGoogleの認可URLである',
  async ({ ctx, page }) => {
    const link = page.getByRole('link', { name: 'Googleアカウントと連携', exact: true });
    await expect(link).toBeVisible({ timeout: 30_000 });
    await expect(link).toHaveAttribute(
      'href',
      `/connect/google-analytics/start?projectId=${currentProject(ctx).id}`
    );
    // 同意画面(accounts.google.com)へは出ない: リダイレクト先のURLだけを読む。
    const href = (await link.getAttribute('href')) ?? '';
    const response = await page.request.get(href, { maxRedirects: 0 });
    expect(response.status(), `連携の起点がリダイレクトしていない (status=${response.status()})`).toBeGreaterThanOrEqual(300);
    const authorizeUrl = new URL(response.headers().location ?? '');
    expect(authorizeUrl.origin + authorizeUrl.pathname).toBe('https://accounts.google.com/o/oauth2/v2/auth');
    expect(authorizeUrl.searchParams.get('scope')).toBe('https://www.googleapis.com/auth/analytics.readonly');
    expect(authorizeUrl.searchParams.get('client_id')).toBe(GA_CLIENT_ID);
    expect(authorizeUrl.searchParams.get('access_type')).toBe('offline');
  }
);

Then(
  /^プロパティの一覧に表示名「([^」]+)」とプロパティID「([^」]+)」がある$/,
  async ({ page }, displayName: string, propertyId: string) => {
    const option = page.locator('select[name="propertyId"] option', { hasText: displayName });
    await expect(option).toHaveCount(1, { timeout: 30_000 });
    await expect(option).toHaveAttribute('value', propertyId);
    await expect(option).toContainText(propertyId);
  }
);

When(/^プロパティ「([^」]+)」を選んで保存する$/, async ({ page }, propertyId: string) => {
  // ハイドレーション前のselectOptionは値が戻りうる(#1385)ため、選択から含めて再試行する。
  await retryUntilPass(async () => {
    await page.locator('select[name="propertyId"]').selectOption(propertyId);
    await page.getByRole('button', { name: 'プロパティを保存', exact: true }).click();
    await page
      .getByText('プロパティを保存しました。')
      .first()
      .waitFor({ state: 'visible', timeout: DEFAULT_VISIBLE_TIMEOUT_MS });
  });
});

Then(
  /^Google Analytics設定の状態に「([^」]+)」と表示される$/,
  async ({ page }, expected: string) => {
    await expect(page.getByText(expected, { exact: true })).toBeVisible({ timeout: 30_000 });
  }
);

When('そのプロジェクトのGoogle Analytics設定で連携を解除する', async ({ ctx, page }) => {
  const project = currentProject(ctx);
  await openSettings(page, gaSettingsPath(project.id), /Google Analytics設定$/);

  // goto直後はハイドレーション前の可能性があり、そのままだと「設定を削除」のクリックが
  // onClickの未結線で空振りする(#1385)。clickUntilVisibleで再試行できるようにするが、
  // 削除ボタンはwindow.confirm()を出すため、page.once('dialog', ...)のままでは2回目
  // 以降のダイアログを誰も受けられずPlaywrightに自動でdismissされてしまう。
  // withDialogAcceptedでaction中は毎回acceptし、終了後は必ずpage.offで外す(このシナリオの
  // 後続ステップに登録が漏れないように)。
  //
  // 【冪等性についての注記】削除は「設定済みの間だけ描画される」ボタンをクリックする
  // 操作で、成功すると同じ描画コミットでボタン自身が消え「未設定」の表示と入れ替わる。
  // 万一1回目のクリックが実際には効いていて表示待ちだけが失敗した場合でも、再試行時に
  // 「未設定」が既に見えていればクリックはスキップされ(clickUntilVisibleのstate-aware化)、
  // まだ見えていなければボタンは既にDOMから無いため2回目の削除が物理的に飛ぶことはない。
  await withDialogAccepted(page, () =>
    clickUntilVisible(
      page.getByRole('button', { name: '連携を解除', exact: true }),
      page.getByText('未設定', { exact: true })
    )
  );
});

Then('Google Analytics設定のAPI応答は未設定を示す', async ({ ctx, request }) => {
  const response = await request.get(
    `/api/projects/${currentProject(ctx).id}/api-keys/google-analytics`,
    { headers: await adminHeaders(request) }
  );
  expect(response.status(), 'GAの設定状態を取得できない').toBe(200);
  const status = (await response.json()) as {
    configured: boolean;
    connected: boolean;
    propertyId: string | null;
  };
  expect(status.configured, '解除したのに設定済みのままである').toBe(false);
  expect(status.connected, '解除したのに連携済みのままである').toBe(false);
  expect(status.propertyId, '解除したのにプロパティIDが残っている').toBeNull();
});

Then(
  /^Google Analytics設定のAPI応答は設定済みでプロパティIDが「([^」]+)」である$/,
  async ({ ctx, request }, propertyId: string) => {
    const response = await request.get(
      `/api/projects/${currentProject(ctx).id}/api-keys/google-analytics`,
      { headers: await adminHeaders(request) }
    );
    expect(response.status(), 'GAの設定状態を取得できない').toBe(200);
    expect((await response.json()) as { configured: boolean; propertyId: string }).toMatchObject({
      configured: true,
      propertyId,
    });
  }
);

// ------------------------------------------------- 設定画面(AdSense)

When('そのプロジェクトのGoogle AdSense設定でパブリッシャーIDとOAuthクライアントを保存する', async ({ ctx, page }) => {
  const project = currentProject(ctx);
  await openSettings(page, adSenseSettingsPath(project.id), /Google AdSense設定$/);
  const secret = clientSecret(ctx);

  // GA設定の保存ステップと同じ理由(#1385): goto直後のfill()はハイドレーション完了時の
  // 最初のレンダリングでReactのstateへ戻されうるため、fill()から含めて再試行する。
  // 同じ値による再送信でありべき等なので安全(上のGA保存ステップの注記を参照)。
  await retryUntilPass(async () => {
    await page.locator('input[name="accountId"]').fill(ADSENSE_ACCOUNT_ID);
    await page.locator('input[name="clientId"]').fill(ADSENSE_CLIENT_ID);
    await page.locator('input[name="clientSecret"]').fill(secret);
    await page.getByRole('button', { name: 'まとめて保存', exact: true }).click();
    await page
      .getByText('保存しました。')
      .first()
      .waitFor({ state: 'visible', timeout: DEFAULT_VISIBLE_TIMEOUT_MS });
  });
});

When('そのプロジェクトのGoogle AdSense設定でパブリッシャーIDを空のままOAuthクライアントを保存する', async ({ ctx, page }) => {
  const project = currentProject(ctx);
  await openSettings(page, adSenseSettingsPath(project.id), /Google AdSense設定$/);
  const secret = clientSecret(ctx);

  // パブリッシャーID欄には触れない(空のまま)。ハイドレーション前のfill()が戻される問題(#1385)は
  // 他の保存ステップと同じくfill()から含めて再試行して避ける。
  await retryUntilPass(async () => {
    await page.locator('input[name="clientId"]').fill(ADSENSE_CLIENT_ID);
    await page.locator('input[name="clientSecret"]').fill(secret);
    await page.getByRole('button', { name: 'まとめて保存', exact: true }).click();
    await page
      .getByText('保存しました。')
      .first()
      .waitFor({ state: 'visible', timeout: DEFAULT_VISIBLE_TIMEOUT_MS });
  });
});

Then('Google AdSense設定のパブリッシャーID欄は空である', async ({ page }) => {
  await expect(page.locator('input[name="accountId"]')).toHaveValue('', { timeout: 30_000 });
});

Then(
  /^Google AdSense設定の状態に「([^」]+)」と表示される$/,
  async ({ page }, expected: string) => {
    await expect(page.getByText(expected, { exact: true })).toBeVisible({ timeout: 30_000 });
  }
);

Then(
  /^AdSenseのアカウント一覧に表示名「([^」]+)」とパブリッシャーID「([^」]+)」がある$/,
  async ({ page }, displayName: string, accountId: string) => {
    const option = page.locator('select[name="selectedAccountId"] option', { hasText: displayName });
    await expect(option).toHaveCount(1, { timeout: 30_000 });
    await expect(option).toHaveAttribute('value', accountId);
    await expect(option).toContainText(accountId);
  }
);

When(/^パブリッシャーID「([^」]+)」を一覧から選んで保存する$/, async ({ page }, accountId: string) => {
  // ハイドレーション前のselectOptionは値が戻りうる(#1385)ため、選択から含めて再試行する。
  await retryUntilPass(async () => {
    await page.locator('select[name="selectedAccountId"]').selectOption(accountId);
    await page.getByRole('button', { name: 'パブリッシャーIDを保存', exact: true }).click();
    await page
      .getByText('パブリッシャーIDを保存しました。')
      .first()
      .waitFor({ state: 'visible', timeout: DEFAULT_VISIBLE_TIMEOUT_MS });
  });
});

Then('AdSenseのアカウント一覧を取得できなかった理由が表示される', async ({ page }) => {
  await expect(page.getByText(/アカウント一覧を取得できませんでした: .+/)).toBeVisible({ timeout: 30_000 });
});

When(/^パブリッシャーID「([^」]+)」を手入力して保存する$/, async ({ page }, accountId: string) => {
  await retryUntilPass(async () => {
    await page.locator('input[name="accountId"]').fill(accountId);
    await page.getByRole('button', { name: 'まとめて保存', exact: true }).click();
    await page
      .getByText('保存しました。')
      .first()
      .waitFor({ state: 'visible', timeout: DEFAULT_VISIBLE_TIMEOUT_MS });
  });
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

Then('画面にGoogle Analyticsのクライアントシークレットとリフレッシュトークンは現れない', async ({ ctx, page }) => {
  const html = await page.content();
  expect(html, '保存したGAのクライアントシークレットが画面に再表示されている').not.toContain(
    gaClientSecret(ctx)
  );
  expect(html, '保存したGAのリフレッシュトークンが画面に再表示されている').not.toContain(
    STUB_GA_REFRESH_TOKEN
  );
});

Then('Google Analytics設定のAPI応答にクライアントシークレットもリフレッシュトークンも含まれない', async ({ ctx, request }) => {
  const response = await request.get(
    `/api/projects/${currentProject(ctx).id}/api-keys/google-analytics`,
    { headers: await adminHeaders(request) }
  );
  expect(response.status(), 'GAの設定状態を取得できない').toBe(200);
  const body = await response.text();
  expect(body, 'API応答にクライアントシークレットが含まれている').not.toContain(gaClientSecret(ctx));
  expect(body, 'API応答にリフレッシュトークンが含まれている').not.toContain(STUB_GA_REFRESH_TOKEN);
  expect(body, 'API応答にサービスアカウントJSONが含まれている').not.toContain('private_key');
  // 設定済みであること自体は返る(それが無いと画面が状態を出せない)。
  expect((await response.json()) as { configured: boolean; hasClientSecret: boolean }).toMatchObject({
    configured: true,
    hasClientSecret: true,
  });
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

Then(/^AdSenseは連携済みで保存されたパブリッシャーIDは「([^」]+)」である$/, async ({ ctx, request }, expected: string) => {
  const status = await adSenseStatus(request, currentProject(ctx).id);
  expect(status.configured, 'パブリッシャーIDが自動保存されていない').toBe(true);
  expect(status.accountId).toBe(expected);
  expect(status.accountId, '保存値に accounts/ 接頭辞が残っている').not.toContain('accounts/');
});

Then('AdSenseはGoogleアカウントと連携済みだがパブリッシャーIDは未取得である', async ({ ctx, request }) => {
  const status = await adSenseStatus(request, currentProject(ctx).id);
  expect(status.connected, 'リフレッシュトークンが保存されていない').toBe(true);
  expect(status.configured, 'パブリッシャーIDが無いのに設定済み扱いになっている').toBe(false);
  expect(status.accountId).toBeNull();
});

Then(/^AdSenseの保存されたパブリッシャーIDは「([^」]+)」である$/, async ({ ctx, request }, expected: string) => {
  expect((await adSenseStatus(request, currentProject(ctx).id)).accountId).toBe(expected);
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

When(
  /^認可コード「([^」]+)」でGoogle AnalyticsのOAuth連携を完了する$/,
  async ({ ctx, request }, code: string) => {
    const outcome = await completeGoogleAnalyticsOAuth(request, currentProject(ctx).id, code);
    expect(outcome.status, `OAuth連携が成功しない: ${outcome.body}`).toBe(204);
    ctx.analyticsOAuth = outcome;
  }
);

When(
  /^認可コード「([^」]+)」でGoogle AnalyticsのOAuth連携を完了しようとする$/,
  async ({ ctx, request }, code: string) => {
    ctx.analyticsOAuth = await completeGoogleAnalyticsOAuth(request, currentProject(ctx).id, code);
  }
);

async function googleAnalyticsStatus(
  request: APIRequestContext,
  projectId: number
): Promise<{ configured: boolean; connected: boolean; propertyId: string | null }> {
  const response = await request.get(`/api/projects/${projectId}/api-keys/google-analytics`, {
    headers: await adminHeaders(request),
  });
  expect(response.status(), 'GAの設定状態を取得できない').toBe(200);
  return (await response.json()) as { configured: boolean; connected: boolean; propertyId: string | null };
}

Then('Google Analyticsは連携済みでプロパティは未選択になる', async ({ ctx, request }) => {
  const status = await googleAnalyticsStatus(request, currentProject(ctx).id);
  expect(status.connected, '認可コードの交換後も連携済みになっていない').toBe(true);
  expect(status.configured, 'プロパティ未選択なのに設定済み扱いになっている').toBe(false);
  expect(status.propertyId).toBeNull();
});

Then('Google Analyticsは連携済みにならない', async ({ ctx, request }) => {
  const status = await googleAnalyticsStatus(request, currentProject(ctx).id);
  expect(status.connected, '拒否されたはずの認可コードで連携済みになっている').toBe(false);
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

async function openUnauthenticatedCallback(
  request: APIRequestContext,
  ctx: ScenarioState,
  kind: 'adsense' | 'google-analytics'
): Promise<void> {
  const project = currentProject(ctx);
  const response = await request.get(
    `/connect/${kind}/callback?code=forged-code&state=${project.id}.forged-nonce`,
    { maxRedirects: 0 }
  );
  ctx.analyticsCallback = {
    kind,
    status: response.status(),
    location: response.headers().location ?? '',
  };
}

When('認証なしでAdSenseのOAuthコールバックURLを開く', async ({ ctx, request }) => {
  await openUnauthenticatedCallback(request, ctx, 'adsense');
});

When('認証なしでGoogle AnalyticsのOAuthコールバックURLを開く', async ({ ctx, request }) => {
  await openUnauthenticatedCallback(request, ctx, 'google-analytics');
});

Then('認可コードは処理されず、ログイン画面へ戻される', async ({ ctx, request }) => {
  const outcome = ctx.analyticsCallback as {
    kind: 'adsense' | 'google-analytics';
    status: number;
    location: string;
  };
  expect(
    outcome.status,
    `未認証のコールバックがリダイレクトで拒否されていない (status=${outcome.status})`
  ).toBeGreaterThanOrEqual(300);
  expect(outcome.status, '未認証のコールバックが成功扱いになっている').toBeLessThan(400);
  expect(outcome.location, `ログイン画面へ戻されていない: ${outcome.location}`).toContain('/login');

  const status = await request.get(`/api/projects/${currentProject(ctx).id}/api-keys/${outcome.kind}`, {
    headers: await adminHeaders(request),
  });
  const body = (await status.json()) as { configured: boolean; connected?: boolean };
  expect(
    body.configured || body.connected === true,
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

/** 資格情報のエンドポイント(GA 6本 + AdSense 5本)。1本でも外れれば秘密情報が漏れるのでまとめて叩く。 */
async function requestAllCredentialEndpoints(
  request: APIRequestContext,
  projectId: number,
  headers: Record<string, string>
): Promise<DeniedOutcome[]> {
  const base = `/api/projects/${projectId}/api-keys`;
  const calls: { what: string; run: () => Promise<{ status: number; body: string }> }[] = [
    { what: 'GET google-analytics', run: () => send(request.get(`${base}/google-analytics`, { headers })) },
    {
      what: 'PUT google-analytics/client',
      run: () => send(request.put(`${base}/google-analytics/client`, {
        headers,
        data: { clientId: 'intruder.apps.googleusercontent.com', clientSecret: 'intruder-secret' },
      })),
    },
    {
      what: 'POST google-analytics/oauth-callback',
      run: () => send(request.post(`${base}/google-analytics/oauth-callback`, {
        headers,
        data: { code: 'intruder-code', redirectUri: 'https://localhost/connect/google-analytics/callback' },
      })),
    },
    { what: 'GET google-analytics/properties', run: () => send(request.get(`${base}/google-analytics/properties`, { headers })) },
    {
      what: 'PUT google-analytics/property',
      run: () => send(request.put(`${base}/google-analytics/property`, { headers, data: { propertyId: '111' } })),
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
  await connectGoogleAnalyticsFor(
    request,
    otherProject.id,
    gaClientSecret(ctx),
    'at1231-authorization-code'
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
