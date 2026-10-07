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
  // `@stub-isolation:threads`(下の at-threads-exclusive、issue #1579)と `@stub-isolation:facebook`(at-facebook-exclusive、issue #1580)、`@stub-isolation:x`(at-x-exclusive、issue #1583)、`@stub-isolation:linkedin`(at-linkedin-exclusive、issue #1581)、`@stub-isolation:hatena`(at-hatena-exclusive、issue #1582)は専用レーンへ集めるので除く。
  tags: '@stage:provision and not @stub-isolation:threads and not @stub-isolation:facebook and not @stub-isolation:x and not @stub-isolation:linkedin and not @stub-isolation:hatena',
});

/**
 * issue #1318: GPU の無いホストでのリリース検証(`scripts/release-verify-tag.py`)は
 * `AT_EXCLUDE_REQUIRES_GPU=1` を設定して `web-test-at-clean` を実行する。このときだけ
 * `@requires-gpu`(実機 `lbs-comfyui` が無いと通らないシナリオ)を生成時タグ式で除外する
 * (`--grep-invert` は依存プロジェクトを絞り込まないため使えない。Readiness評価で実測)。
 * 未設定(通常の `test:at:clean` / `test:at`)では今までどおり全シナリオが対象になる。
 */
const excludeRequiresGpu = process.env.AT_EXCLUDE_REQUIRES_GPU === '1' ? ' and not @requires-gpu' : '';

/**
 * issue #1401: CPU 構成の ComfyUI(`lbs-comfyui-cpu`)と約 5GB のモデルを要する実機 AI レーン
 * (`@requires-real-ai-cpu`、`media/image-generation-cpu.feature`)の除外。`@requires-gpu` とは
 * 別のタグ・別の環境変数で、その意味には触れない。
 *
 * `AT_EXCLUDE_REQUIRES_REAL_AI_CPU=1` のとき(リリース検証 `scripts/release-verify-tag.py` が設定する)
 * だけ生成時タグ式から除外する。未設定(手動の `test:at` / `test:at:clean`)では対象に含まれ、
 * CPU 構成の ComfyUI が起動していなければシナリオが明示的に失敗する(暗黙のスキップにしない)。
 * `test:at:fast` は `@slow` を除くので、いずれにせよこのシナリオは含まれない。
 */
const excludeRequiresRealAiCpu =
  process.env.AT_EXCLUDE_REQUIRES_REAL_AI_CPU === '1' ? ' and not @requires-real-ai-cpu' : '';

/** 段階4: それ以外すべて。@destructive は含めない(下の at-destructive が最後にまとめて実行する)。
 * `@stub-isolation:llm`(下の at-llm-exclusive)、`@account-isolation:timezone`
 * (下の at-timezone-exclusive、issue #1374)、`@stub-isolation:analytics`
 * (下の at-analytics-exclusive、issue #1372)も除く。 */
const atMain = defineBddProject({
  ...BDD_COMMON,
  name: 'at-main',
  outputDir: '.features-gen/at-main',
  tags:
    'not @stage:setup and not @stage:provision and not @destructive'
    + ' and not @stub-isolation:llm and not @account-isolation:timezone'
    + ' and not @stub-isolation:analytics and not @site-isolation:preview'
    + excludeRequiresGpu + excludeRequiresRealAiCpu,
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
 *   - `ai/review-step-fact-check.feature` 通常系+Brave Searchスタブへ500を仕込む(校閲、issue #1214)
 *   - `ai/review-step-reader-style.feature` 通常系(`/api/projects/{id}/ai/review-steps/**`、issue #1221)
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
 * GA/AdSenseスタブ(`ga-stub` / `adsense-stub`)にも同型の構造的リスクが実在することを
 * issue #1188で確認済み(対処はこのIssueのAcceptance Criteriaの対象外・確認のみが要件)。
 * issue #1372で対処した(下の `atAnalyticsExclusive`)。
 * `docs/ACCEPTANCE_TESTING.md` §9 に記録した。
 *
 * `stubs/external-stubs.feature`(このファイルの対象、下記)は llm-stub だけでなく
 * `ga-stub` / `adsense-stub` へも実際にトラフィックを送る(1シナリオの中で
 * 全スタブを1つのループで叩くため、GA/AdSense だけをこのファイルから切り出すことは
 * シナリオ内容の変更を要し、このIssueおよび issue #1372 の Out of Scope
 * 「注入シナリオ自体の追加・削除」に抵触する)。そのため `at-analytics-exclusive`
 * (issue #1372)は `at-provision` ではなく `at-llm-exclusive` の完了を待つよう
 * `dependencies` を設定し、この2レーンが互いに並行して走らないようにした ——
 * さもないと `external-stubs.feature` の GA/AdSense プローブ(このレーン内)と
 * `analytics/report-failures.feature` の注入(`at-analytics-exclusive`)が、レーンを
 * 分けたにもかかわらず**レーン間で**同じ衝突を再現してしまう。詳細は
 * `atAnalyticsExclusive` 自身のコメントを参照。
 */
const atLlmExclusive = defineBddProject({
  ...BDD_COMMON,
  name: 'at-llm-exclusive',
  outputDir: '.features-gen/at-llm-exclusive',
  tags: '@stub-isolation:llm' + excludeRequiresGpu + excludeRequiresRealAiCpu,
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
  tags: '@account-isolation:timezone' + excludeRequiresGpu + excludeRequiresRealAiCpu,
});

/**
 * issue #1372: `ga-stub` / `adsense-stub`(共有・単一プロセス、`llm-stub`と同型)へ
 * 実際にトラフィックを送る、または `POST /__control/force` でその共有状態を仕込む/読む
 * (`analytics.steps.ts` の `After({ tags: '@analytics' })` が毎シナリオ後に無条件で呼ぶ
 * `resetStub()` による暗黙の書き込みを含む)、全シナリオの専用レーン。issue #1188 が
 * `llm-stub` について確認だけして対処を持ち越した同型リスクを、ここで解消する。
 *
 * ## `@stub-isolation:llm` と同じレースが `ga-stub` / `adsense-stub` でも実測できる
 *
 * `analytics/report-failures.feature`(`@mode:serial`、`POST /__control/force` で
 * 401/429/500/タイムアウトを注入)と、`analytics/dashboard-report.feature` 等
 * **別ファイル**が同じスタブへ通常系のリクエストを送ると、`ai/resilience.feature` の
 * ケース(#1188)と同じ2つの故障モードが起こる。issue #1372 で実機の `ga-stub`
 * (`http://127.0.0.1:18082`)へ直接 `curl` して実測(2026-09-30):
 *
 *   1. `POST /__control/force '{"status":429,"count":1}'` の直後に、認証済みの
 *      `runReport` リクエスト(dashboard-report.feature 相当)を割り込ませると、
 *      **横取りされた側が429を受け取る**(`{"error":{"code":429,...,"status":"FORCED"}}`)。
 *   2. その後に届く報告注入シナリオ自身の `runReport` リクエストは、仕込みを消費され
 *      尽くしているため **200(実データ)を受け取る** —— 注入シナリオが自分の注入を
 *      観測できない。
 *   3. 仕込み直後(消費される前)に、`resetStub()` だけが割り込んでも同じことが起きる:
 *      `analytics/analytics-authorization.feature` のように、それ自体はスタブへ
 *      トラフィックを送らないシナリオでも、`After({ tags: '@analytics' })` が並行して
 *      `resetStub('google-analytics')` / `resetStub('adsense')` を呼べば、注入シナリオの
 *      仕込みは消え、その後のリクエストは200になる。
 *
 * `llm-stub` の場合と同じ理由(相関IDをサービス境界越しに伝播させる変更はプロダクション
 * コードの変更を要し、このIssueおよび `infra/e2e-stubs/lib/stub.js` の注入機構自体の
 * Scope外)で、採ったのは `ga-stub` / `adsense-stub` へ触れる全ファイルを1つの
 * プロジェクトへ集め、`workers: 1` で内部を完全直列化する案 —— `at-llm-exclusive`
 * (#1188)・`at-timezone-exclusive`(#1374)と同型。
 *
 * ## `@stub-isolation:analytics` を付けたファイル
 *
 *   - `analytics/report-failures.feature`  注入元(401・429・500・タイムアウト)
 *   - `analytics/dashboard-report.feature`  通常系(GA/AdSenseの指標・収益をダッシュボードに表示)
 *   - `analytics/credentials.feature`       通常系(資格情報のCRUD・OAuth連携、実際にga-stub/adsense-stubへトークン交換する)
 *   - `analytics/analytics-authorization.feature` 認可チェック。2番目のシナリオが
 *     対照として実際にOAuth連携する。1番目のシナリオはスタブへ触れないが、`@analytics`の
 *     `After`フックが両シナリオ後に無条件で`resetStub()`を呼ぶため、ファイル全体を対象にした
 *     (上記「実測」3.)。
 *
 * ## `at-provision` ではなく `at-llm-exclusive` に依存する理由
 *
 * `stubs/external-stubs.feature`(`@stub-isolation:llm`、上の `atLlmExclusive`)は
 * llm-stub だけでなく `ga-stub` / `adsense-stub` へも実トラフィックを送る「同じ入力に
 * 対して常に同じ応答を返す」等のシナリオを持つ(1シナリオが全スタブを1ループで叩くため、
 * GA/AdSense 分だけを切り出すには機能ファイル自体の変更が要り、このIssueの Out of Scope
 * 「注入シナリオ自体の追加・削除」に抵触するため切り出さなかった)。したがって
 * `at-llm-exclusive` と `at-analytics-exclusive` を互いに独立させて `at-provision` にだけ
 * 依存させると、2つの専用レーンが並行実行され、レーンを分けた意味がそのまま失われる ——
 * `external-stubs.feature` の GA/AdSense プローブ(`at-llm-exclusive`内)と
 * `report-failures.feature` の注入(`at-analytics-exclusive`内)が、レーン間で同じ衝突を
 * 再現しうる。そこで `at-analytics-exclusive` は `at-llm-exclusive` の完了を待つ
 * (`dependencies: ['at-llm-exclusive']`。`at-provision` は `at-llm-exclusive` の依存を
 * 通じて推移的に含まれる)。両レーンとも通常は `at-main` に比べて小さいため、直列に
 * つないでもスイート全体の所要時間への影響は小さいと判断した(Requirement 4)。
 *
 * `at-main` とは並列に走る(`at-main` はどちらの排他レーンにも依存しないため)。
 */
const atAnalyticsExclusive = defineBddProject({
  ...BDD_COMMON,
  name: 'at-analytics-exclusive',
  outputDir: '.features-gen/at-analytics-exclusive',
  tags: '@stub-isolation:analytics' + excludeRequiresGpu + excludeRequiresRealAiCpu,
});

/**
 * issue #1632: プレビュー検証用の共有サイト(siteKey `at65previewprobe`)を
 * プロジェクトのテスト環境へ紐づける全ファイルの専用レーン。
 *
 * `publishing/preview.feature` と `publishing/preview-signed-url.feature` は背景で同じサイトを
 * シナリオごとの別プロジェクトへ紐づける。サイトは同時に1プロジェクトにしか紐づけられないため、
 * `@mode:serial`(同一ファイル内だけ直列化)では別ファイルの背景と並列になり、紐付けが
 * 409(既に別プロジェクトに紐付けられています)で落ちる。`workers: 1` の専用プロジェクトへ
 * 集めて直列化する(`at-timezone-exclusive` と同型)。
 */
const atPreviewExclusive = defineBddProject({
  ...BDD_COMMON,
  name: 'at-preview-exclusive',
  outputDir: '.features-gen/at-preview-exclusive',
  tags: '@site-isolation:preview' + excludeRequiresGpu + excludeRequiresRealAiCpu,
});

/**
 * issue #1579: Threads の告知(`project/project-sns-threads.feature`)の専用レーン。
 *
 * 全シナリオが `threads-stub` の単一のグローバル状態を開始時に初期化し、投稿・更新の記録を
 * 検証する。既定の並列度(fullyParallel)だと、シナリオ同士が互いの記録を消す/混ぜるため、
 * `@mode:serial`(シナリオ単位の指定で直列化にならない)では防げない。`workers: 1` の専用
 * プロジェクトへ集めて直列化する(`at-analytics-exclusive` と同型)。シナリオは `@stage:provision`
 * なので、at-provision からは `not @stub-isolation:threads` で除き、ここだけが走らせる。
 */
const atThreadsExclusive = defineBddProject({
  ...BDD_COMMON,
  name: 'at-threads-exclusive',
  outputDir: '.features-gen/at-threads-exclusive',
  tags: '@stub-isolation:threads' + excludeRequiresGpu + excludeRequiresRealAiCpu,
});

/**
 * issue #1580: Facebook ページの告知(`project/project-sns-facebook.feature`)の専用レーン。
 * 理由は at-threads-exclusive と同じ(`facebook-stub` の単一のグローバル状態を全シナリオが初期化・検証する)。
 * `workers: 1` の専用プロジェクトへ集めて直列化する。
 */
const atFacebookExclusive = defineBddProject({
  ...BDD_COMMON,
  name: 'at-facebook-exclusive',
  outputDir: '.features-gen/at-facebook-exclusive',
  tags: '@stub-isolation:facebook' + excludeRequiresGpu + excludeRequiresRealAiCpu,
});

/**
 * issue #1583: X の告知(`project/project-sns-x.feature`・`project/site-letsblog-sns-announce.feature`・
 * `project/project-sns-templates.feature`)の専用レーン。理由は at-threads-exclusive と同じ
 * (`x-stub` の単一のグローバル状態を全シナリオが初期化・検証する)。`workers: 1` の専用プロジェクトへ集めて直列化する。
 */
const atXExclusive = defineBddProject({
  ...BDD_COMMON,
  name: 'at-x-exclusive',
  outputDir: '.features-gen/at-x-exclusive',
  tags: '@stub-isolation:x' + excludeRequiresGpu + excludeRequiresRealAiCpu,
});

/**
 * issue #1581: LinkedIn の告知(`project/project-sns-linkedin.feature`)の専用レーン。
 * 理由は at-threads-exclusive と同じ(`linkedin-stub` の単一のグローバル状態を全シナリオが初期化・検証する)。
 * `workers: 1` の専用プロジェクトへ集めて直列化する。
 */
const atLinkedinExclusive = defineBddProject({
  ...BDD_COMMON,
  name: 'at-linkedin-exclusive',
  outputDir: '.features-gen/at-linkedin-exclusive',
  tags: '@stub-isolation:linkedin' + excludeRequiresGpu + excludeRequiresRealAiCpu,
});

/**
 * issue #1582: はてなブックマークの告知(`project/project-sns-hatena.feature`)の専用レーン。
 * 理由は at-threads-exclusive と同じ(`hatena-stub` の単一のグローバル状態を全シナリオが初期化・検証する)。
 * `workers: 1` の専用プロジェクトへ集めて直列化する。
 */
const atHatenaExclusive = defineBddProject({
  ...BDD_COMMON,
  name: 'at-hatena-exclusive',
  outputDir: '.features-gen/at-hatena-exclusive',
  tags: '@stub-isolation:hatena' + excludeRequiresGpu + excludeRequiresRealAiCpu,
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
 *
 * ## この段階の**内部**も直列化する(issue #1387)
 *
 * 「他に誰も走っていない」を `dependencies` で担保しても、**この段階のシナリオ同士**は
 * グローバルの `workers`(既定はCPU由来、実測4)でそのまま並列に走っていた。24シナリオが
 * それぞれ別のサービスを止めるので、互いの停止に巻き込まれる。
 *
 * 2026-09-23 のリリース検証(develop `d6a48a3a`、run `20260922T221642Z-3414881`)で
 * **5件が落ちた**。実行ログは `Running 355 tests using 4 workers` で、連番
 * `[339] media-service停止` / `[340] log-writer停止` / `[342] Penpot停止` /
 * `[343][344] RabbitMQ停止` / `[345] log-writer停止` が同時に走っている。
 *
 * 決定的だったのは `service-degradation.feature:24`。**media-service を止める**シナリオ
 * なのに、エラーは**自分が止めていない content-service** が到達不能だった:
 *
 *   media-service の停止が記事の公開を止めている (status=409):
 *   content-serviceのレンダリング呼び出しに失敗しました:
 *   I/O error on POST request for "http://content:8080/api/internal/content/render/pre-image"
 *
 * `async-path.feature:7` のログインが `net::ERR_NETWORK_CHANGED` で落ちたのも同じ理由と
 * 見ている(コンテナの停止・起動が docker のネットワークを揺らす)。ホスト側の回線断は
 * 否定済み —— `net-watchdog.service` が該当時間帯に2分ごと走り、毎回復旧動作なしで
 * 正常終了している。
 *
 * したがって `workers: 1` で内部も完全に直列化する。`at-llm-exclusive`(#1188)・
 * `at-timezone-exclusive`(#1374)・`at-analytics-exclusive`(#1372)と同じ手法で、
 * `TestProject.workers` はグローバルの `workers`(`E2E_WORKERS` での上書きを含む)より
 * 優先される。
 */
const atDestructive = defineBddProject({
  ...BDD_COMMON,
  name: 'at-destructive',
  outputDir: '.features-gen/at-destructive',
  tags: '@destructive and not @stage:setup and not @stage:provision' + excludeRequiresGpu + excludeRequiresRealAiCpu,
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
  // at-timezone-exclusive / at-analytics-exclusive / at-destructive / クロスブラウザ系)に
  // 効く。30秒で落ちていたはずの
  // 将来の性能退行が、90秒なら通ってしまう範囲がスイート全体に広がる。Playwright には
  // ディレクトリ単位で既定予算を変える手段が無く、そのためだけに専用プロジェクトを増やすのは
  // 割に合わないと判断して、この代償を受け入れた。identity 以外のレーンは、旧30秒の天井付近に
  // いるシナリオが観測されなかったため個別には計測していない。
  //
  // シナリオ/フィーチャ単位の`@timeout:`タグ(90000 / 180000 / 300000 / 600000)は
  // 従来どおりこの値を上書きする。
  timeout: 90_000,
  reporter: [['html'], ['./e2e/reporters/at-metrics-reporter.ts']],

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
    // at-provision → at-main → at-llm-exclusive → at-timezone-exclusive →
    // at-analytics-exclusive(at-llm-exclusiveの完了を待つ、issue #1372)も
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
      // at-main とは並列に走るが、at-llm-exclusive とは並列に走らせない
      // (at-provisionではなくat-llm-exclusiveに依存させる理由はatAnalyticsExclusive
      // 自身のコメントを参照)。ga-stub/adsense-stubに触れるシナリオが他と同時実行
      // されないよう、以下の at-destructive はこれの完了も待つ(issue #1372)。
      ...atAnalyticsExclusive,
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['at-llm-exclusive'],
      // このプロジェクト内の同時実行を1に固定する(at-llm-exclusiveと同じ理由)。
      workers: 1,
    },
    {
      // at-main とは並列に走る。共有サイトに触れるため、at-destructive はこれの完了も待つ(issue #1632)。
      ...atPreviewExclusive,
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['at-provision'],
      workers: 1,
    },
    {
      // at-main とは並列に走る。threads-stub の共有状態に触れるため、at-destructive はこれの完了も待つ(issue #1579)。
      ...atThreadsExclusive,
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['at-provision'],
      workers: 1,
    },
    {
      // at-main とは並列に走る。facebook-stub の共有状態に触れるため、at-destructive はこれの完了も待つ(issue #1580)。
      ...atFacebookExclusive,
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['at-provision'],
      workers: 1,
    },
    {
      // at-main とは並列に走る。x-stub の共有状態に触れるため、at-destructive はこれの完了も待つ(issue #1583)。
      ...atXExclusive,
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['at-provision'],
      workers: 1,
    },
    {
      // at-main とは並列に走る。linkedin-stub の共有状態に触れるため、at-destructive はこれの完了も待つ(issue #1581)。
      ...atLinkedinExclusive,
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['at-provision'],
      workers: 1,
    },
    {
      // at-main とは並列に走る。hatena-stub の共有状態に触れるため、at-destructive はこれの完了も待つ(issue #1582)。
      ...atHatenaExclusive,
      use: { ...devices['Desktop Chrome'] },
      dependencies: ['at-provision'],
      workers: 1,
    },
    {
      ...atDestructive,
      use: { ...devices['Desktop Chrome'] },
      // at-destructive は「他に誰も走っていない」ことが前提(#929)。at-llm-exclusive /
      // at-timezone-exclusive / at-analytics-exclusive も共有状態に触れるため、at-main と
      // 同様に完了を待ってから始める(issue #1188、issue #1374、issue #1372)。
      dependencies: ['at-main', 'at-llm-exclusive', 'at-timezone-exclusive', 'at-analytics-exclusive', 'at-preview-exclusive', 'at-threads-exclusive', 'at-facebook-exclusive', 'at-x-exclusive', 'at-linkedin-exclusive', 'at-hatena-exclusive'],
      // この段階の**内部**も直列化する(issue #1387)。dependencies は他プロジェクトの
      // 完了しか担保せず、24シナリオ同士は既定の並列度でそのまま走っていた。それぞれが
      // 別のサービスを止めるため互いの停止に巻き込まれ、2026-09-23 のリリース検証で
      // 5件が落ちた。詳細は上の atDestructive の doc を参照。
      workers: 1,
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
