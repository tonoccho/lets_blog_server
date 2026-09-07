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
 * issue #938 (AT-12): カスタムタグ生成・検証・テンプレートギャラリーの各テストは
 * `e2e/features/custom-tag/` の受け入れシナリオへ移行し、このファイルから削除した。
 *
 * | 移行前のテスト | 移行先 |
 * | --- | --- |
 * | 正常系: プロンプト入力からタグ生成・自動保存までの完全フロー | `generation.feature` › プロンプトからタグを生成すると、検証を通過した内容が自動保存される |
 * | バリデーション: パターンに一致しないタグ名では生成が開始されない | `generation.feature` › 命名規則に反するタグ名では生成が開始されない |
 * | セキュリティ検証: 不正なHTMLを要求した場合は拒否されるか検証結果が示される | `generation.feature` › 危険なHTMLを含むカスタムタグは検証で拒否される / 生成結果の検証結果が画面に示される |
 * | エラーハンドリング: 生成に失敗した場合はエラーメッセージが表示される | `generation.feature` › 生成に失敗したときエラーが表示される |
 * | テンプレート検索・詳細表示・クローンフロー | `templates.feature` › テンプレートを複製すると、自分のプロジェクトに独立した複製が作られる |
 *
 * **残しているのはレスポンシブテスト1件だけ**である。これはブラウザ・ビューポート差の
 * 検証であって、カスタムタグ機能の受け入れ基準ではない。担当は AT-18 (#944) で、
 * 移行先も同Issueが決める(docs/ACCEPTANCE_CRITERIA.md AC-UX-007)。
 */

/**
 * issue #758: このファイルは #564(Credentialsプロバイダ廃止・Keycloak移行)以降、
 * 実質的に常時スキップされ続けていた。
 *
 * 旧実装の `openProjectCustomTagsTab` はログイン処理を持たないまま `page.goto('/projects')`
 * していた。`/projects` は `apps/web/src/proxy.ts` の PUBLIC_PATHS に含まれないため、未ログインの
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

test.describe('カスタムタグ生成フォームのレスポンシブ表示', () => {
  test.skip(!E2E_ADMIN_PASSWORD, 'E2E_ADMIN_PASSWORDが未設定のためスキップ');

  // submitGeneration は成功/失敗いずれかの表示を最大30秒待つが、Playwrightの既定の
  // テストタイムアウトも30秒なので、待ち切る前にテスト自体が落ちる。ログイン+遷移の
  // 時間も含めると確実に超えるため、このdescribe全体のタイムアウトを引き上げる。
  // (LLMバックエンドは往復に時間がかかり、疎通できない場合はエラー表示までさらに待つ)
  //
  // issue #949: mode: 'serial' も宣言する。docs/e2e-testing.md §9.1 の原則
  // (beforeAll でフィクスチャを構築する describe は serial にする、#765)に反していた。
  // 宣言が無いと beforeAll がワーカーごとに走り、同じフィクスチャが重複構築される。
  // #938 で残るテストは1件になったが、beforeAll を持つ以上この宣言は引き続き要る。
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

});
