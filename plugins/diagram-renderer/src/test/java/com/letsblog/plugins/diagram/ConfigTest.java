package com.letsblog.plugins.diagram;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigTest {

    @Test
    void defaultsWhenEnvironmentIsEmpty() {
        DiagramRenderer.Config config = DiagramRenderer.Config.fromEnv(Map.of());
        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.plantUmlBaseUrl()).isEqualTo("http://localhost:8081");
        assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.maxBodyBytes()).isEqualTo(10L * 1024 * 1024);
    }

    @Test
    void environmentOverridesAndTrailingSlashIsTrimmed() {
        DiagramRenderer.Config config = DiagramRenderer.Config.fromEnv(Map.of(
                "PORT", "9090",
                "PLANTUML_BASE_URL", "http://plantuml.example/",
                "PLANTUML_REQUEST_TIMEOUT_SECONDS", "7",
                "RENDER_MAX_BODY_BYTES", "1024"));
        assertThat(config.port()).isEqualTo(9090);
        assertThat(config.plantUmlBaseUrl()).isEqualTo("http://plantuml.example");
        assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(7));
        assertThat(config.maxBodyBytes()).isEqualTo(1024);
    }

    @Test
    void blankValuesFallBackToDefaults() {
        DiagramRenderer.Config config = DiagramRenderer.Config.fromEnv(Map.of(
                "PORT", " ", "PLANTUML_BASE_URL", "", "PLANTUML_REQUEST_TIMEOUT_SECONDS", " ",
                "RENDER_MAX_BODY_BYTES", ""));
        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.plantUmlBaseUrl()).isEqualTo("http://localhost:8081");
        assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.maxBodyBytes()).isEqualTo(10L * 1024 * 1024);
    }
}
