package com.letsblog.publishing.cms.agent;

/**
 * WordPress自動プロビジョニング用エージェント(wordpress/provision-agent)経由のwp-cli操作が
 * 失敗したことを表す例外。
 */
public class AgentOperationException extends RuntimeException {
    public AgentOperationException(String message) {
        super(message);
    }

    public AgentOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
