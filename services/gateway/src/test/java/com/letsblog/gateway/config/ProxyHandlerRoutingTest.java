package com.letsblog.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ProxyHandler#resolveRoute(String)}(AntPathMatcherによる先勝ちルーティング)・
 * {@link ProxyHandler#buildTargetUri(RouteProperties.Route, ServerRequest)}
 * (クエリ文字列の連結・タイムアウトのルート別上書き)のユニットテスト(issue #642)。
 *
 * <p>{@code services/gateway/src/main/resources/application.yml}のルート表は、より広い
 * パターンにマッチするルートより先に、より特定度の高いパターンを持つルートを置くことで
 * 意図した優先順位を実現している(#560のコメント「先勝ち。特定度の高いパスを先に置くこと」
 * 参照)。このテストは、その「先勝ち」の挙動自体(特定度による自動優先付けは行わない)を
 * 固定化し、将来ProxyHandler側のロジックが変わった場合に検知できるようにする。
 *
 * <p>ルート表とダウンストリームの実{@code @RequestMapping}との整合性そのものは
 * {@link RouteControllerContractTest}が別途検証する。
 */
class ProxyHandlerRoutingTest {

    private static RouteProperties.Route route(String id, String uri, Duration responseTimeout, String... paths) {
        RouteProperties.Route route = new RouteProperties.Route();
        route.setId(id);
        route.setUri(uri);
        route.setPaths(List.of(paths));
        route.setResponseTimeout(responseTimeout);
        return route;
    }

    private static RouteProperties routeProperties(RouteProperties.Route... routes) {
        RouteProperties properties = new RouteProperties();
        properties.setDefaultResponseTimeout(Duration.ofSeconds(60));
        properties.setRoutes(List.of(routes));
        return properties;
    }

    private static ProxyHandler handler(RouteProperties routeProperties) {
        // resolveRoute/buildTargetUriはWebClientを使わないため、ダミーのWebClientで足りる。
        return new ProxyHandler(WebClient.builder().build(), routeProperties);
    }

    private static ServerRequest requestFor(String pathAndQuery) {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(pathAndQuery).build());
        return ServerRequest.create(exchange, List.of());
    }

    // ------------------------------------------------------------------
    // resolveRoute: AntPathMatcherの*/**挙動
    // ------------------------------------------------------------------

    @Test
    @DisplayName("単一セグメントワイルドカード(*)は1階層だけにマッチし、それ以上深いパスにはマッチしない")
    void singleSegmentWildcardMatchesOnlyOneLevelDeep() {
        RouteProperties.Route oneSegment = route("one-segment", "http://one-segment-svc:8080", null, "/api/foo/*");
        RouteProperties properties = routeProperties(oneSegment);
        ProxyHandler handler = handler(properties);

        assertEquals(oneSegment, handler.resolveRoute("/api/foo/bar"));
        assertNull(handler.resolveRoute("/api/foo/bar/baz"), "*は/を跨いでマッチしないはず");
        assertNull(handler.resolveRoute("/api/foo"), "*は1階層を要求するため、階層が無い場合はマッチしないはず");
    }

    @Test
    @DisplayName("複数セグメントワイルドカード(**)は0階層以上の深さにマッチする")
    void doubleWildcardMatchesAnyDepthIncludingZero() {
        RouteProperties.Route multi = route("multi", "http://multi-svc:8080", null, "/api/foo/**");
        RouteProperties properties = routeProperties(multi);
        ProxyHandler handler = handler(properties);

        assertEquals(multi, handler.resolveRoute("/api/foo/bar"));
        assertEquals(multi, handler.resolveRoute("/api/foo/bar/baz/qux"));
        // AntPathMatcherは"/**"サフィックスのパターンについて、末尾スラッシュ無しの
        // ベースパス自体(0階層)にもマッチする特別扱いをする。
        // (services/gateway/src/main/resources/application.ymlの各ルートが
        // "/api/xxx/**"という形でコントローラーのベースパス自体もカバーできている前提の
        // 根拠となる挙動のため、ここで明示的に固定化しておく。)
        assertEquals(multi, handler.resolveRoute("/api/foo"));
    }

    // ------------------------------------------------------------------
    // resolveRoute: 先勝ち(first-match-wins)の順序依存性
    // ------------------------------------------------------------------

    @Test
    @DisplayName("重複するパターンでは、パターンの特定度に関わらずルート表で先に書かれた方が勝つ")
    void firstListedRouteWinsEvenWhenLessSpecific() {
        RouteProperties.Route specific =
                route("specific", "http://specific-svc:8080", null, "/api/projects/*/special/**");
        RouteProperties.Route broad = route("broad", "http://broad-svc:8080", null, "/api/projects/**");

        // 特定度の高いルートを先に置いた場合: 意図通りspecificが勝つ。
        ProxyHandler specificFirst = handler(routeProperties(specific, broad));
        assertEquals(specific, specificFirst.resolveRoute("/api/projects/123/special/thing"));

        // 順序を入れ替えて広いルートを先に置くと、パターンとしてはより特定度の高い
        // specificが存在していても、先に評価されるbroadが勝ってしまう。
        // (これがまさにissue #642が問題にしている「順序ミスによるサイレントな誤ルーティング」
        // のクラス。ルート表の並び順は開発者の責任であり、ProxyHandler側は特定度を
        // 自動判定しないことをここで固定化する。)
        ProxyHandler broadFirst = handler(routeProperties(broad, specific));
        assertEquals(broad, broadFirst.resolveRoute("/api/projects/123/special/thing"));
    }

    // ------------------------------------------------------------------
    // resolveRoute: マッチしない場合
    // ------------------------------------------------------------------

    @Test
    @DisplayName("どのルートにもマッチしない場合はnullを返す(呼び出し側が404を返す。issue #583)")
    void returnsNullWhenNoRouteMatches() {
        RouteProperties.Route unrelated = route("unrelated", "http://unrelated-svc:8080", null, "/api/other/**");
        ProxyHandler handler = handler(routeProperties(unrelated));

        assertNull(handler.resolveRoute("/api/unmatched/thing"));
    }

    // ------------------------------------------------------------------
    // buildTargetUri: ベースURI・クエリ文字列の連結
    // ------------------------------------------------------------------

    @Test
    @DisplayName("マッチしたルートのuriを使い、クエリ文字列があれば?付きで連結する")
    void buildTargetUriUsesMatchedRouteAndAppendsQueryString() {
        RouteProperties.Route route = route("foo", "http://foo-svc:8080", null, "/api/foo/**");
        ProxyHandler handler = handler(routeProperties(route));

        ServerRequest request = requestFor("/api/foo/bar?x=1&y=2");
        String target = handler.buildTargetUri(route, request);

        assertEquals("http://foo-svc:8080/api/foo/bar?x=1&y=2", target);
    }

    @Test
    @DisplayName("クエリ文字列が無い場合は?を付与しない")
    void buildTargetUriOmitsQuestionMarkWhenNoQuery() {
        RouteProperties.Route route = route("foo", "http://foo-svc:8080", null, "/api/foo/**");
        ProxyHandler handler = handler(routeProperties(route));

        ServerRequest request = requestFor("/api/foo/bar");
        String target = handler.buildTargetUri(route, request);

        assertEquals("http://foo-svc:8080/api/foo/bar", target);
    }

    @Test
    @DisplayName("?だけ付いた空のクエリ文字列でも?を付与しない(空文字のクエリを下流へ渡さない)")
    void buildTargetUriOmitsQuestionMarkWhenQueryIsBlank() {
        RouteProperties.Route route = route("foo", "http://foo-svc:8080", null, "/api/foo/**");
        ProxyHandler handler = handler(routeProperties(route));

        // requestFor(String)はURIテンプレートとして解釈されて空クエリが落ちるため、URIを直接渡す。
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.GET, URI.create("/api/foo/bar?")).build());
        ServerRequest request = ServerRequest.create(exchange, List.of());

        assertEquals("http://foo-svc:8080/api/foo/bar", handler.buildTargetUri(route, request));
    }

    // ------------------------------------------------------------------
    // handle: ルートが無い場合(issue #583でフォールバックを廃止)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("どのルートにもマッチしない要求は、下流へ転送せずgateway自身が本文の無い404を返す")
    void respondsWithEmptyNotFoundWhenNoRouteMatches() {
        RouteProperties.Route unrelated = route("unrelated", "http://unrelated-svc:8080", null, "/api/other/**");
        AtomicBoolean forwarded = new AtomicBoolean(false);
        ExchangeFunction recordingOk = request -> {
            forwarded.set(true);
            return Mono.just(ClientResponse.create(HttpStatus.OK).build());
        };
        ProxyHandler handler = handlerWithDownstream(routeProperties(unrelated), recordingOk);

        StepVerifier.create(handler.handle(requestFor("/api/unmatched/thing")))
                .assertNext(response -> assertEquals(HttpStatus.NOT_FOUND, response.statusCode()))
                .verifyComplete();

        assertFalse(forwarded.get(), "経路が無い要求を下流へ転送してはいけない");
    }

    // ------------------------------------------------------------------
    // ルート別response-timeoutの上書き(handle()を通した統合的な検証)
    // ------------------------------------------------------------------

    private ProxyHandler handlerWithDownstream(RouteProperties routeProperties, ExchangeFunction downstream) {
        WebClient webClient = WebClient.builder().exchangeFunction(downstream).build();
        return new ProxyHandler(webClient, routeProperties);
    }

    @Test
    @DisplayName("ルート個別のresponse-timeoutが既定値より優先され、超過するとGATEWAY_TIMEOUTになる")
    void perRouteResponseTimeoutOverridesDefaultAndTriggersGatewayTimeout() {
        RouteProperties.Route slowRoute =
                route("slow", "http://slow-svc:8080", Duration.ofSeconds(2), "/api/slow/**");
        RouteProperties properties = routeProperties(slowRoute);
        properties.setDefaultResponseTimeout(Duration.ofSeconds(60));

        ExchangeFunction delayedOk = request ->
                Mono.just(ClientResponse.create(HttpStatus.OK).build()).delayElement(Duration.ofSeconds(5));
        ProxyHandler handler = handlerWithDownstream(properties, delayedOk);
        ServerRequest request = requestFor("/api/slow/task");

        StepVerifier.withVirtualTime(() -> handler.handle(request))
                .expectSubscription()
                // ルートのresponse-timeout(2秒)で打ち切られるはず。既定の60秒までは待たない。
                .thenAwait(Duration.ofSeconds(2))
                .assertNext(response -> assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.statusCode()))
                .verifyComplete();
    }

    @Test
    @DisplayName("ルートにresponse-timeoutの指定が無い場合は既定値(default-response-timeout)が使われる")
    void routeWithoutOverrideUsesDefaultResponseTimeout() {
        RouteProperties.Route routeWithoutOverride =
                route("no-override", "http://svc:8080", null, "/api/plain/**");
        RouteProperties properties = routeProperties(routeWithoutOverride);
        properties.setDefaultResponseTimeout(Duration.ofSeconds(2));

        ExchangeFunction delayedOk = request ->
                Mono.just(ClientResponse.create(HttpStatus.OK).build()).delayElement(Duration.ofSeconds(5));
        ProxyHandler handler = handlerWithDownstream(properties, delayedOk);
        ServerRequest request = requestFor("/api/plain/task");

        StepVerifier.withVirtualTime(() -> handler.handle(request))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(2))
                .assertNext(response -> assertEquals(HttpStatus.GATEWAY_TIMEOUT, response.statusCode()))
                .verifyComplete();
    }

    @Test
    @DisplayName("タイムアウト内に応答があれば通常どおりそのステータスを返す")
    void respondsNormallyWhenDownstreamRespondsWithinTimeout() {
        RouteProperties.Route fastRoute =
                route("fast", "http://fast-svc:8080", Duration.ofSeconds(2), "/api/fast/**");
        RouteProperties properties = routeProperties(fastRoute);

        ExchangeFunction immediateOk = request -> Mono.just(ClientResponse.create(HttpStatus.OK).build());
        ProxyHandler handler = handlerWithDownstream(properties, immediateOk);
        ServerRequest request = requestFor("/api/fast/task");

        StepVerifier.create(handler.handle(request))
                .assertNext(response -> {
                    assertNotNull(response);
                    assertEquals(HttpStatus.OK, response.statusCode());
                })
                .verifyComplete();
    }
}
