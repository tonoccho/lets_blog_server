import { REVIEW_STEPS, StepFinding } from '../proofreadLogic';
import { computeBodyHash, mergeChecklistState, ReviewChecklistDocumentState } from '../reviewChecklistLogic';
import { previewUnresolvedMessage, reviewBeforePreview } from '../previewReviewLogic';

/** プレビュー直前のレビュー(issue #1226)。判定はPublish(#1217)と同じ`reviewBeforePublish`を使う。 */

function finding(message: string): StepFinding {
  return {
    step: REVIEW_STEPS[1],
    suggestion: { originalText: '本文の一部', message, suggestion: null, sources: [] },
    startOffset: 0,
    endOffset: 0,
  };
}

function stateFor(content: string, messages: string[]): ReviewChecklistDocumentState {
  return mergeChecklistState(undefined, messages.map(finding), computeBodyHash(content));
}

describe('reviewBeforePreview', () => {
  it('未レビューならレビューを実行し、未対応の件数を返す(止めない)', async () => {
    const runReview = jest.fn(async () => stateFor('本文', ['a', 'b']));
    const outcome = await reviewBeforePreview({ content: '本文', snapshot: undefined, runReview });
    expect(runReview).toHaveBeenCalledTimes(1);
    expect(outcome).toEqual({ unresolvedCount: 2, reviewed: true, failed: false });
  });

  it('本文が未変更なら再実行せず、保持している結果の件数を返す', async () => {
    const runReview = jest.fn(async () => stateFor('本文', []));
    const outcome = await reviewBeforePreview({
      content: '本文',
      snapshot: stateFor('本文', ['x']),
      runReview,
    });
    expect(runReview).not.toHaveBeenCalled();
    expect(outcome).toEqual({ unresolvedCount: 1, reviewed: false, failed: false });
  });

  it('本文が変わっていれば再実行する', async () => {
    const runReview = jest.fn(async () => stateFor('加筆', []));
    const outcome = await reviewBeforePreview({ content: '加筆', snapshot: stateFor('本文', ['x']), runReview });
    expect(runReview).toHaveBeenCalledTimes(1);
    expect(outcome.unresolvedCount).toBe(0);
  });

  it('本文が空ならレビューしない', async () => {
    const runReview = jest.fn();
    const outcome = await reviewBeforePreview({ content: '  \n', snapshot: undefined, runReview });
    expect(runReview).not.toHaveBeenCalled();
    expect(outcome).toEqual({ unresolvedCount: 0, reviewed: false, failed: false });
  });

  it('レビューが失敗してもプレビューを止めないよう例外にせず、失敗として返す(扱いは#1224)', async () => {
    const outcome = await reviewBeforePreview({
      content: '本文',
      snapshot: undefined,
      runReview: async () => {
        throw new Error('boom');
      },
    });
    expect(outcome).toEqual({ unresolvedCount: 0, reviewed: false, failed: true, error: 'boom' });
  });

  it('Error以外が投げられても失敗として返す', async () => {
    const outcome = await reviewBeforePreview({
      content: '本文',
      snapshot: undefined,
      runReview: async () => {
        throw 'oops';
      },
    });
    expect(outcome).toMatchObject({ failed: true, error: 'oops' });
  });
});

describe('previewUnresolvedMessage', () => {
  it('未対応があれば件数と、プレビューは表示した旨を含める', () => {
    const message = previewUnresolvedMessage(3);
    expect(message).toContain('3件');
    expect(message).toContain('プレビュー');
    expect(message).toContain('指摘チェックリスト');
  });

  it('未対応が0件ならメッセージは無い', () => {
    expect(previewUnresolvedMessage(0)).toBeUndefined();
  });
});
