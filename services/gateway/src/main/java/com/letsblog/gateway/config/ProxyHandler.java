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
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    /** 下流への転送時に除外する接続用ヘッダー(WebClientが接続に応じて再設定するため)。 */
    private static final Set<String> EXCLUDED_CONNECTION_HEADERS =
            Set.of("host", "content-length");

    /**
     * 下流への転送時に除外する、内部専用のアクター詐称可能ヘッダー(issue #639)。
     *
     * <p>legacy-api/identity-serviceのCurrentActorServiceは、JWT認証が無い場合にこれらのヘッダーを
     * そのまま信頼する(元々はWeb BFF(Next.js)がNextAuthセッションの内容を転送する専用の経路として
     * 導入されたもの)。gatewayは全サービスへのリクエストが通過する唯一のエントリポイントであり、
     * nginx({@code nginx/conf.d/default.conf}の{@code /api/}ロケーション)もこのgateway以外の
     * ダウンストリームへは転送しないため、ここでクライアントが直接送信したこれらのヘッダーを
     * 強制的に除去すれば、有効なAPIキー保有者(legacy-api)や未認証リクエスト(identity-service。
     * SecurityConfigがpermitAllのため)が{@code X-Actor-Role: admin}等を自己申告して
     * admin限定APIへ到達する経路を遮断できる。
     *
     * <p>Web(web/src/lib/apiClient.ts)・VSCode拡張(extension/src/apiClient.ts)は、いずれも
     * 既にKeycloak発行JWT(Authorizationヘッダー)のみを送信しており、これらのヘッダーには
     * 依存していないことを確認済み(#564/#565は完了済み。legacy-api/identity-serviceの
     * CurrentActorService/SecurityConfigのJavadocコメントは2026-08時点で「未着手」と記載しているが、
     * 実際のクライアント実装は既に移行済みであり、この点はコメントが古くなっている)。そのため、
     * 正規の経路(Authorizationヘッダー)を壊すことなく全面的に除去できる。gatewayと
     * legacy-api/identity-service間に、このヘッダーへ依存する内部専用の代替経路は無い。
     */
    private static final Set<String> EXCLUDED_ACTOR_HEADERS =
            Set.of("x-actor-id", "x-actor-role");

    /** 下流への転送時に除外するヘッダー全体。 */
    private static final Set<String> EXCLUDED_REQUEST_HEADERS =
            Stream.concat(EXCLUDED_CONNECTION_HEADERS.stream(), EXCLUDED_ACTOR_HEADERS.stream())
                    .collect(Collectors.toUnmodifiableSet());

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

    /**
     * ルート表を先頭から順に評価し、最初にマッチしたルートを返す(先勝ち。パスパターンの
     * 特定度による自動優先付けは行わないため、ルート表側で順序を意識する必要がある)。
     *
     * <p>package-privateなのは、ユニットテスト(ProxyHandlerRoutingTest)・ルート表と
     * 各下流サービスの実{@code @RequestMapping}との整合性を検証するコントラクトテスト
     * (RouteControllerContractTest、issue #642)から、このロジックの複製を作らず直接
     * 呼び出すため。
     */
    RouteProperties.Route resolveRoute(String path) {
        for (RouteProperties.Route route : routeProperties.getRoutes()) {
            for (String pattern : route.getPaths()) {
                if (PATH_MATCHER.match(pattern, path)) {
                    return route;
                }
            }
        }
        return null;
    }

    /** package-privateな理由はresolveRoute(String)のJavadoc参照(issue #642)。 */
    String buildTargetUri(RouteProperties.Route route, ServerRequest request) {
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
