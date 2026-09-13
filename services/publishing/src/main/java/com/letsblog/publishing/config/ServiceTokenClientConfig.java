package com.letsblog.publishing.config;

import com.letsblog.common.auth.ServiceTokenClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * サービス間のClient Credentials認証用トークンクライアント(#567)をBeanとして公開する。
 *
 * <p>issue #1207で、content-serviceのテーマ骨格取得ブリッジ({@code ContentServiceClient}の
 * {@code fetchAndSplice}/{@code fetchRealPost})が、利用者単位の認可を一切行わないPlaywright専用の
 * 内部呼び出しであるにもかかわらず呼び出し元ユーザーのBearerトークンを転送していた
 * ({@code forwardedBearer})点を見直し、本サービス自身のトークンを付与する方式へ切り替えるために
 * 追加した(media-serviceの{@code ServiceTokenClientConfig}、issue #1083と同じ実装・同じ理由)。
 *
 * <p>Keycloakが停止/無応答のときにTCP接続の確立で長時間ハングしないようタイムアウトを設定する。
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
