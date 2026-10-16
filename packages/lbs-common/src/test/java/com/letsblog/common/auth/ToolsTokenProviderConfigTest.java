package com.letsblog.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * {@link ToolsTokenProviderConfig}(#1804)。{@code keycloak.tools.*}から組み立てる。secretが空なら無効、
 * あるのにtoken-uriが空なら起動時に理由の分かる例外にする。{@link ServiceTokenClient}のBeanは作らない
 * (letsblog-servicesのBeanと型が衝突しないようにするため)。
 */
class ToolsTokenProviderConfigTest {

    private static AnnotationConfigApplicationContext contextWith(Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        context.register(ToolsTokenProviderConfig.class, ServiceTokenClientConfig.class);
        return context;
    }

    @Test
    @DisplayName("secretが未設定なら無効のプロバイダーになる")
    void secret未設定は無効() {
        try (AnnotationConfigApplicationContext context = contextWith(Map.of(
                "keycloak.admin.token-uri", "http://localhost:1/token",
                "keycloak.admin.client-id", "svc",
                "keycloak.admin.client-secret", "s"))) {
            context.refresh();

            assertThat(context.getBean(ToolsTokenProvider.class).enabled()).isFalse();
        }
    }

    @Test
    @DisplayName("secretが空白だけでも無効")
    void secret空白は無効() {
        try (AnnotationConfigApplicationContext context = contextWith(Map.of(
                "keycloak.tools.client-secret", "   ",
                "keycloak.tools.token-uri", "http://localhost:1/token",
                "keycloak.admin.token-uri", "http://localhost:1/token",
                "keycloak.admin.client-id", "svc",
                "keycloak.admin.client-secret", "s"))) {
            context.refresh();

            assertThat(context.getBean(ToolsTokenProvider.class).enabled()).isFalse();
        }
    }

    @Test
    @DisplayName("secretとtoken-uriがあれば有効になり、既存のServiceTokenClientのBeanとは別の型で共存する")
    void secretがあれば有効で既存Beanと共存() {
        try (AnnotationConfigApplicationContext context = contextWith(Map.of(
                "keycloak.tools.client-secret", "tools-secret",
                "keycloak.tools.token-uri", "http://localhost:1/token",
                "keycloak.admin.token-uri", "http://localhost:1/token",
                "keycloak.admin.client-id", "svc",
                "keycloak.admin.client-secret", "s"))) {
            context.refresh();

            assertThat(context.getBean(ToolsTokenProvider.class).enabled()).isTrue();
            assertThat(context.getBeansOfType(ServiceTokenClient.class)).hasSize(1);
        }
    }

    @Test
    @DisplayName("secretがあるのにtoken-uriが空なら、設定名を示す例外で起動を止める")
    void token_uriが空なら起動を止める() {
        try (AnnotationConfigApplicationContext context = contextWith(Map.of(
                "keycloak.tools.client-secret", "tools-secret",
                "keycloak.admin.token-uri", "http://localhost:1/token",
                "keycloak.admin.client-id", "svc",
                "keycloak.admin.client-secret", "s"))) {
            assertThatThrownBy(context::refresh)
                    .hasStackTraceContaining("keycloak.tools.token-uri");
        }
    }

    @Test
    @DisplayName("keycloak.tools.*が一切無くても(使わないサービスで)Beanは無効として生える")
    void プロパティが無くても無効() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ToolsTokenProviderConfig.class);
            context.refresh();

            assertThat(context.getBean(ToolsTokenProvider.class).enabled()).isFalse();
        }
    }
}
