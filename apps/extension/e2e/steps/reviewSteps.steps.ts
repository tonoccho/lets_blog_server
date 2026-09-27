/** レビュー5ステップの指摘生成のステップ(issue #1215)。 */

import { Then, When } from '../support/gherkin';
import { w } from './common.steps';
import * as apiClient from '../../src/apiClient';
import { REVIEW_STEPS } from '../../src/proofreadLogic';
import type { AiReviewStepSuggestionsResult } from '../../src/schemas';

interface StepCall {
  stepKey: string;
  projectId: number;
  result: AiReviewStepSuggestionsResult;
}

function calls(world: unknown): StepCall[] {
  return ((world as { stepCalls?: StepCall[] }).stepCalls ?? []);
}

When('5つのレビューステップを本文 {string} で順に依頼する', async (world, text) => {
  const scope = w(world);
  const recorded: StepCall[] = [];
  for (const step of REVIEW_STEPS) {
    const result = await apiClient.reviewStepSuggestions(scope.token, scope.actor, scope.project.id, step.key, text);
    recorded.push({ stepKey: step.key, projectId: scope.project.id, result });
  }
  (world as { stepCalls?: StepCall[] }).stepCalls = recorded;
});

Then('依頼したステップキーは日本語チェック・校正チェック・校閲・読者視点でのチェック・文体チェックの順である', (world) => {
  const keys = calls(world).map((c) => c.stepKey);
  const expected = ['JAPANESE', 'PROOFREADING', 'FACT_CHECK', 'READER_PERSPECTIVE', 'STYLE'];
  if (JSON.stringify(keys) !== JSON.stringify(expected)) {
    throw new Error(`ステップの順序が違います: ${JSON.stringify(keys)}`);
  }
  const labels = REVIEW_STEPS.map((s) => s.label);
  const expectedLabels = ['日本語チェック', '校正チェック', '校閲', '読者視点でのチェック', '文体チェック'];
  if (JSON.stringify(labels) !== JSON.stringify(expectedLabels)) {
    throw new Error(`ステップ名が違います: ${JSON.stringify(labels)}`);
  }
});

Then('全ステップで対象プロジェクトのIDが送られている', (world) => {
  const scope = w(world);
  for (const call of calls(world)) {
    if (call.projectId !== scope.project.id) throw new Error(`projectIdが違います: ${call.projectId}`);
  }
});

Then('全ステップから指摘一覧の応答が返る', (world) => {
  const recorded = calls(world);
  if (recorded.length !== 5) throw new Error(`応答が5件ではありません: ${recorded.length}`);
  for (const call of recorded) {
    if (!Array.isArray(call.result.suggestions)) throw new Error(`${call.stepKey}: suggestionsがありません`);
  }
});
