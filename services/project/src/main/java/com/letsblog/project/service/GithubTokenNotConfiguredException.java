package com.letsblog.project.service;

/**
 * プロジェクトにもユーザー設定にもGitHubトークンが無いとき(issue #1771)。
 * {@code GlobalExceptionHandler}が409のまま、応答の{@code details.reason}に{@link #REASON}を載せる。
 * 呼び出し側(ai-service)が日本語の文言を照合せずに原因を判定するための印である。
 */
public class GithubTokenNotConfiguredException extends IllegalStateException {

    /** 応答の {@code details.reason} に載せる、機械が読める原因。 */
    public static final String REASON = "GITHUB_TOKEN_NOT_CONFIGURED";

    public GithubTokenNotConfiguredException(String message) {
        super(message);
    }
}
