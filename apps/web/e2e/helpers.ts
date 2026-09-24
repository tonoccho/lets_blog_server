import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { expect } from '@playwright/test';
import type { APIRequestContext, Page } from '@playwright/test';
import { fetchAccessToken as fetchAccessTokenInternal } from './token-cache';
import { withAccountLock } from './account-lock';

/**
 * E2E専用の合成アカウント(issue #564で導入、#588で各specの重複定義をここへ集約)。
 * 実ユーザー(s.tonouchi@gmail.com)は使わない。
 *   - e2e-test@letsblog.local  (role: user。非admin側の検証用)
 *   - e2e-admin@letsblog.local (role: admin。realmロールadminを付与済み。admin側の検証用)
 * アカウントの発行は scripts/provision-e2e-keycloak-users.sh(ローカルのdev Keycloak専用)、
 * パスワードは環境変数E2E_TEST_PASSWORD/E2E_ADMIN_PASSWORDで注入する
 * (このリポジトリの.envには含めない)。詳細はdocs/e2e-testing.md参照。
 */
export const E2E_TEST_EMAIL = 'e2e-test@letsblog.local';
export const E2E_ADMIN_EMAIL = 'e2e-admin@letsblog.local';
export const E2E_TEST_PASSWORD = process.env.E2E_TEST_PASSWORD ?? '';
export const E2E_ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD ?? '';

/** リポジトリルート(apps/web/e2e から3階層上)。スクリプト実行のたびに解決する。 */
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..');

/**
 * issue #564: Keycloakへの移行に伴い、/loginは自前フォームを持たずKeycloakのホスト型
 * ログイン画面へ即座にリダイレクトするようになった。E2Eからログイン状態を作るには、
 * このホスト型フォームへ実際に値を入力してサインインを完了させる必要がある。
 * 全specがこの共通ヘルパー経由でログインする。
 *
 * <p><b>`goto` に load を待たせない。中断されうる進行中のナビゲーションを作らない(issue #1017)。</b>
 *
 * <p>以前はこう書いていた:
 *
 * <pre>
 *   await page.goto('/login');                                  // 既定は load 完了まで待つ
 *   await page.waitForURL(/\/auth\/realms\/letsblog\//, ...);   // ナビゲーションイベントを待つ
 * </pre>
 *
 * <p>この2行はどちらも**進行中のナビゲーションに紐づいて待つ**。`/login` は
 * マウント時に `signIn("keycloak")` を呼ぶだけの画面で、そこから Keycloak への遷移は
 * クライアント側で起きる。この遷移が、先行するナビゲーション(`goto` の load 待ち)を
 * **中断**すると、待っていた側は `net::ERR_ABORTED; maybe frame was detached?` で
 * **即座に**失敗する。タイムアウトではないので、待ち時間を伸ばしても直らない。
 * `/login` の読み込みが遅いほど中断は起きやすく、ワーカー数を増やすと確率的に踏む。
 * 全 UI シナリオがこのヘルパーを通るため、製品の不具合と見分けの付かない失敗になる。
 *
 * <p>実測は issue #1017 の本文に記録がある(2026-09-02、AT-9(#935)の15シナリオ)。
 * ワーカー数1では2回の実行でログイン失敗0件、ワーカー数2では5回の実行で2件が落ちた。
 * 落ちるシナリオは毎回違う。なおこの開発ホストは Playwright のブラウザが OS 共有
 * ライブラリ(`libatk-1.0.so.0` ほか)を欠いていて起動できないため、ブラウザを使う
 * シナリオをこの場で再実行して追試することは現状できない(#1045。本件とは別問題)。
 *
 * <p>そこで:
 * <ul>
 *   <li>`goto` は `waitUntil: 'commit'`(応答が返った時点で戻る)にする。**これが修正の本体**。
 *       load を待たずに戻るので、クライアント側リダイレクトが始まる時点で `goto` の
 *       ナビゲーションは既に解決しており、中断されうる進行中のナビゲーションがそもそも無い。</li>
 *   <li>URL の到達は {@code expect(page).toHaveURL()} で待つ。Playwright 1.62.1 の
 *       {@code toHaveURL}(`playwright/lib/matchers/expect.js`)は**引数の型で経路が分かれる**。
 *       ネイティブ {@code URLPattern} インスタンスか関数のときだけ
 *       {@code toHaveURLWithPredicate} → {@code Frame.waitForURL()} を通る。分岐の条件は
 *       {@code isURLPattern = (v) => v instanceof globalThis.URLPattern}
 *       (`playwright-core/lib/coreBundle.js`)で、**正規表現も文字列もこれには一致しない**。
 *       ここで渡しているのは正規表現と文字列なので、実際に通るのはもう一方——
 *       {@code toMatchText} → {@code mainFrame()._expect("to.have.url")} →
 *       サーバ側 {@code Frame.expect()} の**ポーリング**である。1度照合したあと
 *       {@code retryWithProgressAndBackoff} が即時 → 20 → 50 → 100 → 100 → 500ms
 *       (以降 500ms 据え置き)で再試行し、毎回インジェクトスクリプトを走らせて
 *       {@code document.location.href} を読み直す。ナビゲーションイベントの購読で
 *       一致を検出しているのではない。</li>
 *   <li>この経路には、旧 {@code page.waitForURL()} が持っていた**中断の再送出**が無い。
 *       旧経路は {@code waitForNavigation()} で "navigated" を購読し、イベントが error を
 *       運んでいればそれをそのまま throw していた——`net::ERR_ABORTED; maybe frame was
 *       detached?` が表に出てくるのはそこである。ポーリング経路が各試行の前に呼ぶ
 *       {@code performActionPreChecks} も未コミットのナビゲーションを待ちはするが、
 *       それがエラーで終わっても待ちを解いて次の試行へ進むだけで throw しない。
 *       とはいえ**修正の本体はあくまで上の `commit` 化**である。中断されうる進行中の
 *       ナビゲーションをそもそも作らないことが原因の除去で、こちらはその上での備えに
 *       すぎない。どちらか一方だけで足りるかは実測で切り分けていない(#1045 により
 *       この開発ホストではブラウザが起動できない)。</li>
 *   <li>URL が一致したあとの {@code waitForLoadState('load')} は**必要**である。
 *       上のポーリング経路は `load` の発火を待たない。{@code performActionPreChecks} が
 *       待つのは未コミットのナビゲーションまでで、照合そのものは `:root` を解決できる
 *       実行コンテキストさえあれば走る——どちらもドキュメントのコミット時点で満たされる。
 *       一方、以前使っていた {@code page.waitForURL()} は `waitUntil` 未指定なら `load`
 *       を既定とし、到達先の load 完了まで待っていた。つまりこの1行は、待ち方を
 *       {@code toHaveURL()} に変えたことで失われた待機を明示的に埋め直すものであって、
 *       冗長な念押しではない。呼び出し側は直後にフォーム入力やクリックを行うため、
 *       「load 完了後に操作する」というこのヘルパーの約束はこの行が担っている。</li>
 * </ul>
 *
 * <p>待ち時間を伸ばして誤魔化しているのではない(#843 の轍を踏まない)。上で見たとおり
 * 修正前の失敗はタイムアウトではなく即時の ERR_ABORTED であり、待ち時間では直らない。
 * 直しているのは `goto` が進行中のナビゲーションを抱えたまま待つ、という構図の方である。
 *
 * <p>timeout はそれとは別の理由で、**2つの待機とも** 15 秒から 30 秒へ揃えた。
 * 1つ目は、`goto` が load を待たなくなった結果、これまで `goto` 側が負担していた
 * `/login` の読み込み時間がこの待機に含まれるようになったためである。
 * 2つ目(ログイン後のコールバック待ち)にそうした機械的な必要は無いが、
 * どちらも「URL 到達をアサートし、続けて load を待つ」という同じ2行の形で書いてある以上、
 * 値を非対称にしておく理由も無いので合わせた(この timeout が掛かるのは URL 到達までで、
 * 続く {@code waitForLoadState('load')} は既定の timeout を使う)。
 *
 * <p><b>issue #1295フォローアップ(QAのFAIL、note 7391): アカウント単位のクロスプロセスロック
 * で囲む。</b>ここが実際にKeycloakのホスト型ログインフォームへ値を入力してsubmitする、
 * 実認証リクエストを送る唯一の箇所である(clientId="letsblog-web"、Authorization Code
 * フロー)。`fetchAccessToken`(パスワードグラント、clientId="letsblog-e2e")向けに実装した
 * アカウント単位クロスプロセスロック({@link withAccountLock}、実体は`./account-lock`)は
 * このブラウザ経由の経路を一切通っておらず、QAが受け入れテストを既定の並列度で実行した際に
 * `user_temporarily_disabled`を2件再現した(fetchAccessToken側の対策だけでは閉じない
 * 別経路だった)。両経路が同じアカウント単位ロックファイルを取り合うことで、
 * 同一アカウントに対する実Keycloak認証リクエストは、パスワードグラントか対話ログインかを
 * 問わず同時に1本までに揃い、`quickLoginCheckMilliSeconds`(1秒)以内に同一アカウントへの
 * 認証試行が重なる状況そのものが起きなくなる。
 *
 * <p>ロック獲得後の実行時間は、`fetchAccessToken`の実HTTPリクエスト1本(既定タイムアウト
 * 30秒)より長くなりうる(ページ遷移・フォーム操作・ログイン後のコールバック待ちを含む)ため、
 * ロック待ちのタイムアウトは既定の30秒ではなく120秒に伸ばしてある(既定の並列度4ワーカーが
 * 同じアカウントで待ち行列を作っても、待ち時間の合計が30秒を超えて誤ってタイムアウトしない
 * ようにするため)。
 *
 * <p><b>issue #1391: `/login`直後の一過性の接続断からの再試行。</b> `@destructive`シナリオが
 * コンテナを再起動した直後(例: `diagram.steps.ts`の`stopService(ctx, 'penpot-frontend')`)、
 * 共有Dockerブリッジネットワークの再構成に伴う一過性の接続断が、直後の最初の操作——
 * `signIn("keycloak")`が内部で発行する`/api/auth/csrf`・`/api/auth/providers`への
 * クライアント側fetch、またはKeycloakへのトップレベル遷移そのもの——を1回だけ巻き込む
 * ことがある(実測は実装報告に記録: `docker logs lbs-web`に
 * `[next-auth][error][CLIENT_FETCH_ERROR] Failed to fetch`が残っていた)。
 *
 * <p>ホストからの疎通確認(`global-setup.ts`と同じ発想を`startService`の復旧待ちに足す案)は
 * この断を検知できない。実測(`docker compose stop/start penpot-frontend`前後で
 * `curl`・keep-aliveの`requests.Session`を200ms間隔で反復)では、ホストから
 * `https://localhost/`への到達性は一度も失われなかった——断はキープアライブ接続の再利用に
 * 限って一瞬だけ起きるとみられ、疎通確認のような新規接続のプローブでは観測できない。
 *
 * <p>NextAuthのこのクライアント側フローに再試行は無いため、1回の断が
 * `/api/auth/error`や`chrome-error://chromewebdata/`という「それ以上進行しない」状態に
 * 固定され、後続の{@code toHaveURL}のポーリングはタイムアウトまで同じURLを観測し続ける
 * (実際の失敗ログでは57回にわたって同じURLが記録されていた)。断そのものを消せない以上、
 * `/login`から撮り直す再試行で復旧する({@link isTransientLoginDeadEnd}が行き止まりURLと
 * 判定した場合のみ。実際のログイン障害は再試行せずそのまま失敗させる)。
 *
 * <p>タイムアウトを延ばして誤魔化しているのではない(#843の轍を踏まない)。各試行のtimeout
 * (30秒)は変えておらず、行き止まりURLを検知したときだけ`/login`から撮り直す、という
 * 別の戦略を足しているだけである。
 */
const TRANSIENT_LOGIN_DEAD_END_PATTERNS = [/^chrome-error:\/\//, /\/api\/auth\/error(\?|$)/];

/** issue #1391: 一過性の接続断が固定化した「それ以上進行しないURL」かどうかを判定する。 */
function isTransientLoginDeadEnd(url: string): boolean {
  return TRANSIENT_LOGIN_DEAD_END_PATTERNS.some((pattern) => pattern.test(url));
}

/**
 * `/login`へ遷移し、Keycloakのレルムへリダイレクトされるまで待つ(issue #1391)。
 * 行き止まりURL({@link isTransientLoginDeadEnd})に落ちた場合だけ、`/login`から
 * もう一度撮り直す(最大2回試行。1回の一過性断を前提にしており、断が繰り返す場合や
 * 実際のログイン障害はそのまま失敗させる)。
 */
async function gotoLoginAndWaitForKeycloakRedirect(page: Page): Promise<void> {
  const maxAttempts = 2;
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    await page.goto('/login', { waitUntil: 'commit' });
    try {
      await expect(page).toHaveURL(/\/auth\/realms\/letsblog\//, { timeout: 30000 });
      return;
    } catch (error) {
      if (attempt >= maxAttempts || !isTransientLoginDeadEnd(page.url())) {
        throw error;
      }
      // 行き止まりURLに落ちた1回目の失敗のみ再試行する。ループの次周で /login を撮り直す。
    }
  }
}

export async function loginViaKeycloak(page: Page, email: string, password: string): Promise<void> {
  await withAccountLock(
    email,
    async () => {
      await gotoLoginAndWaitForKeycloakRedirect(page);
      await page.waitForLoadState('load');

      await page.locator('#username').fill(email);
      await page.locator('#password').fill(password);
      // e2e-login-guard:locked — このメソッド全体がwithAccountLockで囲まれている(issue #1295)。
      await page.locator('#kc-login').click();

      // VERIFY_PROFILE等の追加required actionが出た場合のみ処理する(通常のログインでは出ない)。
      if (await page.locator('#firstName').isVisible({ timeout: 3000 }).catch(() => false)) {
        await page.locator('#firstName').fill('E2E');
        await page.locator('#lastName').fill('Test');
        await page.locator('input[type="submit"]').first().click();
      }

      // ログイン後のコールバック(Keycloak → /api/auth/callback/keycloak → /)の待機。
      // 上の待機と書き方・timeout を揃えてある(#1017 でこの形にした)。
      await expect(page).toHaveURL('/', { timeout: 30000 });
      await page.waitForLoadState('load');
    },
    { timeoutMs: 120_000 }
  );
}

/** admin権限の合成アカウントでログインする(呼び出し側の重複を減らすための薄いラッパー)。 */
export function loginAsAdmin(page: Page): Promise<void> {
  return loginViaKeycloak(page, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

/** 一般ユーザー権限の合成アカウントでログインする。 */
export function loginAsUser(page: Page): Promise<void> {
  return loginViaKeycloak(page, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
}

/**
 * E2E専用のKeycloakクライアント(issue #588)。infra/keycloak/realm-export.json と
 * scripts/provision-e2e-keycloak-users.sh の定義と一致させること。
 *
 * realm既定のadmin-cliを使わない理由: admin-cliはKeycloakの既定で
 * client.use.lightweight.access.token.enabled=true になっており、発行される
 * アクセストークンから sub と realm_access.roles が落ちる。その状態のトークンでは
 * identity-serviceの /api/identity/me が403になり、下流サービスの認可が通らない。
 */
/**
 * ブラウザを介さずAPIを直接叩くテスト(記事公開など、Web UIに機能が存在せずVSCode拡張が
 * gateway経由で行っている操作)のためにKeycloakからアクセストークンを取得する(issue #588)。
 *
 * letsblog-web/letsblog-vscodeクライアントはいずれもdirect access grantを許可していない
 * (Authorization Code + PKCE専用)ため、E2E専用のletsblog-e2eクライアント
 * (public、directAccessGrantsEnabled=true、lightweight access token無効)で
 * Resource Owner Password Credentialsグラントを使う。
 * gatewayはaudience/azpを検証せず、下流サービスはsubとrealm_access.rolesを見る
 * (CurrentActorService / KeycloakRealmRoleConverter)ため、このトークンで
 * ブラウザ経由と同じ権限の呼び出しができる。
 * ローカル開発スタック(https://localhost)専用の手段であり、本番の認証フローには影響しない。
 *
 * issue #1295: `fetchAccessToken`のアカウント単位キャッシュ/同時呼び出しの合流、および
 * issue #1295フォローアップ(レビュー指摘、note 7363): ワーカー(別OSプロセス)をまたいだ
 * 排他。実体は`./token-cache`に分離してある(理由: 複数プロセスをまたいだ排他を検証する
 * `token-cross-process.test.ts`が、子プロセス側で`@playwright/test`本体の重い実行時依存
 * (`expect`等)を引きずらずにこのロジックだけを読み込めるようにするため)。
 * 実装の詳細・設計根拠は`./token-cache`のコメントを参照。ここでは再エクスポートし、
 * 呼び出し側(80箇所以上のステップ定義)の import 元を変えずに済むようにする。
 */
export {
  fetchAccessToken,
  _resetAccessTokenCacheForTests,
  _sharedTokenCacheFilePathForTests,
} from './token-cache';

/**
 * issue #1295: `fetchAccessToken`/`loginViaKeycloak`以外の箇所(ステップ定義に直書きされた
 * `#kc-login`送信)からも、同じアカウント単位クロスプロセスロックを使えるよう再エクスポートする。
 * 実体は`./account-lock`のコメント参照。
 */
export { withAccountLock } from './account-lock';

/**
 * issue #1295フォローアップ: 受け入れテスト全体の最初(`at-seed`段階、単一プロセス)で
 * 両方のE2E合成アカウントのトークンを一度だけ先取りする。
 *
 * `at-seed`はPlaywrightの依存プロジェクト機構により、`at-provision`/`at-main`等の
 * 並列ワーカーが起動する**前**に、単一のテスト・単一のプロセスとして必ず実行し終える
 * (`playwright.config.ts`の`dependencies`)。ここで両アカウント分のトークンを取得して
 * `./token-cache`の共有キャッシュファイルへ書いておけば、後続の全ワーカーは起動直後から
 * 共有キャッシュを読むだけで済み、複数ワーカーが同時に実HTTPリクエストを送る状況
 * (レビュー指摘、note 7363)そのものを回避できる。
 *
 * `seed.setup.ts`から呼ぶ(Keycloakへユーザーを作成した直後。作成前に呼ぶと
 * アカウントが存在せず401になる)。
 */
export async function prefetchAccessTokensForAllE2eAccounts(request: APIRequestContext): Promise<void> {
  await fetchAccessTokenInternal(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
  await fetchAccessTokenInternal(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

/**
 * docker composeのサービスを停止/起動する(issue #588、サービス障害時の縮退表示の検証用)。
 * スタック全体を壊しうる操作のため、呼び出すシナリオには必ず `@destructive` を付け、
 * 停止したサービスを後始末で起動し直すこと
 * (`e2e/features/cross-cutting/service-degradation.feature` と `e2e/steps/degradation.steps.ts`)。
 */
export function composeServiceControl(action: 'stop' | 'start', service: string): void {
  execFileSync('docker', ['compose', action, service], {
    cwd: REPO_ROOT,
    stdio: 'pipe',
    timeout: 180_000,
  });
}

/**
 * scripts/wait-for-stack-healthy.sh を呼び出し、指定サービスがhealthyになるまで待つ。
 * global-setup.ts(テスト開始前の全サービス待機)と、縮退表示テストの復旧確認で使う。
 */
export function waitForServicesHealthy(services?: string[], timeoutSeconds = 600): void {
  const args = ['--timeout', String(timeoutSeconds)];
  if (services && services.length > 0) {
    args.push('--services', services.join(' '));
  }
  execFileSync(path.join(REPO_ROOT, 'scripts', 'wait-for-stack-healthy.sh'), args, {
    cwd: REPO_ROOT,
    stdio: 'inherit',
    timeout: (timeoutSeconds + 30) * 1000,
  });
}

let cachedNextAuthSecret: string | undefined;

/**
 * 実行中のwebコンテナが使っている `NEXTAUTH_SECRET` を取得する(issue #1053)。
 *
 * NextAuthのセッションCookieはこの鍵でJWE暗号化されている。「リフレッシュトークンが
 * 使えない状態にする」ステップ(auth.steps.ts)はCookieを直接decode/re-encodeして
 * リフレッシュトークンを壊れた値に差し替えるため、webコンテナと同じ鍵が要る。
 * 開発者のホスト側シェルが `NEXTAUTH_SECRET` を export しているかに依存させたくないため、
 * (`E2E_TEST_PASSWORD` 等と違い、この値はdocker-composeがwebコンテナへ渡す側の秘密であり
 * `~/.config/lets-blog-e2e.env` の対象ではない)、実際に動いているコンテナへ直接問い合わせる。
 */
export function getNextAuthSecret(): string {
  if (cachedNextAuthSecret) {
    return cachedNextAuthSecret;
  }
  const secret = execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'web', 'printenv', 'NEXTAUTH_SECRET'],
    { cwd: REPO_ROOT, stdio: ['ignore', 'pipe', 'pipe'] }
  )
    .toString()
    .trim();
  if (!secret) {
    throw new Error('webコンテナからNEXTAUTH_SECRETを取得できなかった(コンテナが起動しているか確認すること)');
  }
  cachedNextAuthSecret = secret;
  return secret;
}

/**
 * フィクスチャのプロジェクトを gateway 経由のAPIで直接作成する(issue #753、共通化は #844)。
 *
 * <p>Web UI(/projects の作成フォーム → 一覧 → /projects/{id})でも用意できるが、その導線は
 * 1テストあたり ダッシュボード+一覧+詳細 のサーバーレンダリングで25回前後 gateway を呼ぶ。
 * gateway の api-global バケットはクライアント単位ではなく**グローバル**に 100リクエスト/分
 * (services/gateway の RateLimitProperties)であり、ワーカー数を増やすと簡単に上限へ達する。
 * APIを直接呼べばフィクスチャ1件あたり1往復に抑えられる。
 *
 * <p><b>accessToken は管理者のものであること(issue #949)。</b>
 * {@code POST /api/projects} は #830 の認可強化で {@code requireAdmin()} を通るようになった。
 * 非管理者のトークンを渡すと403で落ちる。{@code beforeAll} から呼ぶと、その describe の
 * テストが**全て実行されない**ため、原因の分かりにくい形で検証が丸ごと消える
 * (custom-tag-generation.spec.ts で実際にそうなっていた)。
 * {@link deleteFixtureProject} も同様に管理者を要する。
 *
 * @param accessToken **管理者**のアクセストークン({@link E2E_ADMIN_EMAIL} で取得したもの)
 * @param prefix プロジェクト名/slug の接頭辞(spec ごとに変えて衝突と識別性を確保する)
 */
export async function createFixtureProject(
  request: APIRequestContext,
  accessToken: string,
  prefix: string
): Promise<{ id: number; name: string }> {
  const unique = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const name = `E2E ${prefix} ${unique}`;
  const response = await request.post('/api/projects', {
    headers: { Authorization: `Bearer ${accessToken}` },
    data: { name, slug: `e2e-${prefix.toLowerCase()}-${unique}` },
  });
  expect(
    response.ok(),
    `フィクスチャのプロジェクト作成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);

  return { id: ((await response.json()) as { id: number }).id, name };
}

/**
 * {@link createFixtureProject} で作ったプロジェクトを削除する(issue #844)。
 *
 * <p>{@code DELETE /api/projects/{id}} も {@code requireAdmin()} を通るため、
 * accessToken は**管理者**のものであること(issue #949)。
 */
export async function deleteFixtureProject(
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
