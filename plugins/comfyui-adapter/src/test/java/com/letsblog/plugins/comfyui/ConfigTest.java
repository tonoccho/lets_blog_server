package com.letsblog.plugins.comfyui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigTest {

    @Test
    void defaultsWhenEnvironmentIsEmpty() {
        ComfyUiAdapter.Config config = ComfyUiAdapter.Config.fromEnv(Map.of());
        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.comfyUiBaseUrl()).isEqualTo("http://127.0.0.1:8188");
        assertThat(config.defaultCheckpoint()).isEqualTo("v1-5-pruned-emaonly.safetensors");
        assertThat(config.pollInterval()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void environmentOverridesAndTrailingSlashIsTrimmed() {
        ComfyUiAdapter.Config config = ComfyUiAdapter.Config.fromEnv(Map.of(
                "PORT", "9090",
                "COMFYUI_BASE_URL", "http://gpu.example:8188/",
                "COMFYUI_CHECKPOINT", "sdxl.safetensors",
                "COMFYUI_POLL_INTERVAL_MILLIS", "250"));
        assertThat(config.port()).isEqualTo(9090);
        assertThat(config.comfyUiBaseUrl()).isEqualTo("http://gpu.example:8188");
        assertThat(config.defaultCheckpoint()).isEqualTo("sdxl.safetensors");
        assertThat(config.pollInterval()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void blankValuesFallBackToDefaults() {
        ComfyUiAdapter.Config config = ComfyUiAdapter.Config.fromEnv(Map.of(
                "PORT", " ", "COMFYUI_BASE_URL", "", "COMFYUI_CHECKPOINT", " ", "COMFYUI_POLL_INTERVAL_MILLIS", ""));
        assertThat(config.port()).isEqualTo(8080);
        assertThat(config.comfyUiBaseUrl()).isEqualTo("http://127.0.0.1:8188");
        assertThat(config.defaultCheckpoint()).isEqualTo("v1-5-pruned-emaonly.safetensors");
        assertThat(config.pollInterval()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void maxWaitIsTheLargerOfTheFloorAndTheProportionalBudget() {
        assertThat(ComfyUiAdapter.maxWaitSeconds(1)).isEqualTo(120);
        assertThat(ComfyUiAdapter.maxWaitSeconds(7)).isEqualTo(120);
        assertThat(ComfyUiAdapter.maxWaitSeconds(8)).isEqualTo(124);
        assertThat(ComfyUiAdapter.maxWaitSeconds(16)).isEqualTo(188);
    }
}
