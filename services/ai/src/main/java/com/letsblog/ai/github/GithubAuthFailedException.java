package com.letsblog.ai.github;

/**
 * GitHubが401を返したとき(トークンが無効、期限切れ、またはスコープ不足)の失敗(issue #1771)。
 * {@code GlobalExceptionHandler}が502のまま、応答の{@code details.reason}に{@link #REASON}を載せる。
 */
public class GithubAuthFailedException extends GithubApiException {

    /** 応答の {@code details.reason} に載せる、機械が読める原因。 */
    public static final String REASON = "GITHUB_AUTH_FAILED";

    public GithubAuthFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
