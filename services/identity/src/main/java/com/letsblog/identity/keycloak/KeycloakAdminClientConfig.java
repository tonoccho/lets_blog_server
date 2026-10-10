package com.letsblog.identity.keycloak;

import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(KeycloakAdminProperties.class)
public class KeycloakAdminClientConfig {

    /**
     * Keycloakコンテナが停止/無応答の場合に、TCP接続確立自体が(OSの既定値に従い)
     * 数分単位でハングしてしまうのを防ぐ(#562の受入基準: Keycloak停止時に明確なエラーを返す)。
     * PenpotClient等、既存の外部API連携クライアントと同じ考え方・値
     * (services/legacy-api/.../ai/PenpotClient参照)。
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public KeycloakAdminClient keycloakAdminClient(KeycloakAdminProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(TIMEOUT);

        RestClient.Builder builder = RestClient.builder().requestInterceptor(new ExternalCallLoggingInterceptor("keycloak-admin")).requestFactory(requestFactory);
        return new KeycloakAdminClient(builder, properties);
    }
}
