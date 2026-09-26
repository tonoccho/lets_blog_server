import type { APIRequestContext } from '@playwright/test';
import { Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * 校閲ステップ(FACT_CHECK、Web検索を伴う事実確認)のステップ定義(issue #1214)。
 *
 * 呼び出し先・プロジェクトフィクスチャ・共通の検証ステップ(`指摘一覧が1件以上返る` など)は
 * `aiReviewStep.steps.ts`(issue #1213)と `aiGeneration.steps.ts`(issue #1146)にあり、
 * ここでは校閲固有のもの(FACT_CHECKの依頼、スキップ、出典)だけを定義する。結果は
 * `aiReviewStep.steps.ts` と同じ `ctx.aiReviewStep*` に置き、共通ステップをそのまま使えるようにする。
 * Brave Searchスタブへの500の仕込み・プロジェクトのキー設定は `aiWebSearch.steps.ts`(issue #1147)。
 */

interface AiProjectFixture {
  projectId: number;
}

interface FactCheckSuggestion {
  id: string;
  stepKey: string;
  originalText: string;
  message: string;
  sources?: { title: string; url: string }[];
}

/** 校閲の応答。`skipped`/`skipReason` は校閲だけが返し、他のステップの応答には含まれない。 */
interface FactCheckResult {
  suggestions: FactCheckSuggestion[];
  skipped?: boolean;
  skipReason?: string;
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

When(
  /^「FACT_CHECK」ステップで「(.+)」という本文の指摘生成を依頼する$/,
  async ({ ctx, request }, text: string) => {
    const projectId = (ctx.aiGenerationProject as AiProjectFixture).projectId;
    const token = await adminToken(request);
    const response = await request.post(
      `/api/projects/${projectId}/ai/review-steps/FACT_CHECK/suggestions`,
      { headers: { Authorization: `Bearer ${token}` }, data: { text } }
    );
    // スキップはHTTPエラーではなく200の応答で伝えるのが要件のため、ここで200を要求する。
    expect(
      response.ok(),
      `校閲ステップの呼び出しがHTTPエラーになりました (status=${response.status()}): ${await response.text()}`
    ).toBe(true);
    ctx.aiReviewStepKey = 'FACT_CHECK';
    ctx.aiReviewStepText = text;
    ctx.aiReviewStepResult = (await response.json()) as FactCheckResult;
  }
);

Then('校閲ステップはスキップされていない', async ({ ctx }) => {
  const result = ctx.aiReviewStepResult as FactCheckResult;
  expect(result.skipped, `校閲の応答: ${JSON.stringify(result)}`).toBe(false);
});

Then('校閲ステップはスキップされた旨と理由が応答に含まれる', async ({ ctx }) => {
  const result = ctx.aiReviewStepResult as FactCheckResult;
  expect(result.skipped, `校閲がスキップされた旨が応答にありません: ${JSON.stringify(result)}`).toBe(true);
  expect(
    typeof result.skipReason === 'string' && result.skipReason.trim() !== '',
    `スキップの理由が応答にありません: ${JSON.stringify(result)}`
  ).toBe(true);
});

Then('指摘一覧は空である', async ({ ctx }) => {
  const result = ctx.aiReviewStepResult as FactCheckResult;
  expect(result.suggestions, `指摘が返っています: ${JSON.stringify(result)}`).toEqual([]);
});

Then('返った指摘はすべて出典のタイトルとURLを持つ', async ({ ctx }) => {
  const result = ctx.aiReviewStepResult as FactCheckResult;
  for (const suggestion of result.suggestions) {
    expect(
      suggestion.sources?.length ?? 0,
      `指摘に出典がありません: ${JSON.stringify(suggestion)}`
    ).toBeGreaterThan(0);
    for (const source of suggestion.sources ?? []) {
      expect(source.title, `出典にタイトルがありません: ${JSON.stringify(source)}`).toBeTruthy();
      expect(source.url, `出典のURLがhttp(s)ではありません: ${JSON.stringify(source)}`).toMatch(/^https?:\/\//);
    }
  }
});

Then('応答にスキップ情報も出典も含まれない', async ({ ctx }) => {
  const result = ctx.aiReviewStepResult as Record<string, unknown> & FactCheckResult;
  expect('skipped' in result, `既存ステップの応答にskippedが含まれます: ${JSON.stringify(result)}`).toBe(false);
  expect('skipReason' in result, `既存ステップの応答にskipReasonが含まれます: ${JSON.stringify(result)}`).toBe(false);
  for (const suggestion of result.suggestions) {
    expect('sources' in suggestion, `既存ステップの指摘にsourcesが含まれます: ${JSON.stringify(suggestion)}`).toBe(false);
  }
});
