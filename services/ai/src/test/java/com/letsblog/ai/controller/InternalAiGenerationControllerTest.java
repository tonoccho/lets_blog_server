package com.letsblog.ai.controller;

import com.letsblog.ai.service.AiAssistService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * legacy-apiに残った画像生成コード(AiAssistService#generateImagePrompt等)からのLLMテキスト生成
 * ブリッジ呼び出しを受けるInternalAiGenerationControllerの回帰テスト(issue #574)。
 */
@ExtendWith(MockitoExtension.class)
class InternalAiGenerationControllerTest {

    @Mock
    private AiAssistService aiAssistService;

    private InternalAiGenerationController controller() {
        return new InternalAiGenerationController(aiAssistService);
    }

    @Test
    void generate_サービスへ委譲する() {
        when(aiAssistService.generateForBridge(1L, "プロンプト", "OPENAI")).thenReturn("結果");

        InternalAiGenerationController.GenerateResponse response = controller()
                .generate(new InternalAiGenerationController.GenerateRequest(1L, "プロンプト", "OPENAI"));

        assertEquals("結果", response.result());
    }
}
