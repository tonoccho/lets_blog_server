import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  createFixtureProject,
  deleteFixtureProject,
  expect,
  fetchAccessToken,
  loginAsAdmin,
  loginAsUser,
} from '../support';
import {
  criticalOrSeriousViolations,
  formLabelViolations,
  headingHierarchyViolations,
  horizontalScrollViolation,
  imageAltTextViolations,
  linkTextViolations,
} from '../support/a11yChecks';
import {
  cleanupPageFixtures,
  getOrBuildPageFixtures,
  PAGE_INVENTORY_APP_ONLY,
  type PageFixtures,
} from '../support/pageInventory';
import { describeFailures, sweepAllPages, type PageVisitFailure } from '../support/pageSweep';
import {
  compareKeySets,
  findMissingTranslations,
  loadLocaleMessages,
  scanTranslationCallSites,
  withoutKey,
} from '../support/i18nCompleteness';
import messagesJa from '../../messages/ja.json';
import messagesEn from '../../messages/en.json';

/**
 * 横断的品質(アクセシビリティ・国際化・レスポンシブ・クロスブラウザ)のステップ定義
 * (issue #944 / AT-18)。
 *
 * `apps/web/e2e/accessibility.spec.ts`(Playwright直書き、11テスト)をここへ全面移行した。
 * 旧specの `Identify accessibility violations for review` は違反を記録するだけで
 * 合否を決めていなかった(調査用)。ここでは合否を決めるものだけを扱う。
 */

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function getJson<T>(request: APIRequestContext, token: string, path: string, label: string): Promise<T> {
  const response = await request.get(path, { headers: { Authorization: `Bearer ${token}` } });
  expect(response.ok(), `${label}の取得に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(
    true
  );
  return (await response.json()) as T;
}

// --------------------------------------------------------------- 共通の前提

Given('全ページ分のフィクスチャ\\(プロジェクト・サイト・自ユーザー\\)を1回だけ用意する', async ({ ctx, request }) => {
  await getOrBuildPageFixtures(ctx, request);
});

Given('一般ユーザーとしてログイン済みである', async ({ page }) => {
  await loginAsUser(page);
});

Given('管理者としてログイン済みである', async ({ page }) => {
  await loginAsAdmin(page);
});

After({ tags: '@ui-quality' }, async ({ ctx, request }) => {
  const fixtures = ctx.at18PageFixtures as PageFixtures | undefined;
  if (fixtures) {
    await cleanupPageFixtures(request, fixtures);
  }
});

// --------------------------------------------------------------- アクセシビリティ

When('全24ページをそれぞれ適切な権限でログインして開き、axeで走査する', async ({ ctx, browser }) => {
  const fixtures = ctx.at18PageFixtures as PageFixtures;
  const failures = await sweepAllPages(browser, fixtures, async (page) => {
    const violations = await criticalOrSeriousViolations(page);
    if (violations.length > 0) {
      throw new Error(violations.join(' / '));
    }
  });
  ctx.at18AxeFailures = failures;
});

Then('どのページにもcritical・serious相当の違反が無い', async ({ ctx }) => {
  const failures = describeFailures(ctx.at18AxeFailures as PageVisitFailure[]);
  expect(failures, 'axeがcritical・serious相当の違反を検出したページがある').toEqual([]);
});

When('全24ページをそれぞれ適切な権限でログインして開き、見出し階層を調べる', async ({ ctx, browser }) => {
  const fixtures = ctx.at18PageFixtures as PageFixtures;
  // /login・/setup(role: none)はKeycloakのホスト型画面へリダイレクトするため対象外
  // (PAGE_INVENTORY_APP_ONLYのコメント参照)。
  const failures = await sweepAllPages(
    browser,
    fixtures,
    async (page) => {
      const violations = await headingHierarchyViolations(page);
      if (violations.length > 0) {
        throw new Error(violations.join(' / '));
      }
    },
    undefined,
    PAGE_INVENTORY_APP_ONLY
  );
  ctx.at18HeadingFailures = failures;
});

Then('どのページもh1が1つだけで、見出しレベルが飛ばない', async ({ ctx }) => {
  const failures = describeFailures(ctx.at18HeadingFailures as PageVisitFailure[]);
  expect(failures, '見出し階層が妥当でないページがある').toEqual([]);
});

/**
 * 「キーボードのみで」の実際の意味(issue #944の実装メモ)。
 *
 * Playwrightの `locator.click()` はマウスイベントを合成する。ここでは `.focus()`
 * (プログラム的なフォーカス移動、マウスを介さない)と `page.keyboard` だけを使い、
 * どの要素の操作も `.click()` を経由しないことで「キーボードのみ」を表す。
 * タブ順の網羅的な検証(全要素をTabで辿れるか)は別の関心事であり、ここは
 * 「ログイン→プロジェクト作成→保存」という主要フローがマウス無しで完了できることを見る。
 */
async function keyboardOnlyCreateProject(ctx: Record<string, unknown>, page: Page): Promise<void> {
  await page.goto('/login', { waitUntil: 'commit' });
  await expect(page).toHaveURL(/\/auth\/realms\/letsblog\//, { timeout: 30000 });
  await page.waitForLoadState('load');

  await page.locator('#username').focus();
  await page.keyboard.type(E2E_ADMIN_EMAIL);
  await page.keyboard.press('Tab');
  await page.keyboard.type(E2E_ADMIN_PASSWORD);
  await page.locator('#kc-login').focus();
  await page.keyboard.press('Enter');

  await expect(page).toHaveURL('/', { timeout: 30000 });
  await page.waitForLoadState('load');

  await page.goto('/projects', { waitUntil: 'load' });

  const unique = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const name = `AT-18 keyboard-only ${unique}`;
  const slug = `at18-kb-${unique}`;

  await page.locator('input[name="name"]').focus();
  await page.keyboard.type(name);
  await page.locator('input[name="slug"]').focus();
  await page.keyboard.press('Control+A');
  await page.keyboard.type(slug);
  await page.locator('button[type="submit"]').focus();
  await page.keyboard.press('Enter');

  await expect(page.getByText('作成しました。')).toBeVisible({ timeout: 15000 });
  ctx.at18KeyboardCreatedProjectName = name;
}

When('キーボードのみでログインしプロジェクトを新規作成して保存する', async ({ ctx, page }) => {
  await keyboardOnlyCreateProject(ctx, page);
});

When(
  'ビューポート幅{int}pxでキーボードのみでログインしプロジェクトを新規作成して保存する',
  async ({ ctx, page }, width: number) => {
    await page.setViewportSize({ width, height: 800 });
    await keyboardOnlyCreateProject(ctx, page);
  }
);

Then('作成したプロジェクトが一覧に表示される', async ({ ctx, page }) => {
  const name = ctx.at18KeyboardCreatedProjectName as string;
  await page.reload({ waitUntil: 'load' });
  await expect(page.getByText(name)).toBeVisible();
});

After({ tags: '@ui-quality' }, async ({ ctx, request }) => {
  const name = ctx.at18KeyboardCreatedProjectName as string | undefined;
  if (!name) {
    return;
  }
  const token = await adminToken(request);
  const projects = await getJson<{ id: number; name: string }[]>(
    request,
    token,
    '/api/projects',
    'プロジェクト一覧'
  );
  const created = projects.find((project) => project.name === name);
  if (created) {
    await deleteFixtureProject(request, token, created.id);
  }
});

When('ホーム画面の操作可能な要素にキーボードでフォーカスを移す', async ({ page }) => {
  await page.goto('/', { waitUntil: 'load' });
  await page.keyboard.press('Tab');
});

Then('フォーカスされた要素にフォーカスリングが描画されている', async ({ page }) => {
  const style = await page.evaluate(() => {
    const el = document.activeElement;
    if (!el || el === document.body) {
      return null;
    }
    const computed = getComputedStyle(el);
    return { outlineStyle: computed.outlineStyle, outlineWidth: computed.outlineWidth, boxShadow: computed.boxShadow };
  });
  expect(style, 'Tab移動後、body以外の要素にフォーカスが当たっていない').not.toBeNull();
  const hasVisibleOutline = style!.outlineStyle !== 'none' && style!.outlineWidth !== '0px';
  const hasVisibleBoxShadow = style!.boxShadow !== 'none';
  expect(
    hasVisibleOutline || hasVisibleBoxShadow,
    `フォーカスした要素に視認できるフォーカスリングが無い(outline=${style!.outlineStyle} ${style!.outlineWidth}, boxShadow=${style!.boxShadow})`
  ).toBe(true);
});

When('画像を含むページを開き、img要素を調べる', async ({ ctx, browser }) => {
  const fixtures = ctx.at18PageFixtures as PageFixtures;
  const failures = await sweepAllPages(browser, fixtures, async (page) => {
    const violations = await imageAltTextViolations(page);
    if (violations.length > 0) {
      throw new Error(violations.join(' / '));
    }
  });
  ctx.at18ImageFailures = failures;
});

Then('すべてのimg要素にalt属性またはaria-labelが付いている', async ({ ctx }) => {
  const failures = describeFailures(ctx.at18ImageFailures as PageVisitFailure[]);
  expect(failures, 'alt属性もaria-labelも無いimg要素があるページがある').toEqual([]);
});

When('ホーム画面のリンクを調べる', async ({ ctx, page }) => {
  await page.goto('/', { waitUntil: 'load' });
  ctx.at18LinkViolations = await linkTextViolations(page);
});

Then('すべてのリンクにテキスト・aria-label・titleのいずれかがある', async ({ ctx }) => {
  expect(ctx.at18LinkViolations, 'アクセシブルな名前が無いリンクがある').toEqual([]);
});

When('プロジェクト作成フォームを調べる', async ({ ctx, page }) => {
  await page.goto('/projects', { waitUntil: 'load' });
  ctx.at18FormViolations = await formLabelViolations(page);
});

Then('すべての入力要素にラベルまたはaria-labelが関連付いている', async ({ ctx }) => {
  expect(ctx.at18FormViolations, 'ラベルが関連付いていない入力要素がある').toEqual([]);
});

When('ログイン画面\\(Keycloakホスト型\\)を開き、axeで走査する', async ({ ctx, page }) => {
  await page.goto('/login', { waitUntil: 'commit' });
  await expect(page).toHaveURL(/\/auth\/realms\/letsblog\//, { timeout: 30000 });
  await page.waitForLoadState('load');
  ctx.at18LoginViolations = await criticalOrSeriousViolations(page);
});

Then('そのページにもcritical・serious相当の違反が無い', async ({ ctx }) => {
  expect(ctx.at18LoginViolations, 'Keycloakログイン画面にcritical・serious相当の違反がある').toEqual([]);
});

// --------------------------------------------------------------- 国際化

When('ソース上の全t\\(\\)呼び出しを、全ロケールのメッセージファイルへ照合する', async ({ ctx }) => {
  const callSites = scanTranslationCallSites();
  expect(callSites.length, 't()の呼び出し箇所を抽出できていない').toBeGreaterThan(0);
  const messagesByLocale = loadLocaleMessages();
  ctx.at18CallSites = callSites;
  ctx.at18MissingTranslations = findMissingTranslations(callSites, messagesByLocale);
  ctx.at18MessagesByLocale = messagesByLocale;
});

Then('欠落している翻訳キーは無い', async ({ ctx }) => {
  const missing = ctx.at18MissingTranslations as ReturnType<typeof findMissingTranslations>;
  expect(
    missing.map((entry) => `${entry.locale}: ${entry.namespace}.${entry.key} (${entry.usedIn.join(', ')})`),
    '呼び出し箇所はあるのに、いずれかのロケールのメッセージファイルにキーが無い(issue #718と同種の退行)'
  ).toEqual([]);
});

Then('header.adminキーを欠落させた状態を再現すると、その欠落が検出される', async ({ ctx }) => {
  const callSites = ctx.at18CallSites as ReturnType<typeof scanTranslationCallSites>;
  const messagesByLocale = ctx.at18MessagesByLocale as ReturnType<typeof loadLocaleMessages>;
  const reproduced = withoutKey(messagesByLocale, 'ja', 'header', 'admin');
  const missing = findMissingTranslations(callSites, reproduced);
  const detected = missing.some((entry) => entry.locale === 'ja' && entry.namespace === 'header' && entry.key === 'admin');
  expect(
    detected,
    '#718(header.adminキー欠落)を再現しても検出できない。チェック関数自体が壊れている'
  ).toBe(true);
});

const ADMIN_LABEL_LOCATOR = 'button[aria-expanded] span.font-medium';

When('表示言語を英語に切り替える', async ({ page }) => {
  await page.goto('/', { waitUntil: 'load' });
  await page.getByLabel('言語選択').selectOption('en');
  await page.waitForLoadState('load');
});

Then('ヘッダーのラベルが英語表示になる', async ({ page }) => {
  await expect(page.locator(ADMIN_LABEL_LOCATOR).first()).toHaveText(messagesEn.header.admin);
});

When('ページを再読み込みする', async ({ page }) => {
  await page.reload({ waitUntil: 'load' });
});

Then('ヘッダーのラベルは英語表示のままである', async ({ page }) => {
  await expect(page.locator(ADMIN_LABEL_LOCATOR).first()).toHaveText(messagesEn.header.admin);
});

// タイムゾーン(issue #944 シナリオ11)

/**
 * 監査ログの `PROJECT_CREATED PROJECT #<id>` という題(issue #944)。
 *
 * 統合ログ画面はエントリのタイトルしか表示せず(`UnifiedLogRow.tsx`)、フォーマット済みの
 * 日時文字列はミリ秒までは含まないため、ほぼ同時刻に他の操作が走ると
 * `getByText(日時文字列)` だけでは行を一意に特定できない(実測: 同一秒内の別操作と衝突し
 * 7件一致した)。プロジェクトIDを含む監査ログの題名は他と衝突しない一意な手掛かりになるので、
 * これを画面上の行を特定するアンカーとして使う。
 */
Given('操作ログに記録される操作を1件実行しておく', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const project = await createFixtureProject(request, token, 'at18-timezone');
  ctx.at18TimezoneProjectId = project.id;
  const marker = `PROJECT_CREATED PROJECT #${project.id}`;

  const deadline = Date.now() + 15000;
  let entry: { createdAt: string } | undefined;
  while (Date.now() < deadline && !entry) {
    const result = await getJson<{ content: { createdAt: string; title: string }[] }>(
      request,
      token,
      `/api/operation-logs/unified?type=AUDIT&q=${encodeURIComponent(marker)}&page=0&size=5`,
      '統合ログ'
    );
    entry = result.content.find((candidate) => candidate.title === marker);
    if (!entry) {
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
  }
  expect(entry, `プロジェクト作成の監査ログ(${marker})が現れない`).toBeTruthy();
  ctx.at18TimezoneMarker = marker;
  ctx.at18TimezoneEntryIso = (entry as { createdAt: string }).createdAt;
});

When('個人設定のタイムゾーンをAmerica\\/New_Yorkに変更する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const me = await getJson<{ locale: string | null; timezone: string | null }>(
    request,
    token,
    '/api/identity/me',
    '自ユーザー情報'
  );
  ctx.at18OriginalTimezone = me.timezone;
  ctx.at18OriginalLocale = me.locale;
  const response = await request.patch('/api/identity/me/preferences', {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: { locale: me.locale ?? 'ja', timezone: 'America/New_York' },
  });
  expect(
    response.ok(),
    `タイムゾーンの変更に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

When('操作ログ画面を開く', async ({ page }) => {
  await page.goto('/operation-logs', { waitUntil: 'load' });
});

Then('表示される日時がAmerica\\/New_Yorkでの換算値と一致する', async ({ ctx, page }) => {
  const iso = ctx.at18TimezoneEntryIso as string;
  const marker = ctx.at18TimezoneMarker as string;
  const expected = new Date(iso).toLocaleString('ja-JP', {
    timeZone: 'America/New_York',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  });
  const titleCell = page.getByText(marker, { exact: true });
  await expect(titleCell).toBeVisible({ timeout: 15000 });
  const row = titleCell.locator('xpath=..');
  await expect(row).toContainText(expected);
});

After({ tags: '@i18n' }, async ({ ctx, request }) => {
  const projectId = ctx.at18TimezoneProjectId as number | undefined;
  const token = await adminToken(request);
  if (projectId) {
    await deleteFixtureProject(request, token, projectId);
  }
  if (ctx.at18OriginalTimezone !== undefined) {
    await request.patch('/api/identity/me/preferences', {
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      data: {
        locale: (ctx.at18OriginalLocale as string | null) ?? 'ja',
        timezone: (ctx.at18OriginalTimezone as string | null) ?? 'Asia/Tokyo',
      },
    });
  }
});

When('全ロケールのメッセージファイルのキー集合を比較する', async ({ ctx }) => {
  ctx.at18KeySetDiff = compareKeySets(loadLocaleMessages());
});

Then('どのロケールにも他方にしか無いキーが無い', async ({ ctx }) => {
  const diff = ctx.at18KeySetDiff as ReturnType<typeof compareKeySets>;
  expect(
    diff.map((entry) => `${entry.locale}に無い: ${entry.missingFromThisLocale.join(', ')}`),
    'ロケール間でキー集合が一致しない'
  ).toEqual([]);
});

// --------------------------------------------------------------- レスポンシブ

When('ビューポート幅{int}pxで全24ページをそれぞれ適切な権限でログインして開く', async ({ ctx, browser }, width: number) => {
  const fixtures = ctx.at18PageFixtures as PageFixtures;
  const failures = await sweepAllPages(
    browser,
    fixtures,
    async (page) => {
      const violation = await horizontalScrollViolation(page);
      if (violation) {
        throw new Error(violation);
      }
    },
    { width, height: 800 }
  );
  ctx.at18ResponsiveFailures = failures;
});

Then('どのページも横スクロールが発生しない', async ({ ctx }) => {
  const failures = describeFailures(ctx.at18ResponsiveFailures as PageVisitFailure[]);
  expect(failures, '横スクロールが発生しているページがある').toEqual([]);
});

Then('どのページも横スクロールが発生せず、主要な操作要素が画面内に収まる', async ({ ctx }) => {
  const failures = describeFailures(ctx.at18ResponsiveFailures as PageVisitFailure[]);
  expect(failures, '横スクロールが発生しているページがある(タブレット幅)').toEqual([]);
});

When('ビューポート幅{int}pxでモバイル用ナビゲーションを開閉する', async ({ ctx, page }, width: number) => {
  await page.setViewportSize({ width, height: 800 });
  await page.goto('/', { waitUntil: 'load' });

  const openButton = page.getByRole('button', { name: messagesJa.header.openMenu });
  await openButton.click();

  const dialog = page.getByRole('dialog', { name: messagesJa.header.navigation });
  await expect(dialog).toBeVisible();

  const firstLink = dialog.getByRole('link').first();
  const href = await firstLink.getAttribute('href');
  await firstLink.click();

  await expect(dialog).toBeHidden();
  ctx.at18MobileNavHref = href;
});

Then('モバイル用ナビゲーションのリンクが操作できる', async ({ ctx, page }) => {
  const href = ctx.at18MobileNavHref as string | null;
  expect(href, 'モバイル用ナビゲーションのリンクにhrefが無い').toBeTruthy();
  await expect(page).toHaveURL(new RegExp(`${(href as string).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`));
});

// --------------------------------------------------------------- クロスブラウザ
// 「一般ユーザーとしてログインする」は e2e/steps/auth.steps.ts が既に定義している(Step、issue #929)。

Then('ホーム画面が表示され、致命的なレンダリング崩れが無い', async ({ page }) => {
  await expect(page).toHaveURL('/');
  await expect(page.locator('body')).toBeVisible();
  const violations = await criticalOrSeriousViolations(page);
  expect(violations, 'ホーム画面にcritical・serious相当の違反がある(クロスブラウザ)').toEqual([]);
});

When('投稿履歴画面を開く', async ({ page }) => {
  await page.goto('/posts', { waitUntil: 'load' });
});

Then('投稿履歴が表示され、致命的なレンダリング崩れが無い', async ({ page }) => {
  await expect(page).toHaveURL(/\/posts$/);
  await expect(page.locator('body')).toBeVisible();
  const violations = await criticalOrSeriousViolations(page);
  expect(violations, '投稿履歴画面にcritical・serious相当の違反がある(クロスブラウザ)').toEqual([]);
});
