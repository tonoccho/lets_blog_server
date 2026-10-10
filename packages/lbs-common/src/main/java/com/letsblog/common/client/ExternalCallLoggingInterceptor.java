package com.letsblog.common.client;

import java.io.IOException;
import java.net.URI;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * 外部(第三者API・LLM・ComfyUIなど)へのHTTP呼び出しを、1呼び出し1行で記録する(issue #1734)。
 *
 * <p>行の書式は{@code external call: target=… host=… method=… path=… status=… duration_ms=… outcome=…}。
 * 内部サービス宛ての{@code sync call:}({@link SyncServiceClient})と対をなす。相関IDはMDC経由で
 * ログパターンに載る。
 *
 * <p>出すのは論理名・ホスト・メソッド・パス(クエリなし)・ステータス・所要時間・結果だけ。クエリ、
 * ヘッダ(Authorization等)、リクエスト/レスポンスの本文、URLのuserinfo、例外メッセージは出さない
 * (docs/LOGGING_AND_MONITORING.md「Why request bodies are not logged」)。レスポンスは読まず、
 * そのまま呼び出し元へ返す。
 *
 * <p>水準: 成功かつ閾値以内はINFO。例外・status 400以上・閾値超過はWARN(閾値超過は{@code slow=true}を付ける)。
 */
public class ExternalCallLoggingInterceptor implements ClientHttpRequestInterceptor {

    /** 既定の遅延閾値(ミリ秒)。これを超えた呼び出しはWARNにする。 */
    public static final long DEFAULT_SLOW_THRESHOLD_MS = 5000L;

    private static final Logger log = LoggerFactory.getLogger(ExternalCallLoggingInterceptor.class);

    private static final String FORMAT =
            "external call: target={} host={} method={} path={} status={} duration_ms={} outcome={}";
    private static final String SLOW_FORMAT = FORMAT + " slow=true";

    private final String target;
    private final long slowThresholdMs;
    private final LongSupplier nanoClock;

    public ExternalCallLoggingInterceptor(String target) {
        this(target, DEFAULT_SLOW_THRESHOLD_MS);
    }

    public ExternalCallLoggingInterceptor(String target, long slowThresholdMs) {
        this(target, slowThresholdMs, System::nanoTime);
    }

    ExternalCallLoggingInterceptor(String target, long slowThresholdMs, LongSupplier nanoClock) {
        this.target = target;
        this.slowThresholdMs = slowThresholdMs;
        this.nanoClock = nanoClock;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        long start = nanoClock.getAsLong();
        String status = "none";
        String outcome = "success";
        try {
            ClientHttpResponse response = execution.execute(request, body);
            int code = response.getStatusCode().value();
            status = Integer.toString(code);
            if (code >= 400) {
                outcome = "http_error";
            }
            return response;
        } catch (IOException | RuntimeException e) {
            outcome = e.getClass().getSimpleName();
            throw e;
        } finally {
            logCall(request, status, outcome, (nanoClock.getAsLong() - start) / 1_000_000L);
        }
    }

    private void logCall(HttpRequest request, String status, String outcome, long durationMs) {
        URI uri = request.getURI();
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        Object[] args = {target, uri.getHost(), request.getMethod().name(), path, status, durationMs, outcome};
        boolean slow = durationMs > slowThresholdMs;
        if (slow) {
            log.warn(SLOW_FORMAT, args);
        } else if (!"success".equals(outcome)) {
            log.warn(FORMAT, args);
        } else {
            log.info(FORMAT, args);
        }
    }
}
