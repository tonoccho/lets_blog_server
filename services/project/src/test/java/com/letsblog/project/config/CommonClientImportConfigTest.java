package com.letsblog.project.config;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.common.client.GenerationJobClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #1479: project-serviceがジョブを受理するのに要る {@code GenerationJobClient} と
 * {@code ServiceTokenClient} は、lbs-commonのオプトイン部品({@code @Import}で取り込む。#1483)。
 * 取り込み漏れ(Beanが無い)や、必要なプロパティの欠落で起動に失敗しないことを固定する。
 */
@DisplayName("project-service: lbs-commonオプトイン部品の取り込み(issue #1479)")
class CommonClientImportConfigTest {

    @Test
    @DisplayName("GenerationJobClient と ServiceTokenClient が Bean として組み立てられる")
    void beansAreWired() {
        new ApplicationContextRunner()
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withPropertyValues(
                        "app.ai-service-uri=http://ai:8080",
                        "keycloak.admin.token-uri=http://keycloak:8080/token",
                        "keycloak.admin.client-id=letsblog-services",
                        "keycloak.admin.client-secret=s")
                .withUserConfiguration(CommonClientImportConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(GenerationJobClient.class);
                    assertThat(context).hasSingleBean(ServiceTokenClient.class);
                });
    }
}
