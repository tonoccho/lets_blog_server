package com.letsblog.common.migration;

/** {@link SchemaMigrationJob} の実行中に発生した回復不能なエラー。 */
public class MigrationException extends RuntimeException {

    public MigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
