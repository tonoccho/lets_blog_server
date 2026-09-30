package com.letsblog.publishing.github;

/**
 * GitHub API呼び出しの失敗(認証失敗・権限不足・レート制限・リポジトリ/PR不在・通信失敗)。
 * メッセージは利用者にそのまま見せる前提で、原因が判別できる文面にする。
 * {@code GlobalExceptionHandler}が502へ写す(汎用の500にはしない)。
 */
public class GithubApiException extends RuntimeException {

    public GithubApiException(String message) {
        super(message);
    }

    public GithubApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
