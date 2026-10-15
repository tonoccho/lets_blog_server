package com.letsblog.plugins.claude;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigTest {

    @Test
    void defaultsWhenEnvironmentIsEmpty() {
        ClaudeAdapter.Config config = ClaudeAdapter.Config.fromEnv(Map.of());
        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.anthropicBaseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void environmentOverridesAndTrailingSlashIsTrimmed() {
        ClaudeAdapter.Config config = ClaudeAdapter.Config.fromEnv(Map.of(
                "PORT", "9090",
                "ANTHROPIC_BASE_URL", "http://proxy.example/",
                "LLM_REQUEST_TIMEOUT_SECONDS", "7"));
        assertThat(config.port()).isEqualTo(9090);
        assertThat(config.anthropicBaseUrl()).isEqualTo("http://proxy.example");
        assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void blankValuesFallBackToDefaults() {
        ClaudeAdapter.Config config = ClaudeAdapter.Config.fromEnv(Map.of(
                "PORT", " ", "ANTHROPIC_BASE_URL", "", "LLM_REQUEST_TIMEOUT_SECONDS", " "));
        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.anthropicBaseUrl()).isEqualTo("https://api.anthropic.com");
        assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(120));
    }
}
