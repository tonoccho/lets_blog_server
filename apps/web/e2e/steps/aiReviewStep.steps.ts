import type { APIRequestContext } from '@playwright/test';
import { Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * レビューステップ単位の指摘生成(`POST /api/projects/{projectId}/ai/review-steps/{stepKey}/
 * suggestions`)のステップ定義(issue #1213)。
 *
 * このAPIにWeb管理画面は無く、`request` フィクスチャで gateway を直接叩く(`@api`)。
 * プロジェクトフィクスチャ(`AI設定用のプロジェクトが用意されている`)と後片付けのAfterフックは
 * `aiGeneration.steps.ts`(issue #1146 / AT-8-1)が`@ai`向けに定義済みのため、ここでは再定義せず
 * `ctx.aiGenerationProject` を読むだけにする(兄弟issueとのステップ重複を避けるため、
 * `ai.steps.ts` / `aiGeneration.steps.ts` と同様に新規ファイルへ分離する)。
 *
 * LLMは docker-compose.e2e-stubs.yml が `infra/e2e-stubs/llm` へ差し替える。`@stub` が
 * 付いているので、スタブが起動していなければ `stubs.steps.ts` の Before フックが
 * スキップではなく失敗させる。
 */

/** シナリオ限りのAI設定用プロジェクト(`aiGeneration.steps.ts`が生成・削除を管理する)。 */
interface AiProjectFixture {
  projectId: number;
}

/** レビューステップの指摘1件(`ReviewStepSuggestion`)。 */
interface ReviewStepSuggestionResult {
  id: string;
  stepKey: string;
  originalText: string;
  message: string;
}

/** `POST /api/projects/{projectId}/ai/review-steps/{stepKey}/suggestions` の応答。 */
interface ReviewStepSuggestionsResult {
  suggestions: ReviewStepSuggestionResult[];
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

function currentProjectId(ctx: Record<string, unknown>): number {
  return (ctx.aiGenerationProject as AiProjectFixture).projectId;
}

async function requestReviewStepSuggestions(
  request: APIRequestContext,
  projectId: number,
  stepKey: string,
  text: string
): Promise<ReviewStepSuggestionsResult> {
  const token = await adminToken(request);
  const response = await request.post(
    `/api/projects/${projectId}/ai/review-steps/${stepKey}/suggestions`,
    {
      headers: { Authorization: `Bearer ${token}` },
      data: { text },
    }
  );
  expect(
    response.ok(),
    `レビューステップの指摘生成の呼び出しに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as ReviewStepSuggestionsResult;
}

// AI設定用のプロジェクトフィクスチャ(`前提 AI設定用のプロジェクトが用意されている`)と
// 後片付けのAfterフックは aiGeneration.steps.ts が @ai タグ向けに定義済みのため、
// ここでは専用の Given を定義せず ctx.aiGenerationProject を読むだけにする。

When(
  /^「(JAPANESE|PROOFREADING|READER_PERSPECTIVE|STYLE)」ステップで「(.+)」という本文の指摘生成を依頼する$/,
  async ({ ctx, request }, stepKey: string, text: string) => {
    const projectId = currentProjectId(ctx);
    ctx.aiReviewStepKey = stepKey;
    ctx.aiReviewStepText = text;
    ctx.aiReviewStepResult = await requestReviewStepSuggestions(request, projectId, stepKey, text);
  }
);

When(
  /^同じ本文で「(JAPANESE|PROOFREADING|READER_PERSPECTIVE|STYLE)」ステップの指摘生成を2回依頼する$/,
  async ({ ctx, request }, stepKey: string) => {
    const projectId = currentProjectId(ctx);
    const text = 'この文章は決定性確認のために書きました。二文目もここに置きます。';
    ctx.aiReviewStepText = text;
    ctx.aiReviewStepRuns = [
      await requestReviewStepSuggestions(request, projectId, stepKey, text),
      await requestReviewStepSuggestions(request, projectId, stepKey, text),
    ];
  }
);

When(
  /^先頭に「(.+)」を挿入した本文で同じステップの指摘生成を依頼する$/,
  async ({ ctx, request }, prefix: string) => {
    const projectId = currentProjectId(ctx);
    const stepKey = ctx.aiReviewStepKey as string;
    const originalText = ctx.aiReviewStepText as string;
    const insertedText = `${prefix}${originalText}`;
    ctx.aiReviewStepResultAfterInsertion = await requestReviewStepSuggestions(
      request,
      projectId,
      stepKey,
      insertedText
    );
  }
);

Then('指摘一覧が1件以上返る', async ({ ctx }) => {
  const result = ctx.aiReviewStepResult as ReviewStepSuggestionsResult;
  expect(
    result.suggestions.length,
    `レビューステップの指摘が返っていません: ${JSON.stringify(result)}`
  ).toBeGreaterThan(0);
});

Then('返った指摘はすべて指定したステップキーを持つ', async ({ ctx }) => {
  const stepKey = ctx.aiReviewStepKey as string;
  const result = ctx.aiReviewStepResult as ReviewStepSuggestionsResult;
  for (const suggestion of result.suggestions) {
    expect(suggestion.stepKey, `指摘のstepKeyが期待と異なります: ${JSON.stringify(suggestion)}`).toBe(
      stepKey
    );
  }
});

Then('返った指摘の該当箇所はすべて本文中に存在する', async ({ ctx }) => {
  const text = ctx.aiReviewStepText as string;
  const result = ctx.aiReviewStepResult as ReviewStepSuggestionsResult;
  for (const suggestion of result.suggestions) {
    expect(
      suggestion.originalText,
      `指摘に該当箇所がありません: ${JSON.stringify(suggestion)}`
    ).toBeTruthy();
    expect(
      text,
      `指摘の該当箇所「${suggestion.originalText}」が本文中に存在しません`
    ).toContain(suggestion.originalText);
  }
});

/**
 * 決定性の確認では、一致を見る前に**中身があること**を確かめる(`ai.steps.ts`と同じ理由。
 * CLAUDE.md → Test-First Implementation「通ってしまうシナリオは基準を確かめていない」)。
 */
Then('2回の指摘生成の識別子が一致する', async ({ ctx }) => {
  const [first, second] = ctx.aiReviewStepRuns as ReviewStepSuggestionsResult[];
  expect(
    first.suggestions.length,
    `比較対象のレビューステップの指摘が空です: ${JSON.stringify(first)}`
  ).toBeGreaterThan(0);
  const firstIds = first.suggestions.map((s) => s.id).sort();
  const secondIds = second.suggestions.map((s) => s.id).sort();
  expect(secondIds, '指摘の識別子が1回目と2回目で異なります').toEqual(firstIds);
});

Then('挿入前後で指摘の識別子が一致する', async ({ ctx }) => {
  const before = ctx.aiReviewStepResult as ReviewStepSuggestionsResult;
  const after = ctx.aiReviewStepResultAfterInsertion as ReviewStepSuggestionsResult;
  expect(
    before.suggestions.length,
    `挿入前の指摘が空です: ${JSON.stringify(before)}`
  ).toBeGreaterThan(0);
  const beforeIds = before.suggestions.map((s) => s.id).sort();
  const afterIds = after.suggestions.map((s) => s.id).sort();
  expect(afterIds, '前方への挿入前後で指摘の識別子が変わりました').toEqual(beforeIds);
});
