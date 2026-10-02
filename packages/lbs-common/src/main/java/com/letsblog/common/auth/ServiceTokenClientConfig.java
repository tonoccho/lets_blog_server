package com.letsblog.common.auth;

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
 * <p><b>オプトイン(#1483)</b>: 本クラスはlbs-commonにあるが{@code @Configuration}の
 * コンポーネントスキャン対象ではない(各サービスは自パッケージ配下しかスキャンしない)。
 * {@code keycloak.admin.*}プロパティを持ち、トークンを必要とするサービスだけが
 * {@code @Import(ServiceTokenClientConfig.class)}で取り込む。使わないサービスには
 * Beanもプロパティ要求も生じない。media-service・publishing-serviceが利用する(以前は各サービスに
 * 同一内容の複製があった。identity-serviceは#1483の対象外で独自の複製を持つ)。
 *
 * <p>以下はmedia-service側の経緯。issue #583で画像生成がmedia-serviceへ移り、{@code PlatformServiceClient}が
 * platform-serviceの内部ブリッジ({@code /api/internal/platform/image-generation-config})を
 * 呼ぶ必要が生じたため追加した。この内部ブリッジは#742でJWT必須になっており、
 * 呼び出し元ユーザーのトークンではなく<b>このサービス自身</b>のトークンを付与する
 * (システム全体の設定を1つ解決するだけで、特定ユーザーのデータではないため)。
 *
 * <p>legacy-apiの同名クラスは{@code KeycloakAdminProperties}を再利用していたが、
 * media-serviceはKeycloak Admin REST APIを使わずこのプロパティ型を持たないため、
 * 同じ設定キー({@code keycloak.admin.*})を{@code @Value}で直接読む。
 *
 * <p>publishing-service側の経緯: issue #1207で、content-serviceのテーマ骨格取得ブリッジ
 * ({@code ContentServiceClient}の{@code fetchAndSplice}/{@code fetchRealPost})が、利用者単位の
 * 認可を一切行わないPlaywright専用の内部呼び出しであるにもかかわらず呼び出し元ユーザーのBearer
 * トークンを転送していた({@code forwardedBearer})点を見直し、本サービス自身のトークンを付与する
 * 方式へ切り替えるために追加した(media-serviceのissue #1083と同じ実装・同じ理由)。
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
