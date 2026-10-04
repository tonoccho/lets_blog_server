package com.letsblog.ai.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1600: プロバイダ/モデルが画像入力(vision)に対応しているかの判定。
 * 判定はモデル名の許可リストで行い、リストに無いモデルは非対応として扱う(タグ付けを省略する)。
 */
class VisionSupportTest {

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4o", "gpt-4o-mini", "GPT-4O", "chatgpt-4o-latest", "gpt-4.1", "gpt-4.1-nano",
            "gpt-4.5-preview", "gpt-5", "gpt-4-turbo", "gpt-4-vision-preview", "o1", "o3", "o4-mini"})
    void OPENAIの画像対応モデル(String model) {
        assertTrue(VisionSupport.supports(AiProvider.OPENAI, model), model);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-3.5-turbo", "gpt-4", "o1-mini", "o1-preview", "o3-mini", "text-embedding-3-small"})
    void OPENAIの画像非対応モデル(String model) {
        assertFalse(VisionSupport.supports(AiProvider.OPENAI, model), model);
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022", "claude-sonnet-4-5",
            "Claude-Opus-4"})
    void CLAUDEの画像対応モデル(String model) {
        assertTrue(VisionSupport.supports(AiProvider.CLAUDE, model), model);
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-2.1", "claude-instant-1.2", "claude-1.3", "gpt-4o", "something"})
    void CLAUDEの画像非対応モデル(String model) {
        assertFalse(VisionSupport.supports(AiProvider.CLAUDE, model), model);
    }

    @ParameterizedTest
    @ValueSource(strings = {"llava:7b", "llama3.2-vision:11b", "minicpm-v:8b", "moondream", "gemma3:4b",
            "qwen2.5vl:7b", "qwen2-vl:7b", "qwen3-vl:8b", "llama4:scout", "mistral-small3.1:24b",
            "granite3.2-vision"})
    void OLLAMAの画像対応モデル(String model) {
        assertTrue(VisionSupport.supports(AiProvider.OLLAMA, model), model);
    }

    @ParameterizedTest
    @ValueSource(strings = {"qwen2.5:7b-instruct", "llama3.1:8b", "gemma3:1b", "gemma3:1b-it-qat", "qwen3:8b"})
    void OLLAMAの画像非対応モデル(String model) {
        assertFalse(VisionSupport.supports(AiProvider.OLLAMA, model), model);
    }

    @Test
    void モデル名もプロバイダも未指定なら非対応() {
        assertFalse(VisionSupport.supports(AiProvider.OPENAI, null));
        assertFalse(VisionSupport.supports(AiProvider.OPENAI, "  "));
        assertFalse(VisionSupport.supports(null, "gpt-4o"));
    }
}
