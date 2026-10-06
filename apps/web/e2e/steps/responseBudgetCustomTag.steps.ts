import type { APIRequestContext, Locator, Page } from '@playwright/test';
import { Given, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, createFixtureProject, deleteFixtureProject, expect, fetchAccessToken } from '../support';
import { measureServerActionRoundTrip, recordResponseTime } from '../support/responseBudget';
import { adminHeaders, registerCleanup, uniqueSuffix, waitForHydrated } from '../support/responseBudgetFixtures';

/**
 * カスタムタグ(`app/custom-tag-templates/actions.ts` / `app/custom-tags/actions.ts`)の
 * Server Action の3秒予算シナリオ(issue #1477、`features/response-budget/server-action-custom-tag.feature`)
 * のステップ定義。計測は共通の `measureServerActionRoundTrip`、判定は共通ステップ(`responseBudget.steps.ts`)。
 */

const PROJECT_ID_KEY = 'responseBudgetCtProjectId';
const TEMPLATE_KEY = 'responseBudgetTemplate';

interface Template {
  id: number;
  templateName: string;
}

async function createProject(request: APIRequestContext, ctx: Record<string, unknown>): Promise<number> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const project = await createFixtureProject(request, token, 'rb1477ct');
  ctx[PROJECT_ID_KEY] = project.id;
  registerCleanup(ctx, async () => {
    // custom_tags / custom_tag_templates はプロジェクトを消しても残る(ADR-0004)ので、先にタグを個別に消す。
    const headers = await adminHeaders(request);
    const tags = await request.get(`/api/projects/${project.id}/custom-tags`, { headers });
    if (tags.ok()) {
      for (const tag of (await tags.json()) as { id: number }[]) {
        await request.delete(`/api/custom-tags/${tag.id}`, { headers });
      }
    }
    await deleteFixtureProject(request, token, project.id);
  });
  return project.id;
}

/** テンプレートを API で作る。公開の指定があれば公開まで行う。後片付けを積む。 */
async function createTemplate(
  request: APIRequestContext,
  ctx: Record<string, unknown>,
  options: { projectId: number | null; published: boolean }
): Promise<Template> {
  const headers = await adminHeaders(request);
  const templateName = `E2E1477 テンプレート ${uniqueSuffix()}`;
  const created = await request.post('/api/custom-tag-templates', {
    headers,
    data: {
      templateName,
      description: 'e2e1477 の検証用テンプレート',
      category: 'e2e1477',
      htmlTemplate: '<div class="e2e1477">{{content}}</div>',
      cssContent: '.e2e1477 { color: rgb(1, 2, 3); }',
      projectId: options.projectId,
    },
  });
  expect(created.ok(), `テンプレートの作成に失敗しました (status=${created.status()}): ${await created.text()}`).toBe(true);
  const template = (await created.json()) as Template;
  registerCleanup(ctx, async () => {
    // 複製(画面から作られ id を知らない)も名前で拾って消す。
    const scopes = ['?showAll=true', ...(options.projectId === null ? [] : [`?projectId=${options.projectId}&showAll=true`])];
    const seen = new Set<number>();
    for (const scope of scopes) {
      const list = await request.get(`/api/custom-tag-templates${scope}`, { headers });
      if (!list.ok()) continue;
      for (const t of (await list.json()) as Template[]) {
        if (t.templateName.startsWith('E2E1477 ') && !seen.has(t.id)) {
          seen.add(t.id);
          await request.delete(`/api/custom-tag-templates/${t.id}`, { headers });
        }
      }
    }
  });
  if (options.published) {
    const published = await request.post(`/api/custom-tag-templates/${template.id}/publish`, { headers });
    expect(published.ok(), `テンプレートの公開に失敗しました (status=${published.status()})`).toBe(true);
  }
  ctx[TEMPLATE_KEY] = template;
  return template;
}

Given('応答時間予算の検証用に未公開のカスタムタグテンプレートがある', async ({ request, ctx }) => {
  await createTemplate(request, ctx, { projectId: null, published: false });
});

Given('応答時間予算の検証用に公開済みのカスタムタグテンプレートがある', async ({ request, ctx }) => {
  await createTemplate(request, ctx, { projectId: null, published: true });
});

Given('応答時間予算の検証用のプロジェクトに公開済みのカスタムタグテンプレートがある', async ({ request, ctx }) => {
  const projectId = await createProject(request, ctx);
  await createTemplate(request, ctx, { projectId, published: true });
});

Given('応答時間予算の検証用のカスタムタグ用プロジェクトがある', async ({ request, ctx }) => {
  await createProject(request, ctx);
});

/** ギャラリーを開き、そのテンプレートのカードを開いて詳細パネルを出す(ハイドレーション後に1回だけクリックする)。 */
async function openTemplateDetail(page: Page, query: string, templateName: string): Promise<void> {
  await page.goto(`/custom-tag-templates${query}`);
  const cardHeading = page.getByRole('heading', { name: templateName, level: 3 });
  await expect(cardHeading).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(cardHeading);
  await cardHeading.click();
  await expect(page.getByRole('button', { name: '閉じる' }).first()).toBeVisible({ timeout: 30_000 });
}

function template(ctx: Record<string, unknown>): Template {
  return ctx[TEMPLATE_KEY] as Template;
}

When('テンプレートギャラリーでそのテンプレートを公開して Server Action の往復を計測する', async ({ page, ctx }) => {
  await openTemplateDetail(page, '?showAll=true', template(ctx).templateName);
  const timing = await measureServerActionRoundTrip(page, async () => {
    await page.getByRole('button', { name: '公開する' }).click();
    await expect(page.getByRole('button', { name: '閉じる' })).toHaveCount(0, { timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, 'テンプレートの公開(Server Action)の往復');
});

When('テンプレートギャラリーでそのテンプレートを非公開に戻して Server Action の往復を計測する', async ({ page, ctx }) => {
  await openTemplateDetail(page, '', template(ctx).templateName);
  const timing = await measureServerActionRoundTrip(page, async () => {
    await page.getByRole('button', { name: '非公開に戻す' }).click();
    await expect(page.getByRole('button', { name: '閉じる' })).toHaveCount(0, { timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, 'テンプレートの公開取り下げ(Server Action)の往復');
});

When('テンプレートギャラリーでそのテンプレートを複製して Server Action の往復を計測する', async ({ page, ctx }) => {
  await openTemplateDetail(page, `?projectId=${ctx[PROJECT_ID_KEY]}`, template(ctx).templateName);
  const cloneName = page.getByPlaceholder('新しいテンプレート名');
  await cloneName.fill(`E2E1477 複製 ${uniqueSuffix()}`);
  page.once('dialog', (dialog) => void dialog.accept());
  const timing = await measureServerActionRoundTrip(page, async () => {
    await page.getByRole('button', { name: '複製を作成' }).click();
    await expect(cloneName).toHaveCount(0, { timeout: 30_000 });
  });
  recordResponseTime(ctx, timing.roundTripMs, 'テンプレートの複製(Server Action)の往復');
});

When(
  'テンプレートギャラリーでそのテンプレートを新しいタグ名でプロジェクトで使い Server Action の往復を計測する',
  async ({ page, ctx }) => {
    await openTemplateDetail(page, `?projectId=${ctx[PROJECT_ID_KEY]}`, template(ctx).templateName);
    const tagName = `e2e1477t${uniqueSuffix()}`;
    await page.getByLabel('適用先プロジェクト').selectOption(String(ctx[PROJECT_ID_KEY]));
    await page.getByPlaceholder('タグ名(例: note)').fill(tagName);
    const timing = await measureServerActionRoundTrip(page, async () => {
      await page.getByRole('button', { name: 'プロジェクトで使う' }).click();
      await expect(page.getByText(`[${tagName}] を`)).toBeVisible({ timeout: 30_000 });
    });
    recordResponseTime(ctx, timing.roundTripMs, 'テンプレートをプロジェクトで使う(Server Action)の往復');
  }
);

/** カスタムタグ管理タブの「AIでカスタムタグを生成」フォーム。 */
function generationForm(page: Page): Locator {
  return page.locator('form').filter({ has: page.locator('textarea[name="prompt"]') });
}

When('カスタムタグ管理画面でAIにタグの生成を要求し、結果を開いて自動で走る検証の Server Action の往復を計測する', async ({ page, ctx, request }) => {
  await page.goto(`/projects/${ctx[PROJECT_ID_KEY]}/tags`);
  const tab = page.getByRole('button', { name: 'カスタムタグ管理', exact: true });
  await expect(tab).toBeVisible({ timeout: 30_000 });
  await waitForHydrated(tab);
  const form = generationForm(page);
  await expect(async () => {
    await tab.click();
    await expect(form.locator('textarea[name="prompt"]')).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });

  const tagName = `e2e1477g${uniqueSuffix()}`;
  await form.locator('textarea[name="prompt"]').fill('青いボタンコンポーネントを作成してください');
  await form.locator('input[name="tagName"]').fill(tagName);
  const submit = form.locator('button[type="submit"]');
  // 送信ボタンはセッションとハイドレーションの完了まで押せない(#778 / #1414)。
  await expect(submit).toHaveText('生成', { timeout: 30_000 });
  await expect(submit).toBeEnabled();
  await submit.click();
  // 生成は非同期ジョブとして受理される(#1409)。受理されたら、完了するまで待って結果を開く。
  const queued = page.getByTestId('custom-tag-generation-queued');
  await expect(queued).toBeVisible({ timeout: 30_000 });
  const jobId = Number(await queued.getAttribute('data-job-id'));
  const headers = await adminHeaders(request);
  await expect
    .poll(
      async () => {
        const response = await request.get(`/api/generation-jobs/${jobId}`, { headers });
        return ((await response.json()) as { status: string }).status;
      },
      { timeout: 120_000 }
    )
    .toBe('done');

  // 検証(validateCustomTagAction)は、結果(未保存)を表示したときに自動で送られる。その往復を計る。
  const timing = await measureServerActionRoundTrip(
    page,
    async () => {
      await page.goto(`/projects/${ctx[PROJECT_ID_KEY]}/tags?tab=custom-tags&customTagJob=${jobId}`);
      await expect(page.getByTestId('custom-tag-generation-result')).toBeVisible({ timeout: 30_000 });
    },
    { timeoutMs: 120_000 }
  );
  expect(timing.roundTripsMs.length, `結果の表示のあとに検証の Server Action が送られていません: ${timing.roundTripsMs}`).toBeGreaterThanOrEqual(1);
  recordResponseTime(ctx, timing.roundTripsMs[timing.roundTripsMs.length - 1], 'カスタムタグの検証(Server Action)の往復');
});
