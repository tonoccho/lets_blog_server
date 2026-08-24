package com.letsblog.api.render;

/**
 * {@link MediaRenderClient}経由でのRechartsチャートレンダリングに失敗した場合に投げる。
 * 実際のレンダリング処理自体はmedia-service({@code RechartsRenderer}、issue #573)へ移設済み。
 */
public class RechartsRenderException extends RuntimeException {
    public RechartsRenderException(String message) {
        super(message);
    }

    public RechartsRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}
