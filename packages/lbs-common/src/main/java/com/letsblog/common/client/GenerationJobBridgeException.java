package com.letsblog.common.client;

/**
 * ai-serviceの{@code generation_jobs}への同期呼び出し({@link GenerationJobClient})の失敗を表す(#1483で
 * media-serviceの{@code GenerationJobBridgeException}(#573)とlog-writerの
 * {@code GenerationJobUnavailableException}(#825)を統合した)。
 *
 * <p><b>media-service</b>: 進捗更新({@code updateStatus})の失敗はベストエフォートで握りつぶすが、
 * ジョブ作成({@code create})の失敗はジョブID無しでは後続処理を進められないため、この例外として伝播させる。
 *
 * <p><b>log-writer</b>: 統合操作ログ(UnifiedOperationLogService#list)はAIジョブの取得失敗を握って
 * 残りのソースを返す(#825)。汎用的な例外型(IdentityServiceUnavailableExceptionなど)を捕まえると、
 * 将来同じメソッドに別の呼び出しが増えたときにその失敗まで巻き込んで握り潰すため、
 * <b>捕捉する範囲をこのクライアント固有の失敗だけに狭められるよう専用の型にしている</b>。
 */
public class GenerationJobBridgeException extends RuntimeException {
    public GenerationJobBridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
