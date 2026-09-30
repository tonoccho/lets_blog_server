package com.letsblog.publishing.service;

/**
 * PRから記事を取り出せない理由(issue #1338)。メッセージは利用者にそのまま見せる。
 * {@code GlobalExceptionHandler}が{@link Kind}ごとに404/409/422へ写す。
 */
public class PullRequestArticleException extends RuntimeException {

    /** 失敗の種類。 */
    public enum Kind {
        /** 記事ディレクトリ、またはarticle.mdがPRに無い。 */
        NOT_FOUND,
        /** 記事ディレクトリが2つ以上ある(1 PR = 1 記事に反する)。 */
        MULTIPLE,
        /** スラッグやfront matterが不正。 */
        INVALID
    }

    private final Kind kind;

    public PullRequestArticleException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public PullRequestArticleException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
