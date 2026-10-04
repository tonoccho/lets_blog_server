package com.letsblog.project.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #1484: サービスURIの既定値が撤去済みのlegacy-api(http://api:8080)を指したまま残らないこと。
 * 環境変数が渡らない構成でも、実在するサービスへ向く必要がある(#825の再発防止)。
 */
@DisplayName("application.yml のサービスURI既定値(issue #1484)")
class ServiceUriDefaultsTest {

    private static Properties load() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return yaml.getObject();
    }

    @Test
    @DisplayName("ai-service-uri の既定値は実在する ai-service を指す")
    void aiServiceUriDefaultsToAi() {
        assertThat(load().getProperty("app.ai-service-uri")).endsWith(":http://ai:8080}");
    }

    @Test
    @DisplayName("どのサービスURIの既定値も撤去済みの legacy-api(api:8080)を指さない")
    void noUriDefaultsToLegacyApi() {
        load().forEach((key, value) -> {
            if (key.toString().endsWith("-uri")) {
                assertThat(value.toString()).as(key.toString()).doesNotContain("//api:8080");
            }
        });
    }

    @Test
    @DisplayName("ジョブ更新のClient Credentials用 keycloak.admin.* が定義されている(issue #1479)")
    void keycloakServiceCredentialsAreConfigured() {
        Properties props = load();
        assertThat(props.getProperty("keycloak.admin.token-uri")).contains("KEYCLOAK_TOKEN_URI");
        assertThat(props.getProperty("keycloak.admin.client-id")).contains("KEYCLOAK_SERVICES_CLIENT_ID");
        assertThat(props.getProperty("keycloak.admin.client-secret")).contains("KEYCLOAK_SERVICES_CLIENT_SECRET");
    }
}
