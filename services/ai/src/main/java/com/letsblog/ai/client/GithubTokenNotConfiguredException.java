package com.letsblog.ai.client;

/**
 * project-serviceのgithub-accessが「GitHubトークン未設定」の409を返したときの失敗(issue #1771)。
 * 判定はproject-serviceが応答に載せた{@code details.reason}で行い、日本語の文言は照合しない。
 * メッセージは従来どおりproject-serviceの応答本文のままにする。
 */
public class GithubTokenNotConfiguredException extends IllegalStateException {

    /** project-serviceの応答と、このサービスの応答の {@code details.reason} に載る原因。 */
    public static final String REASON = "GITHUB_TOKEN_NOT_CONFIGURED";

    public GithubTokenNotConfiguredException(String message, Throwable cause) {
        super(message, cause);
    }
}
