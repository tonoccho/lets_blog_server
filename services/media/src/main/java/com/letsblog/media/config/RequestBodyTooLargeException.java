package com.letsblog.media.config;

import java.io.IOException;

/**
 * {@code /api/render/**} のリクエスト本文が上限を超えたことを示す(issue #1135)。
 *
 * <p>チャンク転送でContent-Lengthが無いとき、{@link RenderBodySizeLimitFilter}が読み取り量を数えて
 * ストリーム読み取り中に投げる。Jacksonの読み取りを経由すると{@code HttpMessageNotReadableException}に
 * 包まれるが、{@code @ExceptionHandler}は原因の連鎖も辿るので、{@code GlobalExceptionHandler}が413へ写す。
 */
public class RequestBodyTooLargeException extends IOException {

    private final long maxBytes;

    public RequestBodyTooLargeException(long maxBytes) {
        super("リクエスト本文が上限(" + maxBytes + "バイト)を超えています。");
        this.maxBytes = maxBytes;
    }

    public long getMaxBytes() {
        return maxBytes;
    }
}
