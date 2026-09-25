package com.letsblog.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * gatewayのリバースプロキシとしての基本的なヘッダー転送を検証する。
 *
 * <p>issue #639時点では、legacy-api/identity-serviceのCurrentActorServiceがJWT認証の無い場合に
 * クライアント送信のactor詐称可能ヘッダー(旧実行者ID/実行者ロールの自己申告用ヘッダー)をそのまま
 * 信頼していたため、gatewayでこれらを強制除去するテストがここにあった。issue #566で
 * 当該ヘッダーへの信頼(ヘッダーベースのフォールバック)自体を撤去しKeycloakのJWTのみを
 * 信頼するよう全面移行したことに伴い、gateway側の特別な除去ロジックとその回帰テストは撤去した
 * (詳細はProxyHandlerのEXCLUDED_REQUEST_HEADERSのJavadoc参照)。
 */
class ProxyHandlerTest {

    private static final List<HttpMessageReader<?>> NO_MESSAGE_READERS = List.of();

    private AtomicReference<ClientRequest> capturedRequest;

    @BeforeEach
    void setUp() {
        capturedRequest = new AtomicReference<>();
    }

    private ProxyHandler handlerWithDownstream(ExchangeFunction downstream) {
        AtomicReference<ClientRequest> captor = capturedRequest;
        ExchangeFunction capturing = request -> {
            captor.set(request);
            return downstream.exchange(request);
        };
        WebClient webClient = WebClient.builder().exchangeFunction(capturing).build();

        // issue #583でフォールバックを廃止したため、テスト対象のパスを拾う包括ルートを1本置く。
        RouteProperties.Route catchAll = new RouteProperties.Route();
        catchAll.setId("test-catch-all");
        catchAll.setUri("http://downstream:8080");
        catchAll.setPaths(List.of("/**"));

        RouteProperties routeProperties = new RouteProperties();
        routeProperties.setRoutes(List.of(catchAll));
        routeProperties.setDefaultResponseTimeout(Duration.ofSeconds(5));

        return new ProxyHandler(webClient, routeProperties);
    }

    private ServerRequest requestWithHeaders(HttpHeaders headers) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/api/sites");
        headers.forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        ServerWebExchange exchange = MockServerWebExchange.from(builder.build());
        return ServerRequest.create(exchange, NO_MESSAGE_READERS);
    }

    @Test
    @DisplayName("既存の正規フロー: Authorizationヘッダー(Keycloak JWT)はそのまま下流へ引き継がれる")
    void preservesLegitimateAuthorizationHeader() {
        ExchangeFunction echoAuthPresence = request -> {
            HttpStatus status = request.headers().getFirst(HttpHeaders.AUTHORIZATION) != null
                    ? HttpStatus.OK
                    : HttpStatus.UNAUTHORIZED;
            return Mono.just(ClientResponse.create(status).build());
        };
        ProxyHandler handler = handlerWithDownstream(echoAuthPresence);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer legit-jwt-token");

        StepVerifier.create(handler.handle(requestWithHeaders(headers)))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.statusCode()))
                .verifyComplete();
    }

    @Test
    @DisplayName("接続用ヘッダー(host)以外の任意のクライアントヘッダーはそのまま下流へ転送される")
    void forwardsArbitraryHeadersWithoutSpecialCasing() {
        ExchangeFunction alwaysOk = request -> Mono.just(ClientResponse.create(HttpStatus.OK).build());
        ProxyHandler handler = handlerWithDownstream(alwaysOk);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Custom-Client-Header", "some-value");

        StepVerifier.create(handler.handle(requestWithHeaders(headers)))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.statusCode()))
                .verifyComplete();

        ClientRequest forwarded = capturedRequest.get();
        assertNotNull(forwarded.headers().getFirst("X-Custom-Client-Header"));
    }

    @Test
    @DisplayName("接続用ヘッダー(host/content-length)は下流へ転送しない。接続に応じてWebClientが張り直す")
    void dropsConnectionScopedRequestHeaders() {
        ExchangeFunction alwaysOk = request -> Mono.just(ClientResponse.create(HttpStatus.OK).build());
        ProxyHandler handler = handlerWithDownstream(alwaysOk);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.HOST, "forged-host.example");
        headers.add(HttpHeaders.CONTENT_LENGTH, "999");
        headers.add("X-Kept-Header", "kept");

        StepVerifier.create(handler.handle(requestWithHeaders(headers)))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.statusCode()))
                .verifyComplete();

        ClientRequest forwarded = capturedRequest.get();
        assertNull(forwarded.headers().getFirst(HttpHeaders.HOST),
                "クライアントが申告したHostをそのまま下流へ渡してはいけない");
        assertNull(forwarded.headers().getFirst(HttpHeaders.CONTENT_LENGTH));
        assertEquals("kept", forwarded.headers().getFirst("X-Kept-Header"));
    }

    @Test
    @DisplayName("下流の応答ヘッダーは、接続用ヘッダー(transfer-encoding等)を除いてクライアントへ引き継がれる")
    void copiesResponseHeadersExceptConnectionScopedOnes() {
        ExchangeFunction respondsWithHeaders = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("X-Downstream-Header", "kept")
                .header(HttpHeaders.TRANSFER_ENCODING, "chunked")
                .build());
        ProxyHandler handler = handlerWithDownstream(respondsWithHeaders);

        StepVerifier.create(handler.handle(requestWithHeaders(new HttpHeaders())))
                .assertNext(response -> {
                    assertEquals("kept", response.headers().getFirst("X-Downstream-Header"));
                    assertNull(response.headers().getFirst(HttpHeaders.TRANSFER_ENCODING),
                            "接続用ヘッダーは下流の応答からそのまま返してはいけない");
                })
                .verifyComplete();
    }

    private String bodyOf(org.springframework.web.reactive.function.server.ServerResponse response) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/sites").build());
        response.writeTo(exchange, new org.springframework.web.reactive.function.server.ServerResponse.Context() {
            @Override
            public List<org.springframework.http.codec.HttpMessageWriter<?>> messageWriters() {
                return org.springframework.http.codec.ServerCodecConfigurer.create().getWriters();
            }

            @Override
            public List<org.springframework.web.reactive.result.view.ViewResolver> viewResolvers() {
                return List.of();
            }
        }).block();
        return exchange.getResponse().getBodyAsString().block();
    }

    private static org.springframework.web.reactive.function.client.WebClientRequestException connectFailure() {
        return new org.springframework.web.reactive.function.client.WebClientRequestException(
                new java.net.ConnectException("Connection refused: downstream/172.20.0.16:8080"),
                org.springframework.http.HttpMethod.GET, java.net.URI.create("http://downstream:8080/api/sites"),
                new HttpHeaders());
    }

    @Test
    @DisplayName("下流へ接続できない(接続拒否等)場合、500ではなく503を返し、本文で到達不能なサービスを示す(issue #1096)")
    void returns503WhenDownstreamIsUnreachable() {
        ProxyHandler handler = handlerWithDownstream(request -> Mono.error(connectFailure()));

        StepVerifier.create(handler.handle(requestWithHeaders(new HttpHeaders())))
                .assertNext(response -> {
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode());
                    String body = bodyOf(response);
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("test-catch-all"), body);
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("503"), body);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("接続確立後・応答受信前に下流が落ちた場合も503を返す(502だと拡張が外部サービス障害と誤読する)。再送はしない(issue #1096)")
    void returns503WhenDownstreamClosesBeforeResponseAndDoesNotResend() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ProxyHandler handler = handlerWithDownstream(request -> {
            calls.incrementAndGet();
            return Mono.error(new org.springframework.web.reactive.function.client.WebClientRequestException(
                    reactor.netty.http.client.PrematureCloseException.TEST_EXCEPTION,
                    org.springframework.http.HttpMethod.POST, java.net.URI.create("http://downstream:8080/x"),
                    new HttpHeaders()));
        });

        StepVerifier.create(handler.handle(requestWithHeaders(new HttpHeaders())))
                .assertNext(response -> {
                    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode());
                    org.junit.jupiter.api.Assertions.assertTrue(bodyOf(response).contains("test-catch-all"));
                })
                .verifyComplete();
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("TimeoutExceptionは従来どおり504のまま(issue #1096 回帰)")
    void timeoutStillMapsTo504() {
        ProxyHandler handler = handlerWithDownstream(request -> Mono.error(new java.util.concurrent.TimeoutException()));

        StepVerifier.create(handler.handle(requestWithHeaders(new HttpHeaders())))
                .assertNext(response -> assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.statusCode()))
                .verifyComplete();
    }
}
