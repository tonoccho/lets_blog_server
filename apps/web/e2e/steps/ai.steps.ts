import type { APIRequestContext } from '@playwright/test';
import { Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * AI執筆支援のうち、タグ提案と校正チェックのステップ定義(issue #1004)。
 *
 * 両APIにWeb管理画面は無く、VSCode拡張(`apps/extension/src/apiClient.ts` の
 * `suggestTags` / `proofreadContent`)だけが呼ぶ。したがってページ操作ではなく
 * `request` フィクスチャで gateway を直接叩く(`@api`)。
 *
 * LLMは docker-compose.e2e-stubs.yml が `infra/e2e-stubs/llm` へ差し替える。
 * `@stub` が付いているので、スタブが起動していなければ
 * `stubs.steps.ts` の Before フックがスキップではなく失敗させる。
 */

/** `POST /api/ai/tags` の応答(`AiTagsResponse`)。 */
interface TagsResult {
  categories: string[];
  tags: string[];
}

/** 校正チェックで検出した1件の指摘(`ProofreadIssue`)。 */
interface ProofreadIssueResult {
  type: string;
  originalText: string;
  message: string;
  suggestion: string | null;
}

/** `POST /api/ai/proofread` の応答(`AiProofreadResponse`)。 */
interface ProofreadResult {
  issues: ProofreadIssueResult[];
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function requestTags(request: APIRequestContext, text: string): Promise<TagsResult> {
  const token = await adminToken(request);
  const response = await request.post('/api/ai/tags', {
    headers: { Authorization: `Bearer ${token}` },
    data: { text },
  });
  expect(
    response.ok(),
    `タグ提案の呼び出しに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as TagsResult;
}

async function requestProofread(request: APIRequestContext, text: string): Promise<ProofreadResult> {
  const token = await adminToken(request);
  const response = await request.post('/api/ai/proofread', {
    headers: { Authorization: `Bearer ${token}` },
    data: { text },
  });
  expect(
    response.ok(),
    `校正チェックの呼び出しに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as ProofreadResult;
}

When(/^「(.+)」という本文でタグ提案を依頼する$/, async ({ ctx, request }, text: string) => {
  ctx.aiText = text;
  ctx.aiTags = await requestTags(request, text);
});

When(/^「(.+)」という本文で校正チェックを依頼する$/, async ({ ctx, request }, text: string) => {
  ctx.aiText = text;
  ctx.aiProofread = await requestProofread(request, text);
});

When(
  /^「(.+)」という本文でタグ提案と校正チェックをそれぞれ2回依頼する$/,
  async ({ ctx, request }, text: string) => {
    ctx.aiText = text;
    ctx.aiTagsRuns = [await requestTags(request, text), await requestTags(request, text)];
    ctx.aiProofreadRuns = [
      await requestProofread(request, text),
      await requestProofread(request, text),
    ];
  }
);

Then('カテゴリ候補が1件以上返る', async ({ ctx }) => {
  const result = ctx.aiTags as TagsResult;
  expect(
    result.categories.length,
    `カテゴリ候補が返っていません: ${JSON.stringify(result)}`
  ).toBeGreaterThan(0);
});

Then('タグ候補が1件以上返る', async ({ ctx }) => {
  const result = ctx.aiTags as TagsResult;
  expect(result.tags.length, `タグ候補が返っていません: ${JSON.stringify(result)}`).toBeGreaterThan(0);
});

Then('校正の指摘が1件以上返る', async ({ ctx }) => {
  const result = ctx.aiProofread as ProofreadResult;
  expect(
    result.issues.length,
    `校正の指摘が返っていません: ${JSON.stringify(result)}`
  ).toBeGreaterThan(0);
});

Then('各指摘の該当箇所が本文中にそのまま存在する', async ({ ctx }) => {
  const text = ctx.aiText as string;
  const result = ctx.aiProofread as ProofreadResult;
  for (const issue of result.issues) {
    expect(issue.originalText, `指摘に該当箇所がありません: ${JSON.stringify(issue)}`).toBeTruthy();
    expect(
      text,
      `指摘の該当箇所「${issue.originalText}」が本文中に存在しません`
    ).toContain(issue.originalText);
  }
});

/**
 * 決定性の確認では、一致を見る前に**中身があること**を確かめる。
 * 空の応答どうしを比べても常に一致してしまい、決定性を何も検証しないため
 * (CLAUDE.md → Test-First Implementation「通ってしまうシナリオは基準を確かめていない」)。
 */
Then('2回のタグ提案の結果が一致する', async ({ ctx }) => {
  const [first, second] = ctx.aiTagsRuns as TagsResult[];
  expect(
    first.categories.length + first.tags.length,
    `比較対象のタグ提案が空です: ${JSON.stringify(first)}`
  ).toBeGreaterThan(0);
  expect(second, 'タグ提案の結果が1回目と2回目で異なります').toEqual(first);
});

Then('2回の校正チェックの結果が一致する', async ({ ctx }) => {
  const [first, second] = ctx.aiProofreadRuns as ProofreadResult[];
  expect(
    first.issues.length,
    `比較対象の校正の指摘が空です: ${JSON.stringify(first)}`
  ).toBeGreaterThan(0);
  expect(second, '校正チェックの結果が1回目と2回目で異なります').toEqual(first);
});
