package com.letsblog.media.config;

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
 * <p>issue #583で画像生成がmedia-serviceへ移り、{@code PlatformServiceClient}が
 * platform-serviceの内部ブリッジ({@code /api/internal/platform/image-generation-config})を
 * 呼ぶ必要が生じたため追加した。この内部ブリッジは#742でJWT必須になっており、
 * 呼び出し元ユーザーのトークンではなく<b>このサービス自身</b>のトークンを付与する
 * (システム全体の設定を1つ解決するだけで、特定ユーザーのデータではないため)。
 *
 * <p>legacy-apiの同名クラスは{@code KeycloakAdminProperties}を再利用していたが、
 * media-serviceはKeycloak Admin REST APIを使わずこのプロパティ型を持たないため、
 * 同じ設定キー({@code keycloak.admin.*})を{@code @Value}で直接読む。
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
