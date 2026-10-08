package com.letsblog.publishing.service;

import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * 環境ごとの独立したWordPress操作を共有Executor上で並列に走らせ、結果を投入順(=呼び出し側が決めた
 * 環境の並び)で受け取るための小さな道具(issue #1687。比較取得の並列化は#1474)。
 *
 * <p>失敗の扱いは並列化前の逐次実行と揃える。実行時例外はラップせず呼び出し元へそのまま伝える。ただし
 * 逐次なら先頭の失敗で後続が走らなかったのに対し、並列では先に全環境の完了を待ってから、投入順で
 * 最初の失敗を投げる(1環境の失敗が他環境の実行を止めない)。
 *
 * <p>ワーカースレッドにはリクエストスレッドのThreadLocalが無い。ProjectServiceClient等のリクエストスコープの
 * クライアントは認可ヘッダを{@code HttpServletRequest}プロキシ(=RequestContextHolder)から読み、
 * CurrentActorServiceはSecurityContextHolderを読み、ログ相関IDはMDCにあるため、そのままでは
 * 「No thread-bound request found」になる。投入時に呼び出しスレッドのこれら3つを捕捉し、タスクの実行中だけ
 * ワーカーへ設定して終了時に必ず元へ戻す。個々のクライアントを直すのではなく投入点で一括して引き継ぐので、
 * 今後ワーカーから呼ばれるコードが増えても同じ失敗を繰り返さない。呼び出し元は全タスクの完了を待って
 * から戻るため、リクエストが終了した後にこの属性が使われることはない。
 */
final class EnvironmentTasks {

    private EnvironmentTasks() {
    }

    static <T> CompletableFuture<T> submit(ExecutorService executor, Supplier<T> task) {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        SecurityContext securityContext = SecurityContextHolder.getContext();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(() -> {
            RequestAttributes previousRequest = RequestContextHolder.getRequestAttributes();
            SecurityContext previousSecurity = SecurityContextHolder.getContext();
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            try {
                RequestContextHolder.setRequestAttributes(requestAttributes);
                SecurityContextHolder.setContext(securityContext);
                setMdc(mdc);
                return task.get();
            } finally {
                RequestContextHolder.setRequestAttributes(previousRequest);
                SecurityContextHolder.setContext(previousSecurity);
                setMdc(previousMdc);
            }
        }, executor);
    }

    private static void setMdc(Map<String, String> contextMap) {
        if (contextMap == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(contextMap);
        }
    }

    /** 全タスクを並列に投入し、投入順の結果を返す。 */
    static <T> List<T> runAll(ExecutorService executor, List<Supplier<T>> tasks) {
        List<CompletableFuture<T>> futures = new ArrayList<>(tasks.size());
        for (Supplier<T> task : tasks) {
            futures.add(submit(executor, task));
        }
        return awaitAll(futures);
    }

    /**
     * 投入済みfutureの完了を、成否を問わず待つ。投入後に呼び出し側の処理が例外で中断するとき、例外を
     * 伝える前にワーカーを終えさせるために使う(捕捉したリクエスト属性がリクエスト終了後に使われない)。
     */
    static void awaitQuietly(java.util.Collection<? extends CompletableFuture<?>> futures) {
        for (CompletableFuture<?> future : futures) {
            try {
                future.join();
            } catch (CompletionException | java.util.concurrent.CancellationException ignored) {
                // 失敗の報告は呼び出し側が既に伝える例外に任せる
            }
        }
    }

    /** 全futureの完了を待ち、投入順の結果を返す。失敗があれば投入順で最初のものを投げる。 */
    static <T> List<T> awaitAll(List<CompletableFuture<T>> futures) {
        List<T> results = new ArrayList<>(futures.size());
        RuntimeException firstFailure = null;
        for (CompletableFuture<T> future : futures) {
            try {
                results.add(future.join());
            } catch (CompletionException e) {
                if (firstFailure == null) {
                    firstFailure = e.getCause() instanceof RuntimeException cause ? cause : e;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        return results;
    }
}
