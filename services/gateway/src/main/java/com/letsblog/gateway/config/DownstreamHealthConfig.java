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
 * 下流サービスの状態を{@code /actuator/health}に集約する(#560)。将来のサービス抽出に伴い、
 * 対応するprobeを追加していく(#561でidentity-serviceを追加)。
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
