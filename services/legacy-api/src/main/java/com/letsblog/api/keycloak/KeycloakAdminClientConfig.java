package com.letsblog.api.keycloak;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * services/identity/.../keycloak/KeycloakAdminClientConfigと同じ方針(#681)。
 * Keycloakコンテナが停止/無応答の場合に、TCP接続確立自体が(OSの既定値に従い)
 * 数分単位でハングしてしまうのを防ぐ。
 */
@Configuration
@EnableConfigurationProperties(KeycloakAdminProperties.class)
public class KeycloakAdminClientConfig {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public KeycloakAdminClient keycloakAdminClient(KeycloakAdminProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(TIMEOUT);

        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        return new KeycloakAdminClient(builder, properties);
    }
}
