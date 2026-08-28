package com.letsblog.publishing.cms.ssh;

/**
 * SSH接続・コマンド実行・ファイル転送のいずれかが失敗したことを表す例外。
 */
public class SshOperationException extends RuntimeException {
    public SshOperationException(String message) {
        super(message);
    }

    public SshOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
