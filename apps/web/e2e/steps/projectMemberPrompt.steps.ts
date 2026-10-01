import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  createFixtureProject,
  deleteFixtureProject,
  expect,
  fetchAccessToken,
} from '../support';

/**
 * プロジェクトメンバーの追加を促す案内のステップ定義(issue #1069)。
 *
 * 「管理者としてログインする」は auth.steps.ts の共通ステップを再利用する。
 * 検証用プロジェクトとサイトは毎回一意な名前で作り、{@link After} で必ず片付ける。
 */

const NOTICE_NO_MEMBERS = 'このプロジェクトにはメンバーがいません';
const NOTICE_BIND_SITE_FIRST = '先にサイトを紐付けてください';

type PromptCtx = {
  mpProjectId?: number;
  mpSiteId?: number;
  /** 故障注入(#1519)を掛けたプロジェクトID。After で必ず解除する。 */
  mpFaultProjectId?: number;
};

const NOTICE_MEMBERS_UNAVAILABLE = 'メンバー情報を取得できませんでした';

/** gateway の故障注入の制御パス(docker-compose.e2e-stubs.yml でだけ有効。docs/ACCEPTANCE_TESTING.md 参照)。 */
const faultInjectionPath = (projectId: number): string => `/api/__fault-injection/project-users/${projectId}`;

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function createProject(request: APIRequestContext, ctx: Record<string, unknown>): Promise<number> {
  const project = await createFixtureProject(request, await adminToken(request), 'mp1069');
  (ctx as PromptCtx).mpProjectId = project.id;
  return project.id;
}

/** WordPress を構築せずに済む、AGENT 接続の登録だけのサイトを作って test 環境へ紐付ける。 */
async function bindFixtureSite(
  request: APIRequestContext,
  ctx: Record<string, unknown>,
  projectId: number
): Promise<void> {
  const headers = { Authorization: `Bearer ${await adminToken(request)}` };
  const siteKey = `mp1069${Date.now().toString().slice(-8)}`;
  const created = await request.post('/api/sites', {
    headers,
    data: {
      name: `mp1069 ${siteKey}`,
      siteKey,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'AGENT',
        baseUrl: 'http://wordpress',
        username: 'mp1069-fixture',
        appPassword: 'mp1069 fixture app password',
      },
    },
  });
  expect(
    created.ok(),
    `サイトの登録に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const siteId = ((await created.json()) as { id: number }).id;
  (ctx as PromptCtx).mpSiteId = siteId;

  const bound = await request.post(`/api/projects/${projectId}/environments`, {
    headers,
    data: { environment: 'test', siteId },
  });
  expect(
    bound.ok(),
    `サイトの紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`
  ).toBe(true);
}

Given('サイトが紐付いていてメンバーが居ないプロジェクトがある', async ({ request, ctx }) => {
  const projectId = await createProject(request, ctx);
  await bindFixtureSite(request, ctx, projectId);
});

Given('サイトが紐付いておらずメンバーも居ないプロジェクトがある', async ({ request, ctx }) => {
  await createProject(request, ctx);
});

Given('メンバーが1人以上居るプロジェクトがある', async ({ request, ctx }) => {
  // サイト未紐付けのまま追加すれば WordPress への同期は走らない(メンバー行の有無だけを作る)。
  const projectId = await createProject(request, ctx);
  const headers = { Authorization: `Bearer ${await adminToken(request)}` };
  const users = await request.get('/api/users', { headers });
  expect(users.ok(), `ユーザー一覧の取得に失敗しました (status=${users.status()})`).toBe(true);
  const member = ((await users.json()) as { id: number; email: string }[]).find(
    (user) => user.email === E2E_TEST_EMAIL
  );
  expect(member, `メンバーに加える ${E2E_TEST_EMAIL} が見つかりません`).toBeTruthy();
  const added = await request.post(`/api/projects/${projectId}/users`, {
    headers,
    data: { userId: member!.id, wpRole: 'author' },
  });
  expect(
    added.ok(),
    `メンバーの追加に失敗しました (status=${added.status()}): ${await added.text()}`
  ).toBe(true);
});

Given('メンバー一覧の取得が失敗する状態にする', async ({ request, ctx }) => {
  // 対象はこのシナリオ専用のプロジェクトだけ。プロジェクトID単位の注入なので、
  // 並列に走る他シナリオ(ダッシュボードのメンバー一覧など)は巻き込まない。
  const projectId = await createProject(request, ctx);
  const injected = await request.put(faultInjectionPath(projectId), {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
  });
  expect(
    injected.status(),
    `故障注入に失敗しました。gateway が docker-compose.e2e-stubs.yml を重ねた構成で起動していません (status=${injected.status()})`
  ).toBe(204);
  (ctx as PromptCtx).mpFaultProjectId = projectId;
});

When(/^(?:その)?プロジェクトの詳細を開く$/, async ({ page, ctx }) => {
  await page.goto(`/projects/${(ctx as PromptCtx).mpProjectId}`);
});

When('そのプロジェクトの詳細のメンバータブを開く', async ({ page, ctx }) => {
  await page.goto(`/projects/${(ctx as PromptCtx).mpProjectId}`);
  await page.getByRole('button', { name: 'メンバー', exact: true }).click();
});

Then('概要タブにメンバーが居ない旨の案内が表示される', async ({ page }) => {
  await expect(page.getByText(NOTICE_NO_MEMBERS)).toBeVisible();
});

Then('その案内からメンバー追加の操作へ進める', async ({ page }) => {
  await expect(page.getByRole('combobox', { name: 'ユーザーを追加' })).toBeVisible();
  await expect(page.getByRole('button', { name: '追加', exact: true })).toBeVisible();
});

Then('概要タブに先にサイトを紐付けるよう促す案内が表示される', async ({ page }) => {
  await expect(page.getByText(NOTICE_BIND_SITE_FIRST)).toBeVisible();
});

Then('メンバー追加を促す案内は表示されない', async ({ page }) => {
  await expect(page.getByText(NOTICE_BIND_SITE_FIRST)).toBeVisible(); // 画面が描き終わってから否定を見る
  await expect(page.getByText(NOTICE_NO_MEMBERS)).toHaveCount(0);
  await expect(page.getByRole('combobox', { name: 'ユーザーを追加' })).toHaveCount(0);
});

Then('メンバーが居ない旨の案内は表示されない', async ({ page }) => {
  // プロジェクト詳細が描き終わるのを待つ。h1 は 404 ページにもあるため、詳細にしか無いタブで待つ。
  await expect(page.getByRole('button', { name: 'メンバー', exact: true })).toBeVisible();
  await expect(page.getByText(NOTICE_NO_MEMBERS)).toHaveCount(0);
});

Then('メンバーが居ない旨と、追加方法の案内が表示される', async ({ page }) => {
  await expect(page.getByText('参加ユーザーはいません')).toBeVisible();
  await expect(page.getByText('「ユーザーを追加」から追加してください')).toBeVisible();
});

Then('メンバー情報を取得できなかったことが表示される', async ({ page }) => {
  await expect(page.getByText(NOTICE_MEMBERS_UNAVAILABLE).first()).toBeVisible();
});

After({ tags: '@project-member-prompt' }, async ({ request, ctx }) => {
  const { mpProjectId, mpSiteId, mpFaultProjectId } = ctx as PromptCtx;
  const token = await adminToken(request);
  if (mpFaultProjectId !== undefined) {
    // 失敗したシナリオでも必ず解除する。解除し損ねると、そのプロジェクトのメンバー一覧が5xxのまま残る。
    await request.delete(faultInjectionPath(mpFaultProjectId), { headers: { Authorization: `Bearer ${token}` } });
  }
  if (mpProjectId !== undefined) {
    await deleteFixtureProject(request, token, mpProjectId);
  }
  if (mpSiteId !== undefined) {
    await request.delete(`/api/sites/${mpSiteId}`, { headers: { Authorization: `Bearer ${token}` } });
  }
});
