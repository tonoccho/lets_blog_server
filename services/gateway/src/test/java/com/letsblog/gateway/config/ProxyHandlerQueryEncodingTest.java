package com.letsblog.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * gatewayが下流へ転送するリクエストラインを、受け取ったまま(再エンコードせずに)
 * 組み立てることを検証する(issue #1002)。
 *
 * <p><b>何が起きていたか</b>: {@link ProxyHandler#buildTargetUri} が組み立てた
 * 「既にパーセントエンコード済みの」URI文字列を、{@code WebClient.uri(String)} へ
 * <b>文字列として</b>渡していた。WebClientの既定の{@code DefaultUriBuilderFactory}は
 * 渡された文字列をURIテンプレートとみなして符号化し直すため、クエリ値の{@code %}が
 * {@code %25}へ置き換わる。結果として
 *
 * <pre>
 *   クライアント: GET /api/content-cache?url=https%3A%2F%2Fexample.com%2F
 *   下流が受信  : GET /api/content-cache?url=https%253A%252F%252Fexample.com%252F
 *   下流が復号  : url = "https%3A%2F%2Fexample.com%2F"   ← 絶対URLとして解釈できない
 * </pre>
 *
 * となり、content-serviceの{@code ContentCacheController}が
 * 「http/https形式の絶対URLを指定してください」で拒否していた。
 *
 * <p><b>なぜ受け入れテスト(Gherkin)ではなくここで検証するのか</b>: 利用者から見た
 * ふるまい(カード情報が取得できる)は
 * {@code apps/web/e2e/features/cross-cutting/gateway-routing.feature} が押さえている。
 * ただしそちらは外部ページの取得を伴うため、「転送するリクエストラインが1バイトも
 * 変わらない」という gateway 自身の契約(空クエリ・素のクエリ・エンコード済みパスを
 * 含む)までは表現できない。契約そのものはこのユニットテストで固定する。
 */
class ProxyHandlerQueryEncodingTest {

    /** クライアントが送ってきた生のパス+クエリを、gatewayが下流へ転送する形で捕捉する。 */
    private ClientRequest forward(String rawPathAndQuery) {
        final AtomicReference<ClientRequest> captured = new AtomicReference<>();
        final ExchangeFunction capturing = clientRequest -> {
            captured.set(clientRequest);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        final WebClient webClient = WebClient.builder().exchangeFunction(capturing).build();

        final RouteProperties.Route catchAll = new RouteProperties.Route();
        catchAll.setId("test-catch-all");
        catchAll.setUri("http://downstream:8080");
        catchAll.setPaths(List.of("/**"));
        final RouteProperties routeProperties = new RouteProperties();
        routeProperties.setRoutes(List.of(catchAll));
        routeProperties.setDefaultResponseTimeout(Duration.ofSeconds(5));

        final ProxyHandler handler = new ProxyHandler(webClient, routeProperties);

        // MockServerHttpRequest.get(String)はURIテンプレートとして受け取った文字列を
        // encode()するため、%を含む生のクエリを与えるとテスト側で二重エンコードしてしまう。
        // 検証したいのは「クライアントから届いたバイト列」なので、URIを直接渡す。
        final ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, URI.create(rawPathAndQuery)).build());
        final ServerRequest request = ServerRequest.create(exchange, List.of());

        StepVerifier.create(handler.handle(request))
                .assertNext(response -> assertEquals(HttpStatus.OK, response.statusCode()))
                .verifyComplete();

        final ClientRequest forwarded = captured.get();
        assertNotNull(forwarded, "下流への転送が行われていない");
        return forwarded;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("パーセントエンコード済みのクエリ値は、そのままのバイト列で下流へ転送される(#1002)")
    void forwardsPercentEncodedQueryValueWithoutReEncoding() {
        final String rawQuery = "url=https%3A%2F%2Fexample.com%2F";

        final ClientRequest forwarded = forward("/api/content-cache?" + rawQuery);

        assertEquals(rawQuery, forwarded.url().getRawQuery(),
                "クエリ文字列が再エンコードされている(%が%25になっていないか)");
        assertEquals("https://example.com/", decode(forwarded.url().getRawQuery().substring("url=".length())),
                "下流が@RequestParamで1回復号したときに絶対URLへ戻らない");
    }

    @Test
    @DisplayName("値の中に&や日本語(エンコード済み)を含むクエリでも、そのままのバイト列で転送される(#1002)")
    void forwardsQueryValueContainingEncodedAmpersandAndJapanese() {
        // encodeURIComponent("https://example.com/?a=1&b=2&q=日本語") と同じ結果。
        final String encodedValue =
                "https%3A%2F%2Fexample.com%2F%3Fa%3D1%26b%3D2%26q%3D%E6%97%A5%E6%9C%AC%E8%AA%9E";
        final String rawQuery = "url=" + encodedValue;

        final ClientRequest forwarded = forward("/api/content-cache?" + rawQuery);

        assertEquals(rawQuery, forwarded.url().getRawQuery(),
                "クエリ文字列が再エンコードされている(%が%25になっていないか)");
        assertEquals("https://example.com/?a=1&b=2&q=日本語", decode(encodedValue),
                "テストデータ自体が想定のURLをエンコードしたものになっていない");
        assertEquals("https://example.com/?a=1&b=2&q=日本語",
                decode(forwarded.url().getRawQuery().substring("url=".length())),
                "値の中の&や日本語が壊れている");
    }

    @Test
    @DisplayName("エンコードを要さない素のクエリパラメータは、これまでどおりそのまま転送される(退行防止)")
    void forwardsPlainQueryParametersUnchanged() {
        final String rawQuery = "page=1&size=20&sort=createdAt,desc";

        final ClientRequest forwarded = forward("/api/posts?" + rawQuery);

        assertEquals("/api/posts", forwarded.url().getRawPath());
        assertEquals(rawQuery, forwarded.url().getRawQuery());
    }

    @Test
    @DisplayName("クエリの無いリクエストは?を付けずに転送される(退行防止)")
    void forwardsRequestWithoutQueryUnchanged() {
        final ClientRequest forwarded = forward("/api/sites");

        assertEquals("http://downstream:8080/api/sites", forwarded.url().toString());
        assertEquals(null, forwarded.url().getRawQuery());
    }

    @Test
    @DisplayName("パスセグメント中のパーセントエンコードも再エンコードされない(退行防止。#1002と同じ経路)")
    void forwardsPercentEncodedPathSegmentWithoutReEncoding() {
        final ClientRequest forwarded = forward("/api/posts/caf%C3%A9%20latte");

        assertEquals("/api/posts/caf%C3%A9%20latte", forwarded.url().getRawPath(),
                "パスが再エンコードされている(%が%25になっていないか)");
    }
}
