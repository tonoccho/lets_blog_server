import type { APIRequestContext, Page } from '@playwright/test';
import { Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';
import { SERVER_ACTION_HEADER, measureServerActionRoundTrip, recordResponseTime } from '../support/responseBudget';
import { adminHeaders, registerCleanup, uniqueSuffix, waitForHydrated } from '../support/responseBudgetFixtures';

/**
 * テンプレートギャラリーの作成・編集・削除の画面(issue #1550)のステップ定義。
 * `features/custom-tag/templates.feature`(受け入れ)と
 * `features/response-budget/server-action-custom-tag.feature`(3秒予算)の両方から使う。
 *
 * 既存のテンプレートを用意するステップ(「自分が作った未公開のカスタムタグテンプレートがある」)と
 * 詳細パネルを開く手順は `customTag.steps.ts` / `responseBudgetCustomTag.steps.ts` にあり、
 * ここでは重ねて定義しない。画面から作った・名前を変えたテンプレートの後片付けは、
 * `customTag.steps.ts` の `After({ tags: '@custom-tag' })` が読む `tagCreatedTemplateNames` へ名前を積む。
 */

type Ctx = Record<string, unknown>;

const CREATED = {
  description: 'e2e1550 の説明',
  category: 'e2e1550',
  html: '<section class="e2e1550">{{content}}</section>',
  css: '.e2e1550 { color: rgb(4, 5, 6); }',
};

function trackName(ctx: Ctx, name: string): void {
  const names = ((ctx.tagCreatedTemplateNames as string[] | undefined) ?? []);
  names.push(name);
  ctx.tagCreatedTemplateNames = names;
}

async function openGallery(page: Page, query = ''): Promise<void> {
  await page.goto(`/custom-tag-templates${query}`, { waitUntil: 'commit' });
  await expect(page.getByRole('heading', { name: 'カスタムタグテンプレート' })).toBeVisible({ timeout: 30_000 });
}

/** 「新しいテンプレート」を押してフォームを出す。ハイドレーション前のクリックは取りこぼされるので再試行する。 */
async function openCreateForm(page: Page): Promise<void> {
  await openGallery(page);
  const button = page.getByRole('button', { name: '新しいテンプレート', exact: true });
  await expect(button).toBeVisible({ timeout: 30_000 });
  const nameField = page.getByLabel('テンプレート名', { exact: true });
  await expect(async () => {
    await button.click();
    await expect(nameField).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
}

async function fillCreateForm(page: Page, name: string): Promise<void> {
  await page.getByLabel('テンプレート名', { exact: true }).fill(name);
  await page.getByLabel('説明', { exact: true }).fill(CREATED.description);
  await page.getByLabel('カテゴリー', { exact: true }).fill(CREATED.category);
  await page.locator('textarea[name="htmlTemplate"]').fill(CREATED.html);
  await page.locator('textarea[name="cssContent"]').fill(CREATED.css);
}

async function openDetail(page: Page, query: string, templateName: string): Promise<void> {
  await openGallery(page, query);
  const cardHeading = page.getByRole('heading', { name: templateName, level: 3 });
  await expect(cardHeading).toBeVisible({ timeout: 30_000 });
  const close = page.getByRole('button', { name: '閉じる' });
  await expect(async () => {
    await cardHeading.click();
    await expect(close).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
}

async function deleteViaApi(request: APIRequestContext, id: number): Promise<void> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const response = await request.delete(`/api/custom-tag-templates/${id}`, { headers: { Authorization: `Bearer ${token}` } });
  expect(response.ok(), `テンプレートのAPI削除に失敗しました (status=${response.status()})`).toBe(true);
}

// ---- 作成 ----

When('テンプレートギャラリーの「新しいテンプレート」から、名前・説明・カテゴリー・HTML・CSSを入力して作成する', async ({ ctx, page }) => {
  const name = `E2E1550 作成 ${uniqueSuffix()}`;
  ctx.crudName = name;
  trackName(ctx, name);
  await openCreateForm(page);
  await fillCreateForm(page, name);
  await page.getByRole('button', { name: '作成', exact: true }).click();
  await expect(page.getByLabel('テンプレート名', { exact: true })).toHaveCount(0, { timeout: 30_000 });
});

Then('「未公開を含める」を有効にしたギャラリーに、そのテンプレートが未公開として現れる', async ({ ctx, page }) => {
  await openGallery(page, '?showAll=true');
  const card = page
    .locator('div')
    .filter({ has: page.getByRole('heading', { name: (ctx.crudName ?? ctx.tagTemplateName) as string, level: 3 }) })
    .last();
  await expect(card).toBeVisible({ timeout: 30_000 });
  await expect(card).toContainText('非公開');
});

Then('詳細パネルに、入力した名前・説明・カテゴリー・HTML・CSSが表示される', async ({ ctx, page }) => {
  await openDetail(page, '?showAll=true', ctx.crudName as string);
  await expect(page.getByLabel('テンプレート名', { exact: true })).toHaveValue(ctx.crudName as string);
  await expect(page.getByLabel('説明', { exact: true })).toHaveValue(CREATED.description);
  await expect(page.getByLabel('カテゴリー', { exact: true })).toHaveValue(CREATED.category);
  await expect(page.locator('textarea[name="htmlTemplate"]')).toHaveValue(CREATED.html);
  await expect(page.locator('textarea[name="cssContent"]')).toHaveValue(CREATED.css);
});

// ---- 編集 ----

When('テンプレートギャラリーの詳細パネルで、名前とHTMLを変えて保存する', async ({ ctx, page }) => {
  await openDetail(page, '?showAll=true', ctx.tagTemplateName as string);
  const newName = `E2E1550 編集後 ${uniqueSuffix()}`;
  const newHtml = '<aside class="e2e1550-edited">{{content}}</aside>';
  ctx.crudName = newName;
  ctx.crudHtml = newHtml;
  trackName(ctx, newName);
  await page.getByLabel('テンプレート名', { exact: true }).fill(newName);
  await page.locator('textarea[name="htmlTemplate"]').fill(newHtml);
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('button', { name: '閉じる' })).toHaveCount(0, { timeout: 30_000 });
});

Then(
  '「未公開を含める」を有効にして読み込み直したギャラリーで同じテンプレートを開くと、変更後の名前とHTMLが表示される',
  async ({ ctx, page }) => {
    await openDetail(page, '?showAll=true', ctx.crudName as string);
    await expect(page.getByLabel('テンプレート名', { exact: true })).toHaveValue(ctx.crudName as string);
    await expect(page.locator('textarea[name="htmlTemplate"]')).toHaveValue(ctx.crudHtml as string);
  }
);

// ---- 削除 ----

When('テンプレートギャラリーの詳細パネルで「削除」を押し、確認ダイアログを承諾する', async ({ ctx, page }) => {
  await openDetail(page, '?showAll=true', ctx.tagTemplateName as string);
  page.once('dialog', (dialog) => void dialog.accept());
  await page.getByRole('button', { name: '削除', exact: true }).click();
  await expect(page.getByRole('button', { name: '閉じる' })).toHaveCount(0, { timeout: 30_000 });
});

When('テンプレートギャラリーの詳細パネルで「削除」を押し、確認ダイアログをキャンセルする', async ({ ctx, page }) => {
  await openDetail(page, '?showAll=true', ctx.tagTemplateName as string);
  const dialogSeen = new Promise<void>((resolve) =>
    page.once('dialog', (dialog) => {
      void dialog.dismiss().then(() => resolve());
    })
  );
  await page.getByRole('button', { name: '削除', exact: true }).click();
  await dialogSeen;
});

Then('「未公開を含める」を有効にしたギャラリーに、そのテンプレートは現れない', async ({ ctx, page }) => {
  await openGallery(page, '?showAll=true');
  await expect(page.getByRole('heading', { name: ctx.tagTemplateName as string, level: 3 })).toHaveCount(0);
});

// ---- 入力検証とエラー表示 ----

When('テンプレートギャラリーの「新しいテンプレート」でHTMLを空にしたまま作成を押す', async ({ ctx, page }) => {
  const requests: string[] = [];
  ctx.crudActionRequests = requests;
  ctx.crudName = `E2E1550 空 ${uniqueSuffix()}`;
  trackName(ctx, ctx.crudName as string);
  await openCreateForm(page);
  await page.getByLabel('テンプレート名', { exact: true }).fill(ctx.crudName as string);
  // ギャラリー自体も引数なしの Server Action を呼ぶので、入力した名前を引数に持つ要求だけを数える。
  const typedName = ctx.crudName as string;
  page.on('request', (request) => {
    if (request.method() !== 'POST' || !(SERVER_ACTION_HEADER in request.headers())) return;
    if ((request.postData() ?? '').includes(typedName)) requests.push(request.url());
  });
  await page.getByRole('button', { name: '作成', exact: true }).click();
});

Then('Server Action の要求は送られない', async ({ ctx, page }) => {
  // 要求が非同期に送られる余地を与えてから数える。
  await page.waitForTimeout(1_000);
  expect(ctx.crudActionRequests as string[]).toHaveLength(0);
});

Then('必須欄が空であることが画面に表示され、入力した名前は残っている', async ({ ctx, page }) => {
  await expect(page.getByRole('alert').filter({ hasText: '必須' })).toContainText('必須', { timeout: 10_000 });
  await expect(page.getByLabel('テンプレート名', { exact: true })).toHaveValue(ctx.crudName as string);
});

When(
  'テンプレートギャラリーの詳細パネルを開いたまま、そのテンプレートをAPIで削除してから、名前を変えて保存する',
  async ({ ctx, page, request }) => {
    await openDetail(page, '?showAll=true', ctx.tagTemplateName as string);
    await deleteViaApi(request, ctx.tagTemplateId as number);
    ctx.crudName = `E2E1550 失敗 ${uniqueSuffix()}`;
    await page.getByLabel('テンプレート名', { exact: true }).fill(ctx.crudName as string);
    await page.getByRole('button', { name: '保存', exact: true }).click();
  }
);

Then('保存に失敗した理由が画面に表示され、変更した名前は入力欄に残っている', async ({ ctx, page }) => {
  await expect(page.getByRole('alert').filter({ hasText: '保存に失敗しました' })).toContainText('保存に失敗しました', { timeout: 30_000 });
  await expect(page.getByLabel('テンプレート名', { exact: true })).toHaveValue(ctx.crudName as string);
});

// ---- 3秒予算(response-budget/server-action-custom-tag.feature) ----

When('テンプレートギャラリーで新しいテンプレートを作成して Server Action の往復を計測する', async ({ ctx, page, request }) => {
  const name = `E2E1477 作成 ${uniqueSuffix()}`;
  registerCleanup(ctx, async () => {
    const headers = await adminHeaders(request);
    const list = await request.get('/api/custom-tag-templates?showAll=true', { headers });
    if (!list.ok()) return;
    for (const t of (await list.json()) as { id: number; templateName: string }[]) {
      if (t.templateName === name) await request.delete(`/api/custom-tag-templates/${t.id}`, { headers });
    }
  });
  await openCreateForm(page);
  await waitForHydrated(page.getByRole('button', { name: '作成', exact: true }));
  await fillCreateForm(page, name);
  const timing = await measureServerActionRoundTrip(page, async () => {
    await page.getByRole('button', { name: '作成', exact: true }).click();
    await expect(page.getByLabel('テンプレート名', { exact: true })).toHaveCount(0, { timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, 'テンプレートの作成(Server Action)の往復');
});

When('テンプレートギャラリーでそのテンプレートの名前を変えて保存し Server Action の往復を計測する', async ({ ctx, page }) => {
  const template = ctx.responseBudgetTemplate as { templateName: string };
  await openDetail(page, '?showAll=true', template.templateName);
  await page.getByLabel('テンプレート名', { exact: true }).fill(`E2E1477 編集後 ${uniqueSuffix()}`);
  const timing = await measureServerActionRoundTrip(page, async () => {
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByRole('button', { name: '閉じる' })).toHaveCount(0, { timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, 'テンプレートの編集(Server Action)の往復');
});

When('テンプレートギャラリーでそのテンプレートを削除して Server Action の往復を計測する', async ({ ctx, page }) => {
  const template = ctx.responseBudgetTemplate as { templateName: string };
  await openDetail(page, '?showAll=true', template.templateName);
  page.once('dialog', (dialog) => void dialog.accept());
  const timing = await measureServerActionRoundTrip(page, async () => {
    await page.getByRole('button', { name: '削除', exact: true }).click();
    await expect(page.getByRole('button', { name: '閉じる' })).toHaveCount(0, { timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, 'テンプレートの削除(Server Action)の往復');
});
