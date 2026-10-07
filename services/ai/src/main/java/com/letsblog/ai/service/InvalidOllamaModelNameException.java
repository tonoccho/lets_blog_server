package com.letsblog.ai.service;

/**
 * Ollamaのモデル名が空または不正な形式のときの例外(issue #1675)。400 Bad Requestに対応する。
 * IllegalArgumentExceptionにしないのは、GlobalExceptionHandlerがそれを409にするため
 * ({@link InvalidConnectionUrlException}と同じ理由)。
 */
public class InvalidOllamaModelNameException extends RuntimeException {
    public InvalidOllamaModelNameException(String message) {
        super(message);
    }
}
