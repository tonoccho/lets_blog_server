package com.letsblog.platform.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link AiProvider#fromString}。未設定(null / 空白)はnull、前後の空白と大文字小文字は無視、不明な値は理由つきで拒否する。 */
class AiProviderTest {

    @Test
    void 未設定はnullを返す() {
        assertNull(AiProvider.fromString(null));
        assertNull(AiProvider.fromString(""));
        assertNull(AiProvider.fromString("   "));
    }

    @Test
    void 前後の空白と大文字小文字を無視して解釈する() {
        assertEquals(AiProvider.OLLAMA, AiProvider.fromString("ollama"));
        assertEquals(AiProvider.CLAUDE, AiProvider.fromString(" Claude "));
        assertEquals(AiProvider.OPENAI, AiProvider.fromString("OPENAI"));
    }

    @Test
    void 不明な値は理由つきで拒否する() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AiProvider.fromString("gemini"));

        assertEquals("不明なAIプロバイダーです: gemini", e.getMessage());
    }
}
