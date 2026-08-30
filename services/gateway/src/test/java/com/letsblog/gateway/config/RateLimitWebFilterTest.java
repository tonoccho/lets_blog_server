package com.letsblog.gateway.config;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
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
 *
 * <p>#749で追加した「api-globalのクライアント単位分割・内部(BFF)トラフィックの別バケット化」の
 * 検証もここで行う。X-Forwarded-Forを付けないリクエストは内部扱い(api-internalバケット)に
 * なるため、既存のシナリオはapi-internalの上限を消費する点に注意。
 */
class RateLimitWebFilterTest {

    private RateLimitWebFilter filter;
    private WebFilterChain chain;

    @BeforeEach
    void setUp() {
        RateLimitProperties properties = new RateLimitProperties();
        // テストを高速化するため、期間を短くしておく(既定値のままだと枯渇に1分かかる)。
        properties.setApiGlobal(new RateLimitProperties.Bucket(2, Duration.ofMinutes(1)));
        properties.setApiInternal(new RateLimitProperties.Bucket(2, Duration.ofMinutes(1)));
        properties.setAuthEndpoint(new RateLimitProperties.Bucket(2, Duration.ofMinutes(1)));
        properties.setOperationLogEndpoint(new RateLimitProperties.Bucket(2, Duration.ofMinutes(1)));
        properties.setUploadEndpoint(new RateLimitProperties.Bucket(2, Duration.ofMinutes(1)));

        filter = new RateLimitWebFilter(properties);
        chain = mock(WebFilterChain.class);
        when(chain.filter(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());
    }

    /** X-Forwarded-Forもトークンも無い = lbs-net内部からの直接呼び出し(BFF相当)。 */
    private ServerWebExchange exchangeFor(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    /** reverse-proxy(nginx)経由の外部リクエスト。nginxは観測したpeerアドレスを末尾に追記する。 */
    private ServerWebExchange externalExchangeFor(String path, String... forwardedFor) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get(path).header("X-Forwarded-For", forwardedFor).build());
    }

    /** BFFからの呼び出し(内部)で、ログインユーザーのアクセストークンが載っているもの。 */
    private ServerWebExchange internalExchangeFor(String path, String subject) {
        String token = new PlainJWT(new JWTClaimsSet.Builder().subject(subject).build()).serialize();
        return MockServerWebExchange.from(
                MockServerHttpRequest.get(path).header("Authorization", "Bearer " + token).build());
    }

    private void consume(ServerWebExchange exchange) {
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();
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

    @Test
    @DisplayName("api-globalは外部クライアント(X-Forwarded-For末尾のIP)ごとに枠が分かれる")
    void apiGlobalIsPartitionedPerExternalClient() {
        // クライアントAがapi-globalの上限(2)まで消費する
        consume(externalExchangeFor("/api/projects", "203.0.113.10"));
        consume(externalExchangeFor("/api/projects", "203.0.113.10"));

        ServerWebExchange exhausted = externalExchangeFor("/api/projects", "203.0.113.10");
        consume(exhausted);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exhausted.getResponse().getStatusCode());

        // 別IPのクライアントBは影響を受けない
        ServerWebExchange other = externalExchangeFor("/api/projects", "203.0.113.20");
        consume(other);
        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, other.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("X-Forwarded-Forは末尾の値で分割する(先頭のクライアント申告値は詐称できるため使わない)")
    void externalClientKeyUsesLastForwardedForEntry() {
        // 先頭の値だけが異なる2つのリクエストは、同じクライアント(末尾=10.0.0.1)として同じ枠を使う
        consume(externalExchangeFor("/api/projects", "1.1.1.1, 10.0.0.1"));
        consume(externalExchangeFor("/api/projects", "2.2.2.2, 10.0.0.1"));

        ServerWebExchange exchange = externalExchangeFor("/api/projects", "3.3.3.3, 10.0.0.1");
        consume(exchange);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("内部(BFF)トラフィックはapi-internalバケットを使い、外部の枯渇の影響を受けない")
    void internalTrafficUsesSeparateBucketFromExternalClients() {
        // 外部クライアントのapi-global枠を枯渇させる
        consume(externalExchangeFor("/api/projects", "203.0.113.10"));
        consume(externalExchangeFor("/api/projects", "203.0.113.10"));
        ServerWebExchange exhausted = externalExchangeFor("/api/projects", "203.0.113.10");
        consume(exhausted);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exhausted.getResponse().getStatusCode());

        ServerWebExchange internal = internalExchangeFor("/api/projects", "user-a");
        consume(internal);

        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, internal.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("内部(BFF)トラフィックはJWTのsub(ログインユーザー)ごとに枠が分かれる")
    void internalTrafficIsPartitionedPerJwtSubject() {
        consume(internalExchangeFor("/api/projects", "user-a"));
        consume(internalExchangeFor("/api/projects", "user-a"));

        ServerWebExchange exhausted = internalExchangeFor("/api/projects", "user-a");
        consume(exhausted);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exhausted.getResponse().getStatusCode());

        ServerWebExchange otherUser = internalExchangeFor("/api/projects", "user-b");
        consume(otherUser);
        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, otherUser.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("トークン無しの内部リクエストは接続元単位でまとめて数える")
    void internalTrafficWithoutTokenSharesPeerBucket() {
        consume(exchangeFor("/api/auth/setup-status"));
        consume(exchangeFor("/api/auth/setup-status"));

        ServerWebExchange exchange = exchangeFor("/api/auth/setup-status");
        consume(exchange);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("auth-endpointはクライアント単位に分割せず、従来どおりプロセス全体で1バケット")
    void authEndpointRemainsProcessWide() {
        consume(externalExchangeFor("/api/auth/login", "203.0.113.10"));
        consume(externalExchangeFor("/api/auth/login", "203.0.113.10"));

        ServerWebExchange otherClient = externalExchangeFor("/api/auth/login", "203.0.113.20");
        consume(otherClient);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, otherClient.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("upload-endpointもクライアント単位に分割しない(資源枯渇防止の総量上限のため)")
    void uploadEndpointRemainsProcessWide() {
        consume(externalExchangeFor("/api/ai/image", "203.0.113.10"));
        consume(externalExchangeFor("/api/ai/image", "203.0.113.10"));

        ServerWebExchange otherClient = externalExchangeFor("/api/ai/image", "203.0.113.20");
        consume(otherClient);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, otherClient.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("追跡するクライアントキーが上限に達したら、以降の新規クライアントは共有枠へまとめる")
    void fallsBackToSharedBucketWhenClientKeysAreExhausted() {
        // 上限(10,000キー)まで別々のクライアントで埋める
        for (int i = 0; i < 10_000; i++) {
            filter.filter(externalExchangeFor("/api/projects", "198.51.100." + i), chain).block();
        }

        // 上限到達後の新規クライアントは共有のフォールバック枠(上限2)を分け合う
        consume(externalExchangeFor("/api/projects", "192.0.2.1"));
        consume(externalExchangeFor("/api/projects", "192.0.2.2"));

        ServerWebExchange thirdNewClient = externalExchangeFor("/api/projects", "192.0.2.3");
        consume(thirdNewClient);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, thirdNewClient.getResponse().getStatusCode());
    }

    /**
     * #749の受入基準「ダッシュボード・プロジェクト詳細・投稿一覧・サイト管理を続けて開いても429にならない」を、
     * 本番既定値(api-internal 600req/分・api-global 100req/分・operation-log 300req/分)のまま検証する。
     *
     * <p>各画面のリクエスト本数は、Server Component/Server Actionのデータ取得呼び出しを実コードから数えた値
     * (web/src/app配下)。BFFはapiClient.tsのapiRequest()経由で1リクエストにつきPOST /api/operation-logsも
     * 1本送るため、それも合わせて再現する。ブラウザ直発はダッシュボードのSSE 2本のみ。
     *
     * <p>4画面1周でBFF 23本。5周=115本は、#749より前の「プロセス全体で100req/分」の上限を超えるため、
     * このテストは分割前の実装では失敗する(=回帰検知として機能する)。
     */
    @Test
    @DisplayName("既定値のまま、4画面を続けて開く操作を5周しても429にならない(#749)")
    void representativeAdminScreenTourDoesNotTriggerRateLimit() {
        RateLimitWebFilter defaultsFilter = new RateLimitWebFilter(new RateLimitProperties());
        String[] dashboard = {
            "/api/sites", "/api/posts", "/api/generation-jobs",
            "/api/dashboard/service-status", "/api/dashboard/service-status/detail",
            "/api/dashboard/container-status",
        };
        String[] projectDetail = {
            "/api/projects/1", "/api/sites", "/api/projects/1/users", "/api/users",
            "/api/projects/1/bulk-management/categories/comparison", "/api/users/1",
            "/api/projects/1/api-keys/github-token", "/api/projects/1/api-keys/brave-search-api-key",
            "/api/projects/1/ai-models/llm/models", "/api/projects/1/ai-models/llm/provider",
        };
        String[] postList = {"/api/posts", "/api/users/1"};
        String[] siteManagement = {
            "/api/sites", "/api/projects", "/api/users", "/api/ssh-key-pairs", "/api/users/1",
        };
        String[] browserOriginated = {
            "/api/dashboard/service-status/stream", "/api/dashboard/container-status/stream",
        };

        for (int round = 0; round < 5; round++) {
            for (String[] screen : new String[][] {dashboard, projectDetail, postList, siteManagement}) {
                for (String path : screen) {
                    assertNotRateLimited(defaultsFilter, internalExchangeFor(path, "user-a"));
                    // apiRequest()が呼び出しごとに送る操作ログ(operation-log-endpointバケット)
                    assertNotRateLimited(defaultsFilter, internalExchangeFor("/api/operation-logs", "user-a"));
                }
            }
            for (String path : browserOriginated) {
                assertNotRateLimited(defaultsFilter, externalExchangeFor(path, "203.0.113.10"));
            }
        }
    }

    private void assertNotRateLimited(RateLimitWebFilter target, ServerWebExchange exchange) {
        StepVerifier.create(target.filter(exchange, chain)).verifyComplete();
        assertNotEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                exchange.getResponse().getStatusCode(),
                "429 になった: " + exchange.getRequest().getPath().value());
    }
}
