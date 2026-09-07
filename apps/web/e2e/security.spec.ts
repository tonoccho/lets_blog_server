import { test, expect, type Dialog, type Locator, type Page } from '@playwright/test';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  createFixtureProject,
  deleteFixtureProject,
  fetchAccessToken,
  loginAsAdmin,
} from './helpers';

/**
 * issue #753: このファイルは、独立ページとして存在しない `/custom-tags` /
 * `/custom-tags/templates` と、ブラウザから無認証で叩ける前提の
 * `/api/custom-tags/validate` を対象にした古いテストだった。検証している観点
 * (XSS/CSSインジェクション検出・CSRF保護・認可・SQLインジェクション耐性・入力サニタイズ)は
 * そのままに、現在の構成へ合わせて書き直している。
 *
 *   - カスタムタグ生成UIはプロジェクト詳細の「タグ」ページ(/projects/{id}/tags)の
 *     「カスタムタグ管理」タブ。requireAdminSession() で保護されているため
 *     helpers.ts の loginAsAdmin でログインしてから遷移する。
 *   - テンプレートギャラリーの実際のルートは `/custom-tag-templates`。
 *   - サニタイズテスト用のフィクスチャ(プロジェクト1件)は gateway のAPIを直接呼んで
 *     作成・削除する(理由は createFixtureProject() のコメント参照)。
 *
 * issue #938 (AT-12): このうち**カスタムタグ領域に属する2つの describe** は
 * `e2e/features/custom-tag/` の受け入れシナリオへ移行し、このファイルから削除した。
 *
 * | 移行前のテスト | 移行先 |
 * | --- | --- |
 * | XSS脆弱性チェック: scriptタグ / イベントハンドラ / JavaScriptプロトコルの検出 | `generation.feature` › 危険なHTMLを含むカスタムタグは検証で拒否される(3例) |
 * | CSS インジェクション検出: behavior プロパティ | `generation.feature` › CSSのインジェクションを含むカスタムタグは検証で拒否される |
 * | 認可テスト: 非adminユーザーはテンプレートを削除できないこと | `templates.feature` › 非adminは他人のテンプレートを削除できない |
 *
 * **残した2つの describe は横断的品質(AT-17 / #943)の担当範囲**である。
 * CSRF保護・SQLインジェクション耐性・入力サニタイズはカスタムタグ画面を舞台にしているが、
 * 受け入れ基準はカスタムタグ機能ではなくアプリ全体の防御(AC-XC-008〜010)であり、
 * 移行先は #943 が決める。
 */

/** 「カスタムタグ管理」タブ内のAI生成フォーム。同じ画面の手動追加フォームと区別する。 */
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

test.describe('Web UIのセキュリティ', () => {
  test.skip(!E2E_ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');

  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
  });

  test('CSRF保護確認: トークンが含まれていることを確認', async ({ page }) => {
    // 画面からのカスタムタグ操作はブラウザが直接 /api/custom-tags/* を叩くのではなく、
    // Next.jsのServer Action(app/custom-tags/actions.ts)経由で行われるため、
    // 旧テストが見ていた X-CSRF-Token ヘッダーはそもそも送出されない。
    // 現在のCSRF対策はNextAuthのCSRFトークン(ダブルサブミットCookie)であり、その存在を確認する。
    const response = await page.request.get('/api/auth/csrf');
    expect(response.ok()).toBe(true);

    const body = (await response.json()) as { csrfToken?: string };
    expect(body.csrfToken).toBeTruthy();

    // Cookie名はNEXTAUTH_URLがhttpsのとき __Host- が付く(next-authの既定動作)。
    const cookies = await page.context().cookies();
    const csrfCookie = cookies.find((cookie) => cookie.name.endsWith('next-auth.csrf-token'));
    expect(csrfCookie, 'NextAuthのCSRFトークンCookieが見つかりません').toBeDefined();
    expect(csrfCookie!.value).toBeTruthy();
    expect(csrfCookie!.httpOnly).toBe(true);
    expect(csrfCookie!.sameSite).toBe('Lax');
  });

  test('SQLインジェクション対策: 特殊文字を含むクエリが安全に処理されること', async ({ page }) => {
    // 生成フォームのタグ名は pattern="[a-zA-Z][a-zA-Z0-9_\-]*" でブラウザ側から拒否されるため、
    // 実際にDBへ文字列が渡る自由入力(テンプレートギャラリーの検索)で検証する。
    const sqlInjectionPayload = "'; DROP TABLE custom_tags; --";

    await page.goto('/custom-tag-templates');
    const searchInput = page.locator('input[placeholder*="検索"]');
    await expect(searchInput).toBeVisible();

    await searchInput.fill(sqlInjectionPayload);
    await page.locator('button:has-text("検索")').click();
    await page.waitForURL(/search=/);

    // 検索自体がエラーにならず、ページが正常に描画されること
    await expect(page.locator('h1:has-text("カスタムタグテンプレート")')).toBeVisible();

    // テーブルが破損していないこと(通常の一覧が引き続き取得できる)
    await page.goto('/custom-tag-templates');
    await expect(page.locator('h1:has-text("カスタムタグテンプレート")')).toBeVisible();
    await expect(page.locator('input[placeholder*="検索"]')).toBeVisible();
  });
});

test.describe('カスタムタグ生成の入力サニタイズ', () => {
  test.skip(!E2E_ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');

  // フィクスチャ情報をbeforeEach/テスト本体/afterEachで受け渡すための変数。
  // Playwrightのワーカープロセスは同時に1テストしか実行せず、ワーカーごとにこのモジュールが
  // 独立して読み込まれるため、モジュールスコープでも並列実行中の他テストと混線しない。
  let fixtureProjectId = 0;
  let fixtureAccessToken = '';

  test.beforeEach(async ({ page, request }) => {
    fixtureProjectId = 0;
    await loginAsAdmin(page);

    // Fixture: 「カスタムタグ管理」タブを確実に開けるよう、専用のプロジェクトを1件作る
    // (プロジェクトが無い環境で無検証のままpassしないようにする。issue #645)。
    fixtureAccessToken = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
    fixtureProjectId = (await createFixtureProject(request, fixtureAccessToken, 'Security')).id;
  });

  test.afterEach(async ({ request }) => {
    if (!fixtureProjectId) {
      return;
    }
    await deleteFixtureProject(request, fixtureAccessToken, fixtureProjectId);
  });

  test('入力サニタイズ: ユーザー入力が正しくサニタイズされること', async ({ page }) => {
    // 生成はOllamaの応答待ちを含むため、既定の30秒では不足しうる。
    test.setTimeout(90_000);

    // XSSが成立した場合に確実に落とせるよう、ダイアログの発生自体を検出する。
    // page.on()で登録したリスナーはテスト本体を抜けてもpageに残り続ける。残したままにすると
    // 他フックが出す window.confirm(例: DeleteProjectButton.tsx の削除確認)まで
    // このリスナーがdismissしてしまい、後片付けが黙って失敗する(issue #753で実際に発生した)。
    // 検出範囲をテスト本体に限定するため、finallyで必ず page.off() する。
    let dialogFired = false;
    const detectDialog = (dialog: Dialog) => {
      dialogFired = true;
      return dialog.dismiss();
    };
    page.on('dialog', detectDialog);

    try {
      await openCustomTagsTab(page, fixtureProjectId);

      const form = generationFormOf(page);
      await expect(form.locator('textarea[name="prompt"]')).toBeVisible();

      await form.locator('textarea[name="prompt"]').fill('テスト');
      await form.locator('input[name="tagName"]').fill(`e2e-sanitize-${Date.now()}`);
      await form.locator('input[name="description"]').fill('<img src=x onerror="alert(\'xss\')">');
      await clickGenerateUntilSubmitted(page, form);

      const successHeading = page.getByText('生成完了！');
      const errorMessage = form.locator('p.text-red-600');
      await expect(successHeading.or(errorMessage)).toBeVisible({ timeout: 60000 });

      // 「入力したHTMLが実行・要素化されていないこと」は生成の成否に関わらず成立すべき不変条件
      // なので、下のskipより前に必ず検証する(skipの後ろに置くと、Ollamaが無い環境では
      // XSSの検証が1つも実行されないまま毎回skipされてしまう)。
      expect(dialogFired, 'スクリプトが実行されダイアログが表示されました').toBe(false);
      expect(await page.locator('img[onerror]').count()).toBe(0);

      // OllamaがE2E実行環境に存在しない場合は生成結果が表示されないため、
      // 生成結果そのものに対する検証だけをスキップする。
      test.skip(
        !(await successHeading.isVisible()),
        'Ollamaでの生成に失敗したため生成結果の検証をスキップ'
      );

      // 生成結果のHTML/CSSプレビュー(実装は<pre>で表示、data-testidは無い)にも
      // イベントハンドラが混入していないこと
      const previewHtml = await page.locator('pre').first().innerHTML();
      expect(previewHtml).not.toContain('onerror');
    } finally {
      page.off('dialog', detectDialog);
    }
  });
});
