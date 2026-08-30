package com.letsblog.logwriter.service;

/**
 * ai-serviceの{@code GET /api/generation-jobs}への同期呼び出しが、ネットワークエラー・
 * タイムアウト・想定外のレスポンスで失敗したことを表す(issue #825)。
 *
 * <p>{@link IdentityServiceUnavailableException}と分けている理由: 統合操作ログ
 * ({@link UnifiedOperationLogService#list})はAIジョブの取得失敗を握って残りのソースを
 * 返す。そこで{@code IdentityServiceUnavailableException}を捕まえてしまうと、
 * identity-serviceの障害まで無言で握り潰すことになる。そちらは統合ログを返せない
 * (操作者を解決できない)ので502のままであるべきなので、型で区別する。
 *
 * <p>{@code GlobalExceptionHandler}に対応するハンドラは置いていない。この例外は
 * {@code UnifiedOperationLogService}が必ず捕捉するため、HTTPレスポンスまで到達しない。
 */
public class GenerationJobUnavailableException extends RuntimeException {
    public GenerationJobUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
