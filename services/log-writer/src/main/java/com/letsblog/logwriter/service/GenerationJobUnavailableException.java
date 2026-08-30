package com.letsblog.logwriter.service;

/**
 * ai-serviceの{@code GET /api/generation-jobs}への同期呼び出しが、ネットワークエラー・
 * タイムアウト・想定外のレスポンスで失敗したことを表す(issue #825)。
 *
 * <p>{@link IdentityServiceUnavailableException}と分けている理由: 統合操作ログ
 * ({@link UnifiedOperationLogService#list})はAIジョブの取得失敗を握って残りのソースを返すため、
 * <b>捕捉する範囲をこのクライアント固有の失敗だけに狭めたい</b>。汎用的な例外型を捕まえると、
 * 将来同じメソッドに別の呼び出しが増えたときにその失敗まで巻き込んで握り潰す。
 *
 * <p>なお現時点で{@code IdentityServiceUnavailableException}がこの経路を飛ぶことはない。
 * 投げるのは{@code CurrentActorService}だけで、それは{@code OperationLogController}が
 * 操作者を解決する時点、つまり{@code list()}に入る前に評価される。
 * 型を分ける判断はその事実に依存していない(将来の巻き込みを防ぐのが目的)。
 *
 * <p>{@code GlobalExceptionHandler}に対応するハンドラは置いていない。この例外は
 * {@code UnifiedOperationLogService}が必ず捕捉するため、HTTPレスポンスまで到達しない。
 */
public class GenerationJobUnavailableException extends RuntimeException {
    public GenerationJobUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
