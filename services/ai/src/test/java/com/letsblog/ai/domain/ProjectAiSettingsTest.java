package com.letsblog.ai.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ProjectAiSettingsの「キーを持つか」の判定(issue #1824): 未設定(null)・空のバイト列・値ありの3通りを3種類のキーで通す。 */
class ProjectAiSettingsTest {

    private static final byte[] EMPTY = new byte[0];
    private static final byte[] VALUE = {1, 2};

    @Test
    void 何も設定していなければどのキーも持たない() {
        ProjectAiSettings settings = new ProjectAiSettings(1L);

        assertFalse(settings.hasBraveSearchApiKey());
        assertFalse(settings.hasOpenAiApiKey());
        assertFalse(settings.hasClaudeApiKey());
    }

    @Test
    void 空のバイト列はキーを持たないものとして扱う() {
        ProjectAiSettings settings = new ProjectAiSettings(1L);
        settings.setBraveSearchApiKeyEncrypted(EMPTY);
        settings.setOpenAiApiKeyEncrypted(EMPTY);
        settings.setClaudeApiKeyEncrypted(EMPTY);

        assertFalse(settings.hasBraveSearchApiKey());
        assertFalse(settings.hasOpenAiApiKey());
        assertFalse(settings.hasClaudeApiKey());
    }

    @Test
    void 値があればキーを持つ_持つキーだけがtrue() {
        ProjectAiSettings settings = new ProjectAiSettings(1L);
        settings.setBraveSearchApiKeyEncrypted(VALUE);

        assertTrue(settings.hasBraveSearchApiKey());
        assertFalse(settings.hasOpenAiApiKey());
        assertFalse(settings.hasClaudeApiKey());

        settings.setOpenAiApiKeyEncrypted(VALUE);
        settings.setClaudeApiKeyEncrypted(VALUE);
        assertTrue(settings.hasOpenAiApiKey());
        assertTrue(settings.hasClaudeApiKey());
    }
}
