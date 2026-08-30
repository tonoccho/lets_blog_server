package com.letsblog.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/**
 * ルーティング(#560)。/api/** 配下のすべてのリクエストを{@link ProxyHandler}へ渡す。
 */
@Configuration
@EnableConfigurationProperties(RouteProperties.class)
public class GatewayRoutingConfig {

    @Bean
    public WebClient gatewayWebClient() {
        return WebClient.builder().build();
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
