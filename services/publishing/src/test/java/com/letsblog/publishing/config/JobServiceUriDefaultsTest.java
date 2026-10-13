package com.letsblog.publishing.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** issue #1702: ジョブ受理(ai-serviceの generation_jobs)に要る設定が application.yml に定義されている。 */
@DisplayName("publishing-service: application.yml のジョブ関連設定(issue #1702)")
class JobServiceUriDefaultsTest {

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
    @DisplayName("ジョブ更新のClient Credentials用 keycloak.admin.* が定義されている")
    void keycloakServiceCredentialsAreConfigured() {
        Properties props = load();
        assertThat(props.getProperty("keycloak.admin.token-uri")).contains("KEYCLOAK_TOKEN_URI");
        assertThat(props.getProperty("keycloak.admin.client-id")).contains("KEYCLOAK_SERVICES_CLIENT_ID");
        assertThat(props.getProperty("keycloak.admin.client-secret")).contains("KEYCLOAK_SERVICES_CLIENT_SECRET");
    }
}
