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
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * トラストバウンダリの不備によるなりすまし・権限昇格経路を遮断する(issue #639)。
 *
 * <p>クライアントが直接送信した{@code X-Actor-Id}/{@code X-Actor-Role}ヘッダーは、gatewayが
 * 下流サービス(legacy-api・identity-service)へ転送する前に除去されなければならない。この2サービスの
 * {@code CurrentActorService}は、JWT認証が無い場合にこれらのヘッダーをそのまま信頼するため
 * (issue本文参照)、gatewayが取り除かなければ、攻撃者は有効なAPIキー(legacy-api)や、
 * 何の認証も無いリクエスト(identity-service。SecurityConfigがpermitAllのため)に
 * {@code X-Actor-Role: admin}を付与するだけでadmin限定APIに到達できてしまう。
 *
 * <p>下流サービス自体のコード(CurrentActorService/SecurityConfig)は本Issueのスコープ外
 * (Out of Scope参照)であり変更しないため、legacy-api/identity-serviceそれぞれの回帰シナリオは、
 * 実際にヘッダーを信頼する現行ロジックを模したフェイクの下流サーバーをこのテスト内に用意し、
 * gateway経由で到達させた場合に詐称が成立しないことを検証する形で再現している。
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

        RouteProperties routeProperties = new RouteProperties();
        routeProperties.setFallbackUri("http://downstream:8080");
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
    @DisplayName("クライアントが送信したX-Actor-Id/X-Actor-Roleヘッダーは下流へ転送されない")
    void stripsClientSuppliedActorHeaders() {
        ExchangeFunction alwaysOk = request -> Mono.just(ClientResponse.create(HttpStatus.OK).build());
        ProxyHandler handler = handlerWithDownstream(alwaysOk);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Actor-Id", "999");
        headers.add("X-Actor-Role", "admin");
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer legit-jwt-token");

        StepVerifier.create(handler.handle(requestWithHeaders(headers)))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.statusCode()))
                .verifyComplete();

        ClientRequest forwarded = capturedRequest.get();
        assertNull(forwarded.headers().getFirst("X-Actor-Id"));
        assertNull(forwarded.headers().getFirst("X-Actor-Role"));
        // 正規の経路(KeycloakのJWT)はそのまま引き継がれる。
        assertEquals("Bearer legit-jwt-token", forwarded.headers().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    @DisplayName("X-Actor-Idのみ・小文字ヘッダー名で送られてきた場合も除去される")
    void stripsActorHeadersRegardlessOfCase() {
        ExchangeFunction alwaysOk = request -> Mono.just(ClientResponse.create(HttpStatus.OK).build());
        ProxyHandler handler = handlerWithDownstream(alwaysOk);

        HttpHeaders headers = new HttpHeaders();
        headers.add("x-actor-id", "1");
        headers.add("x-actor-role", "admin");

        StepVerifier.create(handler.handle(requestWithHeaders(headers)))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.statusCode()))
                .verifyComplete();

        ClientRequest forwarded = capturedRequest.get();
        assertNull(forwarded.headers().getFirst("X-Actor-Id"));
        assertNull(forwarded.headers().getFirst("X-Actor-Role"));
    }

    @Test
    @DisplayName("legacy-api回帰: 有効なAPIキー保有者がX-Actor-Role:adminを付与してもadmin限定APIへ到達できない")
    void legacyApiApiKeyHolderCannotSpoofAdminRole() {
        // legacy-apiのCurrentActorService/ApiKeyAuthFilterを模したフェイク下流。
        // (現行ロジック通り)有効なX-API-Keyだけで認証は通り、JWTが無い場合はX-Actor-Roleヘッダーを
        // そのまま信頼してadmin判定する、という「gatewayが直さない限り脆弱な」実装を再現している。
        ExchangeFunction fakeLegacyApi = request -> {
            boolean hasValidApiKey = request.headers().getFirst("X-API-Key") != null;
            boolean claimsAdminViaHeader = "admin".equals(request.headers().getFirst("X-Actor-Role"));
            HttpStatus status = (hasValidApiKey && claimsAdminViaHeader) ? HttpStatus.OK : HttpStatus.FORBIDDEN;
            return Mono.just(ClientResponse.create(status).build());
        };
        ProxyHandler handler = handlerWithDownstream(fakeLegacyApi);

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-API-Key", "lb_valid-user-key"); // 攻撃者自身が正規に保持する有効なAPIキー
        headers.add("X-Actor-Id", "999");
        headers.add("X-Actor-Role", "admin"); // 攻撃者が自己申告するなりすましロール

        Mono<ServerResponse> response = handler.handle(requestWithHeaders(headers));

        StepVerifier.create(response)
                .assertNext(r -> assertEquals(HttpStatus.FORBIDDEN, r.statusCode()))
                .verifyComplete();
    }

    @Test
    @DisplayName("identity-service回帰: 未認証のリクエストがX-Actor-Role:adminを付与してもadmin操作に到達できない")
    void identityServiceUnauthenticatedRequestCannotSpoofAdminRole() {
        // identity-serviceのSecurityConfig(permitAll)+CurrentActorServiceを模したフェイク下流。
        // 認証は一切求めず(Authorizationヘッダー無し)、JWTが無い場合はX-Actor-Roleヘッダーを
        // そのまま信頼してadmin判定する、という「gatewayが直さない限り脆弱な」実装を再現している。
        ExchangeFunction fakeIdentityService = request -> {
            boolean claimsAdminViaHeader = "admin".equals(request.headers().getFirst("X-Actor-Role"));
            HttpStatus status = claimsAdminViaHeader ? HttpStatus.OK : HttpStatus.FORBIDDEN;
            return Mono.just(ClientResponse.create(status).build());
        };
        ProxyHandler handler = handlerWithDownstream(fakeIdentityService);

        // Authorizationヘッダーを一切送らない(未認証)。X-API-Keyすら不要な経路であることの再現。
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Actor-Id", "1");
        headers.add("X-Actor-Role", "admin");

        Mono<ServerResponse> response = handler.handle(requestWithHeaders(headers));

        StepVerifier.create(response)
                .assertNext(r -> assertEquals(HttpStatus.FORBIDDEN, r.statusCode()))
                .verifyComplete();
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
}
