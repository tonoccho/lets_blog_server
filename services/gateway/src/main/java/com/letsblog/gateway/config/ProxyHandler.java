package com.letsblog.gateway.config;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProxyHandler.class);

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** クライアントへ転送しないヘッダー(下流からの応答をそのまま返す際に除外する)。 */
    private static final Set<String> EXCLUDED_RESPONSE_HEADERS =
            Set.of("transfer-encoding", "connection", "content-length");

    /**
     * 下流への転送時に除外するヘッダー(WebClientが接続に応じて再設定する接続用ヘッダーのみ)。
     *
     * <p>issue #639時点では、クライアントが直接送信したactor詐称可能ヘッダー(旧
     * 実行者ID/実行者ロールの自己申告用ヘッダー)もここで強制除去していた。legacy-api/
     * identity-serviceのCurrentActorServiceがJWT認証の無い場合にこれらをそのまま信頼していた
     * ためだが、issue #566でその信頼(ヘッダーベースのフォールバック)自体を撤去し、KeycloakのJWTの
     * みを信頼するよう全面移行した。これにより、これらのヘッダーはどのサービスにとっても
     * 何の意味も持たない単なる任意ヘッダーとなり、gateway側での特別な除去は不要になったため撤去した。
     */
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
        if (route == null) {
            // issue #583: legacy-apiの削除に伴い、未割り当てパスのフォールバック転送先を廃止した。
            // 以前はどのルートにもマッチしないパスを暗黙にlegacy-apiへ流しており、ルート表に
            // 載せ忘れたエンドポイントがたまたま動いてしまう(逆に、legacy-apiに無ければ
            // legacy-apiの404として返る)状態だった。gateway自身が404を返すようにする。
            return ServerResponse.notFound().build();
        }
        String targetUri = buildTargetUri(route, request);
        Duration timeout = route != null && route.getResponseTimeout() != null
                ? route.getResponseTimeout()
                : routeProperties.getDefaultResponseTimeout();

        // URIとして渡す。String版のuri(...)は渡された文字列をURIテンプレートとして
        // 解釈し直すため、既にエンコード済みの値を二重エンコードしてしまう
        // (buildTargetUriのJavadoc参照。issue #1002)。
        WebClient.RequestBodySpec requestSpec = webClient.method(request.method())
                .uri(URI.create(targetUri))
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
                        e -> ServerResponse.status(HttpStatus.GATEWAY_TIMEOUT).build())
                .onErrorResume(WebClientRequestException.class, e -> unreachable(route, e));
    }

    /**
     * 下流へ到達できなかった場合の応答(issue #1096)。gateway自身の内部エラー(500)ではなく、
     * 「下流が利用できない」ことを表す503を返す。
     *
     * <p>{@link PrematureCloseException}(接続確立後・応答受信前に下流が切断)も502ではなく503とする。
     * 拡張は「本文付きの502」を「下流は応答したが、その上流(外部サービス)が失敗した」と解釈する
     * (issue #1082)ため、502だと利用者を誤った原因へ誘導する。いずれの場合もgateway自身は
     * リクエストを再送しない。
     */
    private Mono<ServerResponse> unreachable(RouteProperties.Route route, WebClientRequestException e) {
        log.warn("downstream unreachable: service={} cause={}", route.getId(), String.valueOf(e.getCause()));
        boolean closedAfterConnect = e.getCause() instanceof PrematureCloseException;
        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
        String message = closedAfterConnect
                ? "サービス " + route.getId() + " は応答を返す前に接続を切断しました。再起動中の可能性があります。"
                : "サービス " + route.getId() + " へ接続できませんでした。起動中または停止中の可能性があります。";
        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "status", status.value(),
                        "error", status.getReasonPhrase(),
                        "service", String.valueOf(route.getId()),
                        "message", message));
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

    /**
     * package-privateな理由はresolveRoute(String)のJavadoc参照(issue #642)。
     *
     * <p>{@code route}は非nullであることを前提とする。マッチしなかった場合は
     * {@link #handle(ServerRequest)}が404を返して呼ばない(issue #583でフォールバックを廃止)。
     *
     * <p><b>戻り値は「既にパーセントエンコードされたURI文字列」であり、再度エンコードしては
     * ならない(issue #1002)。</b>クライアントから届いた生のパス({@code ServerRequest#path()}は
     * {@code RequestPath}の生の値)と生のクエリ({@code getRawQuery()})をそのまま連結している。
     * これを{@code WebClient.uri(String)}へ渡すと、WebClient既定の
     * {@code DefaultUriBuilderFactory}がURIテンプレートとして符号化し直し、値の中の
     * {@code %}が{@code %25}へ置き換わる。すなわち
     * {@code ?url=https%3A%2F%2Fexample.com%2F} が
     * {@code ?url=https%253A%252F%252Fexample.com%252F} になり、下流が
     * {@code @RequestParam}で1回復号しても絶対URLへ戻らない。gatewayは「受け取った
     * リクエストラインをそのまま渡す」のが仕事なので、{@link #handle(ServerRequest)}では
     * {@code URI}へ変換してから渡す({@code URI}版の{@code uri(...)}は
     * {@code UriBuilderFactory}を通さない)。
     *
     * <p>ここで{@code URI.create}が失敗する組み合わせは実運用では起きない。リクエストラインが
     * URIとして解釈できない場合、Reactor Nettyのアダプタがこのハンドラに到達する前に400を
     * 返しているためで、残るのはルート表の{@code uri}(設定値。絶対URI)との連結だけである。
     */
    String buildTargetUri(RouteProperties.Route route, ServerRequest request) {
        String baseUri = route.getUri();
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
