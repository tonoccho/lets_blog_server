package com.letsblog.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.WebFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 旧legacy-apiのRateLimitInterceptorTestが検証していたバケット分類ロジックの移設先(#560)。
 * アップロード系エンドポイントの動的な上限変更(旧AppSettingService経由)は、gatewayが
 * DBを持たない設計のため対象外(静的デフォルト値のみ)。
 */
class RateLimitWebFilterTest {

    private RateLimitWebFilter filter;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        RateLimitProperties properties = new RateLimitProperties();
        // テストを高速化するため、期間を短くしておく(既定値のままだと枯渇に1分かかる)。
        properties.setApiGlobal(new RateLimitProperties.Bucket(2, Duration.ofMinutes(1)));
        properties.setAuthEndpoint(new RateLimitProperties.Bucket(2, Duration.ofMinutes(1)));
        properties.setOperationLogEndpoint(new RateLimitProperties.Bucket(2, Duration.ofMinutes(1)));
        properties.setUploadEndpoint(new RateLimitProperties.Bucket(2, Duration.ofMinutes(1)));

        filter = new RateLimitWebFilter(properties);
        chain = mock(WebFilterChain.class);
        when(chain.filter(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
    }

    private ServerWebExchange exchangeFor(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    @Test
    @DisplayName("通常のエンドポイントはapi-globalバケットで許可される")
    void allowsOtherEndpointsUnderApiGlobal() {
        ServerWebExchange exchange = exchangeFor("/api/projects");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, times(1)).filter(exchange);
    }

    @Test
    @DisplayName("同一バケットの上限を超えると429を返す")
    void rejectsWhenBucketExhausted() {
        // api-globalの上限(2)まで消費する
        StepVerifier.create(filter.filter(exchangeFor("/api/projects"), chain)).verifyComplete();
        StepVerifier.create(filter.filter(exchangeFor("/api/projects"), chain)).verifyComplete();

        ServerWebExchange exchange = exchangeFor("/api/projects");
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
        assertEquals("60", exchange.getResponse().getHeaders().getFirst("Retry-After"));
    }

    @Test
    @DisplayName("setup-status/totp statusは読み取り専用のためapi-globalバケットを使う")
    void authStatusCheckPathsUseGlobalBucket() {
        // auth-endpointバケット(上限2)を先に枯渇させる
        StepVerifier.create(filter.filter(exchangeFor("/api/auth/login"), chain)).verifyComplete();
        StepVerifier.create(filter.filter(exchangeFor("/api/auth/login"), chain)).verifyComplete();

        // setup-statusはapi-globalバケットを使うため、auth-endpointの枯渇の影響を受けない
        ServerWebExchange exchange = exchangeFor("/api/auth/setup-status");
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("画像アップロードはupload-endpointバケットを使う")
    void uploadEndpointUsesDedicatedBucket() {
        // api-globalバケットを枯渇させても、upload系には影響しない
        StepVerifier.create(filter.filter(exchangeFor("/api/projects"), chain)).verifyComplete();
        StepVerifier.create(filter.filter(exchangeFor("/api/projects"), chain)).verifyComplete();

        ServerWebExchange exchange = exchangeFor("/api/ai/image");
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("画像メタデータ系エンドポイントはapi-globalバケットを使う(uploadではない)")
    void lightweightImageMetadataUsesGlobalBucket() {
        ServerWebExchange exchange = exchangeFor("/api/projects/5/image-generation-prompt-defaults");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, times(1)).filter(exchange);
    }

    @Test
    @DisplayName("operation-logsは専用バケットを使う")
    void operationLogsUseDedicatedBucket() {
        ServerWebExchange exchange = exchangeFor("/api/operation-logs");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain, times(1)).filter(exchange);
    }
}
