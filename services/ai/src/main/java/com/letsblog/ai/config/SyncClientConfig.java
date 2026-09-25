package com.letsblog.ai.config;

import com.letsblog.common.client.IdentityClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * サービス間同期呼び出しの共通クライアント(issue #581、C12)のBean定義。lbs-commonの
 * {@link IdentityClient}は素のライブラリクラス(Spring Beanではない)のため、本サービスの
 * {@code app.identity-service-uri}設定値を使って構築する。
 */
@Configuration
public class SyncClientConfig {

    @Bean
    public IdentityClient identityClient(
            RestClient.Builder builder, @Value("${app.identity-service-uri}") String identityServiceUri) {
        return new IdentityClient(builder, identityServiceUri);
    }
}
