import { REVIEW_STEPS, StepFinding } from '../proofreadLogic';
import {
  computeBodyHash,
  mergeChecklistState,
  ReviewChecklistDocumentState,
  setChecklistItemStatus,
} from '../reviewChecklistLogic';
import { publishBlockedMessage, reviewBeforePublish } from '../publishReviewLogic';

/**
 * Publish直前のレビュー(issue #1217)の純ロジック。
 * レビュー実行はrunReviewとして注入し、実行回数(=LLMへの依頼の有無)を数える。
 */

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

function reviewer(content: string, messages: string[]): jest.Mock<Promise<ReviewChecklistDocumentState>, []> {
  return jest.fn(async () => stateFor(content, messages));
}

describe('reviewBeforePublish', () => {
  it('未レビューならレビューを実行し、未対応の指摘があればブロックして件数を返す', async () => {
    const runReview = reviewer('本文', ['a', 'b']);
    const outcome = await reviewBeforePublish({ content: '本文', snapshot: undefined, runReview });
    expect(runReview).toHaveBeenCalledTimes(1);
    expect(outcome).toEqual({ blocked: true, unresolvedCount: 2, reviewed: true });
  });

  it('指摘が0件ならブロックしない', async () => {
    const outcome = await reviewBeforePublish({
      content: '本文',
      snapshot: undefined,
      runReview: reviewer('本文', []),
    });
    expect(outcome).toEqual({ blocked: false, unresolvedCount: 0, reviewed: true });
  });

  it('本文が未変更なら再実行せず、保持している結果の未対応件数で判定する', async () => {
    const runReview = reviewer('本文', ['x']);
    const snapshot = stateFor('本文', ['a', 'b', 'c']);
    const outcome = await reviewBeforePublish({ content: '本文', snapshot, runReview });
    expect(runReview).not.toHaveBeenCalled();
    expect(outcome).toEqual({ blocked: true, unresolvedCount: 3, reviewed: false });
  });

  it('保持している結果の全件が修正済みまたはスキップなら、再実行せずブロックしない', async () => {
    const runReview = reviewer('本文', []);
    const base = stateFor('本文', ['a', 'b']);
    const [first, second] = base.items;
    const snapshot = {
      ...base,
      items: setChecklistItemStatus(setChecklistItemStatus(base.items, first.id, 'fixed'), second.id, 'skipped'),
    };
    const outcome = await reviewBeforePublish({ content: '本文', snapshot, runReview });
    expect(runReview).not.toHaveBeenCalled();
    expect(outcome).toEqual({ blocked: false, unresolvedCount: 0, reviewed: false });
  });

  it('本文が変わっていれば再実行し、新しい結果で判定する', async () => {
    const runReview = reviewer('新しい本文', ['n']);
    const snapshot = stateFor('古い本文', ['a', 'b', 'c']);
    const outcome = await reviewBeforePublish({ content: '新しい本文', snapshot, runReview });
    expect(runReview).toHaveBeenCalledTimes(1);
    expect(outcome).toEqual({ blocked: true, unresolvedCount: 1, reviewed: true });
  });

  it('本文が空ならレビューせず、ブロックしない', async () => {
    const runReview = reviewer('', ['x']);
    const outcome = await reviewBeforePublish({ content: '  \n', snapshot: undefined, runReview });
    expect(runReview).not.toHaveBeenCalled();
    expect(outcome).toEqual({ blocked: false, unresolvedCount: 0, reviewed: false });
  });
});

describe('publishBlockedMessage', () => {
  it('未対応の件数と、チェックリストで対応する導線を含む', () => {
    const message = publishBlockedMessage(3);
    expect(message).toContain('3件');
    expect(message).toContain('指摘チェックリストを表示');
  });
});
