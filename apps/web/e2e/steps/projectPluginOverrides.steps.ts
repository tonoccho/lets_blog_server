import type { APIRequestContext, Locator, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  createFixtureProject,
  deleteFixtureProject,
  expect,
  fetchAccessToken,
} from '../support';

/**
 * プロジェクト単位のプラグインの登録の上書き(issue #1808)のステップ定義。
 * 対象API: `/api/projects/{id}/plugin-registrations`。上書きはプロジェクトごとなので、使い捨てのプロジェクトを
 * Given で作り After で消す(プロジェクトを消すと上書きの行は残るが、プロジェクト ID は再利用されない)。
 * ヘルパーはこのファイルの中に閉じて持つ(兄弟 Issue のステップ定義ファイルと相乗りしない慣行)。
 */

type ScenarioState = Record<string, unknown>;

interface OverrideView {
  id: number;
  kind: string;
  name: string;
  url: string;
  inUse: boolean;
  drawioUrl: string | null;
  credentialConfigured: boolean;
}

async function bearer(request: APIRequestContext, email: string, password: string) {
  return { Authorization: `Bearer ${await fetchAccessToken(request, email, password)}` };
}
const adminHeaders = (request: APIRequestContext) => bearer(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);

function userHeaders(ctx: ScenarioState): Record<string, string> {
  return { Authorization: `Bearer ${ctx.userToken as string}` };
}

const api = (ctx: ScenarioState) => `/api/projects/${ctx.overrideProjectId as number}/plugin-registrations`;

Given('上書きの検証用のプロジェクトがあり、一般ユーザーはメンバーではない', async ({ ctx, request }) => {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  ctx.overrideProjectId = (await createFixtureProject(request, token, 'at-1808-override')).id;
});

Given('一般ユーザーが上書き検証のプロジェクトのメンバーである', async ({ ctx, request }) => {
  const me = await request.get('/api/identity/me', {
    headers: await bearer(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD),
  });
  expect(me.ok(), `一般ユーザーの情報を取得できませんでした (status=${me.status()})`).toBe(true);
  const userId = ((await me.json()) as { id: number }).id;
  const added = await request.post(`/api/projects/${ctx.overrideProjectId as number}/users`, {
    headers: await adminHeaders(request),
    data: { userId, wpRole: 'editor' },
  });
  expect(added.ok(), `メンバー追加に失敗しました (status=${added.status()}): ${await added.text()}`).toBe(true);
});

After(async ({ ctx, request }) => {
  if (ctx.overrideProjectId === undefined) return;
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  await deleteFixtureProject(request, token, ctx.overrideProjectId as number);
});

// ---- 画面 ----

function region(page: Page, kind: string): Locator {
  return page.getByRole('region', { name: `${kind} の上書き` });
}

function entry(page: Page, kind: string, name: string): Locator {
  return region(page, kind).getByRole('listitem').filter({ hasText: name });
}

/** Server Action の応答(POST)を待ってからクリックする。直前の結果表示を取り違えないため。 */
async function clickAndWaitForAction(page: Page, button: Locator): Promise<void> {
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === 'POST' && r.url().includes('/projects/'), {
      timeout: 30_000,
    }),
    button.click(),
  ]);
}

When('上書き検証のプロジェクトの「接続先の上書き」タブを開く', async ({ ctx, page }) => {
  await page.goto(`/projects/${ctx.overrideProjectId as number}`, { waitUntil: 'commit' });
  const aiTab = page.getByRole('button', { name: 'AI・アセット', exact: true });
  await expect(aiTab).toBeVisible({ timeout: 30_000 });
  const overrideTab = page.getByRole('button', { name: '接続先の上書き', exact: true });
  // ハイドレーション完了前のクリックは取りこぼされるので、欄が見えるまでクリックを再試行する(#1283と同型)
  await expect(async () => {
    await aiTab.click();
    await overrideTab.click();
    await expect(region(page, 'LLM')).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
});

async function addOverride(
  page: Page,
  kind: string,
  name: string,
  url: string,
  credential?: string
): Promise<void> {
  const section = region(page, kind);
  await section.getByLabel('名前').fill(name);
  await section.getByLabel('URL', { exact: true }).fill(url);
  if (credential !== undefined) {
    await section.getByLabel('認証情報(任意)').fill(credential);
  }
  const addButton = section.getByRole('button', { name: '追加' });
  await clickAndWaitForAction(page, addButton);
  await expect(addButton).toBeEnabled({ timeout: 30_000 });
  // 受理でも拒否でも、処理の完了後に名前欄が空へ戻るまで待ってから次の入力へ進む
  await expect(section.getByLabel('名前').last()).toHaveValue('', { timeout: 30_000 });
}

When(
  /^プロジェクトの「([^」]+)」欄で、名前「([^」]+)」、URL「([^」]+)」、認証情報「([^」]+)」を追加する$/,
  async ({ page }, kind: string, name: string, url: string, credential: string) => {
    await addOverride(page, kind, name, url, credential);
  }
);

When(
  /^プロジェクトの「([^」]+)」欄で、名前「([^」]+)」、URL「([^」]+)」を追加する$/,
  async ({ page }, kind: string, name: string, url: string) => {
    await addOverride(page, kind, name, url);
  }
);

When(/^プロジェクトの「([^」]+)」欄の「([^」]+)」を使用中にする$/, async ({ page }, kind: string, name: string) => {
  await clickAndWaitForAction(page, entry(page, kind, name).getByRole('button', { name: '使用中にする' }));
});

When(
  /^プロジェクトの「([^」]+)」欄の「([^」]+)」の名前を「([^」]+)」に編集する$/,
  async ({ page }, kind: string, name: string, newName: string) => {
    await entry(page, kind, name).getByRole('button', { name: '編集' }).click();
    const form = region(page, kind).getByRole('form', { name: `${name} を編集` });
    await form.getByLabel('名前').fill(newName);
    await clickAndWaitForAction(page, form.getByRole('button', { name: '更新' }));
  }
);

When(
  /^プロジェクトの「([^」]+)」欄の「([^」]+)」の認証情報を消去する$/,
  async ({ page }, kind: string, name: string) => {
    await clickAndWaitForAction(page, entry(page, kind, name).getByRole('button', { name: '認証情報を消去' }));
  }
);

When(/^プロジェクトの「([^」]+)」欄の「([^」]+)」を削除する$/, async ({ page }, kind: string, name: string) => {
  await clickAndWaitForAction(page, entry(page, kind, name).getByRole('button', { name: '削除' }));
});

Then(
  /^プロジェクトの「([^」]+)」欄に「([^」]+)」と「([^」]+)」が並ぶ$/,
  async ({ page }, kind: string, a: string, b: string) => {
    await expect(entry(page, kind, a)).toBeVisible({ timeout: 30_000 });
    await expect(entry(page, kind, b)).toBeVisible({ timeout: 30_000 });
  }
);

Then(/^プロジェクトの「([^」]+)」欄に「([^」]+)」が表示される$/, async ({ page }, kind: string, name: string) => {
  await expect(entry(page, kind, name)).toBeVisible({ timeout: 30_000 });
});

Then(/^プロジェクトの「([^」]+)」欄に「([^」]+)」は表示されない$/, async ({ page }, kind: string, name: string) => {
  await expect(region(page, kind)).toBeVisible({ timeout: 30_000 });
  await expect(entry(page, kind, name)).toHaveCount(0);
});

Then(
  /^プロジェクトの「([^」]+)」欄の「([^」]+)」の認証情報は「(設定済み|未設定)」と表示される$/,
  async ({ page }, kind: string, name: string, state: string) => {
    await expect(entry(page, kind, name)).toContainText(`認証情報: ${state}`, { timeout: 30_000 });
  }
);

Then(/^画面のどこにも認証情報の値「([^」]+)」は表示されない$/, async ({ page }, secret: string) => {
  await expect(page.getByText(secret)).toHaveCount(0);
  expect(await page.content()).not.toContain(secret);
});

Then(/^プロジェクトの「([^」]+)」欄の使用中は「([^」]+)」だけである$/, async ({ page }, kind: string, name: string) => {
  const inUse = region(page, kind).getByRole('listitem').filter({ has: page.getByText('使用中', { exact: true }) });
  await expect(inUse).toHaveCount(1, { timeout: 30_000 });
  await expect(inUse).toContainText(name);
});

Then(
  /^プロジェクトの「([^」]+)」欄に保存できなかった理由「(.+)」が表示される$/,
  async ({ page }, kind: string, reason: string) => {
    await expect(region(page, kind).getByRole('alert')).toContainText(reason, { timeout: 30_000 });
  }
);

Then('「接続先の上書き」にPenpotの欄は無い', async ({ page }) => {
  await expect(region(page, 'LLM')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('region', { name: /Penpot/ })).toHaveCount(0);
});

// ---- API ----

Given(
  '管理者がAPIで上書き検証のプロジェクトにLLMの上書き「既存」を追加しておく',
  async ({ ctx, request }) => {
    const response = await request.post(api(ctx), {
      headers: await adminHeaders(request),
      data: { kind: 'LLM', name: '既存', url: 'http://192.0.2.10:11434/v1', drawioUrl: null, credential: null },
    });
    expect(response.ok(), `上書きの追加に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
    ctx.overrideRegistrationId = ((await response.json()) as OverrideView).id;
  }
);

When('一般ユーザーが上書きの一覧を要求する', async ({ ctx, request }) => {
  ctx.overrideStatus = (await request.get(api(ctx), { headers: userHeaders(ctx) })).status();
});

When(/^一般ユーザーがLLMの上書き「([^」]+)」を追加するよう要求する$/, async ({ ctx, request }, name: string) => {
  ctx.overrideStatus = (
    await request.post(api(ctx), {
      headers: userHeaders(ctx),
      data: { kind: 'LLM', name, url: 'http://192.0.2.10:11434/v1', drawioUrl: null, credential: null },
    })
  ).status();
});

When('一般ユーザーが既存の上書きを編集するよう要求する', async ({ ctx, request }) => {
  ctx.overrideStatus = (
    await request.put(`${api(ctx)}/${ctx.overrideRegistrationId as number}`, {
      headers: userHeaders(ctx),
      data: { name: '不正な編集', url: 'http://192.0.2.10:11434/v1', drawioUrl: null, credential: null },
    })
  ).status();
});

When('一般ユーザーが既存の上書きを使用中にするよう要求する', async ({ ctx, request }) => {
  ctx.overrideStatus = (
    await request.put(`${api(ctx)}/${ctx.overrideRegistrationId as number}/in-use`, { headers: userHeaders(ctx) })
  ).status();
});

When('一般ユーザーが既存の上書きの認証情報を消去するよう要求する', async ({ ctx, request }) => {
  ctx.overrideStatus = (
    await request.delete(`${api(ctx)}/${ctx.overrideRegistrationId as number}/credential`, {
      headers: userHeaders(ctx),
    })
  ).status();
});

When('一般ユーザーが既存の上書きを削除するよう要求する', async ({ ctx, request }) => {
  ctx.overrideStatus = (
    await request.delete(`${api(ctx)}/${ctx.overrideRegistrationId as number}`, { headers: userHeaders(ctx) })
  ).status();
});

When(
  /^一般ユーザーが認証情報「([^」]+)」つきでLLMの上書き「([^」]+)」を追加するよう要求する$/,
  async ({ ctx, request }, credential: string, name: string) => {
    ctx.overrideStatus = (
      await request.post(api(ctx), {
        headers: userHeaders(ctx),
        data: { kind: 'LLM', name, url: 'http://192.0.2.10:11434/v1', drawioUrl: null, credential },
      })
    ).status();
  }
);

When('一般ユーザーがPenpotの上書きを追加するよう要求する', async ({ ctx, request }) => {
  ctx.overrideStatus = (
    await request.post(api(ctx), {
      headers: userHeaders(ctx),
      data: { kind: 'PENPOT', name: 'Penpot', url: 'http://192.0.2.10:3449', drawioUrl: null, credential: null },
    })
  ).status();
});

Then('上書きの要求は403で拒否される', async ({ ctx }) => {
  expect(ctx.overrideStatus).toBe(403);
});

Then('上書きの要求は409で拒否される', async ({ ctx }) => {
  expect(ctx.overrideStatus).toBe(409);
});

Then('上書きの要求は成功する', async ({ ctx }) => {
  expect(ctx.overrideStatus).toBeGreaterThanOrEqual(200);
  expect(ctx.overrideStatus).toBeLessThan(300);
});

Then(/^管理者のAPIで見ると上書きは「([^」]+)」の1件だけである$/, async ({ ctx, request }, name: string) => {
  const response = await request.get(api(ctx), { headers: await adminHeaders(request) });
  const all = (await response.json()) as OverrideView[];
  expect(all.map((r) => r.name)).toEqual([name]);
  expect(all[0].inUse).toBe(false);
});

Then(
  /^一般ユーザーが取得した上書きの一覧は「([^」]+)」の1件で認証情報は設定済みである$/,
  async ({ ctx, request }, name: string) => {
    const response = await request.get(api(ctx), { headers: userHeaders(ctx) });
    expect(response.status()).toBe(200);
    const text = await response.text();
    ctx.overrideListBody = text;
    const all = JSON.parse(text) as OverrideView[];
    expect(all.map((r) => r.name)).toEqual([name]);
    expect(all[0].credentialConfigured).toBe(true);
  }
);

Then(/^取得した応答のどこにも認証情報の値「([^」]+)」は含まれない$/, async ({ ctx }, secret: string) => {
  expect(ctx.overrideListBody as string).not.toContain(secret);
});
