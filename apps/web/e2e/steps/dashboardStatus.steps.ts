import { execFileSync } from 'node:child_process';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { expect } from '../support';

/**
 * ダッシュボードの状態表示(サービス状態パネル・コンテナ状態パネル・SSE)を支える
 * ステップ定義(issue #1154。親 #940 / AT-14 のシナリオ5・6・7・9)。
 *
 * `platform.steps.ts`(システム設定・バックアップ・拡張配布)とはスコープが異なるため、
 * このファイルには dashboard-status.feature が必要とするステップだけを置く。
 */

// ---- サービス状態パネル(#876の退行検知) ----

Then(
  'サービス状態パネルに認証エラーは表示されず、1件以上のサービス状態がエラー表示なしで表示される',
  async ({ page }) => {
    const panel = page
      .locator('section')
      .filter({ has: page.getByRole('heading', { name: '接続サービスの稼働状況' }) });
    const rows = panel.locator('ul > li');
    await expect(rows.first()).toBeVisible({ timeout: 30_000 });
    const count = await rows.count();
    expect(count, 'サービス状態パネルに1件も表示されていません').toBeGreaterThan(0);
    // #876の症状そのもの: 401/403が握り潰されると赤字の認証エラー文言が出る(修正後は出ない)。
    await expect(panel.getByText('認証されていないため')).toHaveCount(0);
    // 「認証エラー文言が無い」だけに留めると、#876と無関係な理由で個別サービスがERROR表示に
    // なっていても見逃す(このシナリオのACは「稼働中の全サービスがhealthyとして表示される」)。
    // e2e環境にはComfyUIのスタブが用意されており、表示される行のうち本来ERRORになるはずの
    // ものは無いため、行のいずれもエラー表示になっていないことまで確認する。
    await expect(rows.getByText('エラー', { exact: true })).toHaveCount(0);
  }
);

// ---- コンテナ状態パネル(#803・#725の退行検知) ----

const FOREIGN_PROJECT_LABEL = 'e2e1154-foreign-project';

function dockerRunOneShot(name: string, projectLabel: string, exitCode: number): void {
  execFileSync(
    'docker',
    [
      'run',
      '-d',
      '--name',
      name,
      '--label',
      `com.docker.compose.project=${projectLabel}`,
      '--restart',
      'no',
      'busybox',
      'sh',
      '-c',
      `exit ${exitCode}`,
    ],
    { stdio: 'pipe', timeout: 60_000 }
  );
  // 終了を待つ(dのままだと稼働状況APIが見た時点でまだrunningのことがある)。
  execFileSync('docker', ['wait', name], { stdio: 'pipe', timeout: 60_000 });
}

function removeContainer(name: string): void {
  try {
    execFileSync('docker', ['rm', '-f', name], { stdio: 'pipe', timeout: 60_000 });
  } catch {
    // 既に無ければ無視する(シナリオ自身が消している場合がある)。
  }
}

Given('別プロジェクトを装った使い捨てコンテナを起動する', async ({ ctx }) => {
  const name = `lbs-e2e1154foreign${Date.now()}`;
  ctx.dashboardStatusForeignContainer = name;
  dockerRunOneShot(name, FOREIGN_PROJECT_LABEL, 0);
});

Given(
  '本プロジェクト扱いの、正常終了して抜ける使い捨てワンショットコンテナを起動する',
  async ({ ctx }) => {
    const name = `lbs-e2e1154oneshot${Date.now()}`;
    ctx.dashboardStatusOneShotContainer = name;
    dockerRunOneShot(name, 'lets_blog_server', 0);
  }
);

/**
 * `/api/dashboard/container-status` はnginxの`location /api/dashboard/`がgatewayではなく
 * webへ振り分ける(#876)。webのRoute Handlerはセッションcookie(next-authのHttpOnly cookie)を
 * 前提にしており、Bearerトークンを渡す`request`フィクスチャでは認証できずログイン画面のHTMLが
 * 返る。管理者としてログイン済みの`page.request`(cookieを共有する)を使うこと。
 */
async function fetchContainerStatuses(
  pageRequest: APIRequestContext
): Promise<{ name: string; status: string }[]> {
  const response = await pageRequest.get('/api/dashboard/container-status');
  expect(
    response.ok(),
    `コンテナ状態の取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as { name: string; status: string }[];
}

Then('コンテナ状態パネルにその使い捨てコンテナは表示されない', async ({ ctx, page }) => {
  const containers = await fetchContainerStatuses(page.request);
  const shortName = (ctx.dashboardStatusForeignContainer as string).replace(/^lbs-/, '');
  expect(containers.map((c) => c.name)).not.toContain(shortName);
});

Then('コンテナ状態パネルでその使い捨てコンテナはNORMALと表示される', async ({ ctx, page }) => {
  const containers = await fetchContainerStatuses(page.request);
  const shortName = (ctx.dashboardStatusOneShotContainer as string).replace(/^lbs-/, '');
  const entry = containers.find((c) => c.name === shortName);
  expect(entry, `使い捨てコンテナ ${shortName} がコンテナ状態一覧に見つかりません`).toBeDefined();
  expect(entry!.status).toBe('NORMAL');
});

After({ tags: '@destructive and @platform' }, async ({ ctx }) => {
  if (ctx.dashboardStatusForeignContainer) {
    removeContainer(ctx.dashboardStatusForeignContainer as string);
  }
  if (ctx.dashboardStatusOneShotContainer) {
    removeContainer(ctx.dashboardStatusOneShotContainer as string);
  }
});

// ---- SSEでの継続的な更新(parent scenario 9) ----

/**
 * ConnectedServiceStatusBroadcaster / ContainerStatusBroadcasterは購読直後に1件即時送信し、
 * 以後BROADCAST_INTERVAL_MS(15秒)ごとに配信する。イベント名は既定の"message"ではなく
 * "status"(`SseEmitter.event().name("status")`)なので、`onmessage`では拾えず
 * `addEventListener("status", ...)`が必要(既定名のイベントしか拾わない典型的な誤り)。
 * 2件目の到達まで最大15秒強かかりうるため、30秒の猶予を持たせる。
 */
async function subscribeAndCountEvents(page: import('@playwright/test').Page, path: string): Promise<number> {
  return page.evaluate(async (streamPath) => {
    return new Promise<number>((resolve, reject) => {
      const events: string[] = [];
      const source = new EventSource(streamPath);
      const finish = () => {
        source.close();
        resolve(events.length);
      };
      const hardTimeout = setTimeout(finish, 25000);
      source.addEventListener('status', (event: MessageEvent<string>) => {
        events.push(event.data);
        if (events.length >= 2) {
          clearTimeout(hardTimeout);
          finish();
        }
      });
      source.onerror = () => {
        // 初回接続まで多少の再試行が起きうるため、ここでは即失敗にしない。
        // タイムアウトで解決させ、受信数で判定する。
      };
      setTimeout(() => {
        clearTimeout(hardTimeout);
        source.close();
        reject(new Error('SSE接続がタイムアウトしました'));
      }, 30000);
    });
  }, path);
}

When('サービス状態のSSEストリームを購読する', async ({ ctx, page }) => {
  ctx.dashboardStatusSseEventCount = await subscribeAndCountEvents(
    page,
    '/api/dashboard/service-status/stream'
  );
});

When('コンテナ状態のSSEストリームを購読する', async ({ ctx, page }) => {
  ctx.dashboardStatusSseEventCount = await subscribeAndCountEvents(
    page,
    '/api/dashboard/container-status/stream'
  );
});

Then('一定時間内に2件以上の更新イベントを受信する', async ({ ctx }) => {
  expect(ctx.dashboardStatusSseEventCount as number).toBeGreaterThanOrEqual(2);
});
