import { test, expect, type APIRequestContext, type Locator, type Page } from '@playwright/test';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, fetchAccessToken, loginAsAdmin } from './helpers';

/**
 * issue #753: このファイルは、独立ページとして存在しない `/custom-tags` と、ブラウザから
 * 無認証で叩ける前提の `/api/custom-tags/validate` を対象にした古いテストだった。
 * 現在の構成に合わせて次のように書き直している(計測している指標と閾値は元のまま維持する)。
 *
 *   - カスタムタグ生成UIはプロジェクト詳細の「タグ」ページ(/projects/{id}/tags)の
 *     「カスタムタグ管理」タブに埋め込まれている(custom-tag-generation.spec.ts と同じ導線)。
 *     このページは requireAdminSession() で保護されているため、helpers.ts の loginAsAdmin で
 *     Keycloak経由のログインを済ませてから遷移する。
 *   - 検証APIは gateway 経由の POST /api/custom-tags/validate(content-service の
 *     CustomTagController#validate)。ブラウザのfetchではなくPlaywrightのrequestフィクスチャから
 *     アクセストークン付きで呼ぶ(main-scenario.spec.ts と同じ方針)。レスポンスのフィールド名は
 *     `valid` ではなく `isValid`(services/content の ValidationResult)。
 *   - プロジェクトが1件も無い環境で無検証のままpassしないよう、UI側のテストは
 *     beforeEach でプロジェクトを1件作成し afterEach で削除する(issue #645、post-creation.spec.ts と同じ)。
 *     ただしこのフィクスチャの作成・削除は gateway のAPIを直接呼ぶ(理由は
 *     createFixtureProject() のコメント参照)。
 */

/**
 * フィクスチャのプロジェクトを gateway 経由のAPIで直接作成する(issue #753)。
 *
 * post-creation.spec.ts と同じくWeb UI(/projects の作成フォーム → 一覧 → /projects/{id})で
 * 用意することもできるが、その導線は 1テストあたり ダッシュボード+一覧+詳細 の
 * サーバーレンダリングで25回前後 gateway を呼ぶ。gateway の api-global バケットは
 * クライアント単位ではなく**グローバル**に 100リクエスト/分
 * (services/gateway の RateLimitProperties)であり、playwright.config.ts の既定設定
 * (fullyParallel: true + ワーカー数はCPUコア数の半分)で本ファイルと security.spec.ts を
 * 同時に流すとこの上限を超える。超えた分は 429 になるが、Next.js 側は
 * listProjects()/getProject() の失敗を握りつぶして「全0件」や404を描画するため、
 * 「フィクスチャのプロジェクトが消えた」ように見える別のテストの失敗として表面化する
 * (実測: 既定設定では1分間に96リクエストに達し /api/projects が429になった)。
 *
 * プロジェクトのCRUD自体はこのファイルの検証対象ではない(UIからの作成・削除は
 * post-creation.spec.ts が担保している)ため、main-scenario.spec.ts と同じ方針で
 * APIを直接呼び、フィクスチャ1件あたりのリクエストを1往復に抑える。
 */
async function createFixtureProject(
  request: APIRequestContext,
  accessToken: string
): Promise<number> {
  const unique = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const response = await request.post('/api/projects', {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { name: `E2E Perf Project ${unique}`, slug: `e2e-perf-${unique}` },
  });
  expect(
    response.ok(),
    `フィクスチャのプロジェクト作成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);

  return ((await response.json()) as { id: number }).id;
}

/** createFixtureProject() で作ったプロジェクトを削除する。後片付けもAPIで行う。 */
async function deleteFixtureProject(
  request: APIRequestContext,
  accessToken: string,
  projectId: number
): Promise<void> {
  const response = await request.delete(`/api/projects/${projectId}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  expect(
    response.ok(),
    `フィクスチャのプロジェクト削除に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

/** 「カスタムタグ管理」タブ内のAI生成フォーム。同じ画面の下部にある手動追加フォームと区別する。 */
function generationFormOf(page: Page): Locator {
  return page.locator('form').filter({ has: page.locator('textarea[name="prompt"]') });
}

/**
 * プロジェクト詳細の「タグ」ページを開き、「カスタムタグ管理」タブへ切り替える。
 *
 * AI生成フォームの送信ハンドラ(CustomTagGenerationForm#handleSubmit)は
 * `if (!session?.user) return;` で始まる。SessionProvider はサーバー側のセッションを
 * 初期値として受け取らず、マウント後に /api/auth/session を取得して初めて
 * useSession() が埋まるため、それ以前に「生成」を押すと送信が何の反応も無いまま
 * 捨てられる(ボタンは「生成」のまま、エラーも出ない)。issue #753 の実行では実際に
 * この競合で生成リクエストが飛ばず60秒待って失敗したため、セッション取得の完了を待つ。
 *
 * ただしここで待てるのは /api/auth/session の**レスポンス受信**までで、そこから
 * セッションが React の state に反映されるまでの隙間は残る(issue #778)。
 * その隙間は clickGenerateUntilSubmitted() の再試行で吸収する。
 */
async function openCustomTagsTab(page: Page, projectId: number): Promise<void> {
  const sessionLoaded = page.waitForResponse(
    (response) => response.url().includes('/api/auth/session') && response.ok()
  );
  await page.goto(`/projects/${projectId}/tags`);
  await page.locator('button:has-text("カスタムタグ管理")').click();
  await sessionLoaded;
}

/** 「生成」の送信が受理されるまでの再試行回数(issue #778 の競合対策)。 */
const GENERATION_SUBMIT_ATTEMPTS = 5;
/** 1回のクリックが受理されたかどうかを見極めるための待ち時間。 */
const GENERATION_SUBMIT_ACCEPTED_TIMEOUT = 3000;

/**
 * AI生成フォームの「生成」を押し、送信が実際に受理されるまで押し直す(issue #778)。
 *
 * CustomTagGenerationForm#handleSubmit は `if (!session?.user) return;` で始まる。
 * SessionProvider はサーバー側のセッションを初期値として受け取らず、マウント後に
 * /api/auth/session を取得してから useSession() が埋まるため、それ以前のクリックは
 * 何の反応も無いまま捨てられる(ボタンは「生成」のまま、エラーも出ない)。
 * openCustomTagsTab() は /api/auth/session の**レスポンス受信**までは待てるが、
 * レスポンス受信からセッションが React の state に反映されるまでには隙間が残るため、
 * ネットワーク待ちだけではこの競合を閉じられない。アプリ側の修正は issue #778 の
 * 担当範囲なので、ここでは「受理されたことを観測してから先へ進む」ことで吸収する。
 * #778 が修正されたら、この再試行は削除して単純な click() に戻してよい。
 *
 * 受理の判定にはボタンの表示を使う。`isLoading` は上記ガードを通過した後にしか true に
 * ならず、ボタンは `{isLoading ? "生成中..." : "生成"}` を描画するため、表示が
 * 「生成中...」へ変わったことが送信が受理された直接の証拠になる。生成が待ち時間より
 * 速く終わった場合は「生成中...」を観測し損ねうるので、結果の表示(生成完了! /
 * フォーム内のエラー文言)も受理のシグナルとして扱う。
 *
 * @returns 受理されたクリックを行った時刻(Date.now())。捨てられたクリックの待ち時間を
 *          呼び出し側の計測に含めないために返す。
 */
async function clickGenerateUntilSubmitted(page: Page, form: Locator): Promise<number> {
  const submitButton = form.locator('button[type="submit"]');
  const accepted = form
    .locator('button[type="submit"]:has-text("生成中")')
    .or(page.getByText('生成完了！'))
    .or(form.locator('p.text-red-600'))
    .first();

  for (let attempt = 1; attempt <= GENERATION_SUBMIT_ATTEMPTS; attempt++) {
    const clickedAt = Date.now();
    // 送信が受理済みならボタンは disabled のままなので、ここへは戻ってこない
    // (=クリックが失敗するのは受理されていないときだけ)。
    await submitButton.click({ timeout: 10_000 });

    try {
      await expect(accepted).toBeVisible({ timeout: GENERATION_SUBMIT_ACCEPTED_TIMEOUT });
      return clickedAt;
    } catch {
      // 受理されなかった = issue #778 の競合でクリックが捨てられた。押し直す。
    }
  }

  throw new Error(
    `「生成」のクリックが${GENERATION_SUBMIT_ATTEMPTS}回とも送信されませんでした` +
      '(ボタンが「生成中...」にならず、結果もエラーも表示されない)。' +
      'CustomTagGenerationForm#handleSubmit がセッション未反映のままクリックを' +
      '握りつぶしている可能性が高い(issue #778)。'
  );
}

test.describe('カスタムタグ検証APIのパフォーマンス', () => {
  test.skip(!E2E_ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');

  test('APIレスポンス時間が2秒以内であること', async ({ request }) => {
    const accessToken = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);

    const startTime = Date.now();
    const response = await request.post('/api/custom-tags/validate', {
      headers: { Authorization: `Bearer ${accessToken}` },
      data: { htmlTemplate: '<div>{{content}}</div>', cssContent: '.div { padding: 10px; }' },
    });
    const responseTime = Date.now() - startTime;

    expect(
      response.ok(),
      `検証APIの呼び出しに失敗しました (status=${response.status()}): ${await response.text()}`
    ).toBe(true);

    console.log(`API response time: ${responseTime}ms`);
    expect(responseTime).toBeLessThan(2000); // 2秒以内

    // レスポンスのフィールド名はisValid(旧テストのvalidは実装に存在しない)
    expect(await response.json()).toHaveProperty('isValid');
  });

  test('複数リクエストの並列処理パフォーマンス', async ({ request }) => {
    const accessToken = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);

    const startTime = Date.now();

    // 複数のバリデーションリクエストを並列実行する
    const responses = await Promise.all(
      Array.from({ length: 5 }, (_, index) =>
        request.post('/api/custom-tags/validate', {
          headers: { Authorization: `Bearer ${accessToken}` },
          data: {
            htmlTemplate: `<div id="test-${index}">{{content}}</div>`,
            cssContent: `.test-${index} { padding: 10px; }`,
          },
        })
      )
    );

    const totalTime = Date.now() - startTime;

    console.log(`Total time for 5 parallel requests: ${totalTime}ms`);
    console.log(`Average time per request: ${totalTime / 5}ms`);

    expect(responses).toHaveLength(5);
    for (const response of responses) {
      expect(
        response.ok(),
        `検証APIの並列呼び出しに失敗しました (status=${response.status()}): ${await response.text()}`
      ).toBe(true);
    }

    // 5つのリクエストが3秒以内に完了することを確認
    expect(totalTime).toBeLessThan(3000);
  });
});

test.describe('カスタムタグ生成UIのパフォーマンス', () => {
  test.skip(!E2E_ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');

  // フィクスチャ情報をbeforeEach/テスト本体/afterEachで受け渡すための変数。
  // Playwrightのワーカープロセスは同時に1テストしか実行せず、ワーカーごとにこのモジュールが
  // 独立して読み込まれるため、モジュールスコープでも並列実行中の他テストと混線しない。
  let fixtureProjectId = 0;
  let fixtureAccessToken = '';

  test.beforeEach(async ({ page, request }) => {
    fixtureProjectId = 0;
    await loginAsAdmin(page);

    // Fixture: 「カスタムタグ管理」タブを確実に開けるよう、専用のプロジェクトを1件作る。
    fixtureAccessToken = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
    fixtureProjectId = await createFixtureProject(request, fixtureAccessToken);
  });

  test.afterEach(async ({ request }) => {
    if (!fixtureProjectId) {
      return;
    }
    await deleteFixtureProject(request, fixtureAccessToken, fixtureProjectId);
  });

  test('Ollamaレスポンス時間が10秒以内であること', async ({ page }) => {
    // 生成はOllamaの応答待ちを含むため、既定の30秒では不足しうる。
    test.setTimeout(90_000);

    await openCustomTagsTab(page, fixtureProjectId);
    const form = generationFormOf(page);
    await expect(form.locator('textarea[name="prompt"]')).toBeVisible();

    await form.locator('textarea[name="prompt"]').fill('シンプルなボタンコンポーネント');
    await form.locator('input[name="tagName"]').fill(`e2e-perf-button-${Date.now()}`);

    // レスポンスタイムを測定する(捨てられたクリックの待ち時間は計測に含めない。issue #778)
    const startTime = await clickGenerateUntilSubmitted(page, form);

    // 「生成中...」の解除は成功表示(生成完了!)かフォーム内のエラー表示のいずれかで判定する
    // (旧テストが待っていた data-testid="loading-indicator" は実装に存在しない)。
    const successHeading = page.getByText('生成完了！');
    const errorMessage = form.locator('p.text-red-600');
    await expect(successHeading.or(errorMessage)).toBeVisible({ timeout: 60000 });

    const responseTime = Date.now() - startTime;
    console.log(`Ollama response time: ${responseTime}ms`);

    // OllamaがE2E実行環境に存在しない場合、計測値は生成時間ではなくエラー検出時間になるため
    // 性能判定の対象にしない(暗黙にpassさせず明示的にスキップする)。
    test.skip(!(await successHeading.isVisible()), 'Ollamaでの生成に失敗したため性能判定をスキップ');

    expect(responseTime).toBeLessThan(10000); // 10秒以内
  });

  test('UIレンダリング性能: タグ画面のページロード時間', async ({ page }) => {
    // Note: Lighthouse スコアの測定はブラウザのdevtoolsが必要なため、
    // ここではページロード時間と主要要素の表示を確認する。
    const tagsUrl = `/projects/${fixtureProjectId}/tags`;

    // 1回目の遷移はNext.js(devモード)のルートコンパイルを含むため、ウォームアップとして計測しない。
    await page.goto(tagsUrl);

    const navigationStart = Date.now();
    await page.goto(tagsUrl);
    const pageLoadTime = Date.now() - navigationStart;

    console.log(`Page load time: ${pageLoadTime}ms`);

    // ページロード時間が3秒以内であることを確認
    expect(pageLoadTime).toBeLessThan(3000);

    // 主要要素がすべて表示されていることを確認
    await page.locator('button:has-text("カスタムタグ管理")').click();
    const form = generationFormOf(page);
    await expect(form.locator('textarea[name="prompt"]')).toBeVisible();
    await expect(form.locator('input[name="tagName"]')).toBeVisible();
    await expect(form.locator('button:has-text("生成")')).toBeVisible();
  });
});
