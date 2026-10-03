package com.letsblog.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Map;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * 各サービスは{@code com.letsblog.common}をコンポーネントスキャンする(#1596)。
 * その場合に共通版{@code ServiceTokenClientConfig}が勝手に取り込まれないこと(オプトイン)を固定する。
 */
class ServiceTokenClientConfigScanTest {

    /** common配下の{@code CredentialCipher}がサービス側から受け取る鍵(32バイトのBase64)。keycloak.admin.*は与えない。 */
    private static void provideEncryptionKeyOnly(AnnotationConfigApplicationContext context) {
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "app.encryption-key", "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")));
    }

    @Test
    @DisplayName("com.letsblog.commonをスキャンしkeycloak.admin.*が無くても起動し、ServiceTokenClientは登録されない")
    void スキャンしてもプロパティ無しで起動しBeanは無い() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            provideEncryptionKeyOnly(context);
            context.scan("com.letsblog.common");
            context.refresh();

            assertThat(context.getBeanNamesForType(ServiceTokenClient.class)).isEmpty();
        }
    }

    @Test
    @DisplayName("同じ単純名の別パッケージのServiceTokenClientConfigと一緒にスキャンしてもBean名が衝突しない(identity)")
    void 同名の設定クラスと一緒にスキャンしても衝突しない() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            provideEncryptionKeyOnly(context);
            context.scan("com.letsblog.common", "com.letsblog.fixture.conflict");
            context.refresh();

            assertThat(context.getBeansOfType(com.letsblog.fixture.conflict.ServiceTokenClientConfig.class))
                    .hasSize(1);
            assertThat(context.getBeanNamesForType(ServiceTokenClientConfig.class)).isEmpty();
        }
    }
}
