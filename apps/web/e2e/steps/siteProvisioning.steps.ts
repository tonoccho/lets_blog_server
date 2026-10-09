import fs from 'node:fs';
import path from 'node:path';
import type { APIRequestContext, Page } from '@playwright/test';
import { Given, Step, Then, When } from './fixtures';
import { waitForSiteCreationPanel } from '../support/siteCreationPanel';
import { waitForProvisionedSiteRow } from '../support/provisionedSiteRow';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken, loginAsAdmin, createFixtureProject } from '../support';

/**
 * WordPressサイトのプロビジョニング(issue #1167 / AT-5-3)のステップ定義。
 *
 * 移行元は `apps/web/e2e/site-registration.spec.ts`(削除済み)。フォーム操作・
 * タイムアウト値(構築完了まで最大240秒)はそこでの実装をそのまま踏襲している。
 *
 * ## media.steps.ts 等と同じファイルに置かない理由
 *
 * 兄弟 issue(#932 / #933)が同じ「サイトが既にある」フィクスチャを別の切り口で使うため、
 * ここは新規ファイルとして独立させる(#1167 Scope。前例: `mediaGarbageCollection.steps.ts`
 * と `media.steps.ts` の分離)。
 *
 * ## 段階をまたぐ識別子の受け渡し
 *
 * `apps/web/e2e/steps/fixtures.ts` の `ctx` はシナリオ1本に閉じたスコープで、
 * `at-provision` → `at-main` のように別プロジェクトとして実行されうる段階をまたいでは
 * 共有できない。そこで、構築したサイトの識別子を Playwright 標準の出力先
 * (`.gitignore` 済みの `apps/web/test-results/`)へ JSON で書き出し、`at-main` 側は
 * それを読む(`site-provisioning.feature` 冒頭のコメントも参照)。
 */

/** リポジトリルート(apps/web/e2e/steps から4階層上)。 */
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

const FIXTURE_DIR = path.join(REPO_ROOT, 'apps', 'web', 'test-results');
const FIXTURE_FILE = path.join(FIXTURE_DIR, 'site-provisioning-fixture.json');

interface ProvisionedSiteFixture {
  siteKey: string;
  siteName: string;
  siteId: number;
}

/** at-main 段階から読めるように、構築したサイトの識別子をファイルへ書き出す。 */
function writeProvisionedSiteFixture(fixture: ProvisionedSiteFixture): void {
  fs.mkdirSync(FIXTURE_DIR, { recursive: true });
  fs.writeFileSync(FIXTURE_FILE, JSON.stringify(fixture), 'utf8');
}

/** 存在しなければ null(このシナリオを単独実行した場合や、まだ何も構築されていない場合)。 */
function readProvisionedSiteFixture(): ProvisionedSiteFixture | null {
  if (!fs.existsSync(FIXTURE_FILE)) {
    return null;
  }
  return JSON.parse(fs.readFileSync(FIXTURE_FILE, 'utf8')) as ProvisionedSiteFixture;
}

/** issue #765: フィクスチャ名は並列実行時の衝突・孤児サイト対策でタイムスタンプ+乱数にする。 */
function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

/** サイトキーから `GET /api/sites` で数値IDを解決する(環境紐付けAPIはIDを要求するため)。 */
async function resolveSiteId(request: APIRequestContext, siteKey: string): Promise<number> {
  const token = await adminToken(request);
  const response = await request.get('/api/sites', { headers: { Authorization: `Bearer ${token}` } });
  expect(
    response.ok(),
    `サイト一覧の取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const sites = (await response.json()) as { id: number; siteKey: string }[];
  const found = sites.find((site) => site.siteKey === siteKey);
  expect(found, `サイト一覧に siteKey=${siteKey} が見つかりません`).toBeTruthy();
  return (found as { id: number }).id;
}

/**
 * ManagedWordPressForm.tsx をUIから操作する(`e2e/site-registration.spec.ts` の
 * beforeAllを踏襲)。完了メッセージの待機はここでは行わない
 * (呼び出し側が「もし」と「ならば」を分けて書けるように分離してある)。
 */
async function fillManagedWordPressForm(
  page: Page,
  prefix: string
): Promise<{ siteKey: string; siteName: string }> {
  const unique = uniqueSuffix();
  const siteKey = `${prefix}-${unique}`;
  const siteName = `E2E ${prefix} ${unique}`;

  await waitForSiteCreationPanel(page);
  await page.locator('button:has-text("WordPressを新規構築")').click();
  await page.locator('input[name="managedName"]').fill(siteName);
  await page.locator('input[name="managedSiteKey"]').fill(siteKey);
  await page.locator('input[name="managedTitle"]').fill(siteName);
  await page
    .locator('input[name="managedAdminUser"]')
    .fill(`e2eadmin${unique}`.replace(/[^a-zA-Z0-9._-]/g, '').slice(0, 30));
  await page.locator('input[name="managedAdminEmail"]').fill(`e2e-${unique}@letsblog.local`);
  await page.locator('input[name="managedAdminPassword"]').fill('E2eProvision#Passw0rd1');
  await page.locator('button:has-text("構築する")').click();

  return { siteKey, siteName };
}

/**
 * WordPressの自動構築は完了まで数分かかる場合がある。フォームはジョブとして受理するだけで完了を待たない
 * (issue #1696)ので、そのサイトの行が一覧に現れるまで待つ。
 */
async function waitForManagedWordPressCompletion(page: Page, siteKey: string): Promise<void> {
  await waitForProvisionedSiteRow(page, siteKey);
}

/** 構築完了を、そのサイトの行が一覧に現れることで確かめる(サイトキーごとに一意なので、何度目の構築でも取り違えない)。 */
async function waitForSiteRow(page: Page, siteKey: string): Promise<void> {
  await waitForProvisionedSiteRow(page, siteKey);
}

/** フォーム入力から完了待ち・ID解決までを一括で行う(1シナリオで複数サイトを構築する場合に使う)。 */
async function provisionManagedWordPress(
  page: Page,
  request: APIRequestContext,
  prefix: string
): Promise<{ siteKey: string; siteName: string; siteId: number }> {
  const { siteKey, siteName } = await fillManagedWordPressForm(page, prefix);
  await waitForSiteRow(page, siteKey);
  const siteId = await resolveSiteId(request, siteKey);
  return { siteKey, siteName, siteId };
}

// ------------------------------------------------------- サイト一覧ページ共通

// 「前提」でも「もし」でも同じ意味なので Step で定義する(auth.steps.tsと同じ方針)。
Step('サイト一覧ページを開いている', async ({ page }) => {
  await loginAsAdmin(page);
  await page.goto('/sites');
});

// ------------------------------------------------------- 新規プロビジョニング(親シナリオ6)

When('WordPressを新規構築する', async ({ ctx, page }) => {
  const { siteKey, siteName } = await fillManagedWordPressForm(page, 'e2eprov');
  ctx.provisionedSiteKey = siteKey;
  ctx.provisionedSiteName = siteName;
});

Then('構築が完了し、そのサイトが一覧に現れる', async ({ ctx, page }) => {
  await waitForManagedWordPressCompletion(page, ctx.provisionedSiteKey as string);
});

Then('一覧のそのサイト行で疎通確認が成功する', async ({ ctx, page }) => {
  const row = page.locator(`tr:has-text("${ctx.provisionedSiteKey}")`);
  await expect(row).toBeVisible();
  // 一覧は SSR で行とボタンが先に見えるが、ハイドレーション(特に負荷時の dev ビルド)が終わる前の
  // クリックは onClick に届かず、server action が一度も発行されない(#1713 の再現トレースでは
  // クリック後 15 秒間 test-connection の POST が 0 件で、行にはボタンだけが残った)。
  // 疎通確認は副作用のない読み取りなので、結果が出るまでクリックごと再試行する。
  const button = row.locator('button:has-text("疎通確認")');
  await expect(async () => {
    await button.click();
    await expect(row.getByText('FAILED', { exact: true }), `疎通確認が FAILED を返した: ${await row.innerText()}`).toHaveCount(0);
    await expect(row.getByText('SUCCESS', { exact: true })).toBeVisible({ timeout: 5000 });
  }).toPass({ timeout: 60000, intervals: [1000] });
});

Then('プロビジョニング結果を後続シナリオへ公開する', async ({ ctx, request }) => {
  const siteKey = ctx.provisionedSiteKey as string;
  const siteId = await resolveSiteId(request, siteKey);
  writeProvisionedSiteFixture({ siteKey, siteName: ctx.provisionedSiteName as string, siteId });
});

// ------------------------------------------------------- 一覧・作成フォーム(移行元テスト1・2)

Then('サイト一覧の見出しと表が表示される', async ({ page }) => {
  await expect(page.locator('h1:has-text("サイト")')).toBeVisible();
  await expect(page.locator('table')).toBeVisible();
});

Then('サイト作成フォームへ到達できる', async ({ page }) => {
  await waitForSiteCreationPanel(page);
  await expect(page.locator('id=site-creation')).toBeVisible();

  // モード切替ボタン(「既存サイトを登録」/「WordPressを新規構築」)は常に存在する。
  const createButtons = page.locator(
    'button:has-text("既存サイトを登録"), button:has-text("WordPressを新規構築")'
  );
  await expect(createButtons).toHaveCount(2);
  await expect(createButtons.first()).toBeVisible();
});

// ------------------------------------------------- 接続状態表示・検索絞り込み(移行元テスト4・5)

Given('プロビジョニング済みのサイトがある', async ({ ctx, page, request }) => {
  const cached = readProvisionedSiteFixture();
  if (cached) {
    ctx.provisionedSiteKey = cached.siteKey;
    ctx.provisionedSiteName = cached.siteName;
    return;
  }

  // このシナリオを単独実行した場合(--grep等)、まだ何も構築されていない。経路(UI/API)に
  // 関わらず実プロビジョニングは数分かかるため、ここで直接構築して後続シナリオ用にも
  // 公開しておく(既に構築が終わっているシナリオ1の分を待たずに済ませられるわけではない)。
  const { siteKey, siteName, siteId } = await provisionManagedWordPress(page, request, 'e2eprovfallback');
  ctx.provisionedSiteKey = siteKey;
  ctx.provisionedSiteName = siteName;
  writeProvisionedSiteFixture({ siteKey, siteName, siteId });
});

Then('そのサイト行に疎通確認ボタンが表示されている', async ({ ctx, page }) => {
  const row = page.locator(`tr:has-text("${ctx.provisionedSiteKey}")`);
  await expect(row).toBeVisible();
  await expect(row.locator('button:has-text("疎通確認")')).toBeVisible();
});

When('サイト一覧をそのサイトのキーで検索する', async ({ ctx, page }) => {
  const searchInput = page.locator('input[placeholder*="検索"]');
  await expect(searchInput).toBeVisible();
  await searchInput.fill(ctx.provisionedSiteKey as string);
});

Then('検索結果にそのサイトだけが表示される', async ({ ctx, page }) => {
  await expect(page.locator('table')).toBeVisible();
  // ManagedWordPressのURLはサイトキーを部分文字列として含む(https://localhost/sites/<siteKey>)ため、
  // 部分一致だとサイトキー列とURL列の両方に一致してstrict mode違反になる。完全一致で1件に絞る
  // (e2e/site-registration.spec.tsの実装を踏襲)。
  await expect(page.getByRole('cell', { name: ctx.provisionedSiteKey as string, exact: true })).toBeVisible();
});

// ------------------------------------------------------- 1プロジェクト2環境(親シナリオ11)

Given('プロジェクトがある', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const project = await createFixtureProject(request, token, 'at5-3-envs');
  ctx.provisioningProjectId = project.id;
});

When('1つ目のサイトとしてWordPressを新規構築する', async ({ ctx, page, request }) => {
  const result = await provisionManagedWordPress(page, request, 'e2eprov1');
  ctx.site1Key = result.siteKey;
  ctx.site1Id = result.siteId;
});

When('2つ目のサイトとしてWordPressを新規構築する', async ({ ctx, page, request }) => {
  const result = await provisionManagedWordPress(page, request, 'e2eprov2');
  ctx.site2Key = result.siteKey;
  ctx.site2Id = result.siteId;
});

When(
  '構築した2つのサイトをプロジェクトのlocal環境とtest環境にそれぞれ紐付ける',
  async ({ ctx, request }) => {
    const token = await adminToken(request);
    const projectId = ctx.provisioningProjectId as number;

    const bind = async (environment: 'local' | 'test', siteId: number) => {
      const response = await request.post(`/api/projects/${projectId}/environments`, {
        headers: { Authorization: `Bearer ${token}` },
        data: { environment, siteId },
      });
      expect(
        response.ok(),
        `${environment}環境への紐付けに失敗しました (status=${response.status()}): ${await response.text()}`
      ).toBe(true);
    };

    await bind('local', ctx.site1Id as number);
    await bind('test', ctx.site2Id as number);
  }
);

Then('プロジェクトはlocal環境とtest環境の両方にサイトを持っている', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const response = await request.get(`/api/projects/${ctx.provisioningProjectId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    response.ok(),
    `プロジェクトの取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const project = (await response.json()) as {
    localSite: { id: number } | null;
    testSite: { id: number } | null;
  };
  expect(project.localSite?.id, 'local環境にサイトが紐付いていません').toBe(ctx.site1Id);
  expect(project.testSite?.id, 'test環境にサイトが紐付いていません').toBe(ctx.site2Id);
});

// ------------------------------------------------------- at-main段階からの参照(AC3)

Given('at-provision段階で公開されたサイトの識別子を読み込む', async ({ ctx }) => {
  const fixture = readProvisionedSiteFixture();
  expect(
    fixture,
    `at-provision段階のプロビジョニング結果(${FIXTURE_FILE})が見つかりません。` +
      'at-provisionが先に実行されている必要があります(dependencies経由で自動的に先行するはずです)'
  ).not.toBeNull();
  ctx.provisionedSiteKey = (fixture as ProvisionedSiteFixture).siteKey;
});

Then('その識別子のサイトが一覧に表示されている', async ({ ctx, page }) => {
  const row = page.locator(`tr:has-text("${ctx.provisionedSiteKey}")`);
  await expect(row).toBeVisible();
});
