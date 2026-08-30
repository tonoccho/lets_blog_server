package com.letsblog.api.config;

import com.letsblog.api.keycloak.KeycloakAdminProperties;
import com.letsblog.common.auth.ServiceTokenClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * サービス間のClient Credentials認証用トークンクライアント(#567)をBeanとして公開する(issue #742)。
 *
 * <p>#742以前、{@link ServiceTokenClient}は{@code KeycloakAdminClient}が内部で直接
 * {@code new}するだけで、Beanとしては存在しなかった。{@code PlatformServiceClient}が
 * platform-serviceの内部ブリッジを呼ぶ際にこのサービス自身のトークンを付与する必要が生じたため、
 * コンストラクタ注入できるようBean化する。
 *
 * <p>設定は{@code KeycloakAdminProperties}({@code keycloak.admin.*})を再利用する。
 * Keycloak Admin REST APIの呼び出しと同じ{@code letsblog-services}クライアントの
 * クライアントクレデンシャルズグラントで、トークンエンドポイントも同一のため
 * ({@code keycloak/realm-export.json}参照)、設定キーを別に増やす理由が無い。
 *
 * <p>{@code KeycloakAdminClient}側は引き続き自前で{@link ServiceTokenClient}を生成する。
 * そちらへ本Beanを注入する形へ寄せることもできるが、identity-service/platform-serviceにある
 * 同名クラスと実装を揃えてある箇所なので、#742のスコープでは触らない。
 *
 * <p>Keycloakが停止/無応答のときにTCP接続の確立で長時間ハングしないよう、
 * {@code KeycloakAdminClientConfig}と同じタイムアウトを設定したRestClientを渡す。
 *
 * <p>{@code @EnableConfigurationProperties}を本クラスにも付けているのは、
 * {@code KeycloakAdminProperties}をBean化しているのが現状{@code KeycloakAdminClientConfig}だけで、
 * そちらを消すと本クラスが起動に失敗するという暗黙の依存を避けるため(冪等なので二重指定でよい)。
 */
@Configuration
@EnableConfigurationProperties(KeycloakAdminProperties.class)
public class ServiceTokenClientConfig {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public ServiceTokenClient serviceTokenClient(KeycloakAdminProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(TIMEOUT);

        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        return new ServiceTokenClient(
                builder, properties.getTokenUri(), properties.getClientId(), properties.getClientSecret());
    }
}
