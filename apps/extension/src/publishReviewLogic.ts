import {
  countUnresolvedItems,
  isSnapshotCurrent,
  ReviewChecklistDocumentState,
} from './reviewChecklistLogic';

/**
 * Publish直前のレビュー(issue #1217)のうち、vscode APIに依存しない判定部分。
 *
 * レビューの実行(#1215)は`runReview`として注入し、対応状態と本文スナップショット(#1216)は
 * `snapshot`として受け取る。本文が直近のレビュー以降変わっていなければ`runReview`を呼ばず
 * (=LLMへリクエストせず)、保持している結果の未対応件数で判定する。
 */

export interface PublishReviewOutcome {
  /** 未対応の指摘が1件以上あり、投稿を止めるべきか。 */
  blocked: boolean;
  unresolvedCount: number;
  /** 今回レビューを実行したか(falseは保持していた結果の再利用、または本文が空)。 */
  reviewed: boolean;
}

export interface PublishReviewInput {
  content: string;
  snapshot: ReviewChecklistDocumentState | undefined;
  runReview: () => Promise<ReviewChecklistDocumentState>;
}

export async function reviewBeforePublish(input: PublishReviewInput): Promise<PublishReviewOutcome> {
  if (!input.content.trim()) {
    return { blocked: false, unresolvedCount: 0, reviewed: false };
  }
  const reviewed = !isSnapshotCurrent(input.snapshot, input.content);
  const state = reviewed ? await input.runReview() : (input.snapshot as ReviewChecklistDocumentState);
  const unresolvedCount = countUnresolvedItems(state.items);
  return { blocked: unresolvedCount > 0, unresolvedCount, reviewed };
}

/** 投稿をブロックしたときに表示する、未対応の件数とチェックリストでの対応の導線。 */
export function publishBlockedMessage(unresolvedCount: number): string {
  return (
    `未対応の指摘が${unresolvedCount}件あるため、投稿しませんでした。` +
    '「Let\'s Blog: 指摘チェックリストを表示」で各指摘を「修正済み」または「スキップ」にしてから、もう一度投稿してください。'
  );
}
