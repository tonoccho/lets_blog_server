/** プレビュー直前のレビュー(issue #1226)のステップ。前提の文言は publishReview.steps.ts と共有する。 */

import { Then, When } from '../support/gherkin';
import { computeBodyHash, mergeChecklistState } from '../../src/reviewChecklistLogic';
import { PreviewReviewOutcome, previewUnresolvedMessage, reviewBeforePreview } from '../../src/previewReviewLogic';
import { findings, scope } from './publishReview.steps';

When('プレビューの直前レビューを行う', async (world) => {
  const s = scope(world);
  s.previewOutcome = await reviewBeforePreview({
    content: s.content,
    snapshot: s.snapshot,
    runReview: async () => {
      s.reviewRuns += 1;
      return mergeChecklistState(s.snapshot, findings(s.newFindings, '新しい指摘'), computeBodyHash(s.content));
    },
  });
});

function outcomeOf(world: unknown): PreviewReviewOutcome {
  const outcome = scope(world).previewOutcome as PreviewReviewOutcome | undefined;
  if (!outcome) throw new Error('プレビューの直前レビューが行われていません');
  return outcome;
}

// プレビューは常に表示する設計。結果に「止める」意味の値が無いこと(=表示へ進める)を確かめる。
Then('プレビューは表示まで進む', (world) => {
  if ('blocked' in outcomeOf(world)) throw new Error('プレビューを止める結果になっています');
});

Then('プレビューのメッセージに未対応の件数 {string} が含まれる', (world, countText) => {
  const message = previewUnresolvedMessage(outcomeOf(world).unresolvedCount);
  if (!message?.includes(countText)) throw new Error(`件数 ${countText} がありません: ${message}`);
});

Then('プレビューのメッセージは出ない', (world) => {
  const message = previewUnresolvedMessage(outcomeOf(world).unresolvedCount);
  if (message !== undefined) throw new Error(`メッセージが出ています: ${message}`);
});
