package com.letsblog.plugins.diagram;

/** 契約(CONTRACTS.md §0 / §3)のエラー本文 {@code {"error":{"type","message","line"?}}} に写す失敗。 */
final class RenderException extends Exception {

    private final int status;
    private final String type;
    private final Integer line;

    RenderException(int status, String type, String message) {
        this(status, type, message, null);
    }

    RenderException(int status, String type, String message, Integer line) {
        super(message);
        this.status = status;
        this.type = type;
        this.line = line;
    }

    int status() {
        return status;
    }

    String type() {
        return type;
    }

    /** 記法エラーの行番号。分からないときは null。 */
    Integer line() {
        return line;
    }
}
