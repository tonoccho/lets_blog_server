package com.letsblog.platform.service;

/**
 * legacy-apiのBackupExceptionと同じ実装(issue #694)。
 */
public class BackupException extends RuntimeException {
    public BackupException(String message) {
        super(message);
    }

    public BackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
