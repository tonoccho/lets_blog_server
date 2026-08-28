package com.letsblog.publishing.domain;

public enum BulkOperationStatus {
    SUCCESS,
    SKIPPED,
    FAILED;

    public BulkOperationLogLevel toLogLevel() {
        return switch (this) {
            case SUCCESS -> BulkOperationLogLevel.INFO;
            case SKIPPED -> BulkOperationLogLevel.WARNING;
            case FAILED -> BulkOperationLogLevel.ERROR;
        };
    }
}
