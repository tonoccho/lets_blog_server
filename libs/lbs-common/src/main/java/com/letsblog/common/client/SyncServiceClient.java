package com.letsblog.common.client;

import com.letsblog.common.web.CorrelationIdFilter;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * サービス間同期呼び出しの共通クライアント(issue #581、C12)。
 *
 * <p>media-service→ai-service、content-service→media-service等、Phase 19の各抽出Issueが
 * 「C12が定まるまでの暫定策」として個別実装していたRestClientラッパー(タイムアウトのみ設定、
 * リトライ・サーキットブレーカー無し)を置き換える標準実装。1つのインスタンスが1つの呼び出し先
 * サービスに対応し、そのサービスへの全呼び出しでサーキットブレーカーの状態を共有する
 * (呼び出しメソッドが複数あっても、同じ下流の不調を1つのブレーカーで検知する)。
 *
 * <p>提供する機能:
 * <ul>
 *   <li>{@link SyncCallProfile}によるタイムアウト既定値(接続・読み取り)。</li>
 *   <li>冪等なGETのみを対象にしたリトライ(指数バックオフ、{@link SyncServiceClientDefaults}参照)。
 *       POST/PATCH/DELETE/PUTはリトライしない。</li>
 *   <li>下流サービスごとに共有されるサーキットブレーカー。OPEN中は実際のHTTP呼び出しを行わず
 *       即座に{@link SyncServiceCircuitOpenException}を送出する。</li>
 *   <li>下流の5xx/タイムアウト/通信断/サーキットオープンを、生のまま伝播させず
 *       {@link SyncServiceException}階層へ翻訳するエラー変換層。</li>
 * </ul>
 *
 * <p>サービス間認証トークンの付与はこのクライアント自体では行わない。呼び出し元
 * (Bearerトークンをそのまま転送する場合)または{@code ServiceAuthHeaders}
 * (B9のServiceTokenClientでクライアント資格情報トークンを付与する場合)が、各メソッドの
 * {@code Consumer<HttpHeaders>}引数でAuthorizationヘッダーを設定する。
 */
public final class SyncServiceClient {

    private final String serviceName;
    /** 実際の宛先。例外メッセージへ含め、設定ミスによる向き先違いを切り分けられるようにする(issue #827)。 */
    private final String baseUrl;
    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    private SyncServiceClient(
            String serviceName, String baseUrl, RestClient restClient, CircuitBreaker circuitBreaker, Retry retry) {
        this.serviceName = serviceName;
        this.baseUrl = baseUrl;
        this.restClient = restClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
    }

    public static Builder builder(RestClient.Builder restClientBuilder, String serviceName, String baseUrl) {
        return new Builder(restClientBuilder, serviceName, baseUrl);
    }

    // ---- GET(冪等、リトライ対象) ----

    public <T> T get(String uriTemplate, Object[] uriVars, Class<T> responseType, Consumer<HttpHeaders> headers) {
        return execute("GET " + uriTemplate, true, () -> restClient.get()
                .uri(uriTemplate, uriVars)
                .headers(applyHeaders(headers))
                .retrieve()
                .body(responseType));
    }

    public <T> T get(
            String uriTemplate, Object[] uriVars, ParameterizedTypeReference<T> responseType,
            Consumer<HttpHeaders> headers) {
        return execute("GET " + uriTemplate, true, () -> restClient.get()
                .uri(uriTemplate, uriVars)
                .headers(applyHeaders(headers))
                .retrieve()
                .body(responseType));
    }

    // ---- POST/PATCH/PUT/DELETE(非冪等、リトライしない) ----

    public <T> T post(
            String uriTemplate, Object[] uriVars, Object body, Class<T> responseType, Consumer<HttpHeaders> headers) {
        return execute("POST " + uriTemplate, false, () -> restClient.post()
                .uri(uriTemplate, uriVars)
                .headers(applyHeaders(headers))
                .body(body)
                .retrieve()
                .body(responseType));
    }

    /** multipartボディでのPOST(例: メディアアップロードのブリッジ)。 */
    public <T> T postMultipart(
            String uriTemplate, Object[] uriVars, Object multipartBody, Class<T> responseType,
            Consumer<HttpHeaders> headers) {
        return execute("POST " + uriTemplate, false, () -> restClient.post()
                .uri(uriTemplate, uriVars)
                .headers(applyHeaders(headers))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(multipartBody)
                .retrieve()
                .body(responseType));
    }

    public void postNoBody(String uriTemplate, Object[] uriVars, Object body, Consumer<HttpHeaders> headers) {
        execute("POST " + uriTemplate, false, () -> {
            restClient.post()
                    .uri(uriTemplate, uriVars)
                    .headers(applyHeaders(headers))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }

    public void patch(String uriTemplate, Object[] uriVars, Object body, Consumer<HttpHeaders> headers) {
        execute("PATCH " + uriTemplate, false, () -> {
            restClient.patch()
                    .uri(uriTemplate, uriVars)
                    .headers(applyHeaders(headers))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }

    public void put(String uriTemplate, Object[] uriVars, Object body, Consumer<HttpHeaders> headers) {
        execute("PUT " + uriTemplate, false, () -> {
            restClient.put()
                    .uri(uriTemplate, uriVars)
                    .headers(applyHeaders(headers))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }

    public void delete(String uriTemplate, Object[] uriVars, Consumer<HttpHeaders> headers) {
        execute("DELETE " + uriTemplate, false, () -> {
            restClient.delete()
                    .uri(uriTemplate, uriVars)
                    .headers(applyHeaders(headers))
                    .retrieve()
                    .toBodilessEntity();
            return null;
        });
    }

    /**
     * 呼び出し元スレッドのMDCにある相関ID(issue #582)を、呼び出し元が指定したヘッダーより先に
     * 設定する。同期呼び出し先サービスのログにも同じ相関IDが現れるようにするための横断的処理で、
     * 各呼び出しメソッドの{@code Consumer<HttpHeaders>}引数を変更する必要は無い。
     */
    private Consumer<HttpHeaders> applyHeaders(Consumer<HttpHeaders> headers) {
        return h -> {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null && !correlationId.isBlank()) {
                h.set(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId);
            }
            if (headers != null) {
                headers.accept(h);
            }
        };
    }

    /** 呼び出し先サービス名(サーキットブレーカー名。ログ・監視での識別に使う)。 */
    public String serviceName() {
        return serviceName;
    }

    /** 実際に呼び出すベースURL(診断用。例外メッセージにも含まれる。issue #827)。 */
    public String baseUrl() {
        return baseUrl;
    }

    /** 現在のサーキットブレーカー状態(監視・診断用)。 */
    public CircuitBreaker.State circuitBreakerState() {
        return circuitBreaker.getState();
    }

    /**
     * サーキットブレーカー・リトライは、下流呼び出しが送出する生の例外(RestClientResponseException等)
     * ではなく、{@link #translate}で翻訳した後の{@link SyncServiceException}階層を見て判断する必要が
     * ある({@link SyncServiceClientDefaults#circuitBreakerConfig()}の{@code recordExceptions}・
     * {@link SyncServiceClientDefaults#retryConfig()}の{@code retryExceptions}は翻訳後の型を指定して
     * いる)。そのため、翻訳(catch節)は呼び出し自体を包む内側のSupplierで行い、サーキットブレーカー→
     * (該当すれば)リトライの順で外側から重ねる(リトライの各試行がサーキットブレーカーの状態を
     * 都度確認する一般的な合成順)。
     */
    private <T> T execute(String operation, boolean retryable, Supplier<T> call) {
        Supplier<T> translated = translate(operation, call);
        Supplier<T> decorated = CircuitBreaker.decorateSupplier(circuitBreaker, translated);
        if (retryable) {
            decorated = Retry.decorateSupplier(retry, decorated);
        }
        try {
            return decorated.get();
        } catch (CallNotPermittedException e) {
            throw new SyncServiceCircuitOpenException(serviceName, baseUrl, operation);
        }
    }

    private <T> Supplier<T> translate(String operation, Supplier<T> call) {
        return () -> {
            try {
                return call.get();
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().is4xxClientError()) {
                    throw new SyncServiceClientErrorException(
                            serviceName, baseUrl, operation, e.getStatusCode().value(), bodyOrMessage(e), e);
                }
                throw new SyncServiceServerErrorException(
                        serviceName, baseUrl, operation, e.getStatusCode().value(), bodyOrMessage(e), e);
            } catch (ResourceAccessException e) {
                if (isTimeout(e)) {
                    throw new SyncServiceTimeoutException(serviceName, baseUrl, operation, e);
                }
                throw new SyncServiceUnavailableException(serviceName, baseUrl, operation, e);
            } catch (SyncServiceException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new SyncServiceUnavailableException(serviceName, baseUrl, operation, e);
            }
        };
    }

    private static boolean isTimeout(ResourceAccessException e) {
        Throwable cause = e.getCause();
        return cause instanceof java.net.SocketTimeoutException
                || cause instanceof java.net.http.HttpTimeoutException
                || (cause != null && cause.getClass().getSimpleName().contains("Timeout"));
    }

    private static String bodyOrMessage(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return (body != null && !body.isBlank()) ? body : e.getMessage();
    }

    public static final class Builder {

        private final RestClient.Builder restClientBuilder;
        private final String serviceName;
        private final String baseUrl;
        private SyncCallProfile profile = SyncCallProfile.STANDARD;
        private CircuitBreakerConfig circuitBreakerConfig = SyncServiceClientDefaults.circuitBreakerConfig();
        private RetryConfig retryConfig = SyncServiceClientDefaults.retryConfig();
        private CircuitBreakerRegistry circuitBreakerRegistry = SharedRegistries.CIRCUIT_BREAKERS;
        private RetryRegistry retryRegistry = SharedRegistries.RETRIES;

        private Builder(RestClient.Builder restClientBuilder, String serviceName, String baseUrl) {
            this.restClientBuilder = restClientBuilder;
            this.serviceName = serviceName;
            this.baseUrl = baseUrl;
        }

        public Builder profile(SyncCallProfile profile) {
            this.profile = profile;
            return this;
        }

        public Builder circuitBreakerConfig(CircuitBreakerConfig config) {
            this.circuitBreakerConfig = config;
            return this;
        }

        public Builder retryConfig(RetryConfig config) {
            this.retryConfig = config;
            return this;
        }

        /**
         * テスト用: 共有(JVM全体で{@code serviceName}ごとに1つ)のレジストリではなく、
         * 呼び出し元が渡したレジストリを使う。本番コードから呼ぶ必要は無い
         * (サーキットブレーカーは同じ下流サービスを呼ぶ全クライアントで共有すべきため)。
         */
        public Builder circuitBreakerRegistry(CircuitBreakerRegistry registry) {
            this.circuitBreakerRegistry = registry;
            return this;
        }

        public Builder retryRegistry(RetryRegistry registry) {
            this.retryRegistry = registry;
            return this;
        }

        public SyncServiceClient build() {
            HttpClient httpClient = HttpClient.newBuilder().connectTimeout(profile.connectTimeout()).build();
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(profile.readTimeout());
            RestClient restClient =
                    restClientBuilder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(serviceName, circuitBreakerConfig);
            Retry retry = retryRegistry.retry(serviceName + "-retry", retryConfig);
            return new SyncServiceClient(serviceName, baseUrl, restClient, circuitBreaker, retry);
        }
    }

    /** JVM全体で共有する既定レジストリ(下流サービス名をキーに、同名なら同じブレーカー/リトライを再利用する)。 */
    private static final class SharedRegistries {
        static final CircuitBreakerRegistry CIRCUIT_BREAKERS = CircuitBreakerRegistry.ofDefaults();
        static final RetryRegistry RETRIES = RetryRegistry.ofDefaults();
    }
}
