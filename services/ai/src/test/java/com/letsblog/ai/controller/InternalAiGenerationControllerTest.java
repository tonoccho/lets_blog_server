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

    @Test
    void generateWithImage_base64を復号してサービスへ委譲する() {
        byte[] data = {1, 2, 3};
        when(aiAssistService.generateWithImageForBridge(1L, "説明して", "image/png", data)).thenReturn("結果");

        InternalAiGenerationController.GenerateResponse response = controller().generateWithImage(
                new InternalAiGenerationController.GenerateWithImageRequest(1L, "説明して", "image/png", "AQID"));

        assertEquals("結果", response.result());
    }

    @Test
    void generateWithImage_vision非対応でサービスがnullを返したらresultもnull() {
        when(aiAssistService.generateWithImageForBridge(1L, "説明して", "image/png", new byte[] {1, 2, 3}))
                .thenReturn(null);

        InternalAiGenerationController.GenerateResponse response = controller().generateWithImage(
                new InternalAiGenerationController.GenerateWithImageRequest(1L, "説明して", "image/png", "AQID"));

        org.junit.jupiter.api.Assertions.assertNull(response.result());
    }

    @Test
    void generateWithImage_base64が不正なら400相当のIllegalArgumentException() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> controller()
                .generateWithImage(new InternalAiGenerationController.GenerateWithImageRequest(
                        1L, "説明して", "image/png", "***not-base64***")));
    }

    @Test
    void generateWithImage_画像が空ならIllegalArgumentException() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> controller()
                .generateWithImage(new InternalAiGenerationController.GenerateWithImageRequest(
                        1L, "説明して", "image/png", "")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> controller()
                .generateWithImage(new InternalAiGenerationController.GenerateWithImageRequest(
                        1L, "説明して", "image/png", null)));
    }
}
