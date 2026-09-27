import type { APIRequestContext, Page } from '@playwright/test';
import { After, Step, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  composeServiceControl,
  expect,
  fetchAccessToken,
  waitForServicesHealthy,
} from '../support';
import {
  SERVICE_CONTROL_TIMEOUT_MS,
  startService,
  stopService,
} from '../support/serviceControl';

/**
 * 下流サービス障害時の縮退のステップ定義(issue #943 / AT-17)。
 *
 * 移行元は `apps/web/e2e/service-degradation.spec.ts`(削除済み)。移行元は
 * `E2E_ALLOW_SERVICE_DISRUPTION` が無ければ `test.skip` で黙って飛ばしていたが、
 * 「環境を壊すシナリオを隔離する」ことは `@destructive` の段階分離
 * (docs/ACCEPTANCE_TESTING.md §10)が担う。検証しない理由にはしない。
 *
 * 停止したサービスは {@link After} で必ず起動し直し、healthy になるまで待つ。
 * 待たずに次のシナリオへ進むと、無関係なシナリオが起動途中のサービスで落ちる。
 * 下の後始末は `ctx.stoppedServices` に積まれたものを見るので、**このファイル以外の
 * `@destructive` シナリオが止めたサービス**も同じように復旧する
 * (`support/serviceControl.ts` を参照。issue #937 の Penpot がそれである)。
 *
 * サービスの停止・起動のヘルパーは `support/serviceControl.ts` にある。
 */

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

// ------------------------------------------------- ダッシュボードの状態API(移行分)

When('ダッシュボードの状態APIが応答しない状態でトップページを開く', async ({ page }) => {
  // ブラウザ側の定期更新(ポーリング/SSE)だけを落とす。初期表示はサーバー側で取得済みなので、
  // 「更新に失敗しても直前の表示を維持し、エラー画面にはならない」ことを検証できる
  // (ConnectedServiceStatusPanel.tsx の意図した挙動)。
  await page.route('**/api/dashboard/service-status', (route) =>
    route.fulfill({ status: 503, body: 'service unavailable' })
  );
  await page.route('**/api/dashboard/service-status/stream', (route) =>
    route.fulfill({ status: 503, body: 'service unavailable' })
  );
  await page.goto('/');
});

Then('ダッシュボードは表示され続け、エラー画面にはならない', async ({ page }) => {
  await expect(page.locator('h1:has-text("ダッシュボード")')).toBeVisible();
  // グローバルエラーバウンダリ(app/error.tsx)が発火していないこと。
  await expect(page.getByText('エラーが発生しました')).toHaveCount(0);
  // 統計カードは表示され続ける(縮退してもナビゲーションできる状態を保つ)。
  await expect(page.locator('a:has-text("登録サイト数")')).toBeVisible();
});

// ------------------------------------------------------------- サービスの停止・復旧

// 「もし」でも「かつ(前提の続き)」でも使うため Step で定義する。
Step('content-serviceを停止する', async ({ ctx, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + SERVICE_CONTROL_TIMEOUT_MS);
  stopService(ctx, 'content');
});

When('ai-serviceを停止する', async ({ ctx, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + SERVICE_CONTROL_TIMEOUT_MS);
  stopService(ctx, 'ai');
});

When('media-serviceを停止する', async ({ ctx, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + SERVICE_CONTROL_TIMEOUT_MS);
  stopService(ctx, 'media');
});

When('log-writerを停止する', async ({ ctx, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + SERVICE_CONTROL_TIMEOUT_MS);
  stopService(ctx, 'log-writer');
});

When('content-serviceを復旧させる', async ({ ctx, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + SERVICE_CONTROL_TIMEOUT_MS);
  startService(ctx, 'content');
});

// 取得失敗の通知(FetchErrorNotice)。Next.jsのルートアナウンサーも role=alert を持つので文言で絞る。
function fetchErrorNotice(page: Page, label: string) {
  return page.getByRole('alert').filter({ hasText: `${label}を取得できませんでした` });
}

Then('投稿履歴ページは取得失敗を示し、0件とは表示しない', async ({ page }) => {
  await page.goto('/posts');
  // ページ自体は描画される(500やエラーバウンダリにならない)。
  await expect(page.locator('h1:has-text("投稿履歴")')).toBeVisible();
  await expect(page.getByText('エラーが発生しました')).toHaveCount(0);
  // 取得できなかったことを示し、「データが0件」とは表示しない(issue #1235)。
  await expect(fetchErrorNotice(page, '投稿履歴')).toBeVisible();
  await expect(page.getByText('全0件を表示')).toHaveCount(0);
  await expect(page.getByText('投稿履歴はまだありません', { exact: false })).toHaveCount(0);
});

Then('生成画像ギャラリーは取得失敗を示し、画像なしとは表示しない', async ({ page }) => {
  await page.goto('/image-gallery');
  await expect(page.locator('h1:has-text("生成画像ギャラリー")')).toBeVisible();
  await expect(page.getByText('エラーが発生しました')).toHaveCount(0);
  await expect(fetchErrorNotice(page, '生成画像')).toBeVisible();
  await expect(page.getByText('生成画像がありません', { exact: false })).toHaveCount(0);
});

Then('投稿履歴ページは通常どおり表示される', async ({ page }) => {
  await page.goto('/posts');
  await expect(page.locator('h1:has-text("投稿履歴")')).toBeVisible();
  await expect(page.getByText('エラーが発生しました')).toHaveCount(0);
  // 復旧したサービスから件数を取得できている(縮退時の「全0件」ではなく実際の件数が出る)。
  await expect(page.getByText(/全\d+件を表示/)).toBeVisible();
  // 復旧後は失敗の通知が残らない。
  await expect(fetchErrorNotice(page, '投稿履歴')).toHaveCount(0);
});

Then('AIを使わない画面とAPIは通常どおり使える', async ({ page, request }) => {
  // 画面: AIを使わない一覧が描画される。
  await page.goto('/sites');
  await expect(page.locator('h1:has-text("サイト")')).toBeVisible();
  await expect(page.getByText('エラーが発生しました')).toHaveCount(0);

  // API: AI以外のサービスは影響を受けない。
  const response = await request.get('/api/sites', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
  });
  expect(response.status(), 'ai-service の停止が無関係なAPIまで巻き込んでいる').toBe(200);
});

// ------------------------------------------------------- 公開できるWordPressサイト

interface ManagedSiteFixture {
  id: number;
  siteKey: string;
}

Step('管理者が公開可能なWordPressサイトを1件用意している', async ({ ctx, request, $testInfo }) => {
  // WordPress コンテナの払い出しを伴う(実測で20秒前後)。
  $testInfo.setTimeout($testInfo.timeout + SERVICE_CONTROL_TIMEOUT_MS);
  const siteKey = `at17deg${Date.now().toString().slice(-8)}`;
  const response = await request.post('/api/sites/managed-wordpress', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: {
      name: `AT-17 degradation ${siteKey}`,
      siteKey,
      title: 'AT-17 degradation',
      adminUser: 'at17admin',
      adminEmail: 'at17@example.com',
      adminPassword: 'At17Pass!2026',
    },
    timeout: 600_000,
  });
  expect(
    response.ok(),
    `マネージドWordPressの用意に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.managedSite = { id: ((await response.json()) as { id: number }).id, siteKey };
});

Then('記事の公開は成功する', async ({ ctx, request }) => {
  const site = ctx.managedSite as ManagedSiteFixture;
  const response = await request.post('/api/posts/publish', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    multipart: {
      site: site.siteKey,
      title: 'AT-17 media down',
      status: 'publish',
      markdown: '# media 停止中の公開\n\n画像を含まない記事は media に依存しない。',
    },
    timeout: 180_000,
  });
  expect(
    response.ok(),
    `media-service の停止が記事の公開を止めている (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

Then('管理者はサイトの登録と削除を完了できる', async ({ request }) => {
  const token = await adminToken(request);
  const siteKey = `at17log${Date.now().toString().slice(-8)}`;
  const created = await request.post('/api/sites', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name: `AT-17 log-writer down ${siteKey}`,
      siteKey,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'AGENT',
        baseUrl: 'http://wordpress',
        username: 'at17-fixture',
        appPassword: 'at17 fixture app password',
      },
    },
  });
  expect(
    created.ok(),
    `log-writer の停止が業務操作(サイト登録)を止めている (status=${created.status()}): `
      + `${await created.text()}`
  ).toBe(true);

  const deleted = await request.delete(`/api/sites/${((await created.json()) as { id: number }).id}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    deleted.ok(),
    `log-writer の停止が業務操作(サイト削除)を止めている (status=${deleted.status()})`
  ).toBe(true);
});

// ------------------------------------------------------------------- 後始末

After({ tags: '@destructive' }, async ({ ctx, request }) => {
  for (const service of (ctx.stoppedServices as string[] | undefined) ?? []) {
    composeServiceControl('start', service);
  }
  const stopped = (ctx.stoppedServices as string[] | undefined) ?? [];
  if (stopped.length > 0) {
    waitForServicesHealthy(stopped, 300);
  }
  ctx.stoppedServices = [];

  const site = ctx.managedSite as ManagedSiteFixture | undefined;
  if (site) {
    await request.delete(`/api/sites/${site.id}`, {
      headers: { Authorization: `Bearer ${await adminToken(request)}` },
      timeout: 300_000,
    });
  }
});
