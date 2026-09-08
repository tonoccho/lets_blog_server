import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';
import { createFixtureProject, deleteFixtureProject } from '../helpers';

/**
 * AI生成(下書き・セクション生成・画像プロンプト生成)のステップ定義(issue #1146 / AT-8-1、
 * issue #934の子)。
 *
 * これらのエンドポイントにWeb管理画面は無く、VSCode拡張(`apps/extension/src/apiClient.ts` の
 * `generateDraft` / `generateSection` / `generateImagePrompt`)だけが呼ぶ。したがって
 * ページ操作ではなく `request` フィクスチャで gateway を直接叩く(`@api`)。
 *
 * タグ提案・校正チェックのステップは issue #1004 で `ai.steps.ts` にすでにあり、この
 * ファイルには含めない(兄弟issueとのファイル衝突を避けるため、新規ファイルに分離する)。
 *
 * LLMは docker-compose.e2e-stubs.yml が `infra/e2e-stubs/llm` へ差し替える。`@stub` が
 * 付いているので、スタブが起動していなければ `stubs.steps.ts` の Before フックが
 * スキップではなく失敗させる。
 */

/** `POST /api/ai/draft` の応答(`AiDraftResponse`)。 */
interface DraftResult {
  result: string;
}

/** `POST /api/ai/section` の応答(`AiSectionResponse`)。 */
interface SectionResult {
  result: string;
  sources: { title: string; url: string }[];
  searchNote: string | null;
}

/** `POST /api/projects/{id}/ai/generate-image-prompt` の応答(`AiImagePromptResponse`)。 */
interface ImagePromptResult {
  prompt: string;
}

/** シナリオ限りのAI設定用プロジェクト(画像プロンプト生成のみプロジェクトIDを取る)。 */
interface AiProjectFixture {
  projectId: number;
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

Given('AI設定用のプロジェクトが用意されている', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const project = await createFixtureProject(request, token, 'at8-1-ai-generation');
  ctx.aiGenerationProject = { projectId: project.id } satisfies AiProjectFixture;
});

After({ tags: '@ai' }, async ({ ctx, request }) => {
  const project = ctx.aiGenerationProject as AiProjectFixture | undefined;
  if (project) {
    const token = await adminToken(request);
    await deleteFixtureProject(request, token, project.projectId);
  }
});

// --------------------------------------------------------------- 下書き生成

When(/^「(.+)」というお題で下書き生成を依頼する$/, async ({ ctx, request }, topic: string) => {
  const token = await adminToken(request);
  const response = await request.post('/api/ai/draft', {
    headers: { Authorization: `Bearer ${token}` },
    data: { mode: 'draft', text: topic },
  });
  expect(
    response.ok(),
    `下書き生成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.draftResult = (await response.json()) as DraftResult;
});

Then('下書きの結果がスタブの決定的な下書き本文と一致する', async ({ ctx }) => {
  const result = ctx.draftResult as DraftResult;
  expect(result.result, `下書き生成の結果: ${JSON.stringify(result)}`)
    .toContain('これは受け入れテスト用の決定的な下書きです。');
});

// --------------------------------------------------------------- セクション生成(壁打ちの再生成を含む)

When(/^「(.+)」という見出しでセクションの本文生成を依頼する$/, async ({ ctx, request }, heading: string) => {
  const token = await adminToken(request);
  const response = await request.post('/api/ai/section', {
    headers: { Authorization: `Bearer ${token}` },
    data: { mode: 'body', heading, articleTitle: 'E2Eスタブのタイトル' },
  });
  expect(
    response.ok(),
    `セクション生成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.sectionHeading = heading;
  ctx.sectionResult = (await response.json()) as SectionResult;
});

/**
 * 直前の生成結果を `history`(assistantの発話)として積み、`message` で追加指示を送る
 * (AiAssistService#buildSectionChatPrompt が history + message をプロンプトに連結する)。
 * これにより「直前の文脈が次の要求に含まれる」ことを、リクエストの組み立てとして固定する。
 */
When(
  /^その生成結果を踏まえて「(.+)」という追加の指示でセクションの再生成を依頼する$/,
  async ({ ctx, request }, message: string) => {
    const token = await adminToken(request);
    const previous = (ctx.sectionResult as SectionResult).result;
    const response = await request.post('/api/ai/section', {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        mode: 'body',
        heading: ctx.sectionHeading,
        articleTitle: 'E2Eスタブのタイトル',
        history: [{ role: 'assistant', content: previous }],
        message,
      },
    });
    expect(
      response.ok(),
      `セクションの壁打ち再生成に失敗しました (status=${response.status()}): ${await response.text()}`
    ).toBe(true);
    ctx.sectionRetryResult = (await response.json()) as SectionResult;
  }
);

Then('2回目のセクション生成も結果が返る', async ({ ctx }) => {
  const retry = ctx.sectionRetryResult as SectionResult;
  expect(retry.result.length, '2回目のセクション生成の結果が空でした').toBeGreaterThan(0);
});

Then('セクション生成の結果がスタブの決定的な本文と一致する', async ({ ctx }) => {
  const result = ctx.sectionResult as SectionResult;
  expect(result.result, `セクション生成の結果: ${JSON.stringify(result)}`)
    .toContain('これは受け入れテスト用の決定的な下書きです。');
});

// --------------------------------------------------------------- 画像プロンプト生成

When(/^「(.+)」という発言で画像プロンプト生成を依頼する$/, async ({ ctx, request }, message: string) => {
  const token = await adminToken(request);
  const projectId = (ctx.aiGenerationProject as AiProjectFixture).projectId;
  const response = await request.post(`/api/projects/${projectId}/ai/generate-image-prompt`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { message },
  });
  expect(
    response.ok(),
    `画像プロンプト生成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.imagePromptResult = (await response.json()) as ImagePromptResult;
});

Then('画像プロンプト生成の結果がスタブの決定的なプロンプト文字列と一致する', async ({ ctx }) => {
  const result = ctx.imagePromptResult as ImagePromptResult;
  expect(result.prompt).toBe('a deterministic e2e stub illustration, flat vector style, blue and white');
});
