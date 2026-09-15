import type { APIRequestContext, Locator, Page } from '@playwright/test';
import { After, Given, Step, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  expect,
  fetchAccessToken,
} from '../support';

/**
 * カスタムタグ・テンプレート・コンテンツ設定の受け入れシナリオを支えるステップ定義
 * (issue #938 / AT-12)。
 *
 * 画面から確かめるものとAPIから確かめるものが混在する。分け方の理由は各 `.feature` の
 * 冒頭に書いてある(要約: 画面のふるまいが受け入れ基準なら画面から、サーバーの判断が
 * 受け入れ基準ならAPIから。加えて **接頭辞の変更・テンプレートの公開/非公開・
 * 自分のテンプレート一覧には、そもそも画面が存在しない**)。
 *
 * フィクスチャ(プロジェクト・タグ・テンプレート)は全て `After({ tags: '@custom-tag' })`
 * が片付ける。`custom_tags` / `custom_tag_templates` は content-service のスキーマにあり、
 * project-service の `projects` への外部キーを持たない(ADR-0004)ため、
 * **プロジェクトを消してもタグとテンプレートは残る。** 個別に消すこと。
 */

/** タグ名は `[a-zA-Z][a-zA-Z0-9_\-]*` に収める(生成フォームの pattern と同じ制約)。 */
function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

/**
 * シナリオ11(接頭辞の衝突)で2つのプロジェクトが**同じセレクタ**を持つ必要があるため、
 * タグのCSSは固定のクラス名を使う。統合CSSは各セレクタの先頭にプロジェクトごとの
 * 接頭辞を付けるので、同じクラス名でも接頭辞が違えば衝突しない — それがこの検証の主題である。
 */
const SHARED_CSS_CLASS = 'e2e938-shared';
const SHARED_CSS = `.${SHARED_CSS_CLASS} { color: rgb(1, 2, 3); }`;

/** シナリオ内でステップ間の値を受け渡す入れ物(`steps/fixtures.ts` の `ctx`)。 */
type ScenarioState = Record<string, unknown>;

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

/** 「他の利用者」= 非adminの合成アカウント。共有範囲と認可の検証に使う。 */
async function otherUserToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
}

async function authHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await adminToken(request)}` };
}

function trackedTags(ctx: ScenarioState): number[] {
  ctx.tagCreatedTagIds ??= [];
  return ctx.tagCreatedTagIds as number[];
}

function trackedProjects(ctx: ScenarioState): number[] {
  ctx.tagCreatedProjectIds ??= [];
  return ctx.tagCreatedProjectIds as number[];
}

/**
 * 後片付けはテンプレート**名**で行う。画面から作られる複製(シナリオ14)は
 * ステップがidを知らないまま生まれるので、idだけを追うと必ず取り残す。
 */
function trackedTemplateNames(ctx: ScenarioState): string[] {
  ctx.tagCreatedTemplateNames ??= [];
  return ctx.tagCreatedTemplateNames as string[];
}

interface ProjectFixture {
  id: number;
  name: string;
  slug: string;
}

async function createProject(request: APIRequestContext, ctx: ScenarioState): Promise<ProjectFixture> {
  const suffix = uniqueSuffix();
  const response = await request.post('/api/projects', {
    headers: await authHeaders(request),
    data: { name: `E2E 938 ${suffix}`, slug: `e2e-938-${suffix}` },
  });
  expect(
    response.ok(),
    `プロジェクトの作成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const project = (await response.json()) as ProjectFixture;
  trackedProjects(ctx).push(project.id);
  return project;
}

interface CustomTagFixture {
  id: number;
  tagName: string;
  description: string | null;
  htmlTemplate: string;
  cssContent: string | null;
}

async function createCustomTag(
  request: APIRequestContext,
  ctx: ScenarioState,
  projectId: number
): Promise<CustomTagFixture> {
  const tagName = `e2e938t${uniqueSuffix()}`;
  const response = await request.post('/api/custom-tags', {
    headers: await authHeaders(request),
    data: {
      tagName,
      htmlTemplate: `<div class="${SHARED_CSS_CLASS}">{{content}}</div>`,
      cssContent: SHARED_CSS,
      description: 'e2e938 の検証用タグ',
      tagFormat: 'BLOCK',
      projectId,
    },
  });
  expect(
    response.ok(),
    `カスタムタグの作成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const tag = (await response.json()) as CustomTagFixture;
  trackedTags(ctx).push(tag.id);
  return tag;
}

interface TemplateFixture {
  id: number;
  templateName: string;
  htmlTemplate: string;
  cssContent: string | null;
  isPublished: boolean;
  projectId: number | null;
  createdBy: number;
}

async function createTemplate(
  request: APIRequestContext,
  ctx: ScenarioState,
  projectId: number | null
): Promise<TemplateFixture> {
  const templateName = `E2E938 テンプレート ${uniqueSuffix()}`;
  const response = await request.post('/api/custom-tag-templates', {
    headers: await authHeaders(request),
    data: {
      templateName,
      description: 'e2e938 の検証用テンプレート',
      category: 'e2e938',
      htmlTemplate: `<div class="${SHARED_CSS_CLASS}">{{content}}</div>`,
      cssContent: SHARED_CSS,
      projectId,
    },
  });
  expect(
    response.ok(),
    `テンプレートの作成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const template = (await response.json()) as TemplateFixture;
  trackedTemplateNames(ctx).push(template.templateName);
  return template;
}

async function listTemplates(
  request: APIRequestContext,
  token: string,
  query = ''
): Promise<TemplateFixture[]> {
  const response = await request.get(`/api/custom-tag-templates${query}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    response.ok(),
    `テンプレート一覧の取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as TemplateFixture[];
}

async function listProjectTags(
  request: APIRequestContext,
  projectId: number
): Promise<CustomTagFixture[]> {
  const response = await request.get(`/api/projects/${projectId}/custom-tags`, {
    headers: await authHeaders(request),
  });
  expect(
    response.ok(),
    `カスタムタグ一覧の取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as CustomTagFixture[];
}

async function fetchProjectCssBundle(
  request: APIRequestContext,
  projectId: number
): Promise<string> {
  const response = await request.get(`/api/projects/${projectId}/custom-tags/css-bundle`, {
    headers: await authHeaders(request),
  });
  expect(
    response.ok(),
    `統合CSSの取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return response.text();
}

// ---- 画面操作の共通部品 ----

/**
 * タブ切り替え後にしか現れない要素(#1283)。`Tabs`(`src/components/Tabs.tsx`)は
 * クライアントコンポーネントで、タブボタン自体はサーバーレンダリングされて先に
 * 見えているため、ハイドレーション完了前にクリックすると `onClick` が
 * まだ紐付いておらず取りこぼされる。実ブラウザのホストではヘッドレスCIより
 * ハイドレーションが遅く、この取りこぼしが表面化しやすい。
 */
const TAG_PAGE_TAB_MARKERS: Record<string, (page: Page) => Locator> = {
  'カスタムタグ管理': (page) => page.locator('form#custom-tag-form'),
  '統合CSSの取得': (page) =>
    page.getByText('このプロジェクトのカスタムタグのCSSをまとめて', { exact: false }),
};

/**
 * プロジェクト詳細の「タグ」ページを開き、指定タブへ切り替える。
 * パネルはタブの中にあるため、これを通らないと描画されない。
 *
 * クリックそのものは成功しても、ハイドレーション前だとハンドラが付いておらず
 * 何も起きないことがある(#1283)。そのタブでしか現れないマーカーが見えるまで
 * クリックを再試行する。
 */
async function openTagsTab(page: Page, projectId: number, tabLabel: string): Promise<void> {
  await page.goto(`/projects/${projectId}/tags`, { waitUntil: 'commit' });
  const tab = page.getByRole('button', { name: tabLabel, exact: true });
  await expect(tab).toBeVisible({ timeout: 30_000 });

  const markerFactory = TAG_PAGE_TAB_MARKERS[tabLabel];
  if (!markerFactory) {
    throw new Error(`openTagsTab: 未知のタブです(マーカー未登録): ${tabLabel}`);
  }
  const marker = markerFactory(page);

  await expect(async () => {
    await tab.click();
    await expect(marker).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
}

/**
 * AIでカスタムタグを生成するフォーム。同じ画面の下部にある手動追加フォームも
 * `input[name="tagName"]` を持つため、`textarea[name="prompt"]` の有無で絞り込む。
 */
function generationForm(page: Page): Locator {
  return page.locator('form').filter({ has: page.locator('textarea[name="prompt"]') });
}

/** 手動でタグを追加・編集するフォーム(生成フォームと同じ画面の下部)。 */
function upsertForm(page: Page): Locator {
  return page.locator('form#custom-tag-form');
}

async function openGenerationForm(page: Page, projectId: number): Promise<Locator> {
  await openTagsTab(page, projectId, 'カスタムタグ管理');
  const form = generationForm(page);
  await expect(form.locator('textarea[name="prompt"]')).toBeVisible({ timeout: 30_000 });
  return form;
}

/**
 * 生成フォームを送信し、成功(「生成完了!」)か失敗(赤字のエラー)のどちらかに到達するまで待つ。
 *
 * 送信ボタンはセッションが未解決の間 `disabled` で、ラベルも「セッション確認中...」になる
 * (`CustomTagGenerationForm`。issue #778 の修正)。したがって
 * **「生成」と表示され、かつ押せる**ことを待てば、送信が握りつぶされる競合は起きない。
 * 移行前の spec が持っていたクリックの再試行ループは、この修正によって不要になった。
 */
async function submitGeneration(
  page: Page,
  form: Locator,
  prompt: string,
  tagName: string
): Promise<void> {
  await form.locator('textarea[name="prompt"]').fill(prompt);
  await form.locator('input[name="tagName"]').fill(tagName);

  const submit = form.locator('button[type="submit"]');
  await expect(submit).toHaveText('生成', { timeout: 30_000 });
  await expect(submit).toBeEnabled();
  await submit.click();

  await expect(page.getByText('生成完了！').or(form.locator('p.text-red-600')).first()).toBeVisible({
    timeout: 120_000,
  });
}

/** タグ一覧の、そのタグ名の行。 */
function tagRow(page: Page, tagName: string): Locator {
  return page.locator('tr').filter({ has: page.locator(`td:text-is("[${tagName}]")`) });
}

// ---- 共通の前提 ----

Given('カスタムタグを確かめるためのプロジェクトがある', async ({ ctx, request }) => {
  const project = await createProject(request, ctx);
  ctx.tagProjectId = project.id;
  ctx.tagProjectSlug = project.slug;
});

Given('そのプロジェクトにカスタムタグがある', async ({ ctx, request }) => {
  const tag = await createCustomTag(request, ctx, ctx.tagProjectId as number);
  ctx.tagId = tag.id;
  ctx.tagName = tag.tagName;
});

Given('同じCSSのカスタムタグを持つ別のプロジェクトがある', async ({ ctx, request }) => {
  const other = await createProject(request, ctx);
  await createCustomTag(request, ctx, other.id);
  ctx.tagOtherProjectId = other.id;
});

// ---- 生成と検証(generation.feature) ----

When(
  /^そのプロジェクトのカスタムタグ管理画面で「([^」]+)」というプロンプトからカスタムタグを生成する$/,
  async ({ ctx, page }, prompt: string) => {
    const form = await openGenerationForm(page, ctx.tagProjectId as number);
    const tagName = `e2e938g${uniqueSuffix()}`;
    await submitGeneration(page, form, prompt, tagName);
    ctx.tagName = tagName;
    ctx.tagGeneratedName = tagName;
  }
);

Then('生成完了が表示される', async ({ page }) => {
  await expect(page.getByText('生成完了！')).toBeVisible();
});

/**
 * 検証結果の表示は3通りある(エラーあり / 警告あり / どちらも無い)。
 * LLMスタブが返すHTMLには `{{content}}` が無く `missing-content-placeholder` の
 * **警告だけ**が付くため、「検証成功」だけを待つと結果が出ているのに落ちる(issue #949)。
 * ここで確かめたいのは「検証が走って結果が示されること」なので3通りとも受ける。
 */
Then('生成結果の検証結果が画面に示される', async ({ page }) => {
  await expect(
    page
      .getByText('検証成功')
      .or(page.locator('h3:has-text("エラー (")'))
      .or(page.locator('h3:has-text("警告 (")'))
      .first()
  ).toBeVisible({ timeout: 30_000 });
});

Then('ページを開き直すと、生成したタグがカスタムタグ一覧に現れる', async ({ ctx, page }) => {
  await openTagsTab(page, ctx.tagProjectId as number, 'カスタムタグ管理');
  await expect(tagRow(page, ctx.tagGeneratedName as string)).toBeVisible({ timeout: 30_000 });
});

When(
  /^そのプロジェクトのカスタムタグ管理画面でタグ名「([^」]+)」を入力して生成しようとする$/,
  async ({ ctx, page }, tagName: string) => {
    const form = await openGenerationForm(page, ctx.tagProjectId as number);
    await form.locator('textarea[name="prompt"]').fill('e2e938 のタグ名バリデーション');
    await form.locator('input[name="tagName"]').fill(tagName);
    const submit = form.locator('button[type="submit"]');
    await expect(submit).toHaveText('生成', { timeout: 30_000 });
    await submit.click();
  }
);

Then('タグ名の入力は不正と判定され、生成は始まらない', async ({ page }) => {
  const tagNameInput = generationForm(page).locator('input[name="tagName"]');
  const isValid = await tagNameInput.evaluate((el: HTMLInputElement) => el.checkValidity());
  expect(isValid, 'タグ名がブラウザのバリデーションを通ってしまいました').toBe(false);
  await expect(page.getByText('生成完了！')).toHaveCount(0);
});

async function requestValidation(
  request: APIRequestContext,
  ctx: ScenarioState,
  htmlTemplate: string,
  cssContent: string
): Promise<void> {
  const response = await request.post('/api/custom-tags/validate', {
    headers: await authHeaders(request),
    data: { htmlTemplate, cssContent },
  });
  expect(
    response.ok(),
    `検証APIの呼び出しに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.tagValidation = (await response.json()) as {
    isValid: boolean;
    errors: { type: string }[];
  };
}

When(
  /^HTMLテンプレート「([^」]+)」の検証を要求する$/,
  async ({ ctx, request }, htmlTemplate: string) => {
    await requestValidation(request, ctx, htmlTemplate, '');
  }
);

When(
  /^HTMLテンプレート「([^」]+)」とCSS「([^」]+)」の検証を要求する$/,
  async ({ ctx, request }, htmlTemplate: string, cssContent: string) => {
    await requestValidation(request, ctx, htmlTemplate, cssContent);
  }
);

Then(
  /^検証は不合格になり、理由に「([^」]+)」が含まれる$/,
  async ({ ctx }, errorType: string) => {
    const result = ctx.tagValidation as { isValid: boolean; errors: { type: string }[] };
    expect(result.isValid, '危険な内容が検証を通過しました').toBe(false);
    expect(result.errors.map((error) => error.type)).toContain(String(errorType));
  }
);

When(
  'そのプロジェクトのカスタムタグ管理画面で、既にあるタグと同じ名前でカスタムタグを生成する',
  async ({ ctx, page }) => {
    const form = await openGenerationForm(page, ctx.tagProjectId as number);
    await submitGeneration(page, form, 'e2e938 の失敗経路', ctx.tagName as string);
  }
);

Then('生成フォームに失敗の理由が表示される', async ({ ctx, page }) => {
  const message = generationForm(page).locator('p.text-red-600');
  await expect(message).toBeVisible();
  await expect(message).toContainText(ctx.tagName as string);
});

Then('生成完了は表示されない', async ({ page }) => {
  await expect(page.getByText('生成完了！')).toHaveCount(0);
});

// ---- 編集と削除(management.feature) ----

When(
  /^そのプロジェクトのカスタムタグ管理画面でそのタグの説明を「([^」]+)」に変更して保存する$/,
  async ({ ctx, page }, description: string) => {
    await openTagsTab(page, ctx.tagProjectId as number, 'カスタムタグ管理');
    await tagRow(page, ctx.tagName as string).getByRole('button', { name: '編集' }).click();

    const form = upsertForm(page);
    await expect(form.getByRole('heading', { name: `カスタムタグを編集: [${ctx.tagName}]` })).toBeVisible();
    await form.locator('input[name="description"]').fill(description);
    await form.getByRole('button', { name: '更新', exact: true }).click();
  }
);

Then(
  /^ページを開き直すと、そのタグの説明は「([^」]+)」になっている$/,
  async ({ ctx, page }, description: string) => {
    await openTagsTab(page, ctx.tagProjectId as number, 'カスタムタグ管理');
    await expect(tagRow(page, ctx.tagName as string)).toContainText(description, { timeout: 30_000 });
  }
);

When('そのプロジェクトのカスタムタグ管理画面でそのタグを削除する', async ({ ctx, page }) => {
  await openTagsTab(page, ctx.tagProjectId as number, 'カスタムタグ管理');
  const row = tagRow(page, ctx.tagName as string);
  await expect(row).toBeVisible({ timeout: 30_000 });
  page.once('dialog', (dialog) => dialog.accept());
  await row.getByRole('button', { name: '削除' }).click();
});

Then('カスタムタグ一覧にそのタグは表示されない', async ({ ctx, page }) => {
  await expect(tagRow(page, ctx.tagName as string)).toHaveCount(0, { timeout: 30_000 });
});

Then('ページを開き直しても、そのタグは表示されない', async ({ ctx, page }) => {
  await openTagsTab(page, ctx.tagProjectId as number, 'カスタムタグ管理');
  await expect(tagRow(page, ctx.tagName as string)).toHaveCount(0, { timeout: 30_000 });
});

/**
 * 記事は「本文のMarkdown」として表す。`posts` 行を作る公開APIは無く(公開経路の
 * 内部ブリッジのみ)、確かめたいのは行の有無ではなく**削除後に描画した結果**だからである。
 */
Given('そのタグを本文で使っている記事がある', async ({ ctx }) => {
  ctx.tagArticleMarkdown = `[${ctx.tagName}]\ne2e938 の記事本文\n[/${ctx.tagName}]`;
});

When('そのタグの削除を要求する', async ({ ctx, request }) => {
  const response = await request.delete(`/api/custom-tags/${ctx.tagId}`, {
    headers: await authHeaders(request),
  });
  ctx.tagDeleteStatus = response.status();
  ctx.tagDeleteBody = await response.text();
});

Then(/^削除は「(\d+)」で成功する$/, async ({ ctx }, status: string) => {
  expect(ctx.tagDeleteStatus, `応答本文: ${ctx.tagDeleteBody}`).toBe(Number(status));
});

Then('その記事を描画すると、タグは展開されずショートコードのまま残る', async ({ ctx, request }) => {
  const response = await request.post(`/api/projects/${ctx.tagProjectId}/preview/render`, {
    headers: await authHeaders(request),
    data: { markdown: ctx.tagArticleMarkdown },
  });
  expect(
    response.ok(),
    `記事の描画に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const { html } = (await response.json()) as { html: string };
  expect(html, '削除済みのタグが展開されています').toContain(`[${ctx.tagName}]`);
  expect(html, '削除済みのタグが展開されています').toContain(`[/${ctx.tagName}]`);
  expect(html, '削除済みのタグのテンプレートが描画されています').not.toContain(
    `<div class="${SHARED_CSS_CLASS}">`
  );
});

Then('そのプロジェクトのカスタムタグ一覧にそのタグは残っていない', async ({ ctx, request }) => {
  const tags = await listProjectTags(request, ctx.tagProjectId as number);
  expect(tags.map((tag) => tag.tagName)).not.toContain(ctx.tagName);
});

// ---- プレビューとCSS配信(preview-and-css.feature) ----

When(
  /^そのプロジェクトのカスタムタグ管理画面でHTMLテンプレートに「([^」]+)」を入力する$/,
  async ({ ctx, page }, htmlTemplate: string) => {
    await openTagsTab(page, ctx.tagProjectId as number, 'カスタムタグ管理');
    const form = upsertForm(page);
    await expect(form.locator('textarea[name="htmlTemplate"]')).toBeVisible({ timeout: 30_000 });
    await form.locator('textarea[name="htmlTemplate"]').fill(htmlTemplate);
  }
);

Then(
  /^プレビューにテスト用コンテンツが「([^」]+)」として描画される$/,
  async ({ page }, cssClass: string) => {
    // プレビューは入力から300msのデバウンスを置いてサーバーへ問い合わせ、iframe の
    // srcDoc を書き換える。srcDoc の中身は frameLocator から通常どおり辿れる。
    const preview = page.frameLocator('iframe[title="カスタムタグプレビュー"]');
    await expect(preview.locator(`.${cssClass}`)).toContainText('サンプルテキスト', {
      timeout: 30_000,
    });
  }
);

Then('そのプロジェクトのカスタムタグ一覧には、まだ何も登録されていない', async ({ ctx, request }) => {
  expect(await listProjectTags(request, ctx.tagProjectId as number)).toEqual([]);
});

When('そのプロジェクトの統合CSSを画面で表示する', async ({ ctx, page }) => {
  await openTagsTab(page, ctx.tagProjectId as number, '統合CSSの取得');
  const bundle = page.locator('pre');
  await expect(bundle).toBeVisible({ timeout: 30_000 });
  ctx.tagCssBundleShown = await bundle.innerText();
});

Then('統合CSSにそのタグのCSSが含まれる', async ({ ctx }) => {
  const bundle = ctx.tagCssBundleShown as string;
  expect(bundle, '統合CSSにタグ名の見出しがありません').toContain(`/* === ${ctx.tagName} === */`);
  expect(bundle, '統合CSSにタグのセレクタがありません').toContain(`.${SHARED_CSS_CLASS}`);
});

When(
  /^そのプロジェクトのCSSセレクタ接頭辞を「([^」]+)」に変更する$/,
  async ({ ctx, request }, prefix: string) => {
    const response = await request.put(`/api/projects/${ctx.tagProjectId}/css-selector-prefix`, {
      headers: await authHeaders(request),
      data: { cssSelectorPrefix: prefix },
    });
    expect(
      response.ok(),
      `CSSセレクタ接頭辞の保存に失敗しました (status=${response.status()}): ${await response.text()}`
    ).toBe(true);
    expect((await response.json()) as { cssSelectorPrefix: string }).toEqual({
      cssSelectorPrefix: prefix,
    });
  }
);

Then(
  /^そのプロジェクトの統合CSSでは、タグのセレクタが「([^」]+)」で始まる$/,
  async ({ ctx, request }, prefix: string) => {
    const bundle = await fetchProjectCssBundle(request, ctx.tagProjectId as number);
    expect(bundle, '統合CSSのセレクタに接頭辞が付いていません').toContain(
      `${prefix} .${SHARED_CSS_CLASS}`
    );
  }
);

Then(
  /^別のプロジェクトの統合CSSには「([^」]+)」が現れない$/,
  async ({ ctx, request }, prefix: string) => {
    const bundle = await fetchProjectCssBundle(request, ctx.tagOtherProjectId as number);
    expect(bundle, '他プロジェクトの統合CSSに別プロジェクトの接頭辞が漏れています').not.toContain(
      String(prefix)
    );
    // 自分の接頭辞(未設定なのでslug)は付いている。付いていなければ衝突しないことの
    // 根拠が「そもそも接頭辞が無い」になってしまう。
    expect(bundle).toContain(`.${SHARED_CSS_CLASS}`);
  }
);

// ---- テンプレート(templates.feature) ----

Given('自分が作った未公開のカスタムタグテンプレートがある', async ({ ctx, request }) => {
  const template = await createTemplate(request, ctx, null);
  expect(template.isPublished, 'テンプレートが作成直後から公開されています').toBe(false);
  ctx.tagTemplateId = template.id;
  ctx.tagTemplateName = template.templateName;
  ctx.tagTemplateHtml = template.htmlTemplate;
});

Given('そのプロジェクトに公開済みのカスタムタグテンプレートがある', async ({ ctx, request }) => {
  const template = await createTemplate(request, ctx, ctx.tagProjectId as number);
  const published = await request.post(`/api/custom-tag-templates/${template.id}/publish`, {
    headers: await authHeaders(request),
  });
  expect(
    published.ok(),
    `テンプレートの公開に失敗しました (status=${published.status()}): ${await published.text()}`
  ).toBe(true);
  ctx.tagTemplateId = template.id;
  ctx.tagTemplateName = template.templateName;
  ctx.tagTemplateHtml = template.htmlTemplate;
});

Then('他の利用者のテンプレート一覧に、そのテンプレートは現れていない', async ({ ctx, request }) => {
  const templates = await listTemplates(request, await otherUserToken(request));
  expect(templates.map((template) => template.id)).not.toContain(ctx.tagTemplateId);
});

Step('そのテンプレートを公開する', async ({ ctx, request }) => {
  const response = await request.post(`/api/custom-tag-templates/${ctx.tagTemplateId}/publish`, {
    headers: await authHeaders(request),
  });
  expect(
    response.ok(),
    `テンプレートの公開に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

Then('他の利用者のテンプレート一覧にそのテンプレートが現れる', async ({ ctx, request }) => {
  const templates = await listTemplates(request, await otherUserToken(request));
  expect(templates.map((template) => template.id)).toContain(ctx.tagTemplateId);
});

When('そのテンプレートを非公開に戻す', async ({ ctx, request }) => {
  const response = await request.post(`/api/custom-tag-templates/${ctx.tagTemplateId}/unpublish`, {
    headers: await authHeaders(request),
  });
  expect(
    response.ok(),
    `テンプレートの非公開化に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

Then('他の利用者のテンプレート一覧にそのテンプレートは現れない', async ({ ctx, request }) => {
  const templates = await listTemplates(request, await otherUserToken(request));
  expect(templates.map((template) => template.id)).not.toContain(ctx.tagTemplateId);
});

When('テンプレートギャラリーでそのテンプレートを複製する', async ({ ctx, page }) => {
  await page.goto(`/custom-tag-templates?projectId=${ctx.tagProjectId}`, { waitUntil: 'commit' });
  await expect(page.getByRole('heading', { name: 'カスタムタグテンプレート' })).toBeVisible({
    timeout: 30_000,
  });

  // ギャラリーカードは `"use client"` の `CustomTagTemplateGallery` が描画するため、
  // ハイドレーション完了前にクリックすると `onClick`(`setSelectedTemplate`)が
  // まだ紐付いておらず取りこぼされ、詳細モーダルが開かないまま複製名入力欄の
  // 待機がタイムアウトする(issue #1312、#1283/#1284と同種)。
  // `level: 3` でカードの `<h3>` に限定する。モーダルが開くと同じテンプレート名の
  // `<h2>` も現れるため、限定しないとリトライ2周目以降で複数要素にマッチしてしまう。
  const cardHeading = page.getByRole('heading', { name: ctx.tagTemplateName as string, level: 3 });
  await expect(cardHeading).toBeVisible({ timeout: 30_000 });

  const cloneNameInput = page.getByPlaceholder('新しいテンプレート名');
  await expect(async () => {
    await cardHeading.click();
    await expect(cloneNameInput).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });

  const cloneName = `E2E938 複製 ${uniqueSuffix()}`;
  await cloneNameInput.fill(cloneName);
  trackedTemplateNames(ctx).push(cloneName);
  ctx.tagCloneName = cloneName;

  // 複製の確認は window.confirm。承諾しないと複製そのものが行われない。
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: '複製を作成' }).click();
  await expect(cloneNameInput).toHaveCount(0, { timeout: 30_000 });
});

Then('ギャラリーで未公開を含めて表示すると、複製が未公開として現れる', async ({ ctx, page }) => {
  await page.goto(`/custom-tag-templates?projectId=${ctx.tagProjectId}&showAll=true`, {
    waitUntil: 'commit',
  });
  const card = page
    .locator('div')
    .filter({ has: page.getByRole('heading', { name: ctx.tagCloneName as string }) })
    .last();
  await expect(card).toBeVisible({ timeout: 30_000 });
  await expect(card).toContainText('非公開');
});

Then(
  '複製は元とは別のテンプレートとして、そのプロジェクトに登録されている',
  async ({ ctx, request }) => {
    const templates = await listTemplates(
      request,
      await adminToken(request),
      `?projectId=${ctx.tagProjectId}&showAll=true`
    );
    const clone = templates.find((template) => template.templateName === ctx.tagCloneName);
    expect(clone, `複製「${ctx.tagCloneName}」が見つかりません`).toBeDefined();
    expect(clone!.id, '複製が元と同じテンプレートになっています').not.toBe(ctx.tagTemplateId);
    expect(clone!.projectId, '複製が自分のプロジェクトに属していません').toBe(ctx.tagProjectId);
    expect(clone!.isPublished, '複製が最初から公開されています').toBe(false);
    expect(clone!.htmlTemplate, '複製に元の内容が引き継がれていません').toBe(ctx.tagTemplateHtml);
  }
);

When('他の利用者としてそのテンプレートの削除を要求する', async ({ ctx, request }) => {
  const response = await request.delete(`/api/custom-tag-templates/${ctx.tagTemplateId}`, {
    headers: { Authorization: `Bearer ${await otherUserToken(request)}` },
  });
  ctx.tagTemplateDeleteStatus = response.status();
  ctx.tagTemplateDeleteBody = await response.text();
});

Then(/^削除は「(\d+)」で拒否される$/, async ({ ctx }, status: string) => {
  expect(ctx.tagTemplateDeleteStatus, `応答本文: ${ctx.tagTemplateDeleteBody}`).toBe(Number(status));
});

Then('そのテンプレートは残っている', async ({ ctx, request }) => {
  const response = await request.get(`/api/custom-tag-templates/${ctx.tagTemplateId}`, {
    headers: await authHeaders(request),
  });
  expect(response.status(), 'テンプレートが削除されています').toBe(200);
});

When('自分のテンプレート一覧を取得する', async ({ ctx, request }) => {
  ctx.tagMyTemplates = await listTemplates(request, await adminToken(request), '/my-templates');
});

Then('一覧にそのテンプレートが含まれる', async ({ ctx }) => {
  const templates = ctx.tagMyTemplates as TemplateFixture[];
  expect(templates.map((template) => template.id)).toContain(ctx.tagTemplateId);
});

When('他の利用者が自分のテンプレート一覧を取得する', async ({ ctx, request }) => {
  ctx.tagOtherTemplates = await listTemplates(
    request,
    await otherUserToken(request),
    '/my-templates'
  );
});

Then('その一覧にそのテンプレートは含まれない', async ({ ctx }) => {
  const templates = ctx.tagOtherTemplates as TemplateFixture[];
  expect(templates.map((template) => template.id)).not.toContain(ctx.tagTemplateId);
});

// ---- プロジェクト認可(authorization.feature) ----

interface DeniedOutcome {
  status: number;
  body: string;
}

Given(
  'カスタムタグを置くプロジェクトが2つあり、一般利用者は片方だけのメンバーである',
  async ({ ctx, request }) => {
    const memberProject = await createProject(request, ctx);
    const otherProject = await createProject(request, ctx);

    const me = await request.get('/api/identity/me', {
      headers: { Authorization: `Bearer ${await otherUserToken(request)}` },
    });
    expect(me.ok(), `一般利用者の情報を取得できませんでした (status=${me.status()})`).toBe(true);
    const memberUserId = ((await me.json()) as { id: number }).id;

    const added = await request.post(`/api/projects/${memberProject.id}/users`, {
      headers: await authHeaders(request),
      data: { userId: memberUserId, wpRole: 'editor' },
    });
    expect(
      added.ok(),
      `プロジェクトメンバーの追加に失敗しました (status=${added.status()}): ${await added.text()}`
    ).toBe(true);

    ctx.tagAuthzMemberProjectId = memberProject.id;
    ctx.tagAuthzOtherProjectId = otherProject.id;
    ctx.tagAuthzMemberUserId = memberUserId;
  }
);

Given('他プロジェクトに未公開のカスタムタグテンプレートがある', async ({ ctx, request }) => {
  const template = await createTemplate(request, ctx, ctx.tagAuthzOtherProjectId as number);
  expect(template.isPublished, 'テンプレートが作成直後から公開されています').toBe(false);
});

When('一般利用者が他プロジェクトのカスタムタグ一覧を要求する', async ({ ctx, request }) => {
  const response = await request.get(`/api/custom-tags?projectId=${ctx.tagAuthzOtherProjectId}`, {
    headers: { Authorization: `Bearer ${await otherUserToken(request)}` },
  });
  ctx.tagAuthzDenied = { status: response.status(), body: await response.text() } satisfies DeniedOutcome;
});

When('一般利用者が他プロジェクトの統合CSSを要求する', async ({ ctx, request }) => {
  const response = await request.get(
    `/api/custom-tags/css-bundle?projectId=${ctx.tagAuthzOtherProjectId}`,
    { headers: { Authorization: `Bearer ${await otherUserToken(request)}` } }
  );
  ctx.tagAuthzDenied = { status: response.status(), body: await response.text() } satisfies DeniedOutcome;
});

When('一般利用者が他プロジェクトの詳細カスタムタグ一覧を要求する', async ({ ctx, request }) => {
  const response = await request.get(`/api/projects/${ctx.tagAuthzOtherProjectId}/custom-tags`, {
    headers: { Authorization: `Bearer ${await otherUserToken(request)}` },
  });
  ctx.tagAuthzDenied = { status: response.status(), body: await response.text() } satisfies DeniedOutcome;
});

When(
  '一般利用者が他プロジェクトの未公開を含むテンプレート一覧を要求する',
  async ({ ctx, request }) => {
    const response = await request.get(
      `/api/custom-tag-templates?projectId=${ctx.tagAuthzOtherProjectId}&showAll=true`,
      { headers: { Authorization: `Bearer ${await otherUserToken(request)}` } }
    );
    ctx.tagAuthzDenied = { status: response.status(), body: await response.text() } satisfies DeniedOutcome;
  }
);

Then('カスタムタグの操作はプロジェクトメンバーではないとして拒否される', async ({ ctx }) => {
  const outcome = ctx.tagAuthzDenied as DeniedOutcome | undefined;
  expect(outcome, 'カスタムタグ関連の拒否結果が記録されていません').toBeDefined();
  expect(outcome!.status, `応答本文: ${outcome!.body}`).toBe(403);
  expect(outcome!.body, '拒否理由にプロジェクトメンバーである旨が示されていない').toContain(
    'プロジェクトメンバー'
  );
});

// ---- コンテンツキャッシュ(content-cache.feature) ----

interface ContentCacheOutcome {
  status: number;
  body: string;
}

async function requestContentCache(
  request: APIRequestContext,
  ctx: ScenarioState,
  url: string
): Promise<void> {
  const response = await request.get(`/api/content-cache?url=${encodeURIComponent(url)}`, {
    headers: await authHeaders(request),
    timeout: 120_000,
  });
  const outcome: ContentCacheOutcome = { status: response.status(), body: await response.text() };
  ctx.tagCacheUrl = url;
  ctx.tagCachePrevious = ctx.tagCacheOutcome;
  ctx.tagCacheOutcome = outcome;
}

When(
  /^外部URL「([^」]+)」のコンテンツ取得を要求する$/,
  async ({ ctx, request }, url: string) => {
    await requestContentCache(request, ctx, url);
  }
);

Then(
  /^取得は成功し、ページの題名「([^」]+)」が返る$/,
  async ({ ctx }, title: string) => {
    const outcome = ctx.tagCacheOutcome as ContentCacheOutcome;
    expect(outcome.status, `応答本文: ${outcome.body}`).toBe(200);
    const body = JSON.parse(outcome.body) as { data: Record<string, string> };
    expect(body.data.title).toBe(String(title));
  }
);

When('同じURLのコンテンツ取得をもう一度要求する', async ({ ctx, request }) => {
  await requestContentCache(request, ctx, ctx.tagCacheUrl as string);
});

/**
 * TTL内の再取得はスクレイピングを行わず、保存済みの行をそのまま返す
 * (`ContentCacheService#resolve`)。したがって2回の応答は完全に一致する。
 * 一致しなければ、キャッシュが効かず毎回外部へ取りに行っていることになる。
 */
Then('2回目もキャッシュされた同じ内容が返る', async ({ ctx }) => {
  const first = ctx.tagCachePrevious as ContentCacheOutcome;
  const second = ctx.tagCacheOutcome as ContentCacheOutcome;
  expect(second.status).toBe(200);
  expect(second.body, 'キャッシュが効かず内容が変わりました').toBe(first.body);
});

When(
  /^内部アドレス「([^」]+)」のコンテンツ取得を要求する$/,
  async ({ ctx, request }, url: string) => {
    await requestContentCache(request, ctx, url);
  }
);

/**
 * 文面まで照合するのは、#902 の退行を確実に落とすため。
 * 「何らかのエラーになった」で通すと、宛先へ取りに行って失敗しただけの状態と区別が付かない。
 * この文面は `OutboundUrlGuard#requireAllowed` にしか無い。
 */
Then(
  /^取得は「(\d+)」で拒否され、外部の公開ページのみ取得できると示される$/,
  async ({ ctx }, status: string) => {
    const outcome = ctx.tagCacheOutcome as ContentCacheOutcome;
    expect(
      outcome.status,
      `内部アドレス ${ctx.tagCacheUrl} の取得が拒否されませんでした: ${outcome.body}`
    ).toBe(Number(status));
    expect(outcome.body).toContain('外部の公開ページのみ取得できます');
  }
);

// ---- 応答時間(performance.feature) ----

When(/^カスタムタグの検証を同時に「(\d+)」件要求する$/, async ({ ctx, request }, count: string) => {
  const headers = await authHeaders(request);
  const startedAt = Date.now();
  const responses = await Promise.all(
    Array.from({ length: Number(count) }, (_unused, index) =>
      request.post('/api/custom-tags/validate', {
        headers,
        data: {
          htmlTemplate: `<div id="e2e938-${index}">{{content}}</div>`,
          cssContent: `.e2e938-${index} { padding: 10px; }`,
        },
      })
    )
  );
  ctx.tagValidationElapsedMs = Date.now() - startedAt;
  ctx.tagValidationResponses = await Promise.all(
    responses.map(async (response) => ({ status: response.status(), body: await response.text() }))
  );
});

Then(/^すべての応答が「(\d+)」ミリ秒以内に返る$/, async ({ ctx }, limitMs: string) => {
  const elapsed = ctx.tagValidationElapsedMs as number;
  console.log(`検証APIの所要時間: ${elapsed}ms`);
  expect(elapsed, `検証APIの応答が ${limitMs}ms を超えました`).toBeLessThan(Number(limitMs));
});

Then('すべての応答に検証結果が含まれる', async ({ ctx }) => {
  const responses = ctx.tagValidationResponses as { status: number; body: string }[];
  expect(responses.length).toBeGreaterThan(0);
  for (const response of responses) {
    expect(response.status, `検証APIが失敗しました: ${response.body}`).toBe(200);
    // 応答のフィールド名は `valid` ではなく `isValid`(services/content の ValidationResult)。
    expect(JSON.parse(response.body)).toHaveProperty('isValid');
  }
});

When('そのプロジェクトのタグ画面を2回目に開く', async ({ ctx, page }) => {
  const url = `/projects/${ctx.tagProjectId}/tags`;
  // 1回目は Next.js(devモード)のルートコンパイルを含むので計測しない。
  await page.goto(url);
  const startedAt = Date.now();
  await page.goto(url);
  ctx.tagPageLoadMs = Date.now() - startedAt;
});

Then(/^ページロードは「(\d+)」ミリ秒以内に完了する$/, async ({ ctx }, limitMs: string) => {
  const elapsed = ctx.tagPageLoadMs as number;
  console.log(`タグ画面のページロード時間: ${elapsed}ms`);
  expect(elapsed, `ページロードが ${limitMs}ms を超えました`).toBeLessThan(Number(limitMs));
});

Then('カスタムタグ管理タブに生成フォームが表示される', async ({ page }) => {
  await page.getByRole('button', { name: 'カスタムタグ管理', exact: true }).click();
  const form = generationForm(page);
  await expect(form.locator('textarea[name="prompt"]')).toBeVisible({ timeout: 30_000 });
  await expect(form.locator('input[name="tagName"]')).toBeVisible();
  await expect(form.locator('button[type="submit"]')).toBeVisible();
});

// ---- 後片付け ----

/**
 * 作ったものを全て消す。順序は テンプレート → タグ → プロジェクト。
 * テンプレートは**名前**で引き直す(画面から作られた複製はidをステップが知らないため)。
 */
After({ tags: '@custom-tag' }, async ({ ctx, request }) => {
  const headers = await authHeaders(request);
  const names = trackedTemplateNames(ctx);
  const projectIds = trackedProjects(ctx);

  if (names.length > 0) {
    const queries = ['?showAll=true', ...projectIds.map((id) => `?projectId=${id}&showAll=true`)];
    const seen = new Set<number>();
    for (const query of queries) {
      const response = await request.get(`/api/custom-tag-templates${query}`, { headers });
      if (!response.ok()) {
        continue;
      }
      for (const template of (await response.json()) as TemplateFixture[]) {
        if (names.includes(template.templateName) && !seen.has(template.id)) {
          seen.add(template.id);
          await request.delete(`/api/custom-tag-templates/${template.id}`, { headers });
        }
      }
    }
  }

  for (const tagId of trackedTags(ctx)) {
    await request.delete(`/api/custom-tags/${tagId}`, { headers });
  }
  // 生成シナリオが画面から作ったタグは id が分からないので、プロジェクトの一覧から引き直す。
  for (const projectId of projectIds) {
    const response = await request.get(`/api/projects/${projectId}/custom-tags`, { headers });
    if (!response.ok()) {
      continue;
    }
    for (const tag of (await response.json()) as CustomTagFixture[]) {
      await request.delete(`/api/custom-tags/${tag.id}`, { headers });
    }
  }
  // authorization.feature が追加したプロジェクトメンバーシップ(project_users)は、
  // project_id に外部キーが無い(ADR-0004)ためプロジェクト削除では消えず、先に消す必要がある。
  const authzMemberUserId = ctx.tagAuthzMemberUserId as number | undefined;
  const authzMemberProjectId = ctx.tagAuthzMemberProjectId as number | undefined;
  if (authzMemberUserId !== undefined && authzMemberProjectId !== undefined) {
    await request.delete(`/api/projects/${authzMemberProjectId}/users/${authzMemberUserId}`, { headers });
  }
  for (const projectId of projectIds) {
    await request.delete(`/api/projects/${projectId}`, { headers });
  }
});
