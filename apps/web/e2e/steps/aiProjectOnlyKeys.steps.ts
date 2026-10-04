import type { APIRequestContext, APIResponse } from '@playwright/test';
import { Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  createFixtureProject,
  expect,
  fetchAccessToken,
} from '../support';
import { stubRequestCount } from '../support/stubs';

/**
 * ChatGPT / ClaudeのAPIキーはプロジェクト単位だけ(issue #1568)の受け入れシナリオのステップ定義。
 *
 * プロジェクトの後片付け(`After({ tags: '@ai' })`)・プロバイダー切り替え・画像プロンプト生成の
 * 依頼と成功確認は `aiGeneration.steps.ts` / `aiModelSelection.steps.ts` が `ctx.aiGenerationProject`
 * を読んで行うため、ここでは同じスロットに「キー無しのプロジェクト」を入れるだけにする。
 */

const KEY_REQUIRED_MESSAGE = 'このプロジェクトでAPIキーを設定してください';

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

Given('APIキーを設定していないAI設定用のプロジェクトが用意されている', async ({ ctx, request }) => {
  const project = await createFixtureProject(request, await adminToken(request), 'at-1568-no-key');
  ctx.aiGenerationProject = { projectId: project.id };
});

Given(/^プロジェクトのChatGPT APIキーを「(.+)」で設定する$/, async ({ ctx, request }, apiKey: string) => {
  const projectId = (ctx.aiGenerationProject as { projectId: number }).projectId;
  const response = await request.put(`/api/projects/${projectId}/api-keys/openai-api-key`, {
    headers: { Authorization: `Bearer ${await adminToken(request)}` },
    data: { apiKey },
  });
  expect(
    response.ok(),
    `プロジェクトのChatGPT APIキー設定に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

Given('LLMスタブの受信件数を控える', async ({ ctx }) => {
  ctx.llmStubCountBefore1568 = await stubRequestCount('llm');
});

When(
  /^プロジェクトを指定せずにプロバイダー「(.+)」を指定して下書き生成を試みる$/,
  async ({ ctx, request }, provider: string) => {
    ctx.projectOnlyKeyAttempt = await request.post('/api/ai/draft', {
      headers: { Authorization: `Bearer ${await adminToken(request)}` },
      data: { mode: 'draft', text: 'プロジェクト未指定の確認', provider },
    });
  }
);

async function expectKeyRequiredError(response: APIResponse, what: string): Promise<void> {
  expect(response.status(), `${what}がAPIキー無しで成功してしまう`).toBe(502);
  const body = (await response.json()) as { error: string };
  expect(body.error, `${what}のエラー: ${body.error}`).toContain(KEY_REQUIRED_MESSAGE);
}

Then('画像プロンプト生成はこのプロジェクトでAPIキーを設定するよう促すエラーになる', async ({ ctx }) => {
  await expectKeyRequiredError(ctx.imagePromptAttempt as APIResponse, '画像プロンプト生成');
});

Then('下書き生成はこのプロジェクトでAPIキーを設定するよう促すエラーになる', async ({ ctx }) => {
  await expectKeyRequiredError(ctx.projectOnlyKeyAttempt as APIResponse, '下書き生成');
});

Then('LLMスタブの受信件数は増えていない', async ({ ctx }) => {
  const after = await stubRequestCount('llm');
  expect(after, 'キーが無いのにLLMスタブが呼ばれた').toBe(ctx.llmStubCountBefore1568 as number);
});

Then('LLMスタブの受信件数が増えている', async ({ ctx }) => {
  const after = await stubRequestCount('llm');
  expect(after, 'プロジェクトのキーがあるのにLLMスタブが呼ばれていない').toBeGreaterThan(
    ctx.llmStubCountBefore1568 as number
  );
});
