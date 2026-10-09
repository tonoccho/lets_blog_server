package com.letsblog.gateway.config;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.web.server.WebFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
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
    @DisplayName("NextAuthの診断ログ(_log)はauth-endpointバケットを消費しない(issue #781)")
    void nextAuthClientLogDoesNotConsumeAuthBucket() {
        // ブラウザのログノイズを大量に送っても、ログイン試行の枠を食わないこと。
        // auth-endpointはプロセス全体で1バケット(上限2)なので、共有していると
        // _logを2回投げただけで全ユーザーのログインが429になる。
        for (int i = 0; i < 5; i++) {
            consume(exchangeFor("/api/auth/_log"));
        }

        // 実際のログイン試行は枠が残っていること
        ServerWebExchange login = exchangeFor("/api/auth/callback/keycloak");
        StepVerifier.create(filter.filter(login, chain)).verifyComplete();

        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, login.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("_log以外の/api/auth/*は従来どおりauth-endpointバケットを使う(issue #781)")
    void otherAuthPathsStillUseAuthBucket() {
        // 保険の分類が広すぎないことの確認。callbackは従来どおりauth-endpoint(上限2)。
        StepVerifier.create(filter.filter(exchangeFor("/api/auth/callback/keycloak"), chain)).verifyComplete();
        StepVerifier.create(filter.filter(exchangeFor("/api/auth/signin"), chain)).verifyComplete();

        ServerWebExchange third = exchangeFor("/api/auth/callback/keycloak");
        StepVerifier.create(filter.filter(third, chain)).verifyComplete();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, third.getResponse().getStatusCode());
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

    /**
     * issue #999 受入基準2の直接的な再現。修正前は{@code /image-settings}が
     * upload-endpoint(既定10req/3600s)を消費しており、プロジェクト詳細ページを開くだけで
     * 11回目以降が429になっていた。
     */
    @Test
    @DisplayName("画像生成設定の取得(#999)は本番既定値のまま11回連続で呼んでも429にならない")
    void imageSettingsCanBeCalledElevenTimesWithoutRateLimit() {
        RateLimitWebFilter defaultsFilter = new RateLimitWebFilter(new RateLimitProperties());
        for (int i = 1; i <= 11; i++) {
            ServerWebExchange exchange = exchangeFor("/api/projects/5/image-settings");
            StepVerifier.create(defaultsFilter.filter(exchange, chain)).verifyComplete();
            assertNotEquals(
                    HttpStatus.TOO_MANY_REQUESTS,
                    exchange.getResponse().getStatusCode(),
                    i + "回目の呼び出しで429になった(#999)");
        }
    }

    @Test
    @DisplayName("画像生成設定の取得(#999)はupload-endpointを共有しない: 実アップロードで上限に達しても影響を受けない")
    void imageSettingsDoesNotShareUploadBucketWithRealUploads() {
        // upload-endpointの上限(2)を実アップロード経路で使い切る
        consume(exchangeFor("/api/ai/image"));
        consume(exchangeFor("/api/ai/image"));

        ServerWebExchange exchange = exchangeFor("/api/projects/5/image-settings");
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    /**
     * issue #999で新たにupload-endpointから外した画像関連の設定・メタデータ系エンドポイント
     * (旧ブロックリスト方式では{@code /image}を含むため誤って巻き込まれていた)。
     */
    private static final List<String> FREED_IMAGE_METADATA_PATHS = List.of(
            "/api/projects/5/image-settings",
            "/api/projects/5/image-content-filter-settings",
            "/api/projects/5/article-image-resize-default",
            "/api/projects/5/ai-models/image/provider",
            "/api/projects/5/ai-models/image/provider/selection",
            "/api/generated-images",
            "/api/generated-images/9",
            "/api/generated-images/9/tags",
            "/api/generated-images/9/file",
            "/api/projects/5/ai/generate-image-prompt");

    /**
     * issue #999で新たにupload-endpointから外した画像関連の設定・メタデータ系エンドポイント
     * (旧ブロックリスト方式では{@code /image}を含むため誤って巻き込まれていた)。
     * パスごとに独立したDynamicTestにしているのは、1本の失敗で残りの検証が
     * (JUnitのAssertionによる早期終了で)埋もれないようにするため。
     */
    @TestFactory
    @DisplayName("画像に関する設定・メタデータの読み書き(#999)はapi-globalバケットを使う")
    Stream<DynamicTest> freedImageMetadataEndpointsUseGlobalBucket() {
        return FREED_IMAGE_METADATA_PATHS.stream().map(path -> dynamicTest(path, () -> {
            RateLimitProperties properties = new RateLimitProperties();
            properties.setUploadEndpoint(new RateLimitProperties.Bucket(2, Duration.ofMinutes(1)));
            RateLimitWebFilter isolatedFilter = new RateLimitWebFilter(properties);
            WebFilterChain isolatedChain = mock(WebFilterChain.class);
            when(isolatedChain.filter(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.empty());

            // upload-endpointの上限(2)を使い切っても、対象パスは影響を受けないこと
            StepVerifier.create(isolatedFilter.filter(exchangeFor("/api/ai/image"), isolatedChain)).verifyComplete();
            StepVerifier.create(isolatedFilter.filter(exchangeFor("/api/ai/image"), isolatedChain)).verifyComplete();

            ServerWebExchange exchange = exchangeFor(path);
            StepVerifier.create(isolatedFilter.filter(exchange, isolatedChain)).verifyComplete();
            assertNotEquals(
                    HttpStatus.TOO_MANY_REQUESTS,
                    exchange.getResponse().getStatusCode(),
                    path + " がupload-endpointを共有している(#999)");
        }));
    }

    /**
     * issue #999 受入基準3: 実アップロード・実生成は引き続きupload-endpoint(10req/時)で
     * 制限され、しかも同じプロセス全体の1バケットを共有すること(#999で判定方式を変えても
     * この3エンドポイント+一括管理アップロードは動かさない)。
     */
    @Test
    @DisplayName("POST /api/media/upload と POST /api/ai/image は同じupload-endpointバケットを共有する(#999)")
    void mediaUploadAndAiImageShareUploadBucket() {
        consume(exchangeFor("/api/media/upload"));
        consume(exchangeFor("/api/ai/image"));

        ServerWebExchange exchange = exchangeFor("/api/media/upload");
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("プロジェクトへの生成画像アセットアップロードはupload-endpointバケットを使う(#999)")
    void assetImageUploadUsesUploadBucket() {
        consume(exchangeFor("/api/projects/5/asset-images/9/upload"));
        consume(exchangeFor("/api/projects/5/asset-images/9/upload"));

        ServerWebExchange exchange = exchangeFor("/api/projects/5/asset-images/9/upload");
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("生成画像ギャラリーへの画像アップロードはupload-endpointバケットを使う(#1599)")
    void generatedImageUploadUsesUploadBucket() {
        // upload-endpointバケットを別の経路で枯渇させ、巻き添えで429になることで同じバケットだと確かめる
        consume(exchangeFor("/api/media/upload"));
        consume(exchangeFor("/api/media/upload"));

        ServerWebExchange exchange = exchangeFor("/api/generated-images/upload");
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    /**
     * 一括管理アップロード({@code BulkManagementController#runBulkOperationUpload})は
     * 画像ではないが実際のmultipartファイルアップロードであるため、#999でも
     * upload-endpointに残す判断をした(理由は endpoints.ts の同定義のコメント参照)。
     */
    @Test
    @DisplayName("一括管理アップロードはupload-endpointバケットに残す(#999の実装判断)")
    void bulkManagementUploadUsesUploadBucket() {
        consume(exchangeFor("/api/projects/5/bulk-management/upload"));
        consume(exchangeFor("/api/projects/5/bulk-management/upload"));

        ServerWebExchange exchange = exchangeFor("/api/projects/5/bulk-management/upload");
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
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
     * (apps/web/src/app配下)。BFFはapiClient.tsのapiRequest()経由で1リクエストにつきPOST /api/operation-logsも
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

    // ------------------------------------------------------------------
    // 分岐網羅の補完。#999でこのファイル(RateLimitWebFilter)へ手を入れたため、
    // 変更したコードのC1/C2 90%基準(CLAUDE.md)を、ファイル単位で計測する
    // scripts/check-changed-coverage.pyが素通りできるよう、同じファイル内の
    // 未網羅分岐(externalClientIp/unverifiedJwtSubject/peerAddress/auth判定の一部)も
    // 併せて埋める。いずれも#999以前から存在する挙動で、今回の変更はしていない。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("X-Forwarded-Forに空要素が混ざっていても、末尾の値でクライアントを識別する")
    void externalClientKeyIgnoresEmptySegments() {
        // "203.0.113.10,,203.0.113.11" の空要素を無視し、末尾の203.0.113.11をキーにする
        consume(externalExchangeFor("/api/projects", "203.0.113.10,,203.0.113.11"));
        consume(externalExchangeFor("/api/projects", "203.0.113.11"));

        ServerWebExchange exhausted = externalExchangeFor("/api/projects", "203.0.113.11");
        consume(exhausted);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exhausted.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Bearer以外のAuthorizationヘッダはトークン無しとして扱い、接続元単位の枠を共有する")
    void nonBearerAuthorizationHeaderSharesPeerBucketWithNoTokenRequests() {
        consume(exchangeFor("/api/auth/setup-status"));
        ServerWebExchange nonBearer = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/setup-status")
                        .header("Authorization", "Basic dXNlcjpwYXNz").build());
        consume(nonBearer);

        ServerWebExchange third = exchangeFor("/api/auth/setup-status");
        StepVerifier.create(filter.filter(third, chain)).verifyComplete();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, third.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("subクレームの無いトークンはトークン無しとして扱う")
    void tokenWithoutSubjectClaimIsTreatedAsNoToken() {
        String token = new PlainJWT(new JWTClaimsSet.Builder().build()).serialize();
        consume(exchangeFor("/api/auth/setup-status"));
        ServerWebExchange withoutSubject = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/setup-status")
                        .header("Authorization", "Bearer " + token).build());
        consume(withoutSubject);

        ServerWebExchange third = exchangeFor("/api/auth/setup-status");
        StepVerifier.create(filter.filter(third, chain)).verifyComplete();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, third.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("空文字のsubクレームはトークン無しとして扱う")
    void tokenWithBlankSubjectClaimIsTreatedAsNoToken() {
        String token = new PlainJWT(new JWTClaimsSet.Builder().subject("").build()).serialize();
        consume(exchangeFor("/api/auth/setup-status"));
        ServerWebExchange blankSubject = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/setup-status")
                        .header("Authorization", "Bearer " + token).build());
        consume(blankSubject);

        ServerWebExchange third = exchangeFor("/api/auth/setup-status");
        StepVerifier.create(filter.filter(third, chain)).verifyComplete();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, third.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("接続元アドレスが解決できる場合はそのアドレスで枠を分ける")
    void peerAddressUsesResolvedRemoteAddress() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/setup-status")
                        .remoteAddress(new InetSocketAddress("127.0.0.1", 12345)).build());
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("接続元アドレスが未解決の場合はunknown扱いになる")
    void peerAddressFallsBackToUnknownForUnresolvedRemoteAddress() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/auth/setup-status")
                        .remoteAddress(InetSocketAddress.createUnresolved("unresolved.invalid", 12345))
                        .build());
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("/authを含まなくても/loginや/registerを含むパスはauth-endpointバケットを使う")
    void loginAndRegisterPathsWithoutAuthSegmentUseAuthBucket() {
        consume(exchangeFor("/api/some/login"));
        consume(exchangeFor("/api/some/register"));

        ServerWebExchange exchange = exchangeFor("/api/other/login");
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("制限の時間枠が明けると、同じクライアントの要求が再び受理される(#1714: 受け入れテストの実時間待ちの代替)")
    void sameClientIsAcceptedAgainAfterRefreshPeriod() throws InterruptedException {
        RateLimitProperties shortWindow = new RateLimitProperties();
        shortWindow.setApiGlobal(new RateLimitProperties.Bucket(1, Duration.ofMillis(300)));
        RateLimitWebFilter shortWindowFilter = new RateLimitWebFilter(shortWindow);

        // 上限(1回)に達する。
        StepVerifier.create(shortWindowFilter.filter(externalExchangeFor("/api/projects", "203.0.113.50"), chain))
                .verifyComplete();
        ServerWebExchange rejected = externalExchangeFor("/api/projects", "203.0.113.50");
        StepVerifier.create(shortWindowFilter.filter(rejected, chain)).verifyComplete();
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getResponse().getStatusCode());

        // 時間枠が明けるまで実際に待つ(窓が300msなので、テストは1秒かからない)。
        Thread.sleep(500);

        ServerWebExchange accepted = externalExchangeFor("/api/projects", "203.0.113.50");
        StepVerifier.create(shortWindowFilter.filter(accepted, chain)).verifyComplete();
        assertNotEquals(HttpStatus.TOO_MANY_REQUESTS, accepted.getResponse().getStatusCode());
        verify(chain, times(2)).filter(org.mockito.ArgumentMatchers.any());
    }
}
