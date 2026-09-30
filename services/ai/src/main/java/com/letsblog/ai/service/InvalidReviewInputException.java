package com.letsblog.ai.service;

/**
 * レビュー関連API(issue #1210)の不正な入力(未知のステップキー・未知のAIプロバイダー名)を表す
 * (issue #1222)。{@link com.letsblog.ai.config.GlobalExceptionHandler}が400 Bad Requestへ変換する。
 *
 * <p>既存の{@link IllegalArgumentException}を流用しなかったのは、Spring
 * (ExceptionHandlerExceptionResolver、Spring Framework 5.3以降)が、トップレベルの例外に一致する
 * {@code @ExceptionHandler}が無い場合に原因(cause)連鎖を辿って一致するものを探すため。
 * {@code @PathVariable ReviewStepKey}のenum変換失敗(MethodArgumentTypeMismatchException)は、
 * その原因を辿ると{@code Enum.valueOf}が投げる{@link IllegalArgumentException}に行き着き、
 * 既存の{@code handleIllegalArgument}(409 CONFLICT)へ誤って一致してしまう。専用の例外型にすることで
 * この経路と衝突せず、確実に400を返せる。
 */
public class InvalidReviewInputException extends RuntimeException {

    public InvalidReviewInputException(String message) {
        super(message);
    }
}
