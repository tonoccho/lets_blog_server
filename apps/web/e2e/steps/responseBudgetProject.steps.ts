import { Given, When } from './fixtures';
import { expect } from '../support';
import {
  adminHeaders,
  registerCleanup,
  uniqueSuffix,
  waitForHydrated,
} from '../support/responseBudgetFixtures';
import {
  expectVisibleText,
  fieldLocator,
  formOf,
  measureAndRecord,
  openLocation,
  parsePairs,
  projectId,
} from '../support/responseBudgetProject';

/**
 * プロジェクト詳細まわり(`app/projects/actions.ts` と `app/projects/[id]/actions.ts` のうち、
 * 設定・環境・メンバーを扱うもの)の Server Action の3秒予算シナリオ(issue #1477)のステップ定義。
 *
 * 単純な「入力して押す」「選んで押す」「押す」操作は汎用のステップにまとめ、画面ごとに計測を持たない
 * (計測は `support/responseBudget.ts` の共通ヘルパー)。確認ダイアログは常に承諾する。
 * 期待表示が空文字のときは、押したあとの表示を確認せず、Server Action の往復が起きたことだけを確かめる
 * (往復が起きなければ計測側が失敗する)。
 */

// ---- 汎用: 入力して押す / 選んで押す / チェックして押す / 押す ----

When(
  /^プロジェクトの「([^」]+)」(?:タブ|画面)で入力「([^」]+)」して「([^」]+)」を押し「([^」]*)」を確認して「([^」]+)」の往復を計測する$/,
  async ({ page, ctx }, location: string, pairs: string, button: string, expected: string, operation: string) => {
    await openLocation(page, ctx, location);
    const fields = parsePairs(pairs);
    const first = fieldLocator(page, fields[0][0]);
    await expect(first).toBeVisible({ timeout: 30_000 });
    await waitForHydrated(first);
    for (const [name, value] of fields) {
      await fieldLocator(page, name).fill(value);
    }
    const submit = formOf(first).getByRole('button', { name: button, exact: true });
    await measureAndRecord(page, ctx, operation, async () => {
      await submit.click();
      await expectVisibleText(page, expected);
    });
  }
);

When(
  /^プロジェクトの「([^」]+)」(?:タブ|画面)で選択「([^」]+)」して「([^」]+)」を押し「([^」]*)」を確認して「([^」]+)」の往復を計測する$/,
  async ({ page, ctx }, location: string, pairs: string, button: string, expected: string, operation: string) => {
    await openLocation(page, ctx, location);
    const fields = parsePairs(pairs);
    const first = fieldLocator(page, fields[0][0]);
    await expect(first).toBeVisible({ timeout: 30_000 });
    await waitForHydrated(first);
    for (const [name, value] of fields) {
      await fieldLocator(page, name).selectOption(value);
    }
    const submit = formOf(first).getByRole('button', { name: button, exact: true });
    await measureAndRecord(page, ctx, operation, async () => {
      await submit.click();
      await expectVisibleText(page, expected);
    });
  }
);

When(
  /^プロジェクトの「([^」]+)」(?:タブ|画面)でチェック「([^」]+)」を切り替えて「([^」]+)」を押し「([^」]*)」を確認して「([^」]+)」の往復を計測する$/,
  async ({ page, ctx }, location: string, names: string, button: string, expected: string, operation: string) => {
    await openLocation(page, ctx, location);
    const boxes = names.split(';').map((name) => fieldLocator(page, name));
    await expect(boxes[0]).toBeVisible({ timeout: 30_000 });
    await waitForHydrated(boxes[0]);
    for (const box of boxes) await box.click();
    const submit = formOf(boxes[0]).getByRole('button', { name: button, exact: true });
    await measureAndRecord(page, ctx, operation, async () => {
      await submit.click();
      await expectVisibleText(page, expected);
    });
  }
);

When(
  /^プロジェクトの「([^」]+)」(?:タブ|画面)で「([^」]+)」を押し「([^」]*)」を確認して「([^」]+)」の往復を計測する$/,
  async ({ page, ctx }, location: string, button: string, expected: string, operation: string) => {
    await openLocation(page, ctx, location);
    page.on('dialog', (dialog) => void dialog.accept());
    const target = page.getByRole('button', { name: button, exact: true }).first();
    await expect(target).toBeVisible({ timeout: 30_000 });
    await waitForHydrated(target);
    await measureAndRecord(page, ctx, operation, async () => {
      await target.click();
      await expectVisibleText(page, expected);
    });
  }
);

// ---- プロジェクトの作成・削除(app/projects/actions.ts) ----

When('プロジェクト一覧画面でプロジェクトを作成して Server Action の往復を計測する', async ({ page, request, ctx }) => {
  const suffix = uniqueSuffix();
  const slug = `e2e-1477-create-${suffix}`;
  registerCleanup(ctx, async () => {
    const headers = await adminHeaders(request);
    const response = await request.get('/api/projects', { headers });
    if (!response.ok()) return;
    const projects = (await response.json()) as { id: number; slug: string }[];
    for (const project of projects.filter((p) => p.slug === slug)) {
      await request.delete(`/api/projects/${project.id}`, { headers });
    }
  });
  await page.goto('/projects');
  const name = page.locator('input[name="name"]');
  await expect(name).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(name);
  await name.fill(`E2E 1477 create ${suffix}`);
  await page.locator('input[name="slug"]').fill(slug);
  await measureAndRecord(page, ctx, 'プロジェクトの作成', async () => {
    await formOf(name).getByRole('button', { name: '作成', exact: true }).click();
    await expect(page.getByText('作成しました。')).toBeVisible({ timeout: 30_000 });
  });
});

Given('応答時間予算の検証用に削除してよいプロジェクトがある', async ({ request, ctx }) => {
  const suffix = uniqueSuffix();
  const headers = await adminHeaders(request);
  const created = await request.post('/api/projects', {
    headers,
    data: { name: `E2E 1477 delete ${suffix}`, slug: `e2e-1477-delete-${suffix}` },
  });
  expect(created.ok(), `プロジェクトの作成に失敗しました (status=${created.status()})`).toBe(true);
  const id = ((await created.json()) as { id: number }).id;
  ctx.responseBudgetProjectId = id;
  // 削除のシナリオで消えていれば何もしない(共通の After はこの id を使わないよう下で外す)。
  registerCleanup(ctx, async () => {
    await request.delete(`/api/projects/${id}`, { headers: await adminHeaders(request) });
  });
});

When('プロジェクト詳細画面でプロジェクトを削除して Server Action の往復を計測する', async ({ page, ctx }) => {
  const id = projectId(ctx);
  await page.goto(`/projects/${id}`);
  page.once('dialog', (dialog) => void dialog.accept());
  const button = page.getByRole('button', { name: 'プロジェクトを削除', exact: true });
  await expect(button).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(button);
  // 共通の After は ctx.responseBudgetProjectId のプロジェクトを削除して成功を要求するので、
  // 削除済みになるこのシナリオでは外し、後片付けは registerCleanup 側に任せる。
  delete ctx.responseBudgetProjectId;
  await measureAndRecord(page, ctx, 'プロジェクトの削除', async () => {
    await button.click();
    await expect(page).toHaveURL(/\/projects$/, { timeout: 30_000 });
  });
});

// ---- 環境の紐付け(bindEnvironmentAction / unbindEnvironmentAction) ----

Given('そのプロジェクトのテスト環境にサイトが紐付いている', async ({ request, ctx }) => {
  const response = await request.post(`/api/projects/${projectId(ctx)}/environments`, {
    headers: await adminHeaders(request),
    data: { environment: 'test', siteId: ctx.responseBudgetSiteId as number },
  });
  expect(response.ok(), `環境の紐付けに失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
});

When('プロジェクト詳細画面でテスト環境にサイトを紐付けて Server Action の往復を計測する', async ({ page, ctx }) => {
  await openLocation(page, ctx, '概要');
  const select = page.getByLabel('テスト環境に紐付けるサイト');
  await expect(select).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(select);
  await select.selectOption(String(ctx.responseBudgetSiteId));
  await measureAndRecord(page, ctx, '環境へのサイトの紐付け', async () => {
    await formOf(select).getByRole('button', { name: '紐付ける', exact: true }).click();
    await expect(page.getByRole('button', { name: '切離し', exact: true })).toBeVisible({ timeout: 30_000 });
  });
});

When('プロジェクト詳細画面でテスト環境のサイトを切り離して Server Action の往復を計測する', async ({ page, ctx }) => {
  await openLocation(page, ctx, '概要');
  const button = page.getByRole('button', { name: '切離し', exact: true });
  await expect(button).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(button);
  await measureAndRecord(page, ctx, '環境からのサイトの切離し', async () => {
    await button.click();
    await expect(page.getByLabel('テスト環境に紐付けるサイト')).toBeVisible({ timeout: 30_000 });
  });
});

// ---- 外部サービスの設定(APIキー・Google 連携)の前提 ----

Given('そのプロジェクトにGitHubトークンが設定されている', async ({ request, ctx }) => {
  const response = await request.put(`/api/projects/${projectId(ctx)}/api-keys/github-token`, {
    headers: await adminHeaders(request),
    data: { githubToken: 'ghp_e2e1477budgetfixture0000000000000000' },
  });
  expect(response.ok(), `GitHubトークンの保存に失敗しました (status=${response.status()})`).toBe(true);
});

Given('そのプロジェクトにBrave Search APIキーが設定されている', async ({ request, ctx }) => {
  const response = await request.put(`/api/projects/${projectId(ctx)}/api-keys/brave-search-api-key`, {
    headers: await adminHeaders(request),
    data: { apiKey: 'BSA-e2e1477-budget-fixture' },
  });
  expect(response.ok(), `Brave APIキーの保存に失敗しました (status=${response.status()})`).toBe(true);
});

const GA_CLIENT_ID = 'at1231-ga.apps.googleusercontent.com';
const ADSENSE_CLIENT_ID = 'at13-acceptance.apps.googleusercontent.com';

async function putOk(
  response: Awaited<ReturnType<import('@playwright/test').APIRequestContext['put']>>,
  what: string
): Promise<void> {
  expect(response.ok(), `${what}に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
}

Given('そのプロジェクトにGoogle Analyticsが連携済みでプロパティを選べる', async ({ request, ctx }) => {
  const id = projectId(ctx);
  const headers = await adminHeaders(request);
  await putOk(
    await request.put(`/api/projects/${id}/api-keys/google-analytics/client`, {
      headers,
      data: { clientId: GA_CLIENT_ID, clientSecret: `e2e-1477-${uniqueSuffix()}` },
    }),
    'GAのOAuthクライアント保存'
  );
  const callback = await request.post(`/api/projects/${id}/api-keys/google-analytics/oauth-callback`, {
    headers,
    data: { code: 'at1231-authorization-code', redirectUri: 'https://localhost/connect/google-analytics/callback' },
  });
  expect(callback.status(), `GAのOAuth連携に失敗しました: ${await callback.text()}`).toBe(204);
});

Given('そのプロジェクトにAdSenseが連携済みでアカウントを選べる', async ({ request, ctx }) => {
  const id = projectId(ctx);
  const headers = await adminHeaders(request);
  await putOk(
    await request.put(`/api/projects/${id}/api-keys/adsense`, { headers, data: { clientId: ADSENSE_CLIENT_ID } }),
    'AdSenseの設定保存'
  );
  await putOk(
    await request.put(`/api/projects/${id}/api-keys/adsense/client-secret`, {
      headers,
      data: { clientSecret: `e2e-1477-${uniqueSuffix()}` },
    }),
    'AdSenseのクライアントシークレット保存'
  );
  const callback = await request.post(`/api/projects/${id}/api-keys/adsense/oauth-callback`, {
    headers,
    data: { code: 'e2e-stub-adsense-multi-accounts-code', redirectUri: 'https://localhost/connect/adsense/callback' },
  });
  expect(callback.status(), `AdSenseのOAuth連携に失敗しました: ${await callback.text()}`).toBe(204);
});
