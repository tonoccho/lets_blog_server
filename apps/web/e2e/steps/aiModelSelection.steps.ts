import type { APIRequestContext, APIResponse, Page } from '@playwright/test';
import { Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken, loginAsAdmin } from '../support';
import { STUB_URLS } from '../support/stubs';

/**
 * LLMモデル・プロバイダー選択の反映のステップ定義(issue #1148 / AT-8-3、issue #934の子。
 * 親issueのシナリオ10・11を引き取る)。
 *
 * プロジェクトフィクスチャ(`AI設定用のプロジェクトが用意されている`)と後片付けのAfterフックは
 * `aiGeneration.steps.ts`(issue #1146 / AT-8-1)がすでに`@ai`向けに定義済みのため、ここでは
 * 再定義せず `ctx.aiGenerationProject` を読むだけにする(兄弟issueとのステップ重複を避けるため、
 * 新規ファイルに分離する)。画像プロンプト生成の依頼ステップ・成功確認ステップも同ファイルの
 * ものを再利用する。
 *
 * 「選択が以後の生成要求に反映される」ことは、設定の再表示だけでは確認しない。
 * LLMスタブ(infra/e2e-stubs/llm/server.js)が実際に受け取ったリクエストの `model` フィールドまで
 * `/__control/state` の拡張状態(extraState)経由で検査する(issue #1148の実装ノート)。
 *
 * このスタブへは他のシナリオ(このファイル・他のfeatureとも)も並列にリクエストを送るため、
 * 「直近1件」ではなく履歴(`recentModels`)への含有で確認する。加えて、選択するモデル名は
 * `availableModels` の固定値をそのまま使わず一意なsuffixを付ける。固定値のまま比較すると、
 * 他のシナリオが偶然同じ値(既定モデルなど)を使っていた場合に、実際には自分のリクエストを
 * 検証できていないのに通ってしまう(逆に、履歴が上限を超えて自分の値が押し出された場合は
 * 誤って失敗する。どちらも実測で発生した)。
 */

/** シナリオ限りのAI設定用プロジェクト(`aiGeneration.steps.ts`が生成・削除を管理する)。 */
interface AiProjectFixture {
  projectId: number;
}

/** `GET /api/projects/{id}/ai-models/llm/models` の応答(`LlmModelListResponse`)。 */
interface LlmModelListResult {
  availableModels: string[];
  selected: string;
}

/** `GET /__control/state`(llmスタブ)の応答。`recentModels` はスタブ固有の拡張状態。 */
interface LlmStubState {
  recentModels: string[];
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

function unique(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

function currentProjectId(ctx: Record<string, unknown>): number {
  return (ctx.aiGenerationProject as AiProjectFixture).projectId;
}

async function fetchLlmModelList(request: APIRequestContext, token: string, projectId: number) {
  const response = await request.get(`/api/projects/${projectId}/ai-models/llm/models`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    response.ok(),
    `LLMモデル一覧の取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as LlmModelListResult;
}

async function fetchLlmStubRecentModels(): Promise<string[]> {
  const response = await fetch(`${STUB_URLS.llm}/__control/state`);
  const state = (await response.json()) as LlmStubState;
  return state.recentModels ?? [];
}

// --------------------------------------------------------------- モデル一覧・選択

When('利用可能なLLMモデル一覧を取得する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  ctx.llmModelList = await fetchLlmModelList(request, token, currentProjectId(ctx));
});

When('一覧の中から現在の選択と異なるモデルを選択する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const projectId = currentProjectId(ctx);
  const list = ctx.llmModelList as LlmModelListResult;
  expect(list.availableModels.length, '利用可能なLLMモデルが1件もありません(フィクスチャの不備)')
    .toBeGreaterThan(0);
  const base = list.availableModels.find((model) => model !== list.selected) ?? `${list.selected}-e2e-alt`;
  // 他の並列シナリオと衝突しない値にするため、一意なsuffixを付ける(コメント参照)。
  const candidate = `${base}-e2e-${unique()}`;
  ctx.chosenLlmModel = candidate;
  const response = await request.put(`/api/projects/${projectId}/ai-models/llm/models/selection`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { modelName: candidate },
  });
  expect(
    response.ok(),
    `LLMモデルの選択に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

Then('選択中モデルが変更したモデルになっている', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const list = await fetchLlmModelList(request, token, currentProjectId(ctx));
  expect(list.selected).toBe(ctx.chosenLlmModel);
});

Then(
  'LLMスタブが受け取った直近のリクエストに、選択中のモデルを使ったものが含まれる',
  async ({ ctx, request }) => {
    const token = await adminToken(request);
    const list = await fetchLlmModelList(request, token, currentProjectId(ctx));
    const recentModels = await fetchLlmStubRecentModels();
    expect(
      recentModels,
      `LLMスタブが受け取った直近のリクエストのmodel一覧=${JSON.stringify(recentModels)}、`
        + `選択中モデル=${list.selected}(スタブが選択どおりのモデルを受け取っていない)`
    ).toContain(list.selected);
  }
);

// --------------------------------------------------------------- プロバイダー切り替え

When(/^LLMプロバイダーを「(.+)」に切り替える$/, async ({ ctx, request }, provider: string) => {
  const token = await adminToken(request);
  const projectId = currentProjectId(ctx);
  const response = await request.put(`/api/projects/${projectId}/ai-models/llm/provider/selection`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { provider },
  });
  expect(
    response.ok(),
    `LLMプロバイダーの切り替えに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

/**
 * 失敗しうる画像プロンプト生成。成否をその場でアサートせず、応答をそのまま持ち回る
 * (`aiGeneration.steps.ts` の「画像プロンプト生成を依頼する」の非アサート版)。
 */
When(/^「(.+)」という発言で画像プロンプト生成を試みる$/, async ({ ctx, request }, message: string) => {
  const token = await adminToken(request);
  const projectId = currentProjectId(ctx);
  ctx.imagePromptAttempt = await request.post(`/api/projects/${projectId}/ai/generate-image-prompt`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { message },
  });
});

Then('画像プロンプト生成はAPIキー未設定のエラーになる', async ({ ctx }) => {
  const response = ctx.imagePromptAttempt as APIResponse;
  expect(response.status(), 'APIキー未設定のプロバイダーで画像プロンプト生成が成功してしまう').toBe(502);
  // ai-serviceのGlobalExceptionHandlerはErrorResponse(error, details)を返す(`error`フィールド、
  // `message`ではない。com.letsblog.common.web.ErrorResponse参照)。
  const body = (await response.json()) as { error: string };
  expect(body.error, `画像プロンプト生成のエラー: ${body.error}`).toContain('APIキーが設定されていません');
});

// --------------------------------------------------------------- AI執筆支援5機能(issue #1495)

/** 機能名(feature の例の値)ごとの、gateway 経由のエンドポイントと最小のリクエスト本文。 */
const WRITING_ASSIST_FEATURES: Record<string, { path: string; body: Record<string, unknown> }> = {
  下書き: { path: '/api/ai/draft', body: { mode: 'draft', text: 'モデル選択の確認' } },
  'Ask AI': { path: '/api/ai/ask', body: { question: 'モデル選択の確認' } },
  セクション生成: {
    path: '/api/ai/section',
    body: { mode: 'body', heading: 'モデル選択の確認', articleTitle: 'E2Eスタブのタイトル' },
  },
  タグ提案: { path: '/api/ai/tags', body: { text: 'モデル選択の確認' } },
  校正チェック: { path: '/api/ai/proofread', body: { text: 'モデル選択の確認' } },
  // 記事企画(ArticlePlanService, issue #1643)。projectId はパスに入るため `{projectId}` を置換する。
  壁打ちチャット: {
    path: '/api/projects/{projectId}/article-plan/chat',
    body: { history: [], message: 'モデル選択の確認', sessionId: null, githubIssueNumber: null },
  },
  タイトル提案: {
    path: '/api/projects/{projectId}/article-plan/suggest-titles',
    body: { history: [{ role: 'user', content: 'モデル選択の確認' }] },
  },
  構成提案: {
    path: '/api/projects/{projectId}/article-plan/suggest-structure',
    body: { history: [{ role: 'user', content: 'モデル選択の確認' }] },
  },
};

async function callWritingAssist(
  ctx: Record<string, unknown>,
  request: APIRequestContext,
  feature: string,
  options: { withProject: boolean; provider?: string }
): Promise<void> {
  const definition = WRITING_ASSIST_FEATURES[feature];
  expect(definition, `未対応の機能名です: ${feature}`).toBeDefined();
  const token = await adminToken(request);
  const data: Record<string, unknown> = { ...definition.body };
  if (options.withProject) {
    data.projectId = currentProjectId(ctx);
  }
  if (options.provider) {
    data.provider = options.provider;
  }
  const path = definition.path.replace('{projectId}', String(currentProjectId(ctx)));
  ctx.writingAssistAttempt = await request.post(path, {
    headers: { Authorization: `Bearer ${token}` },
    data,
  });
}

When(/^プロジェクトを指定して「(.+)」を依頼する$/, async ({ ctx, request }, feature: string) => {
  await callWritingAssist(ctx, request, feature, { withProject: true });
});

When(/^プロジェクトを指定せずに「(.+)」を依頼する$/, async ({ ctx, request }, feature: string) => {
  await callWritingAssist(ctx, request, feature, { withProject: false });
});

When(
  /^プロバイダー「(.+)」を明示してプロジェクトを指定し「(.+)」を依頼する$/,
  async ({ ctx, request }, provider: string, feature: string) => {
    await callWritingAssist(ctx, request, feature, { withProject: true, provider });
  }
);

Then(/^「(.+)」の依頼は成功する$/, async ({ ctx }, feature: string) => {
  const response = ctx.writingAssistAttempt as APIResponse;
  expect(
    response.ok(),
    `${feature}の依頼に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

Then(/^「(.+)」の依頼はAPIキー未設定のエラーになる$/, async ({ ctx }, feature: string) => {
  const response = ctx.writingAssistAttempt as APIResponse;
  expect(
    response.status(),
    `${feature}がAPIキー未設定のプロバイダーで成功してしまう(選択したプロバイダーが使われていない)`
  ).toBe(502);
  const body = (await response.json()) as { error: string };
  expect(body.error, `${feature}のエラー: ${body.error}`).toContain('APIキーが設定されていません');
});

// --------------------------------------------------------------- 画面でのプロバイダー別モデル(issue #1644)

/** 画面で保存したモデル名(feature上の名前 → 一意なsuffix付きの実際の名前)。 */
function savedUiModels(ctx: Record<string, unknown>): Record<string, string> {
  if (!ctx.uiSavedModels) {
    ctx.uiSavedModels = {};
  }
  return ctx.uiSavedModels as Record<string, string>;
}

function selectedModelLine(page: Page) {
  return page.locator('p', { hasText: '選択中のモデル:' });
}

/** 「AI・アセット」タブを開き、LLMのモデル入力欄が見えるまでクリックを再試行する(ハイドレーション前のクリック取りこぼし対策)。 */
When('プロジェクト詳細の「AI・アセット」のLLM画面を開く', async ({ ctx, page }) => {
  await loginAsAdmin(page);
  await page.goto(`/projects/${currentProjectId(ctx)}`, { waitUntil: 'commit' });
  const aiTab = page.getByRole('button', { name: 'AI・アセット', exact: true });
  await expect(aiTab).toBeVisible({ timeout: 30_000 });
  await expect(async () => {
    await aiTab.click();
    await expect(selectedModelLine(page)).toBeVisible({ timeout: 2_000 });
  }).toPass({ timeout: 30_000 });
});

When(/^画面でAIプロバイダーを「(.+)」に切り替える$/, async ({ page }, provider: string) => {
  const select = page.getByLabel('AIプロバイダー(このプロジェクトの既定)');
  await expect(select).toBeEnabled({ timeout: 30_000 });
  await select.selectOption(provider);
  // 保存完了(select の再有効化)を待つ。切り替え後のモデル表示はそのあとの確認ステップが待つ。
  await expect(select).toBeEnabled({ timeout: 30_000 });
  await expect(page.getByText('保存しました。').first()).toBeVisible({ timeout: 30_000 });
});

When(/^画面でモデル名「(.+)」を保存する$/, async ({ ctx, page }, name: string) => {
  const actual = `${name}-${unique()}`;
  savedUiModels(ctx)[name] = actual;
  const input = page.getByLabel('モデル名(例: gpt-4o-mini)');
  await expect(input).toBeVisible({ timeout: 30_000 });
  await input.fill(actual);
  const submit = input.locator('xpath=ancestor::form[1]').getByRole('button', { name: '保存', exact: true });
  await expect(submit).toBeEnabled({ timeout: 30_000 });
  await submit.click();
  await expect(selectedModelLine(page)).toContainText(actual, { timeout: 30_000 });
});

Then(/^画面の選択中のモデルが「(.+)」になっている$/, async ({ ctx, page }, name: string) => {
  const actual = savedUiModels(ctx)[name];
  expect(actual, `画面で保存していないモデル名です: ${name}`).toBeDefined();
  await expect(selectedModelLine(page)).toContainText(actual, { timeout: 30_000 });
  await expect(page.getByLabel('モデル名(例: gpt-4o-mini)')).toHaveValue(actual, { timeout: 30_000 });
});

Then('画面の選択中のモデルがシステム既定のClaudeモデルになっている', async ({ page, request }) => {
  const response = await request.get('/api/system-settings/app-settings', {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
  });
  expect(response.ok(), `アプリ設定の取得に失敗しました (status=${response.status()})`).toBe(true);
  const settings = (await response.json()) as { key: string; value: string | null }[];
  const claudeDefault = settings.find((s) => s.key === 'llm_claude_model')?.value;
  expect(claudeDefault, 'llm_claude_model の値が取得できません').toBeTruthy();
  await expect(selectedModelLine(page)).toContainText(claudeDefault as string, { timeout: 30_000 });
  await expect(page.getByLabel('モデル名(例: gpt-4o-mini)')).toHaveValue(claudeDefault as string, { timeout: 30_000 });
});

Then(
  /^LLMスタブが受け取った直近のリクエストに、画面で保存した「(.+)」を使ったものが含まれる$/,
  async ({ ctx }, name: string) => {
    const actual = savedUiModels(ctx)[name];
    expect(actual, `画面で保存していないモデル名です: ${name}`).toBeDefined();
    const recentModels = await fetchLlmStubRecentModels();
    expect(
      recentModels,
      `LLMスタブが受け取った直近のリクエストのmodel一覧=${JSON.stringify(recentModels)}、保存したモデル=${actual}`
    ).toContain(actual);
  }
);
