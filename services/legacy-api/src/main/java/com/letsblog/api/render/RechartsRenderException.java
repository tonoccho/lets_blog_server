package com.letsblog.api.render;

/** {@link RechartsRenderer}がチャートのレンダリングに失敗した場合に投げる。 */
public class RechartsRenderException extends RuntimeException {
    public RechartsRenderException(String message) {
        super(message);
    }

    public RechartsRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
