import { request, type FullConfig } from '@playwright/test';
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
 * 環境変数:
 *   E2E_SKIP_HEALTH_WAIT=1 : docker composeのhealthy待ちをスキップする
 *                            (スタック外でPlaywrightだけ動かす場合や、docker CLIが無い環境向け)
 *   E2E_HEALTH_TIMEOUT     : healthy待ちのタイムアウト秒数(既定600)
 */
export default async function globalSetup(config: FullConfig): Promise<void> {
  const baseURL = config.projects[0]?.use?.baseURL ?? 'https://localhost';

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

  console.log('[e2e] 前提確認OK: 全サービスhealthy、公開URLとKeycloakへ疎通');
}
