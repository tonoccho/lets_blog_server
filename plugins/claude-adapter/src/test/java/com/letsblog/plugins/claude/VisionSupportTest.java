package com.letsblog.plugins.claude;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VisionSupportTest {

    @Test
    void currentClaudeModelsSupportImages() {
        assertThat(VisionSupport.supportsImageInput("claude-sonnet-4-5")).isTrue();
        assertThat(VisionSupport.supportsImageInput("claude-3-5-haiku-latest")).isTrue();
        assertThat(VisionSupport.supportsImageInput("  CLAUDE-opus-4  ")).isTrue();
    }

    @Test
    void legacyGenerationsDoNot() {
        assertThat(VisionSupport.supportsImageInput("claude-1.3")).isFalse();
        assertThat(VisionSupport.supportsImageInput("claude-2.1")).isFalse();
        assertThat(VisionSupport.supportsImageInput("claude-instant-1.2")).isFalse();
    }

    @Test
    void nonClaudeBlankOrNullDoNot() {
        assertThat(VisionSupport.supportsImageInput("gpt-4o")).isFalse();
        assertThat(VisionSupport.supportsImageInput("  ")).isFalse();
        assertThat(VisionSupport.supportsImageInput(null)).isFalse();
    }
}
