package com.letsblog.ai.service;

/**
 * プロジェクト単位の接続先URL(issue #1503)の形式が不正なときの例外。400 Bad Requestに対応する。
 * IllegalArgumentExceptionにしないのは、GlobalExceptionHandlerがそれを409にするため
 * ({@link InvalidReviewInputException}と同じ理由)。
 */
public class InvalidConnectionUrlException extends RuntimeException {
    public InvalidConnectionUrlException(String message) {
        super(message);
    }
}
