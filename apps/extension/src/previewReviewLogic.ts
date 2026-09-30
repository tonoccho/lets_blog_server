import { PublishReviewInput, reviewBeforePublish } from './publishReviewLogic';

/**
 * プレビュー直前のレビュー(issue #1226)のうち、vscode APIに依存しない判定部分。
 *
 * 「本文が直近のレビュー以降変わっていなければ再実行しない」の判定はPublish(#1217)と必ず同じに
 * なるよう`reviewBeforePublish`をそのまま使う。Publishと違い、未対応の指摘があってもプレビューは
 * 止めない(結果に「ブロック」の概念を持たない)。レビュー実行の失敗もプレビューを止めないよう
 * 例外にせず`failed`で返す(失敗時の扱いそのものは#1224)。
 */

export interface PreviewReviewOutcome {
  unresolvedCount: number;
  /** 今回レビューを実行したか(falseは保持していた結果の再利用、本文が空、または失敗)。 */
  reviewed: boolean;
  failed: boolean;
  /** failedのときの原因。 */
  error?: string;
}

export async function reviewBeforePreview(input: PublishReviewInput): Promise<PreviewReviewOutcome> {
  try {
    const { unresolvedCount, reviewed } = await reviewBeforePublish(input);
    return { unresolvedCount, reviewed, failed: false };
  } catch (error) {
    return {
      unresolvedCount: 0,
      reviewed: false,
      failed: true,
      error: error instanceof Error ? error.message : String(error),
    };
  }
}

/** 未対応の指摘があるときにプレビューと合わせて示すメッセージ。0件なら出さない(undefined)。 */
export function previewUnresolvedMessage(unresolvedCount: number): string | undefined {
  if (unresolvedCount <= 0) return undefined;
  return (
    `未対応の指摘が${unresolvedCount}件あります。プレビューは表示しました。` +
    '「Let\'s Blog: 指摘チェックリストを表示」で各指摘を「修正済み」または「スキップ」にできます。'
  );
}
