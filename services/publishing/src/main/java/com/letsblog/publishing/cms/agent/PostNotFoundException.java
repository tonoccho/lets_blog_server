package com.letsblog.publishing.cms.agent;

/**
 * provision-agent(infra/wordpress/provision-agent)経由で削除しようとした投稿/メディアが
 * WordPress側にそもそも存在しなかったことを表す(issue #1070)。
 *
 * <p>疎通・実行そのものの失敗を表す{@link AgentOperationException}(502)とは意図的に区別する。
 * 対象が存在しないのは呼び出し側の入力ミスであり、インフラ障害ではないため、
 * {@code SiteNotFoundException}/{@code ProjectNotFoundException}と同じく404として扱う。
 */
public class PostNotFoundException extends RuntimeException {
    public PostNotFoundException(String message) {
        super(message);
    }
}
