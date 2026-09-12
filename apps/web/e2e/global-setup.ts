import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { request, type FullConfig } from '@playwright/test';
import { acquireAcceptanceTestLock } from './at-lock';
import { checkBrowsersLaunchable, requiredBrowserNames } from './browser-prerequisite';
import { waitForServicesHealthy } from './helpers';

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
 * issue #1187: 受け入れテストはMySQL・Keycloakの合成アカウント・infra/e2e-stubsのエラー注入
 * 状態・WordPressのプロビジョニングといったホスト状態を共有する。2つの実行が重なると
 * 一方が仕込んだ状態を他方が横取りするため、ホスト単位で排他する(詳細は ./at-lock.ts)。
 * ブラウザ確認の**後**、ACCEPTANCE_RESET のゼロ構築(最大90分)の**前**に置く。
 * ブラウザが無いホストを無駄に待たせないのは#1045と同じ理由、ゼロ構築を排他の内側に
 * 入れるのは、撤去中に他方がテストしていたら意味がないため(#1187)。
 *
 * 環境変数:
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
 */
export default async function globalSetup(config: FullConfig): Promise<void> {
  const baseURL = config.projects[0]?.use?.baseURL ?? 'https://localhost';
  const repoRoot = path.resolve(__dirname, '..', '..', '..');

  // ブラウザの確認は全ての前に置く。ホスト内で完結し、数秒で終わり、他の前提に依存しない。
  // ACCEPTANCE_RESET のゼロ構築(最大90分)を終えてからブラウザで落ちるのは、待った分だけ無駄になる。
  if (process.env.E2E_SKIP_BROWSER_CHECK === '1') {
    console.log('[e2e] E2E_SKIP_BROWSER_CHECK=1 のため Playwright のブラウザ起動確認をスキップします');
  } else {
    const browsers = requiredBrowserNames(config.projects);
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
  } finally {
    await context.dispose();
  }

  console.log('[e2e] 前提確認OK: ブラウザ起動、全サービスhealthy、公開URLとKeycloakへ疎通');
}
