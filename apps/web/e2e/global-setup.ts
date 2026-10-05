import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { request, type APIRequestContext, type FullConfig } from '@playwright/test';
import {
  describeMissingAccounts,
  executesSeedStage,
  findMissingAccounts,
  resolveAcceptanceResetMode,
  shouldCheckSyntheticAccounts,
  type AccountCheckResult,
} from './acceptance-accounts';
import { withAccountLock } from './account-lock';
import { acquireAcceptanceTestLock } from './at-lock';
import {
  checkBrowsersLaunchable,
  requiredBrowserNames,
  resolveExecutedProjectsFromArgv,
} from './browser-prerequisite';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  waitForServicesHealthy,
} from './helpers';

/**
 * E2E開始前の前提確認(issue #588)。
 *
 * サービス分割後のE2Eは、Keycloak・gateway・各ドメインサービス・reverse-proxy・web(Next.js)が
 * 全て起動していることを前提にする。`docker compose up -d` の直後は一部サービスがまだ起動途中で、
 * 「テストを流し始めた時刻」によって結果が変わる状態だったため、テスト開始前に
 * scripts/wait-for-stack-healthy.sh で全サービスのhealthyを待ってから開始する。
 *
 * さらに、コンテナがhealthyでも公開URL(https://localhost)経由で到達できるとは限らないため
 * (reverse-proxyの証明書・ルーティング設定の不備等)、baseURLとKeycloakのrealmエンドポイントへ
 * 実際にHTTPリクエストを1回ずつ投げて疎通を確認する。ここで失敗した場合はテストを開始しない
 * (原因不明の大量失敗ではなく、明確な前提エラーとして落とす)。
 *
 * issue #945 (AT-19) / #965: 受け入れテストは毎回まっさらな状態から始める。
 * ACCEPTANCE_RESET=1 が指定された場合、healthy待ちの**前に**スタックをゼロから構築し直す。
 * これは全プロジェクト(at-setup 以降)より前に走るので、撤去の直前に置いたウォッシュアウト・
 * プローブが「撤去で消えた」ことの証拠になりうる唯一の場所である(#965 §7-A)。
 * 破壊的なので、既定では実行しない。
 *
 * issue #1045: ブラウザが起動できないホストでは、Playwright は1本目のシナリオの中で
 * 落ち、残りは「did not run」になる(実測 1 failed / 72 did not run)。しかも原因は
 * 60 行のブラウザ起動ログに1行だけ埋もれる。docker のhealthy待ちや疎通確認と同じ理由で、
 * これも前提確認として先に、導入コマンドを添えて落とす。詳細は ./browser-prerequisite.ts。
 *
 * issue #1194: `FullConfig['projects']` は Playwright 自身の仕様により、`--project` で
 * 絞ってもなお**設定が宣言する全プロジェクト**を返す。これを理由に、以前はここで
 * 常に全プロジェクトのブラウザ(chromium/firefox/webkit)を確認していたため、
 * chromium しか使わない `npm run test:at:fast`(`--project=at-main`)まで firefox / webkit
 * の共有ライブラリ不足で落ちていた。ここでは `process.argv` から `--project` を自分で
 * 解析し、選択されたプロジェクトとその依存先だけに
 * 絞ってから(`resolveExecutedProjectsFromArgv`。`--no-deps` なら依存先は含めず、
 * 選択した段階だけ。#1634)必要ブラウザを求める。`--project` が指定されない
 * 実行(全プロジェクトを回す)では、これまで通り全プロジェクトを確認する。
 *
 * issue #1187: 受け入れテストはMySQL・Keycloakの合成アカウント・infra/e2e-stubsのエラー注入
 * 状態・WordPressのプロビジョニングといったホスト状態を共有する。2つの実行が重なると
 * 一方が仕込んだ状態を他方が横取りするため、ホスト単位で排他する(詳細は ./at-lock.ts)。
 * ブラウザ確認の**後**、ACCEPTANCE_RESET のゼロ構築(最大90分)の**前**に置く。
 * ブラウザが無いホストを無駄に待たせないのは#1045と同じ理由、ゼロ構築を排他の内側に
 * 入れるのは、撤去中に他方がテストしていたら意味がないため(#1187)。
 *
 * issue #1202: 無人ループを複数worktree・複数ブランチで並列に走らせると、この共有スタックが
 * どのブランチのコードを載せているかは誰も保証しない。プロダクションコードを変更した
 * ブランチが、別の作業ツリーが作ったスタック(=変更前のコード)に対して緑になる事故を防ぐため、
 * 何より先に(ブラウザ確認・ロック取得・ゼロ構築より前に) scripts/check-worktree-match.py
 * で「どの作業ツリーがこのスタックを作ったか」と「このブランチがorigin/developから
 * プロダクションコードを変更しているか」を確認する。判定ロジックはそこが唯一の実装であり、
 * ここに書き写さない。
 *
 * issue #1634: at-seed を含まない実行で合成アカウント(e2e-test@ / e2e-admin@)が欠けていると、
 * 個々のシナリオが `invalid_grant` 等で散発的に落ち原因が埋もれる。接続確認の後で、
 * `needsSetup:false` のときだけ両アカウントのトークン取得を確認し、欠けていれば名指しして落とす。
 * 判定は ./acceptance-accounts.ts。`ACCEPTANCE_RESET=data` は scripts/run-at-setup.sh が使う
 * データ層リセットで、ロック取得の内側で reset-acceptance-env.sh を実行する。
 *
 * 環境変数:
 *   ACCEPTANCE_RESET=data  : scripts/reset-acceptance-env.sh --yes(データ層のみ、約30秒)を実行してから始める
 *                            (入口は scripts/run-at-setup.sh。破壊的)
 *   ACCEPTANCE_RESET=1     : scripts/rebuild-acceptance-env.sh --yes を実行してから始める
 *                            (compose プロジェクトを撤去し、Docker ボリュームを破棄し、
 *                             ソースからビルドして起動し直す。破壊的)
 *   AT_LOCK_FILE / AT_LOCK_TIMEOUT_SECONDS / AT_LOCK_POLL_SECONDS
 *                           : 受け入れテストの排他ロックの設定(詳細は ./at-lock.ts)
 *   E2E_SKIP_BROWSER_CHECK=1 : Playwrightのブラウザ起動確認をスキップする
 *                            (ブラウザを起動できないホストで @api シナリオだけ回す場合向け。
 *                             docs/e2e-testing.md §3.3)
 *   E2E_SKIP_HEALTH_WAIT=1 : docker composeのhealthy待ちをスキップする
 *                            (スタック外でPlaywrightだけ動かす場合や、docker CLIが無い環境向け)
 *   E2E_HEALTH_TIMEOUT     : healthy待ちのタイムアウト秒数(既定600)
 *   AT_WORKTREE_CHECK_BYPASS=1 : 作業ツリー一致チェック(#1202)を明示的に迂回する唯一の
 *                            エスケープハッチ。迂回したことは標準出力に記録される。
 */
export default async function globalSetup(config: FullConfig): Promise<void> {
  const baseURL = config.projects[0]?.use?.baseURL ?? 'https://localhost';
  const executedProjects = resolveExecutedProjectsFromArgv(config.projects, process.argv);
  const repoRoot = path.resolve(__dirname, '..', '..', '..');

  // 作業ツリーの一致確認は全ての前に置く。ここで拒否された場合、後続のブラウザ確認・
  // ロック取得・ゼロ構築(最大90分)を無駄に費やす前に落とせる。
  console.log('[e2e] 共有スタックを作った作業ツリーとの一致を確認します');
  try {
    execFileSync(
      'python3',
      [path.join(repoRoot, 'scripts', 'check-worktree-match.py'), 'at-start'],
      { cwd: repoRoot, stdio: 'inherit' }
    );
  } catch {
    throw new Error(
      '共有スタックを作った作業ツリーと、テストしようとしている作業ツリーが一致しません。' +
        '詳細は上のログを参照してください。'
    );
  }

  // ブラウザの確認は全ての前に置く。ホスト内で完結し、数秒で終わり、他の前提に依存しない。
  // ACCEPTANCE_RESET のゼロ構築(最大90分)を終えてからブラウザで落ちるのは、待った分だけ無駄になる。
  if (process.env.E2E_SKIP_BROWSER_CHECK === '1') {
    console.log('[e2e] E2E_SKIP_BROWSER_CHECK=1 のため Playwright のブラウザ起動確認をスキップします');
  } else {
    const browsers = requiredBrowserNames(executedProjects);
    console.log(`[e2e] Playwright のブラウザが起動できることを確認します (${browsers.join(', ')})`);
    await checkBrowsersLaunchable(browsers);
  }

  await acquireAcceptanceTestLock();

  if (process.env.ACCEPTANCE_RESET === '1') {
    console.log('[e2e] ACCEPTANCE_RESET=1: 受け入れテスト環境をゼロから構築し直します(破壊的)');
    // ビルドキャッシュが効かない場合のイメージ再ビルドを含むため、上限は大きく取る。
    // 30分ではキャッシュ無しのゼロ構築に足りない(#965 の実測)。
    execFileSync(path.join(repoRoot, 'scripts', 'rebuild-acceptance-env.sh'), ['--yes'], {
      cwd: repoRoot,
      stdio: 'inherit',
      timeout: 5_400_000,
    });
  } else if (resolveAcceptanceResetMode(process.env.ACCEPTANCE_RESET) === 'data') {
    console.log('[e2e] ACCEPTANCE_RESET=data: データ層を初期化します(破壊的。ロックの内側)');
    execFileSync(path.join(repoRoot, 'scripts', 'reset-acceptance-env.sh'), ['--yes'], {
      cwd: repoRoot,
      stdio: 'inherit',
      timeout: 600_000,
    });
  }

  if (process.env.E2E_SKIP_HEALTH_WAIT === '1') {
    console.log('[e2e] E2E_SKIP_HEALTH_WAIT=1 のため docker compose のhealthy待ちをスキップします');
  } else {
    const timeoutSeconds = Number(process.env.E2E_HEALTH_TIMEOUT ?? '600');
    console.log('[e2e] 全サービスがhealthyになるまで待機します');
    waitForServicesHealthy(undefined, timeoutSeconds);
  }

  const context = await request.newContext({ baseURL, ignoreHTTPSErrors: true });
  try {
    const app = await context.get('/', { maxRedirects: 0 });
    // 未ログインでは "/" はログインへの誘導(3xx)になるため、2xx/3xxいずれも到達成功とみなす。
    if (app.status() >= 400) {
      throw new Error(`${baseURL}/ へ到達できません (status=${app.status()})`);
    }

    const realm = await context.get('/auth/realms/letsblog/.well-known/openid-configuration');
    if (!realm.ok()) {
      throw new Error(
        `Keycloakのrealmエンドポイントへ到達できません (status=${realm.status()})。` +
          'docker compose の keycloak / reverse-proxy を確認してください。'
      );
    }

    await verifySyntheticAccounts(context, executedProjects);
  } finally {
    await context.dispose();
  }

  console.log('[e2e] 前提確認OK: ブラウザ起動、全サービスhealthy、公開URLとKeycloakへ疎通');
}

/**
 * 合成アカウントがパスワードグラントでトークンを取得できるか確認する(#1634)。
 *
 * `fetchAccessToken`(token-cache.ts)は5分間のキャッシュを持つため、リセットで消えた直後の
 * アカウントを「取得できた」と誤判定しうる。確認の目的は「今 Keycloak にいるか」なので、
 * キャッシュを経由せず直接グラントを送る。ブルートフォース検知(#1295)を避けるため、
 * 他の経路と同じアカウント単位ロックの内側で送る。
 */
async function verifySyntheticAccounts(
  context: APIRequestContext,
  executedProjects: Parameters<typeof executesSeedStage>[0]
): Promise<void> {
  const setupStatus = await context.get('/api/auth/setup-status');
  let needsSetup: boolean | undefined;
  if (setupStatus.ok()) {
    try {
      const body = (await setupStatus.json()) as { needsSetup?: unknown };
      if (typeof body.needsSetup === 'boolean') needsSetup = body.needsSetup;
    } catch {
      needsSetup = undefined;
    }
  }

  if (!shouldCheckSyntheticAccounts({ executesSeed: executesSeedStage(executedProjects), needsSetup })) {
    return;
  }

  console.log('[e2e] at-seed を含まない実行のため、E2E 合成アカウントの存在を確認します');
  const accounts = [
    { email: E2E_TEST_EMAIL, password: E2E_TEST_PASSWORD },
    { email: E2E_ADMIN_EMAIL, password: E2E_ADMIN_PASSWORD },
  ];
  const results: AccountCheckResult[] = [];
  for (const account of accounts) {
    const ok = await withAccountLock(account.email, async () => {
      // e2e-login-guard:locked — withAccountLock の内側(#1295)。
      const response = await context.post('/auth/realms/letsblog/protocol/openid-connect/token', {
        form: {
          grant_type: 'password',
          client_id: 'letsblog-e2e',
          username: account.email,
          password: account.password,
        },
      });
      return response.ok();
    });
    results.push({ email: account.email, ok });
  }

  const missing = findMissingAccounts(results);
  if (missing.length > 0) {
    throw new Error(describeMissingAccounts(missing));
  }
}
