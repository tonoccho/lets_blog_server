package com.letsblog.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.letsblog.common.auth.ToolsTokenProvider;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;

/** extensionsのツールを呼ぶ専用クライアントの設定(#1804)。secretを設定したときだけ有効になる。 */
class ToolsTokenConfigTest {

    private static Properties load() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return yaml.getObject();
    }

    @Test
    @DisplayName("application.ymlにkeycloak.tools.*があり、secretは環境変数KEYCLOAK_TOOLS_CLIENT_SECRETで空が既定(無効)")
    void applicationYmlDeclaresTheToolsClient() {
        Properties props = load();

        assertThat(props.getProperty("keycloak.tools.token-uri")).contains("KEYCLOAK_TOKEN_URI");
        assertThat(props.getProperty("keycloak.tools.client-id")).contains("KEYCLOAK_TOOLS_CLIENT_ID").endsWith(":letsblog-tools-client}");
        assertThat(props.getProperty("keycloak.tools.client-secret")).isEqualTo("${KEYCLOAK_TOOLS_CLIENT_SECRET:}");
    }

    @Test
    @DisplayName("取り込むと、secretがあるときだけ有効なToolsTokenProviderが得られる")
    void importsAnEnabledProviderOnlyWithASecret() {
        try (AnnotationConfigApplicationContext enabled = new AnnotationConfigApplicationContext()) {
            enabled.getEnvironment().getPropertySources().addFirst(new MapPropertySource("t", Map.of(
                    "keycloak.tools.token-uri", "http://localhost:1/token",
                    "keycloak.tools.client-secret", "s")));
            enabled.register(ToolsTokenConfig.class);
            enabled.refresh();
            assertThat(enabled.getBean(ToolsTokenProvider.class).enabled()).isTrue();
        }
        try (AnnotationConfigApplicationContext disabled = new AnnotationConfigApplicationContext()) {
            disabled.register(ToolsTokenConfig.class);
            disabled.refresh();
            assertThat(disabled.getBean(ToolsTokenProvider.class).enabled()).isFalse();
        }
    }
}
