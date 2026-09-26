package com.letsblog.media.ai;

/**
 * ComfyUIへ接続できない(名前解決不可・接続拒否・タイムアウト)ことを表す(issue #1126、#1405)。
 * 到達したうえでのHTTPエラーとは別の型にし、{@link AiServiceException}(502)のままHTTP応答は変えずに、
 * 画像生成ジョブが失敗理由を文言ではなく型で区別できるようにする。
 */
public class ComfyUiUnreachableException extends AiServiceException {
    public ComfyUiUnreachableException(String message, Throwable cause) {
        super(message, cause);
    }
}
