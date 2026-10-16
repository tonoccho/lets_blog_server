package com.letsblog.ai.controller;

import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.testsupport.AiWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * legacy-apiに残った画像生成コード(AiAssistService#generateImagePrompt等)からのLLMテキスト生成
 * ブリッジ呼び出しを受けるInternalAiGenerationControllerの回帰テスト(issue #574)。
 *
 * <p>issue #1824: AiAssistServiceは実物で、LLMはHTTPフェイク、DBはメモリ上のリポジトリで差し替える。
 */
class InternalAiGenerationControllerTest {

    private AiWorld world;
    private InternalAiGenerationController controller;

    @BeforeEach
    void setUp() {
        world = new AiWorld().ollamaDefault("qwen2.5:7b-instruct");
        controller = new InternalAiGenerationController(world.aiAssistService);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void generate_サービスへ委譲する() {
        world.projectAiSettingsService.setLlmProvider(1L, "OPENAI");
        world.projectAiSettingsService.setOpenAiApiKeyEncrypted(1L, world.credentialCipher.encrypt("sk-project"));
        world.llmConfig(AiProvider.OPENAI, null, "", "gpt-default", List.of("gpt-default"), false);
        world.llmReplies("結果");

        InternalAiGenerationController.GenerateResponse response = controller
                .generate(new InternalAiGenerationController.GenerateRequest(1L, "プロンプト", "OPENAI"));

        assertEquals("結果", response.result());
        assertEquals("Bearer sk-project", world.llmRequests().get(0).header("Authorization"));
        assertEquals(List.of("プロンプト"), world.llmPrompts());
    }

    @Test
    void generateWithImage_base64を復号してサービスへ委譲する() {
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.OLLAMA, "llava:7b");
        world.llmReplies("結果");

        InternalAiGenerationController.GenerateResponse response = controller.generateWithImage(
                new InternalAiGenerationController.GenerateWithImageRequest(1L, "説明して", "image/png", "AQID"));

        assertEquals("結果", response.result());
        // base64 "AQID" は {1, 2, 3}。復号したバイト列が画像として再びbase64でLLMへ渡る。
        assertTrue(world.llmRequests().get(0).bodyText().contains("data:image/png;base64,AQID"));
    }

    @Test
    void generateWithImage_vision非対応でサービスがnullを返したらresultもnull() {
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.OLLAMA, "qwen2.5:7b-instruct");

        InternalAiGenerationController.GenerateResponse response = controller.generateWithImage(
                new InternalAiGenerationController.GenerateWithImageRequest(1L, "説明して", "image/png", "AQID"));

        assertNull(response.result());
        assertTrue(world.llmRequests().isEmpty());
    }

    @Test
    void generateWithImage_base64が不正なら400相当のIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> controller
                .generateWithImage(new InternalAiGenerationController.GenerateWithImageRequest(
                        1L, "説明して", "image/png", "***not-base64***")));
        assertTrue(world.llmRequests().isEmpty());
    }

    @Test
    void generateWithImage_画像が空ならIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> controller
                .generateWithImage(new InternalAiGenerationController.GenerateWithImageRequest(
                        1L, "説明して", "image/png", "")));
        assertThrows(IllegalArgumentException.class, () -> controller
                .generateWithImage(new InternalAiGenerationController.GenerateWithImageRequest(
                        1L, "説明して", "image/png", null)));
        assertTrue(world.llmRequests().isEmpty());
    }
}
