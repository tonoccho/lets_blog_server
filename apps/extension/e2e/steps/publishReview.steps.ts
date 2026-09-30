/** Publish直前のレビューと、未対応の指摘による公開ブロックのステップ(issue #1217)。 */

import { Given, Then, When } from '../support/gherkin';
import { validateScheduledPublication } from '../../src/frontMatter';
import { REVIEW_STEPS, StepFinding } from '../../src/proofreadLogic';
import {
  computeBodyHash,
  mergeChecklistState,
  ReviewChecklistDocumentState,
  setChecklistItemStatus,
} from '../../src/reviewChecklistLogic';
import { PublishReviewOutcome, publishBlockedMessage, reviewBeforePublish } from '../../src/publishReviewLogic';

interface PublishReviewScope {
  content: string;
  snapshot?: ReviewChecklistDocumentState;
  /** レビューを実行したときに見つかる指摘の件数。 */
  newFindings: number;
  /** レビューが実行された回数(=LLMへの依頼が発生した回数)。 */
  reviewRuns: number;
  outcome?: PublishReviewOutcome;
  scheduledError?: string;
}

function scope(world: unknown): PublishReviewScope {
  const holder = world as { publishReview?: PublishReviewScope };
  holder.publishReview ??= { content: '', newFindings: 0, reviewRuns: 0 };
  return holder.publishReview;
}

function findings(count: number, prefix: string): StepFinding[] {
  return Array.from({ length: count }, (_, i) => ({
    step: REVIEW_STEPS[1],
    suggestion: { originalText: '本文', message: `${prefix}${i}`, suggestion: null, sources: [] },
    startOffset: 0,
    endOffset: 0,
  }));
}

Given('記事の本文は {string} で、まだレビューされていない', (world, content) => {
  const s = scope(world);
  s.content = content;
  s.snapshot = undefined;
});

Given('レビューを実行すると指摘が {int} 件見つかる', (world, count) => {
  scope(world).newFindings = Number(count);
});

Given(
  '記事の本文は {string} で、直近のレビューは同じ本文で、未対応 {int} 件・修正済み {int} 件・スキップ {int} 件である',
  (world, content, unresolved, fixed, skipped) => {
    const s = scope(world);
    s.content = content;
    const base = mergeChecklistState(
      undefined,
      findings(Number(unresolved) + Number(fixed) + Number(skipped), '直近の指摘'),
      computeBodyHash(content)
    );
    let items = base.items;
    items.slice(Number(unresolved), Number(unresolved) + Number(fixed)).forEach((item) => {
      items = setChecklistItemStatus(items, item.id, 'fixed');
    });
    items.slice(Number(unresolved) + Number(fixed)).forEach((item) => {
      items = setChecklistItemStatus(items, item.id, 'skipped');
    });
    s.snapshot = { ...base, items };
  }
);

Given('その後、本文が {string} に変わった', (world, content) => {
  scope(world).content = content;
});

When('Publishの直前レビューを行う', async (world) => {
  const s = scope(world);
  s.outcome = await reviewBeforePublish({
    content: s.content,
    snapshot: s.snapshot,
    runReview: async () => {
      s.reviewRuns += 1;
      return mergeChecklistState(s.snapshot, findings(s.newFindings, '新しい指摘'), computeBodyHash(s.content));
    },
  });
});

When('公開予定日時 {string} を公開前に検査する', (world, value) => {
  scope(world).scheduledError = validateScheduledPublication(value, new Date(), { requireFuture: false }).error;
});

function outcomeOf(world: unknown): PublishReviewOutcome {
  const outcome = scope(world).outcome;
  if (!outcome) throw new Error('Publishの直前レビューが行われていません');
  return outcome;
}

Then('レビューが {int} 回実行される', (world, times) => {
  const runs = scope(world).reviewRuns;
  if (runs !== Number(times)) throw new Error(`レビューの実行回数が違います: ${runs} (期待値: ${times})`);
});

Then('レビューは実行されず、LLMへのリクエストは発生しない', (world) => {
  const runs = scope(world).reviewRuns;
  if (runs !== 0) throw new Error(`レビューが再実行されました: ${runs}回`);
});

Then('投稿はブロックされる', (world) => {
  if (!outcomeOf(world).blocked) throw new Error('投稿がブロックされていません');
});

Then('投稿は続行できる', (world) => {
  const outcome = outcomeOf(world);
  if (outcome.blocked) throw new Error(`投稿がブロックされています: 未対応${outcome.unresolvedCount}件`);
});

Then('ブロックのメッセージに未対応の件数 {string} と指摘チェックリストの導線が含まれる', (world, countText) => {
  const message = publishBlockedMessage(outcomeOf(world).unresolvedCount);
  if (!message.includes(countText)) throw new Error(`件数 ${countText} がありません: ${message}`);
  if (!message.includes('指摘チェックリスト')) throw new Error(`チェックリストの導線がありません: ${message}`);
});

Then('公開予定日時の形式エラーになる', (world) => {
  if (!scope(world).scheduledError) throw new Error('形式エラーになっていません');
});
