package com.letsblog.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 下流サービスの状態を{@code /actuator/health}に集約する(#560)。
 *
 * <p>サービス抽出に伴い対応するprobeを追加していく。#561でidentity-serviceを、
 * #643でcontent/media/ai/analytics/log-writerを追加した。#743でplatform/project/publishingを
 * 追加し、{@code services/}配下の全サービス(gatewayを除く10個)が揃った。
 *
 * <p>この3つが漏れていたのは、サービス新設時にルート定義({@code application.yml})は
 * 追加されるのに、集約ヘルスチェックへの追加が別の場所にあって忘れられるため。同型の漏れは
 * ルート表側でも起きている(#716でplatformが{@code RouteControllerContractTest}の
 * 対応表から漏れていた)。{@code DownstreamHealthConfigContractTest}が
 * {@code services/}配下のディレクトリを列挙して突き合わせるので、次にサービスを増やしたときは
 * そのテストが落ちて気付ける。
 */
@Configuration
public class DownstreamHealthConfig {

    @Bean
    public ReactiveHealthIndicator legacyApiHealthIndicator(
            WebClient gatewayWebClient,
            @Value("${app.gateway.fallback-uri}") String legacyApiUri) {
        return downstreamHealthIndicator(gatewayWebClient, legacyApiUri);
    }

    @Bean
    public ReactiveHealthIndicator identityServiceHealthIndicator(
            WebClient gatewayWebClient,
            @Value("${IDENTITY_SERVICE_URI:http://identity:8080}") String identityServiceUri) {
        return downstreamHealthIndicator(gatewayWebClient, identityServiceUri);
    }

    @Bean
    public ReactiveHealthIndicator contentServiceHealthIndicator(
            WebClient gatewayWebClient,
            @Value("${CONTENT_SERVICE_URI:http://content:8080}") String contentServiceUri) {
        return downstreamHealthIndicator(gatewayWebClient, contentServiceUri);
    }

    @Bean
    public ReactiveHealthIndicator mediaServiceHealthIndicator(
            WebClient gatewayWebClient,
            @Value("${MEDIA_SERVICE_URI:http://media:8080}") String mediaServiceUri) {
        return downstreamHealthIndicator(gatewayWebClient, mediaServiceUri);
    }

    @Bean
    public ReactiveHealthIndicator aiServiceHealthIndicator(
            WebClient gatewayWebClient,
            @Value("${AI_SERVICE_URI:http://ai:8080}") String aiServiceUri) {
        return downstreamHealthIndicator(gatewayWebClient, aiServiceUri);
    }

    @Bean
    public ReactiveHealthIndicator analyticsServiceHealthIndicator(
            WebClient gatewayWebClient,
            @Value("${ANALYTICS_SERVICE_URI:http://analytics:8080}") String analyticsServiceUri) {
        return downstreamHealthIndicator(gatewayWebClient, analyticsServiceUri);
    }

    @Bean
    public ReactiveHealthIndicator logWriterServiceHealthIndicator(
            WebClient gatewayWebClient,
            @Value("${LOG_SERVICE_URI:http://log-writer:8080}") String logServiceUri) {
        return downstreamHealthIndicator(gatewayWebClient, logServiceUri);
    }

    // 以下3つの既定URIは、ルート定義(application.yml)が使う ${PROJECT_SERVICE_URI:http://api:8080}
    // のような「未設定ならlegacy-apiへフォールバック」とは意図的に変えてある(issue #743)。
    // ルーティングでは移設途中のサービスをlegacy-apiへ落とすのが正しいが、ヘルスチェックで同じことを
    // すると legacy-api の状態を project/publishing/platform の名前で報告してしまい、
    // 「3サービスが落ちているのに集約ヘルスがUP」という誤報になる。未設定ならDOWNになる方が正しい。
    @Bean
    public ReactiveHealthIndicator projectServiceHealthIndicator(
            WebClient gatewayWebClient,
            @Value("${PROJECT_SERVICE_URI:http://project:8080}") String projectServiceUri) {
        return downstreamHealthIndicator(gatewayWebClient, projectServiceUri);
    }

    @Bean
    public ReactiveHealthIndicator publishingServiceHealthIndicator(
            WebClient gatewayWebClient,
            @Value("${PUBLISHING_SERVICE_URI:http://publishing:8080}") String publishingServiceUri) {
        return downstreamHealthIndicator(gatewayWebClient, publishingServiceUri);
    }

    @Bean
    public ReactiveHealthIndicator platformServiceHealthIndicator(
            WebClient gatewayWebClient,
            @Value("${PLATFORM_SERVICE_URI:http://platform:8080}") String platformServiceUri) {
        return downstreamHealthIndicator(gatewayWebClient, platformServiceUri);
    }

    private ReactiveHealthIndicator downstreamHealthIndicator(WebClient gatewayWebClient, String uri) {
        return () -> gatewayWebClient.get()
                .uri(uri + "/actuator/health")
                .retrieve()
                .toBodilessEntity()
                .map(response -> Health.up().withDetail("statusCode", response.getStatusCode().value()).build())
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(e -> Mono.just(Health.down(e).build()));
    }
}
