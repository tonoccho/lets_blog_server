package com.letsblog.identity.config;

import com.letsblog.common.auth.ServiceTokenClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * サービス間のClient Credentials認証用トークンクライアント(#567)をBeanとして公開する(issue #1324)。
 * リクエストの無いスレッド(Rabbitリスナー)から内部ブリッジを呼ぶ{@code OutboundAuthHeaders}が使う。
 * 設定キーは{@code KeycloakAdminClient}と同じ{@code keycloak.admin.*}。
 */
@Configuration
public class ServiceTokenClientConfig {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public ServiceTokenClient serviceTokenClient(
            @Value("${keycloak.admin.token-uri}") String tokenUri,
            @Value("${keycloak.admin.client-id}") String clientId,
            @Value("${keycloak.admin.client-secret}") String clientSecret) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(TIMEOUT);
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        return new ServiceTokenClient(builder, tokenUri, clientId, clientSecret);
    }
}
