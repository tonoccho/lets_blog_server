package com.letsblog.gateway.config;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * リクエストをルーティング表({@link RouteProperties})に従って下流サービスへ転送する
 * (#560)。Spring Cloud Gatewayが使えない(build.gradleのコメント参照)ため、
 * WebClientでの手組みのリバースプロキシとして実装している。
 *
 * <p>ボディは {@code Flux<DataBuffer>} として遅延ストリーミングされる。そのためタイムアウトは
 * 「レスポンスが返り始めるまでの時間」に対して働き、SSEのような長時間ストリーミング自体は
 * ブロックしない。
 *
 * <p>{@code exchangeToMono} は使わない。そのコールバックが返す {@code Mono<ServerResponse>}
 * は{@code ServerResponse.body(...)}でボディの{@code Publisher}を包んで即座に完了するだけで、
 * 実際にボディを購読するのはこのメソッドの外側(HTTPレスポンス書き込み時)まで遅延される。
 * {@code exchangeToMono}はコールバック完了時点でボディが未消費とみなし、コネクションと
 * ボディを自動的に解放してしまうため、後段の購読時には既に空になっている
 * (実機検証で確認済み: ステータス/ヘッダーは正しく転送されるがボディが0バイトになる)。
 * {@code retrieve()}はこの自動解放を行わないため、代わりにこちらを使う。
 */
public class ProxyHandler {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** クライアントへ転送しないヘッダー(下流からの応答をそのまま返す際に除外する)。 */
    private static final Set<String> EXCLUDED_RESPONSE_HEADERS =
            Set.of("transfer-encoding", "connection", "content-length");

    /** 下流への転送時に除外するヘッダー(WebClientが接続に応じて再設定するため)。 */
    private static final Set<String> EXCLUDED_REQUEST_HEADERS =
            Set.of("host", "content-length");

    private final WebClient webClient;
    private final RouteProperties routeProperties;

    public ProxyHandler(WebClient webClient, RouteProperties routeProperties) {
        this.webClient = webClient;
        this.routeProperties = routeProperties;
    }

    public Mono<ServerResponse> handle(ServerRequest request) {
        String path = request.path();
        RouteProperties.Route route = resolveRoute(path);
        String targetUri = buildTargetUri(route, request);
        Duration timeout = route != null && route.getResponseTimeout() != null
                ? route.getResponseTimeout()
                : routeProperties.getDefaultResponseTimeout();

        WebClient.RequestBodySpec requestSpec = webClient.method(request.method())
                .uri(targetUri)
                .headers(headers -> copyRequestHeaders(request.headers().asHttpHeaders(), headers));

        Mono<ServerResponse> response = requestSpec
                .body(BodyInserters.fromDataBuffers(request.bodyToFlux(DataBuffer.class)))
                .retrieve()
                .onStatus(status -> true, clientResponse -> Mono.empty())
                .toEntityFlux(DataBuffer.class)
                .flatMap(entity -> ServerResponse.status(entity.getStatusCode())
                        .headers(headers -> copyResponseHeaders(entity.getHeaders(), headers))
                        .body(entity.getBody() != null ? entity.getBody() : Flux.empty(), DataBuffer.class));

        return response
                .timeout(timeout)
                .onErrorResume(java.util.concurrent.TimeoutException.class,
                        e -> ServerResponse.status(HttpStatus.GATEWAY_TIMEOUT).build());
    }

    private RouteProperties.Route resolveRoute(String path) {
        for (RouteProperties.Route route : routeProperties.getRoutes()) {
            for (String pattern : route.getPaths()) {
                if (PATH_MATCHER.match(pattern, path)) {
                    return route;
                }
            }
        }
        return null;
    }

    private String buildTargetUri(RouteProperties.Route route, ServerRequest request) {
        String baseUri = route != null ? route.getUri() : routeProperties.getFallbackUri();
        String query = request.uri().getRawQuery();
        String path = request.path();
        return query == null || query.isBlank()
                ? baseUri + path
                : baseUri + path + "?" + query;
    }

    private void copyRequestHeaders(HttpHeaders source, HttpHeaders target) {
        source.forEach((name, values) -> {
            if (!EXCLUDED_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                target.put(name, List.copyOf(values));
            }
        });
    }

    private void copyResponseHeaders(HttpHeaders source, HttpHeaders target) {
        source.forEach((name, values) -> {
            if (!EXCLUDED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                target.put(name, List.copyOf(values));
            }
        });
    }
}
