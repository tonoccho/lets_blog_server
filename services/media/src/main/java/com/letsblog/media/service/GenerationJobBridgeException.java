package com.letsblog.media.service;

/**
 * legacy-apiの{@code /api/generation-jobs}(GenerationJob、issue #573 stage2/stage3、
 * {@link com.letsblog.media.client.GenerationJobClient}参照)呼び出しの失敗を表す。
 * 進捗更新({@code updateStatus})の失敗はベストエフォートで握りつぶすが、ジョブ作成
 * ({@code create})の失敗はジョブID無しでは後続処理を進められないため、この例外として伝播させる。
 */
public class GenerationJobBridgeException extends RuntimeException {
    public GenerationJobBridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
