package com.letsblog.publishing.service;

/**
 * レビュー対象のPRに対応する{@code article_reviews}の行が無い(提出されていない)。
 * メッセージは利用者にそのまま見せる。{@code GlobalExceptionHandler}が404へ写す(issue #1341)。
 */
public class ArticleReviewNotFoundException extends RuntimeException {

    public ArticleReviewNotFoundException(String message) {
        super(message);
    }
}
