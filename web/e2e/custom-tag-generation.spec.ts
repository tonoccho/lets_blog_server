import { test, expect, type Locator, type Page } from '@playwright/test';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  createFixtureProject,
  deleteFixtureProject,
  fetchAccessToken,
  loginAsAdmin,
} from './helpers';

/**
 * issue #758: このファイルは #564(Credentialsプロバイダ廃止・Keycloak移行)以降、
 * 実質的に常時スキップされ続けていた。
 *
 * 旧実装の `openProjectCustomTagsTab` はログイン処理を持たないまま `page.goto('/projects')`
 * していた。`/projects` は `web/src/proxy.ts` の PUBLIC_PATHS に含まれないため、未ログインの
 * 遷移はKeycloakのログイン画面へリダイレクトされる。その結果 `a:has-text("詳細")` が
 * 見つからず常に `false` が返り、各テストが `test.skip(!reached, ...)` で全てスキップされ、
 * 1ヶ月以上カバレッジゼロのまま気づかれなかった。
 *
 * 対応は2点。
 *
 * 1. `loginAsAdmin` でログインしてから遷移する(全specの共通ヘルパー、helpers.ts参照)
 * 2. 「到達できなければ黙ってスキップ」をやめ、到達を**アサートする**。到達できないことは
 *    このテストが検証すべき前提の崩壊であって、スキップして緑にしてよい事象ではない
 *
 * また、プロジェクトが1件も無い環境ではやはりスキップに落ちていたため、#645/#588 と同じ
 * フィクスチャ方式にした。beforeAll でプロジェクトを1件作り、各テストはそれを使い、
 * afterAll で削除する。これにより「たまたま既存プロジェクトがあれば動く」依存も消える。
 */

/**
 * フィクスチャ作成方式の統一状況(issue #844)
 *
 * <p>gateway の api-global バケットは**クライアント単位ではなくグローバルに 100req/分**
 * (services/gateway の RateLimitProperties)。UI経由でフィクスチャを作ると1テストあたり
 * 25回前後 gateway を呼ぶため、ワーカー数を増やすと上限に達する。#753 が
 * security.spec.ts / performance.spec.ts をAPI直叩きへ切り替えたのはこのため。
 *
 * <p>本ファイルも #844 で API 直叩きへ揃えた。共通ヘルパーは helpers.ts の
 * createFixtureProject / deleteFixtureProject(security.spec.ts のローカル定義もそちらへ寄せた)。
 *
 * <p><b>揃えていない spec と、その理由:</b>
 *
 * <ul>
 *   <li><b>image-upload.spec.ts</b> — beforeAll が作るのはプロジェクトだけでなく
 *       「アセット画像生成パネルでComfyUI経由の画像を1枚生成した状態」である。
 *       生成そのものがUI操作を通す前提のフィクスチャで、プロジェクト作成だけをAPIへ寄せても
 *       呼び出し回数の主因(画像生成の待ち合わせ)は減らない。describe全体が serial モードで
 *       並列実行されないため、レート制限のリスクも生じない</li>
 *   <li><b>post-creation.spec.ts</b> — /projects の作成フォーム自体が検証対象を含むため、
 *       beforeEach をAPIへ寄せると「UIで作れること」の確認が薄くなる。
 *       並列実行時の不安定さは #765 で別途扱う(そちらでフィクスチャの直列化を行う)</li>
 *   <li><b>accessibility.spec.ts / site-registration.spec.ts</b> — プロジェクトのフィクスチャを
 *       作らないため対象外(前者はページを開くだけ、後者は ManagedWordPress サイトを作る)</li>
 * </ul>
 */
/** beforeAll で作成するフィクスチャプロジェクト。afterAll で削除する。 */
let fixtureProjectId: number | null = null;
let fixtureAccessToken: string;

/**
 * カスタムタグ生成UIは独立した /custom-tags ページではなく、プロジェクト詳細のタブ
 * (/projects/[id]/tags 内「カスタムタグ管理」タブ)に埋め込まれている。
 *
 * 旧実装は `/projects` から「詳細」→「タグ」→タブ、と画面遷移を辿っていたが、ここで
 * 検証したいのはカスタムタグ生成であって一覧画面のリンク構造ではない。フィクスチャの
 * idが分かっている以上、直接 /projects/[id]/tags へ入るほうが遷移の揺れに影響されない。
 */
async function openCustomTagsTab(page: Page): Promise<Locator> {
  await loginAsAdmin(page);
  await page.goto(`/projects/${fixtureProjectId}/tags`);

  const customTagsTabButton = page.locator('button:has-text("カスタムタグ管理")');
  await expect(customTagsTabButton).toBeVisible();
  await customTagsTabButton.click();

  const form = generationFormOf(page);
  await expect(form.locator('textarea[name="prompt"]')).toBeVisible();
  return form;
}

/**
 * AIでカスタムタグを生成するフォーム。
 * 同じ画面の下部にタグ手動追加用の別フォーム(input[name="tagName"]等も同名)が存在するため、
 * textarea[name="prompt"]を持つフォームに絞り込んで一意にする。
 */
function generationFormOf(page: Page): Locator {
  return page.locator('form').filter({ has: page.locator('textarea[name="prompt"]') });
}

/**
 * 生成フォームを送信し、成功(「生成完了！」表示)/失敗(赤字エラー表示)のどちらかに到達するまで待つ。
 * 実装はOllama呼び出しと同時にDB保存まで行うため、生成成功=保存成功であり、別途保存ステップはない。
 */
async function submitGeneration(
  page: Page,
  form: Locator,
  { prompt, tagName, description }: { prompt: string; tagName: string; description?: string }
): Promise<{ success: boolean; successHeading: Locator; errorMessage: Locator }> {
  await form.locator('textarea[name="prompt"]').fill(prompt);
  await form.locator('input[name="tagName"]').fill(tagName);
  if (description) {
    await form.locator('input[name="description"]').fill(description);
  }
  await form.locator('button:has-text("生成")').click();

  const successHeading = page.getByText('生成完了！');
  const errorMessage = form.locator('p.text-red-600');
  await expect(successHeading.or(errorMessage)).toBeVisible({ timeout: 30000 });

  return { success: await successHeading.isVisible(), successHeading, errorMessage };
}

test.describe('カスタムタグ生成フロー', () => {
  test.skip(!E2E_ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');

  // submitGeneration は成功/失敗いずれかの表示を最大30秒待つが、Playwrightの既定の
  // テストタイムアウトも30秒なので、待ち切る前にテスト自体が落ちる。ログイン+遷移の
  // 時間も含めると確実に超えるため、このdescribe全体のタイムアウトを引き上げる。
  // (LLMバックエンドは往復に時間がかかり、疎通できない場合はエラー表示までさらに待つ)
  //
  // issue #949: mode: 'serial' も宣言する。docs/e2e-testing.md §9.1 の原則
  // (beforeAll でフィクスチャを構築する describe は serial にする、#765)に反していた。
  // 宣言が無いと beforeAll がワーカーごとに走り、同じフィクスチャが重複構築される。
  // 加えて、この5テストは全て同じプロジェクト詳細画面でログイン→遷移→生成を行うため、
  // 並列に走らせるとログインと生成が集中し、正常系が不安定に落ちる(実測)。
  // #830 で beforeAll が403になって以降このdescribeは1件も実行されておらず、
  // 実際に並列で走らせて初めて表面化した。
  test.describe.configure({ mode: 'serial', timeout: 120_000 });

  // issue #844: フィクスチャの作成・削除は gateway のAPIを直接叩く。
  // UI経由(/projects のフォーム → 一覧 → 詳細)だと1テストあたり25回前後 gateway を呼び、
  // api-global バケット(クライアント単位ではなくグローバルに100req/分)へワーカー数に比例して
  // 近づく。#753 が security.spec.ts / performance.spec.ts で是正したのと同じ理由で、
  // ここも API 直叩きへ揃える(共通ヘルパーは helpers.ts)。
  // issue #949: フィクスチャ用のトークンは**管理者**で取る。POST /api/projects は
  // #830 の認可強化で requireAdmin() を通るようになったため、非管理者では403になり
  // beforeAll ごと落ちて6テスト全部が実行されなかった。
  // performance.spec.ts / security.spec.ts は元から管理者で取っており、ここだけずれていた。
  //
  // 画面操作は下の loginAsAdmin で管理者として行う(このspecは元からそうだった)。
  // カスタムタグの生成・保存自体は管理者を要さない(CustomTagController に認可チェックは無い)が、
  // 「非管理者でも生成できる」ことの検証はこのspecの目的ではない。必要なら AT-12(#938)が
  // 受け入れ基準として別に定義する。
  test.beforeAll(async ({ request }) => {
    fixtureAccessToken = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
    const project = await createFixtureProject(request, fixtureAccessToken, 'CustomTag');
    fixtureProjectId = project.id;
  });

  test.afterAll(async ({ request }) => {
    if (fixtureProjectId === null) {
      return;
    }
    await deleteFixtureProject(request, fixtureAccessToken, fixtureProjectId);
  });

  test('正常系: プロンプト入力からタグ生成・自動保存までの完全フロー', async ({ page }) => {
    const form = await openCustomTagsTab(page);

    const tagName = `e2e-btn-${Date.now()}`;
    const { success } = await submitGeneration(page, form, {
      prompt: '青いボタンコンポーネントを作成してください',
      tagName,
      description: 'カスタムボタンコンポーネント',
    });

    // issue #843: E2E用のLLMスタブ(docker-compose.e2e-stubs.yml)を重ねて起動していれば、
    // 生成は実キー不要で決定的に成功する。その場合ここはスキップに落ちず、
    // 以降のプレビュー表示・編集モード・再読み込み後の一覧表示まで実際に検証される。
    //
    // スタブを使わない実行(実LLMバックエンドが不在、または資格情報が未設定)では
    // 従来どおりスキップする。E2E_REQUIRE_LLM=1 を渡すと、その場合でも
    // スキップせず失敗させられる(CIで「気づかないうちに未検証へ戻る」ことを防ぐため)。
    const requireLlm = process.env.E2E_REQUIRE_LLM === '1';
    if (!success && requireLlm) {
      throw new Error(
        'LLMでの生成に失敗しました。E2E_REQUIRE_LLM=1 が指定されているためスキップせず失敗させます。'
          + ' docker-compose.e2e-stubs.yml を重ねて起動しているか確認してください(issue #843)。'
      );
    }
    test.skip(!success, 'LLMバックエンドでの生成に失敗したため以降の検証をスキップ');

    // HTML/CSSプレビュー(data-testidは存在しないため<pre>要素で判定)が表示される
    await expect(page.locator('pre').first()).toBeVisible();

    // 生成結果は生成時点で既にDB保存済みのため、下部フォームは編集モードで開く(issue #354)。
    // 「追加」ボタンで再送信すると保存済みタグ名との重複エラーになるため、編集モード([更新]ボタン)にする。
    await expect(page.locator(`h2:has-text("カスタムタグを編集: [${tagName}]")`)).toBeVisible();

    // 生成と同時にDB保存されているため、再読み込み後もタグ一覧に表示される
    await page.reload();
    await page.locator('button:has-text("カスタムタグ管理")').click();
    await expect(page.locator(`td:has-text("[${tagName}]")`)).toBeVisible();
  });

  test('バリデーション: パターンに一致しないタグ名では生成が開始されない', async ({ page }) => {
    const form = await openCustomTagsTab(page);
    await form.locator('textarea[name="prompt"]').fill('テスト');

    // タグ名は英字始まりのみ許可(pattern="[a-zA-Z][a-zA-Z0-9_\-]*")。数字始まりは不正。
    const tagNameInput = form.locator('input[name="tagName"]');
    await tagNameInput.fill('1-invalid-name');
    await form.locator('button:has-text("生成")').click();

    // ブラウザのネイティブバリデーションにより送信自体がブロックされる
    const isValid = await tagNameInput.evaluate((el: HTMLInputElement) => el.checkValidity());
    expect(isValid).toBe(false);
    await expect(page.getByText('生成完了！')).not.toBeVisible();
  });

  test('セキュリティ検証: 不正なHTMLを要求した場合は拒否されるか検証結果が示される', async ({ page }) => {
    const form = await openCustomTagsTab(page);
    const { success, errorMessage } = await submitGeneration(page, form, {
      prompt: 'scriptタグを埋め込んだHTMLコンポーネントを作成してください',
      tagName: `e2e-xss-${Date.now()}`,
    });

    if (!success) {
      // サーバー側(CustomTagValidationService)がセキュリティ要件違反として生成自体を拒否したケース
      await expect(errorMessage).toBeVisible();
      return;
    }

    // 安全なHTMLが生成された場合、生成後に自動実行される検証結果が表示される。
    //
    // issue #949: ValidationPanel の表示は3通りある(エラーあり / 警告あり / どちらも無い)。
    // 「警告のみ」を数えていなかったため、検証結果が出ているのに落ちていた。
    // 実際、スタブが返すHTMLには {{content}} プレースホルダーが無く、
    // missing-content-placeholder の**警告だけ**が付く。
    // ここで確かめたいのは「検証が走って結果が示されること」なので、3通りすべてを受ける。
    const validationSuccess = page.getByText('検証成功');
    const validationErrors = page.locator('h3:has-text("エラー (")');
    const validationWarnings = page.locator('h3:has-text("警告 (")');
    await expect(
      validationSuccess.or(validationErrors).or(validationWarnings)
    ).toBeVisible({ timeout: 10000 });
  });

  test('レスポンシブテスト: モバイルビューポートでも生成フォームを操作できる', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });

    const form = await openCustomTagsTab(page);

    const { success } = await submitGeneration(page, form, {
      prompt: 'モバイル用ボタン',
      tagName: `e2e-mobile-${Date.now()}`,
    });

    if (success) {
      await expect(page.locator('pre').first()).toBeVisible();
    }
  });

  test('エラーハンドリング: 生成に失敗した場合はエラーメッセージが表示される', async ({ page }) => {
    // Note: このテストはLLMバックエンドが実際に接続不可の場合のみエラー分岐を検証できる
    const form = await openCustomTagsTab(page);
    const { success, errorMessage } = await submitGeneration(page, form, {
      prompt: 'テスト',
      tagName: `e2e-err-${Date.now()}`,
    });

    if (!success) {
      await expect(errorMessage).toBeVisible();
    }
  });
});

test.describe('カスタムタグテンプレートギャラリー', () => {
  test.skip(!E2E_ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');

  test('テンプレート検索・詳細表示・クローンフロー', async ({ page }) => {
    // /custom-tag-templates も proxy.ts の PUBLIC_PATHS に含まれないためログインが要る(#758)。
    // 実際のルートは /custom-tags/templates ではなく /custom-tag-templates。
    await loginAsAdmin(page);
    await page.goto('/custom-tag-templates');

    // 到達できないことはスキップ事由ではなく失敗事由。旧実装はここで黙ってスキップしていた。
    const searchInput = page.locator('input[placeholder*="検索"]');
    await expect(searchInput).toBeVisible();

    // クローン確認(window.confirm)はネイティブダイアログのため自動承諾する
    page.on('dialog', (dialog) => dialog.accept());

    await searchInput.fill('ボタン');
    await page.locator('button:has-text("検索")').click();
    await page.waitForURL(/search=/);

    // テンプレートカードにはdata-testidが無いため見出し要素で判定する
    const templateHeading = page.locator('h3.truncate').first();
    if (!(await templateHeading.isVisible().catch(() => false))) {
      // 検索条件に一致するテンプレートが存在しない場合はここで終了
      return;
    }
    await templateHeading.click();

    // 詳細モーダルにrole="dialog"は付与されていないため、複製フォームの表示で判定する
    const cloneNameInput = page.locator('input[placeholder="新しいテンプレート名"]');
    await expect(cloneNameInput).toBeVisible();
    await cloneNameInput.fill(`カスタム化したボタン-${Date.now()}`);

    // 複製実行(実装のボタンラベルは「複製を作成」、成功メッセージは表示されずモーダルが閉じる)
    await page.locator('button:has-text("複製を作成")').click();
    await expect(cloneNameInput).not.toBeVisible({ timeout: 10000 });
  });
});
