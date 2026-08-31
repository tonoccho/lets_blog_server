package com.letsblog.gateway.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.netty.http.client.HttpClient;

/**
 * ルーティング(#560)。/api/** 配下のすべてのリクエストを{@link ProxyHandler}へ渡す。
 */
@Configuration
@EnableConfigurationProperties(RouteProperties.class)
public class GatewayRoutingConfig {

    /**
     * 下流サービス名(identity/content/...)のDNS解決をキャッシュする上限時間。
     *
     * <p>docker composeのコンテナIPは再作成のたびに入れ替わりうるため、長く持つと危険
     * (下の {@link #gatewayWebClient()} のコメント参照)。短くしすぎるとリクエストごとに
     * Dockerの組み込みDNSへ問い合わせることになるため、既定は10秒とする。
     */
    private final Duration dnsCacheTtl;

    public GatewayRoutingConfig(
            @Value("${app.dns-cache-ttl-seconds:10}") long dnsCacheTtlSeconds) {
        this.dnsCacheTtl = Duration.ofSeconds(dnsCacheTtlSeconds);
    }

    /**
     * 下流サービスへの転送に使うWebClient。
     *
     * <p><b>DNSキャッシュのTTLを明示すること(issue #951)。</b>
     * Reactor Nettyの既定のDNSリゾルバは、解決結果をDNSレコードのTTL(Dockerの組み込みDNSは
     * 600秒)までキャッシュする。docker composeで複数のサービスを<b>同時に</b>再起動すると、
     * DockerがコンテナへIPを割り当て直し、<b>別のサービスが以前のIPを引き継ぐ</b>ことがある。
     * このときgatewayは古いIPを掴んだままなので、
     *
     * <pre>
     *   GET /api/auth/setup-status  →  http://identity:8080  →  実際には media-service へ届く
     * </pre>
     *
     * という取り違えが起きる。届いた先にそのパスは無い(かつ認証必須)ため401になり、
     * gateway自身を再起動するまで直らない。2026-09-01に実測した例:
     *
     * <pre>
     *   gatewayのNetty接続 : identity → 172.19.0.25 / project → 172.19.0.18 / media → 172.19.0.21
     *   実際のコンテナIP   : identity = 172.19.0.18 / project = 172.19.0.21 / media = 172.19.0.25
     * </pre>
     *
     * 症状が「401」なので認可の設定を疑ってしまうが、原因は宛先の取り違えである。
     * 401で済んでいるのは偶然で、取り違えた先に同じパスがあれば<b>別サービスのデータを
     * 操作してしまう</b>。TTLを短くして、再割り当てから最大 {@code dnsCacheTtl} で追従させる。
     */
    @Bean
    public WebClient gatewayWebClient() {
        HttpClient httpClient = HttpClient.create()
                .resolver(spec -> spec
                        .cacheMaxTimeToLive(dnsCacheTtl)
                        .cacheMinTimeToLive(Duration.ZERO)
                        // 解決に失敗した結果を持ち越さない。再起動中の一瞬の失敗を、
                        // 起動後まで引きずらないため。
                        .cacheNegativeTimeToLive(Duration.ZERO));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    public ProxyHandler proxyHandler(WebClient gatewayWebClient, RouteProperties routeProperties) {
        return new ProxyHandler(gatewayWebClient, routeProperties);
    }

    @Bean
    public RouterFunction<ServerResponse> apiProxyRoute(ProxyHandler proxyHandler) {
        return RouterFunctions.route(RequestPredicates.path("/api/**"), proxyHandler::handle);
    }
}
