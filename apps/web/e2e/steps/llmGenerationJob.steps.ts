import type { APIRequestContext, Locator, Page } from '@playwright/test';
import { Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';
import { waitForHydrated } from '../support/responseBudgetFixtures';

/**
 * LLM 生成の3機能(カスタムタグ・静的コンテンツ・タグデザイン)を AI キューに乗せる受け入れシナリオ
 * (issue #1409)のステップ定義。
 *
 * 生成テキストはジョブの `result_payload` にだけ置かれ、利用者が「保存」を押したときに初めて
 * 各機能の既存の保存先へ書かれる。したがってシナリオは次の3点を画面とAPIで確かめる。
 *   - 要求は待たずに受理され、処理キューにジョブが種別つきで現れる(完了後は「結果を見る」が付く)
 *   - 「結果を見る」で開いた画面は、生成結果を未保存として示す
 *   - 「保存」を押すまで、保存先(一覧・設定のGET)に何も現れない
 *
 * 要求した側のステップが `ctx.llmMarkers`(ジョブの `request_payload` に含まれる印)と
 * `ctx.llmReturnUrl`(要求した画面)を残し、ここのステップがそれでジョブを特定する。
 * カスタムタグの画面操作は customTag.steps.ts、静的コンテンツ・タグデザインの画面操作はこのファイルにある。
 */

type ScenarioState = Record<string, unknown>;

interface JobDetail {
  id: number;
  type: string;
  status: string;
  requestPayload: string | null;
  resultPayload: string | null;
}

const JOB_TIMEOUT_MS = 180_000;
const UI_TIMEOUT_MS = 30_000;

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

async function fetchJob(request: APIRequestContext, jobId: number): Promise<JobDetail> {
  const response = await request.get(`/api/generation-jobs/${jobId}`, { headers: await adminHeaders(request) });
  expect(response.ok(), `ジョブの取得に失敗しました (status=${response.status()})`).toBe(true);
  return (await response.json()) as JobDetail;
}

/** 種別と印(要求内容に含まれる文字列)でジョブを特定する。受理後に作られるので、現れるまで短く再試行する。 */
async function findJob(request: APIRequestContext, type: string, markers: string[]): Promise<JobDetail> {
  const headers = await adminHeaders(request);
  const deadline = Date.now() + UI_TIMEOUT_MS;
  while (Date.now() < deadline) {
    const list = await request.get('/api/generation-jobs', { headers });
    expect(list.ok(), `ジョブ一覧の取得に失敗しました (status=${list.status()})`).toBe(true);
    for (const job of ((await list.json()) as { id: number; type: string }[]).filter((j) => j.type === type)) {
      const detail = await fetchJob(request, job.id);
      // MySQL の JSON 正規化は `"siteId":1` を `"siteId": 1` に整えるので、空白を無視して照合する。
      const payload = (detail.requestPayload ?? '').replace(/\s/g, '');
      if (markers.every((marker) => payload.includes(marker.replace(/\s/g, '')))) {
        return detail;
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`種別 ${type}、印 ${markers.join(' / ')} のジョブが見つかりません`);
}

async function waitForTerminal(request: APIRequestContext, jobId: number): Promise<JobDetail> {
  const deadline = Date.now() + JOB_TIMEOUT_MS;
  let job = await fetchJob(request, jobId);
  while (Date.now() < deadline && (job.status === 'running' || job.status === 'pending')) {
    await new Promise((resolve) => setTimeout(resolve, 1000));
    job = await fetchJob(request, jobId);
  }
  return job;
}

function queueItem(page: Page, jobId: number): Locator {
  return page.locator(`[data-testid="info-rail-queue-item"][data-job-id="${jobId}"]`);
}

async function requestedJob(request: APIRequestContext, ctx: ScenarioState, type: string): Promise<JobDetail> {
  const job = await findJob(request, type, ctx.llmMarkers as string[]);
  ctx.llmJobId = job.id;
  return job;
}

// ---- 処理キュー(3機能共通。種別ごとに文言を変えて定義する) ----

const KINDS = [
  { name: 'カスタムタグ生成', type: 'custom_tag_generation', resultKey: 'htmlTemplate' },
  { name: '静的コンテンツ生成', type: 'static_content_generation', resultKey: 'body' },
  { name: 'タグデザイン生成', type: 'tag_design_generation', resultKey: 'cssContent' },
] as const;

for (const kind of KINDS) {
  Then(`処理キューにその${kind.name}のジョブが現れる`, async ({ ctx, page, request }) => {
    const job = await requestedJob(request, ctx, kind.type);
    await expect(queueItem(page, job.id)).toBeVisible({ timeout: UI_TIMEOUT_MS });
    // 種別が生の識別子ではなく、利用者の言葉で示される。
    await expect(queueItem(page, job.id)).toContainText(kind.name);
  });

  When(`処理キューのその${kind.name}のジョブが完了するまで待つ`, async ({ ctx, page, request }) => {
    const job = await requestedJob(request, ctx, kind.type);
    await expect(queueItem(page, job.id)).toContainText('完了', { timeout: JOB_TIMEOUT_MS });
  });

  When(`処理キューのその${kind.name}のジョブの「結果を見る」を押す`, async ({ ctx, page }) => {
    await queueItem(page, ctx.llmJobId as number)
      .getByRole('link', { name: '結果を見る' })
      .click();
  });

  Then(`その${kind.name}のジョブの結果から、生成された内容が読める`, async ({ ctx, request }) => {
    const job = await waitForTerminal(request, ctx.llmJobId as number);
    expect(job.status, `ジョブが完了していません: ${JSON.stringify(job)}`).toBe('done');
    const result = JSON.parse(job.resultPayload ?? '{}') as Record<string, unknown>;
    expect(String(result[kind.resultKey] ?? ''), `結果に ${kind.resultKey} がありません: ${job.resultPayload}`).not.toBe('');
  });
}

When('ダッシュボードへ移動してから、生成を要求した画面へ戻る', async ({ ctx, page }) => {
  // 受理後に離脱するのが条件なので、要求した側のステップが受付の表示を確かめてから呼ばれる。
  await page.goto('/', { waitUntil: 'commit' });
  await page.goto(ctx.llmReturnUrl as string, { waitUntil: 'commit' });
});

// ---- 静的コンテンツ(サイト編集画面) ----

function staticContentItem(page: Page, label: string): Locator {
  return page.locator('div.space-y-2').filter({ has: page.locator(`span:text-is("${label}")`) });
}

async function listStaticContent(
  request: APIRequestContext,
  siteId: number
): Promise<{ contentType: string; body: string }[]> {
  const response = await request.get(`/api/sites/${siteId}/static-content`, { headers: await adminHeaders(request) });
  expect(response.ok(), `静的コンテンツ一覧の取得に失敗しました (status=${response.status()})`).toBe(true);
  return (await response.json()) as { contentType: string; body: string }[];
}

When(/^サイト編集画面で「([^」]+)」の生成を要求する$/, async ({ ctx, page }, label: string) => {
  const siteId = ctx.pluginSiteId as number;
  await page.goto(`/sites/${siteId}/edit`);
  const item = staticContentItem(page, label);
  const generate = item.getByRole('button', { name: /^(生成|再生成)$/ });
  await expect(generate).toBeVisible({ timeout: UI_TIMEOUT_MS });
  await waitForHydrated(generate);
  await generate.click();
  await expect(item.getByTestId('static-content-queued')).toBeVisible({ timeout: UI_TIMEOUT_MS });
  ctx.staticContentLabel = label;
  ctx.llmMarkers = [`"siteId":${siteId}`, `"contentType":"${staticContentType(label)}"`];
  ctx.llmReturnUrl = `/sites/${siteId}/edit`;
});

function staticContentType(label: string): string {
  const types: Record<string, string> = {
    プライバシーポリシー: 'PRIVACY_POLICY',
    運営者情報: 'OPERATOR_INFO',
    利用規約: 'TERMS_OF_SERVICE',
  };
  const type = types[label];
  if (!type) throw new Error(`未知の静的コンテンツ: ${label}`);
  return type;
}

Then('生成の要求を受け付けた旨が静的コンテンツの欄に示され、まだ保存されたとは示されない', async ({ ctx, page }) => {
  const item = staticContentItem(page, ctx.staticContentLabel as string);
  const notice = item.getByTestId('static-content-queued');
  await expect(notice).toBeVisible();
  await expect(notice).toContainText('処理キューに追加されました');
  await expect(item.getByRole('button', { name: 'コピー' })).toHaveCount(0);
});

Then('静的コンテンツの欄に、生成結果が未保存として表示される', async ({ ctx, page }) => {
  const block = staticContentItem(page, ctx.staticContentLabel as string).getByTestId('static-content-generated');
  await expect(block).toBeVisible({ timeout: UI_TIMEOUT_MS });
  await expect(block).toContainText('保存されていません');
  await expect(block.locator('pre')).not.toBeEmpty();
});

When('静的コンテンツの生成結果の「保存」を押す', async ({ ctx, page }) => {
  const save = staticContentItem(page, ctx.staticContentLabel as string)
    .getByTestId('static-content-generated')
    .getByRole('button', { name: '保存', exact: true });
  await expect(save).toBeVisible({ timeout: UI_TIMEOUT_MS });
  await waitForHydrated(save);
  await save.click();
});

Then('静的コンテンツを保存したことが示される', async ({ ctx, page }) => {
  await expect(staticContentItem(page, ctx.staticContentLabel as string).getByText('保存しました。')).toBeVisible({
    timeout: UI_TIMEOUT_MS,
  });
});

Then('そのサイトの静的コンテンツ一覧に、生成された内容が現れる', async ({ ctx, request }) => {
  const type = staticContentType(ctx.staticContentLabel as string);
  const job = await waitForTerminal(request, ctx.llmJobId as number);
  const generated = (JSON.parse(job.resultPayload ?? '{}') as { body?: string }).body;
  expect(generated, `ジョブの結果に本文がありません: ${job.resultPayload}`).toBeTruthy();
  await expect
    .poll(async () => (await listStaticContent(request, ctx.pluginSiteId as number)).find((c) => c.contentType === type)?.body, {
      timeout: UI_TIMEOUT_MS,
    })
    .toBe(generated);
});

Then('そのサイトの静的コンテンツ一覧に、その種別の静的コンテンツは現れない', async ({ ctx, request }) => {
  const type = staticContentType(ctx.staticContentLabel as string);
  const contents = await listStaticContent(request, ctx.pluginSiteId as number);
  expect(contents.map((c) => c.contentType)).not.toContain(type);
});

// ---- タグデザイン(プロジェクト個別・グローバル) ----

function tagDesignBase(projectId: number | null): string {
  return projectId === null ? '/api/tag-design-settings' : `/api/projects/${projectId}/tag-design-settings`;
}

async function requestTagDesignGenerationOnScreen(
  page: Page,
  ctx: ScenarioState,
  url: string,
  projectId: number | null,
  prompt: string
): Promise<void> {
  // 要求ごとの印をプロンプトに入れる(並列に走る他のシナリオのジョブと取り違えない)。
  const marker = `e2e1409d${uniqueSuffix()}`;
  await page.goto(url);
  const promptInput = page.getByPlaceholder(/背景を淡いグレーにして/);
  const generate = page.getByRole('button', { name: '生成', exact: true });
  await expect(generate).toBeVisible({ timeout: UI_TIMEOUT_MS });
  await waitForHydrated(generate);
  await promptInput.fill(`${prompt} ${marker}`);
  await expect(generate).toBeEnabled();
  await generate.click();
  await expect(page.getByTestId('tag-design-queued')).toBeVisible({ timeout: UI_TIMEOUT_MS });
  ctx.llmMarkers = [marker];
  ctx.llmReturnUrl = url;
  ctx.tagDesignTargetProjectId = projectId;
}

When(
  /^プロジェクトの組み込みタグデザイン画面で、プロンプト「([^」]+)」からデザインの生成を要求する$/,
  async ({ ctx, page }, prompt: string) => {
    const projectId = ctx.tagDesignProjectId as number;
    await requestTagDesignGenerationOnScreen(page, ctx, `/projects/${projectId}/tags`, projectId, prompt);
  }
);

When(
  /^グローバルタグデザイン画面で、プロンプト「([^」]+)」からデザインの生成を要求する$/,
  async ({ ctx, page }, prompt: string) => {
    await requestTagDesignGenerationOnScreen(page, ctx, '/admin/tag-design', null, prompt);
  }
);

Then('生成の要求を受け付けた旨がタグデザイン画面に示され、まだ保存されたとは示されない', async ({ page }) => {
  const notice = page.getByTestId('tag-design-queued');
  await expect(notice).toBeVisible();
  await expect(notice).toContainText('処理キューに追加されました');
  await expect(page.getByTestId('tag-design-generated')).toHaveCount(0);
});

Then('タグデザイン画面に、生成結果が未保存として表示される', async ({ page }) => {
  const block = page.getByTestId('tag-design-generated');
  await expect(block).toBeVisible({ timeout: UI_TIMEOUT_MS });
  await expect(block).toContainText('保存されていません');
  await expect(block.locator('pre').first()).not.toBeEmpty();
});

When('タグデザインの生成結果の「保存」を押す', async ({ page }) => {
  const save = page.getByTestId('tag-design-generated').getByRole('button', { name: '保存', exact: true });
  await expect(save).toBeVisible({ timeout: UI_TIMEOUT_MS });
  await waitForHydrated(save);
  await save.click();
});

Then('タグデザインを保存したことが示される', async ({ page }) => {
  await expect(page.getByText('保存しました。')).toBeVisible({ timeout: UI_TIMEOUT_MS });
});

async function tocSetting(
  request: APIRequestContext,
  projectId: number | null
): Promise<{ tagType: string; customCss: string | null }> {
  const response = await request.get(tagDesignBase(projectId), { headers: await adminHeaders(request) });
  expect(response.ok(), `タグデザイン設定の取得に失敗しました (status=${response.status()})`).toBe(true);
  const overview = (await response.json()) as { settings: { tagType: string; customCss: string | null }[] };
  const setting = overview.settings.find((s) => s.tagType === 'TOC');
  expect(setting, 'タグデザイン設定に [toc] がありません').toBeDefined();
  return setting!;
}

Then('保存後に取得した[toc]のタグデザイン設定は、生成された内容になっている', async ({ ctx, request }) => {
  const job = await waitForTerminal(request, ctx.llmJobId as number);
  const generated = (JSON.parse(job.resultPayload ?? '{}') as { cssContent?: string }).cssContent;
  expect(generated, `ジョブの結果にCSSがありません: ${job.resultPayload}`).toBeTruthy();
  await expect
    .poll(async () => (await tocSetting(request, ctx.tagDesignTargetProjectId as number | null)).customCss, {
      timeout: UI_TIMEOUT_MS,
    })
    .toBe(generated);
});

// ---- タグデザイン(API。保存の前後で設定が変わらないことを確かめる) ----

When('プロジェクトの[toc]のタグデザイン生成をジョブとして要求する', async ({ ctx, request }) => {
  const projectId = ctx.tagDesignProjectId as number;
  ctx.tagDesignBaselineCss = (await tocSetting(request, projectId)).customCss;
  const response = await request.post(`${tagDesignBase(projectId)}/TOC/generate/jobs`, {
    headers: await adminHeaders(request),
    data: { prompt: `背景を淡いグレーにしてください e2e1409a${uniqueSuffix()}` },
  });
  expect(
    response.status(),
    `ジョブとして受理されませんでした (status=${response.status()}): ${await response.text()}`
  ).toBe(202);
  ctx.llmJobId = ((await response.json()) as { id: number }).id;
});

When('タグデザイン生成のジョブが終わるまで待つ', async ({ ctx, request }) => {
  ctx.llmTerminalJob = await waitForTerminal(request, ctx.llmJobId as number);
});

Then('そのタグデザイン生成のジョブは「done」で終わり、結果に生成されたCSSが示される', async ({ ctx }) => {
  const job = ctx.llmTerminalJob as JobDetail;
  expect(job.status, `ジョブが完了していません: ${JSON.stringify(job)}`).toBe('done');
  const result = JSON.parse(job.resultPayload ?? '{}') as { cssContent?: string };
  expect(result.cssContent ?? '').not.toBe('');
});

Then('プロジェクトの[toc]のタグデザイン設定は、生成前のまま変わっていない', async ({ ctx, request }) => {
  const job = ctx.llmTerminalJob as JobDetail;
  const generated = (JSON.parse(job.resultPayload ?? '{}') as { cssContent?: string }).cssContent;
  const current = (await tocSetting(request, ctx.tagDesignProjectId as number)).customCss;
  expect(current).toBe(ctx.tagDesignBaselineCss as string | null);
  expect(current).not.toBe(generated);
});
