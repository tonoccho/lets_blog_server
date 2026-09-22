import { defineConfig, devices } from '@playwright/test';
import { defineBddProject } from 'playwright-bdd';

/**
 * issue #588: 実行時間対策。バックエンドがマルチサービス化し、フィクスチャ構築
 * (ManagedWordPressの自動構築・ComfyUIでの画像生成等)を伴うspecが増えたため、
 * 全specを5ブラウザで回すと実行時間が現実的でなくなった。
 *
 * ブラウザ差が結果に影響するのは「画面のレンダリング・アクセシビリティ・認証リダイレクト」
 * 程度であり、バックエンド横断のシナリオ(サイト登録・記事公開・画像生成・縮退表示)は
 * ブラウザを変えても同じ経路を通る。そこで:
 *   - chromium         : 全specを実行する(既定の網羅ブラウザ)
 *   - その他のブラウザ : ブラウザ差が意味を持つspecのみに絞る
 * とし、実行時間を抑えつつクロスブラウザ検証の意図は維持する。
 */
// auth-flow.spec.ts は AT-3(#929)で features/auth/ へ全移行し削除した。
// accessibility.spec.ts は AT-18(#944)で features/ui-quality/ へ全移行し削除したため、
// この配列は現在どのファイルにも一致しない(firefox/webkit/Mobile系プロジェクトは0件で
// 通過する)。クロスブラウザの受け入れシナリオは
// `at-cross-browser-firefox` / `at-cross-browser-webkit`(下記)が担う。
const CROSS_BROWSER_SPECS = [/accessibility\.spec\.ts/];

/**
 * issue #926 (AT-0): 受け入れテストは Gherkin(`.feature`)で記述し、playwright-bdd で
 * Playwright のテストへ変換して実行する。ランナーを Playwright のままにすることで、
 * 下の globalSetup(スタックのhealthy待ち)・ignoreHTTPSErrors・trace/video・retries・
 * ワーカー制御を、受け入れテストでもそのまま使える。
 *
 * 生成物は apps/web/.features-gen/ に出る。testDir('./e2e')の外へ置くことで、chromium 等の
 * 既存 spec 用プロジェクトが生成物を拾わないようにしている(生成物の二重実行を防ぐ)。
 * .gitignore / eslint.config.mjs / tsconfig.json / jest.config.ts の4か所の除外も併せて
 * 更新すること(#848 と同型)。jest.config.ts だけは名指しではなく testMatch で拾う対象を
 * 列挙する形で除外している(理由と、roots で src/ に閉じる案を採らなかった理由は
 * 当該コメントと #994 を参照)。
 * 4か所が揃っていることは scripts/test_bddgen_output_is_excluded.py が検査する。
 *
 * issue #945 (AT-19): 受け入れテストは「まっさらな状態」から**段階順に**実行する。
 *
 *   reset(スクリプト) → at-setup → at-seed → at-provision → at-main
 *
 * 段階は Playwright のプロジェクト間 `dependencies` で表す。前段が失敗すると後続は
 * **実行されない**(Playwright は依存プロジェクトが落ちた場合、依存元をスキップとして
 * 報告する)。大量の失敗で原因が埋もれるのを避けるための設計であり、AC がこれを要求している。
 *
 * 段階の割り当てはタグで行う。`@stage:setup` / `@stage:provision` を付けないシナリオは
 * すべて at-main に入る。
 */
const BDD_COMMON = {
  features: 'e2e/features/**/*.feature',
  // ステップ定義とその依存(support/)を読み込む。support/ は既存 helpers.ts の再エクスポート。
  steps: ['e2e/steps/**/*.ts', 'e2e/support/**/*.ts'],
  featuresRoot: 'e2e/features',
  // `.feature` は日本語キーワード(機能:/シナリオ:/前提/もし/ならば)で書く。
  language: 'ja',
};

/** 段階1: 初回セットアップ(AT-3 / #929)。リセット直後の「ユーザー0人」に対して走る。 */
const atSetup = defineBddProject({
  ...BDD_COMMON,
  name: 'at-setup',
  outputDir: '.features-gen/at-setup',
  tags: '@stage:setup',
});

/** 段階3: WordPress のプロビジョニング(AT-5 / #931)。利用者の指示により他より先に通す。 */
const atProvision = defineBddProject({
  ...BDD_COMMON,
  name: 'at-provision',
  outputDir: '.features-gen/at-provision',
  tags: '@stage:provision',
});

/**
 * issue #1318: GPU の無いホストでのリリース検証(`scripts/release-verify-tag.py`)は
 * `AT_EXCLUDE_REQUIRES_GPU=1` を設定して `web-test-at-clean` を実行する。このときだけ
 * `@requires-gpu`(実機 `lbs-comfyui` が無いと通らないシナリオ)を生成時タグ式で除外する
 * (`--grep-invert` は依存プロジェクトを絞り込まないため使えない。Readiness評価で実測)。
 * 未設定(通常の `test:at:clean` / `test:at`)では今までどおり全シナリオが対象になる。
 */
const excludeRequiresGpu = process.env.AT_EXCLUDE_REQUIRES_GPU === '1' ? ' and not @requires-gpu' : '';

/** 段階4: それ以外すべて。@destructive は含めない(下の at-destructive が最後にまとめて実行する)。
 * `@stub-isolation:llm`(下の at-llm-exclusive)、`@account-isolation:timezone`
 * (下の at-timezone-exclusive、issue #1374)も除く。 */
const atMain = defineBddProject({
  ...BDD_COMMON,
  name: 'at-main',
  outputDir: '.features-gen/at-main',
  tags:
    'not @stage:setup and not @stage:provision and not @destructive'
    + ' and not @stub-isolation:llm and not @account-isolation:timezone'
    + excludeRequiresGpu,
});

/**
 * issue #1188: `llm-stub`(共有・単一プロセス)へ実際にトラフィックを送る、または
 * `POST /__control/force` でその共有状態を仕込む/読む、全シナリオの専用レーン。
 *
 * ## レースの原因
 *
 * `playwright.config.ts` は `fullyParallel: true` で、`@mode:serial`
 * (`node_modules/playwright-bdd/dist/generate/specialTags.js` の `extractMode`)は
 * **同一 .feature ファイル内**のシナリオしか直列化しない。そのため
 * `ai/resilience.feature`(`POST /__control/force` で429/遅延を仕込み、
 * `stubRequestCount('llm')` で受信件数も検証する)が `at-main` の一部として動くと、
 * 同じ `llm-stub` へ通常系のリクエストを送る**別ファイル**
 * (`ai/generation.feature` 等)が別workerで並列に走り、
 *
 *   1. 仕込んだ429/遅延を横取りする(2026-09-08 に実測: `ai/generation.feature`
 *      が502、`ai/resilience.feature` の429シナリオが200)。
 *   2. `stubRequestCount('llm')` の前後比較(「LLMスタブは一度も呼び出されていない」)も
 *      同じ理由で壊れる — 注入ではなく受信件数だけを見るシナリオも被害者になる
 *      (2026-09-19 のリリース検証で実測: 15 → 16)。
 *
 * `infra/e2e-stubs/lib/stub.js` の状態(`forced` / `requests`)はスタブプロセス単位で
 * ただ1つしかなく、テストごとの分離トークンを持たない。相関IDをサービス境界
 * (gateway → ai-service)越しに通す変更はプロダクションコードの変更を要するため、
 * このIssueのScope(playwright.config.ts / infra/e2e-stubs/lib/stub.js の注入機構)外
 * ―― 実際に採ったのは、`llm-stub` へ触れる全ファイルを1つのプロジェクトへ集め、
 * `workers: 1` で内部を完全直列化する案。
 *
 * ## `@stub-isolation:llm` を付けたファイル
 *
 *   - `ai/generation.feature`         通常系(下書き・壁打ち・セクション・画像プロンプト)
 *   - `ai/resilience.feature`         注入元(429・タイムアウト)+ 受信件数の前後比較
 *   - `ai/model-selection.feature`    通常系(モデル選択の反映は履歴なので単体では耐性があるが、
 *                                     resilience.feature の仕込みを横取りしうる側でもある)
 *   - `ai/review-step-suggestions.feature` 通常系(`/api/projects/{id}/ai/review-steps/**`)
 *   - `ai/tag-and-proofread.feature`  通常系(`/api/ai/tags` `/api/ai/proofread`)
 *   - `ai/web-search.feature`         通常系(`/api/projects/{id}/article-plan/chat` が llm を使う)
 *   - `stubs/external-stubs.feature`  llmを含む全スタブへ決定性・注入の直接プローブを送る
 *
 * `ai/authorization.feature`・`ai/review-step-model-settings.feature` は設定CRUDのみで
 * 実際の生成呼び出しが無いため対象外。`ai/generation-job.feature` は既に `@destructive` で
 * 独立実行される(at-destructiveは`at-main`同様このプロジェクトの完了後に走る、下記参照)。
 * `platform/system-settings.feature` のLLM切替シナリオは既存の `@destructive` で
 * 同じ理由から既に単独実行されており、変更不要。
 *
 * `at-provision` に依存するのみ(`at-main` には依存しない)ので、`at-main` の無関係な
 * シナリオとは並列に走る — 全体の実行時間はほぼ増えない。増えるのはこのレーン内の
 * シナリオが互いに直列化される分だけで、`at-main` 全体の所要時間の方が長い前提であれば
 * 実質的なコストはない。
 *
 * GA/AdSenseスタブ(`ga-stub` / `adsense-stub`)にも同型の構造的リスクが実在する
 * (`analytics/report-failures.feature` が注入・`analytics/dashboard-report.feature` 等が
 * 通常系で同じスタブを読む)ことをissue #1188で確認済みだが、対処はこのIssueのAcceptance
 * Criteriaの対象外(確認のみが要件)。対処はissue #1372で追跡する。
 * `docs/ACCEPTANCE_TESTING.md` §9 に記録した。
 */
const atLlmExclusive = defineBddProject({
  ...BDD_COMMON,
  name: 'at-llm-exclusive',
  outputDir: '.features-gen/at-llm-exclusive',
  tags: '@stub-isolation:llm' + excludeRequiresGpu,
});

/**
 * issue #1374: 共有の管理者アカウント(`E2E_ADMIN_EMAIL`)の個人設定TZ
 * (`PATCH /api/identity/me/preferences`)を書き換える全シナリオの専用レーン。
 * #1188(`at-llm-exclusive`、上記)と同型の構造的問題への、同型の対処。
 *
 * ## レースの原因
 *
 * `panel-timezone-hydration.feature`(issue #1362/#1363/#1364/#1366、18シナリオ)・
 * `media/image-gallery.feature`・`ui-quality/internationalization.feature`は、いずれも
 * 同じ共有管理者アカウントの個人設定TZを直接書き換える(`panelTimezone.steps.ts`の
 * 「個人設定のタイムゾーンを未設定にする」、`media.steps.ts`の「個人設定のタイムゾーンを
 * 「X」に変更する」、`uiQuality.steps.ts`の「個人設定のタイムゾーンをAmerica/New_Yorkに
 * 変更する」)。`fullyParallel: true`の下で複数ワーカーが同時にこれを書き換えると、
 * 片方が変更した直後にもう片方が上書きし、期待した換算値と実際の表示がずれる
 * (2026-09-21実測: このファイル単体でも14/18が失敗。回ごとに失敗数が変わるのが
 * 競合の証拠)。
 *
 * ## `@mode:serial` ではなく専用プロジェクトを選んだ理由
 *
 * `panel-timezone-hydration.feature`は173・182・219・228行目に`@mode:serial`(素の
 * シナリオへのタグ)を持っていたが、**生成物に一切反映されていなかった**:
 * playwright-bddの`Formatter.test()`(`node_modules/playwright-bdd/dist/generate/
 * formatter.js:82-106`)は、素のシナリオを`describe.configure`で包む条件を
 * `specialTags.retries !== undefined`にしており、`@mode:serial`(`specialTags.mode`)
 * だけでは満たさない。`describeConfigure`(`formatter.js:156`)に実際に到達するのは
 * 機能ファイル自身のタグ(`file.js`の`renderDescribe`→`formatter.describe`)か
 * シナリオアウトライン(`renderScenarioOutline`→同じく`formatter.describe`)の場合のみで、
 * このファイルにアウトラインは無い。したがって仮に4シナリオ間だけを直列化できたとしても、
 * 同じ管理者設定を書き換える**別ファイル**(`image-gallery.feature`・
 * `internationalization.feature`)との衝突は防げない(`@mode:serial`はファイル内限定、
 * `docs/ACCEPTANCE_TESTING.md` §9)。ファイル単位の直列化を3ファイル分積み上げるより、
 * `at-llm-exclusive`と同じ「専用プロジェクト+`workers: 1`」の方が構造がシンプルで、
 * 3ファイルをまたぐ衝突も一度に解消できる。
 *
 * ## `@account-isolation:timezone` を付けた範囲
 *
 *   - `ui-quality/panel-timezone-hydration.feature`(機能ファイル全体、issue #1374)
 *     18シナリオ全てが個人設定TZを書き換えるため、ファイル単位のタグにした。
 *     173・182・219・228行目にあった効果の無い`@mode:serial`は削除した——このプロジェクトの
 *     `workers: 1`により、ファイル全体(WordPress公開を伴う4シナリオを含む)が既に
 *     完全直列化されるため、シナリオ単位の直列化は不要になった。
 *   - `media/image-gallery.feature`の「ブラウザとプロフィールのタイムゾーンが異なっていても
 *     生成画像ギャラリーはハイドレーションエラー無く開ける」(1シナリオのみ)
 *   - `ui-quality/internationalization.feature`の「日付・時刻が利用者のタイムゾーン設定に
 *     従って表示される」(1シナリオのみ)
 *
 * 後者2ファイルは他の大半のシナリオが個人設定TZに触れないため、ファイル単位ではなく
 * **シナリオ単位**でタグを付けた。シナリオ単位のタグは(`@mode:serial`と異なり)
 * タグ式によるプロジェクトの振り分けには問題無く反映される——`renderTest`
 * (`file.js`)は`pickle.tags`(シナリオ自身のタグ)をそのままテストのタグとして使い、
 * `isSkippedByTagsExpression`もこれを見るため、この除外・振り分けの仕組みは
 * `describe.configure`のような「機能ファイル/アウトラインだけ」という制限を持たない。
 *
 * `at-provision`に依存するのみ(`at-main`には依存しない)ので、`at-main`の無関係な
 * シナリオとは並列に走る——全体の実行時間はほぼ増えない。
 */
const atTimezoneExclusive = defineBddProject({
  ...BDD_COMMON,
  name: 'at-timezone-exclusive',
  outputDir: '.features-gen/at-timezone-exclusive',
  tags: '@account-isolation:timezone' + excludeRequiresGpu,
});

/**
 * 段階5: `@destructive` のシナリオ(issue #929)。
 *
 * 環境の状態を壊すシナリオを**最後に、それだけで**実行する。
 * 例えば「無効化したユーザーの発行済みトークンが拒否される」は共有の合成アカウントを
 * 一時的に無効化する。これを他のシナリオと並列に走らせると、同じアカウントで
 * ログインしている無関係なシナリオが巻き添えで落ちる(実測で3件が落ちた)。
 *
 * Playwright はファイルをまたぐ直列化の手段を持たない(`@mode:serial` は同一ファイル内だけ)。
 * 段階を1つ足して「この段階が走るときは他に誰も走っていない」状態を作るのが、
 * この構成で表現できる唯一の確実な隔離である。
 */
const atDestructive = defineBddProject({
  ...BDD_COMMON,
  name: 'at-destructive',
  outputDir: '.features-gen/at-destructive',
  tags: '@destructive and not @stage:setup and not @stage:provision' + excludeRequiresGpu,
});

/**
 * クロスブラウザ検証専用の段階(issue #944 / AT-18)。
 *
 * `CROSS_BROWSER_SPECS`(下記)は Playwright直書きの `.spec.ts` を対象にした古い仕組みで、
 * `.feature` から生成される spec は `testDir('./e2e')` の意図的に外(`.features-gen/`)に
 * 出るため、`firefox` / `webkit` プロジェクト(下記、`testDir` 未指定=`./e2e`)からは
 * 元々見えない。`accessibility.spec.ts` を `.feature` へ全面移行するにあたり、
 * `@stage:cross-browser` タグを付けたシナリオだけを対象にする専用の bdd プロジェクトを
 * ブラウザごとに用意する。`at-provision` に依存させ、合成アカウントが発行済みの状態で走らせる
 * (chromium 版はタグ除外していないので `at-main` でも実行される。ここは追加のブラウザ差分)。
 */
const atCrossBrowserTags = '@stage:cross-browser';
const atCrossBrowserFirefox = defineBddProject({
  ...BDD_COMMON,
  name: 'at-cross-browser-firefox',
  outputDir: '.features-gen/at-cross-browser-firefox',
  tags: atCrossBrowserTags,
});
const atCrossBrowserWebkit = defineBddProject({
  ...BDD_COMMON,
  name: 'at-cross-browser-webkit',
  outputDir: '.features-gen/at-cross-browser-webkit',
  tags: atCrossBrowserTags,
});

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  // 既定はCIでも1ワーカー(バックエンドを共有するため)。実行時間を優先する環境では
  // E2E_WORKERSで上書きできる。各specのフィクスチャは一意な名前(タイムスタンプ+乱数)で
  // 作られるため、並列度を上げてもデータは衝突しない。
  workers: process.env.E2E_WORKERS ? Number(process.env.E2E_WORKERS) : process.env.CI ? 1 : undefined,

  // 1シナリオあたりのテスト予算(issue #1376)。
  //
  // これまで未設定で、Playwrightの既定30秒がそのまま使われていた。identity系のシナリオは
  // ほぼすべてが使い捨てのKeycloakユーザーを新規に作り(管理者トークン取得 +
  // POST /api/users + kcadmのdocker exec数回)、そのうえで対話ログインを経るため、
  // 既定の並列度では30秒に収まらない。
  //
  // 実測(2026-09-22、develop、`identity/`配下29シナリオ、既定の並列度=4ワーカー):
  //
  //   - 7件が30秒台で時間切れ(avatar-upload ×5、roles-and-permissions ×1、
  //     timezone-override ×1)
  //   - 「成功」側にも 26.5s / 25.3s / 23.6s が並ぶ。余裕が数秒しかなく、負荷が少し
  //     増えるだけで転ぶ
  //   - `--timeout=120000` を与えて同じ実行をすると、時間切れしていたシナリオは
  //     最大 39.6秒で成功する
  //   - 直列実行(`--workers=1`、切り分け専用)では同じシナリオが 10〜15秒。並列にすると
  //     2.5〜3倍に伸びる
  //   - `--timeout=60000` でも **29/29 成功**(2m51s、最遅シナリオ 40.3秒)
  //
  // したがってこのスライスに限れば **60秒でも足りる**。最初この行のコメントは「60秒では
  // 同じ問題を繰り返す」と書いていたが、それは計測せずに書いた主張で、上の実測に否定された。
  //
  // それでも90秒を採るのは、**計測したのが `identity/` 配下29シナリオという一部分だけ**
  // だからである。リリース検証が回すのは at-main 全体(約300シナリオ)で、ワーカーの取り合いは
  // これより厳しくなる。そこでの余裕は測っていない。60秒は今回の最遅 40.3秒に対して1.49倍で、
  // 元の失敗(30秒に対して 26.5s / 25.3s / 23.6s = 1.13〜1.27倍)より確かに広いが、
  // 未計測の負荷差を吸収できる保証は無い。90秒なら2.23倍で、リポジトリ内で既に使われている
  // `@timeout:90000`(`platform/vscode-extension.feature`)とも値が揃う。
  //
  // これは**計測に基づく予算の調整であって、失敗の隠蔽ではない**。アサーションは一切
  // 変えていない。本当にハングするコードは、30秒ではなく90秒かけて同じように失敗する。
  //
  // **代償**: この `timeout` はトップレベル設定で、`projects` のどれも上書きしていないため
  // **全プロジェクト**(at-setup / at-seed / at-provision / at-main / at-llm-exclusive /
  // at-timezone-exclusive / at-destructive / クロスブラウザ系)に効く。30秒で落ちていたはずの
  // 将来の性能退行が、90秒なら通ってしまう範囲がスイート全体に広がる。Playwright には
  // ディレクトリ単位で既定予算を変える手段が無く、そのためだけに専用プロジェクトを増やすのは
  // 割に合わないと判断して、この代償を受け入れた。identity 以外のレーンは、旧30秒の天井付近に
  // いるシナリオが観測されなかったため個別には計測していない。
  //
  // シナリオ/フィーチャ単位の`@timeout:`タグ(90000 / 180000 / 300000 / 600000)は
  // 従来どおりこの値を上書きする。
  timeout: 90_000,
  reporter: 'html',

  // テスト開始前に docker compose の全サービスがhealthyになるまで待ち、公開URLとKeycloakへの
  // 疎通を確認する(#588)。終了後はE2E_DB_CLEANUP=1のときのみ全スキーマの後片付けを行う。
  globalSetup: './e2e/global-setup.ts',
  globalTeardown: './e2e/global-teardown.ts',

  use: {
    // issue #564でKeycloak(Authorization Code + PKCE)へ移行して以降、コールバックURLは
    // Keycloakクライアント(infra/keycloak/realm-export.jsonのletsblog-web)に
    // https://localhost/api/auth/callback/keycloak として固定登録されている。
    // このURLは実行時に差し替えられないため、E2Eも`npm run dev`(http://localhost:3000)を
    // 別途起動するのではなく、docker composeのweb+reverse-proxy+keycloakスタック
    // (https://localhost)へ直接向ける必要がある。証明書はreverse-proxyの自己署名証明書
    // (scripts/generate-certs.sh)なのでignoreHTTPSErrorsが必要。
    baseURL: 'https://localhost',
    ignoreHTTPSErrors: true,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  projects: [
    // 受け入れテスト(.feature)。既存 spec 用のプロジェクトとは testDir が別なので、
    // 双方が互いのテストを拾うことはない。
    //
    // ブラウザ差が結果に影響するのは AT-18(#944)の範囲だけなので、受け入れテストは
    // chromium のみで回す。上の CROSS_BROWSER_SPECS と同じ考え方で、ブラウザ別の
    // 受け入れテストが必要になった時点で AT-18 がプロジェクトを追加する。
    //
    // `--project=at-destructive` を指定すれば、依存する at-setup → at-seed →
    // at-provision → at-main → at-llm-exclusive → at-timezone-exclusive も
    // Playwright が自動で先に実行する。段階を個別に指定する必要はない。
    {
      ...atSetup,
      use: { ...devices['Desktop Chrome'] },
    },
    {
      // 段階2: 合成アカウントの発行(scripts/seed-acceptance-env.sh)。
      // Gherkin ではなく通常の Playwright テストにしてある。受け入れ「基準」ではなく
      // 環境構築の手順であり、利用者から見たふるまいを持たないため。
      name: 'at-seed',
      testDir: './e2e/stages',
      testMatch: /seed\.setup\.ts/,
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['at-setup'],
    },
    {
      ...atProvision,
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['at-seed'],
    },
    {
      ...atMain,
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['at-provision'],
    },
    {
      // at-main と並列に走る(同じ at-provision にのみ依存)。llm-stub に触れるシナリオが
      // at-main の他シナリオと同時実行されないよう、以下の at-destructive はこれと
      // at-main の両方の完了を待つ(issue #1188)。
      ...atLlmExclusive,
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['at-provision'],
      // このプロジェクト内の同時実行を1に固定する(TestProject.workers、Playwright 1.40+)。
      // グローバルの`workers`(E2E_WORKERSでの上書きを含む)より優先してこのプロジェクトだけを
      // 制限するため、他プロジェクトの並列度には影響しない。
      workers: 1,
    },
    {
      // at-main / at-llm-exclusive と並列に走る(同じ at-provision にのみ依存)。共有管理者の
      // 個人設定TZに触れるシナリオが他と同時実行されないよう、以下の at-destructive は
      // これらすべての完了を待つ(issue #1374)。
      ...atTimezoneExclusive,
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['at-provision'],
      // このプロジェクト内の同時実行を1に固定する(at-llm-exclusiveと同じ理由)。
      workers: 1,
    },
    {
      ...atDestructive,
      use: { ...devices['Desktop Chrome'] },
      // at-destructive は「他に誰も走っていない」ことが前提(#929)。at-llm-exclusive /
      // at-timezone-exclusive も共有状態に触れるため、at-main と同様に完了を待ってから
      // 始める(issue #1188、issue #1374)。
      dependencies: ['at-main', 'at-llm-exclusive', 'at-timezone-exclusive'],
    },
    {
      ...atCrossBrowserFirefox,
      use: { ...devices['Desktop Firefox'] },
      dependencies: ['at-provision'],
    },
    {
      ...atCrossBrowserWebkit,
      use: { ...devices['Desktop Safari'] },
      dependencies: ['at-provision'],
    },
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
      testMatch: CROSS_BROWSER_SPECS,
    },
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
      testMatch: CROSS_BROWSER_SPECS,
    },
    {
      name: 'Mobile Chrome',
      use: { ...devices['Pixel 5'] },
      testMatch: CROSS_BROWSER_SPECS,
    },
    {
      name: 'Mobile Safari',
      use: { ...devices['iPhone 12'] },
      testMatch: CROSS_BROWSER_SPECS,
    },
  ],

  // webServerは持たない。上記の通りKeycloakのredirect_uriがhttps://localhost固定のため、
  // docker compose(web/reverse-proxy/keycloak/gateway/各ドメインサービス一式)が既に
  // 起動していることを前提とする(`docker compose up -d`。README/docs/setup.md、
  // docs/e2e-testing.md参照)。Playwright自身にサーバーを起動させる旧来の方式
  // (npm run dev on :3000)は、別ポート・別プロトコルになり登録済みredirect_uriと
  // 一致しなくなるため使えない。起動待ちはglobalSetupが担う。
});
