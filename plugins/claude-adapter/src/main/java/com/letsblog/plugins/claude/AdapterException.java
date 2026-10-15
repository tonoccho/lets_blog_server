package com.letsblog.plugins.claude;

/** OpenAI 互換のエラー本文({@code {"error":{"message","type","code"}}})で返す失敗。 */
final class AdapterException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String type;
    private final String code;

    AdapterException(int status, String type, String code, String message) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
    }

    int status() {
        return status;
    }

    String type() {
        return type;
    }

    String code() {
        return code;
    }

    static AdapterException invalidRequest(String message) {
        return new AdapterException(400, "invalid_request_error", "invalid_request", message);
    }
}
