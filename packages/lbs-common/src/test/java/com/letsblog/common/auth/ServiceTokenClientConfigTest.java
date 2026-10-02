package com.letsblog.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;

/**
 * 共通のServiceTokenClientConfig(#1483)。サービスが{@code @Import}した場合だけBeanが生えること、
 * {@code keycloak.admin.*}から値を読むことを固定する。
 */
class ServiceTokenClientConfigTest {

    @Test
    @DisplayName("@Importしたコンテキストにkeycloak.admin.*から組み立てたServiceTokenClientが登録される")
    void importするとBeanが登録される() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "keycloak.admin.token-uri", "http://localhost:1/token",
                    "keycloak.admin.client-id", "svc",
                    "keycloak.admin.client-secret", "secret")));
            context.register(ServiceTokenClientConfig.class);
            context.refresh();

            assertThat(context.getBean(ServiceTokenClient.class)).isNotNull();
        }
    }

    @Test
    @DisplayName("@Importしないコンテキスト(このConfigを使わないサービス)にはBeanもプロパティ要求も生じない")
    void importしなければBeanは無い() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.refresh();

            assertThat(context.getBeanNamesForType(ServiceTokenClient.class)).isEmpty();
        }
    }
}
