package com.letsblog.publishing.domain;

/**
 * 記事レビューの進行状態(issue #1339、Epic #1333)。DBへは名前の文字列で保存する。
 * #1339が使うのは{@link #SUBMITTED}だけで、他の値は後続Issue(#1341 / #1343 / #1344)が遷移させる。
 */
public enum ArticleReviewState {
    /** 提出済み。 */
    SUBMITTED,
    /** レビュー中。 */
    IN_REVIEW,
    /** 差し戻し。 */
    CHANGES_REQUESTED,
    /** 公開済み。 */
    PUBLISHED
}
