package com.letsblog.common.auth;

import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * {@link ToolsTokenProvider}をBeanとして公開する(#1804)。{@link ServiceTokenClientConfig}と同じく
 * オプトイン({@code @Configuration}等を付けず、使うサービスが{@code @Import}する)。
 *
 * <p>{@code keycloak.tools.client-secret}が空なら無効のプロバイダー(トークンを付けない)。設定されていれば
 * {@code keycloak.tools.token-uri}・{@code keycloak.tools.client-id}の{@code letsblog-tools-client}で
 * client credentialsトークンを取る。{@link ServiceTokenClient}は本クラスの中で作り、Beanにしない
 * ({@code letsblog-services}の{@link ServiceTokenClient}のBeanと型が衝突し、既存の注入先が曖昧になるため)。
 */
public class ToolsTokenProviderConfig {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public ToolsTokenProvider toolsTokenProvider(
            @Value("${keycloak.tools.token-uri:}") String tokenUri,
            @Value("${keycloak.tools.client-id:letsblog-tools-client}") String clientId,
            @Value("${keycloak.tools.client-secret:}") String clientSecret) {
        if (!StringUtils.hasText(clientSecret)) {
            return ToolsTokenProvider.disabled();
        }
        if (!StringUtils.hasText(tokenUri)) {
            throw new IllegalStateException(
                    "keycloak.tools.client-secret が設定されているのに keycloak.tools.token-uri が空です"
                            + "(KEYCLOAK_TOKEN_URI を確認してください)");
        }
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(TIMEOUT);
        RestClient.Builder builder = RestClient.builder()
                .requestInterceptor(new ExternalCallLoggingInterceptor("keycloak-token"))
                .requestFactory(requestFactory);
        return ToolsTokenProvider.of(new ServiceTokenClient(builder, tokenUri, clientId, clientSecret));
    }
}
