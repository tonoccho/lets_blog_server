package com.letsblog.ai.controller;

import com.letsblog.ai.dto.AiProofreadRequest;
import com.letsblog.ai.dto.AiProofreadResponse;
import com.letsblog.ai.dto.ProofreadIssue;
import com.letsblog.ai.service.AiAssistService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * AiControllerの回帰テスト(issue #574)。legacy-apiに残った画像生成関連エンドポイント
 * (generateImagePrompt/image/image-options)を除き、テキスト生成系エンドポイントがAiAssistServiceへ
 * 委譲することを検証する。
 */
@ExtendWith(MockitoExtension.class)
class AiControllerTest {

    @Mock
    private AiAssistService aiAssistService;

    private AiController controller() {
        return new AiController(aiAssistService);
    }

    @Test
    void proofread_サービスへ委譲する() {
        AiController controller = controller();
        AiProofreadRequest request = new AiProofreadRequest("記事本文", null);
        AiProofreadResponse expected = new AiProofreadResponse(
                List.of(new ProofreadIssue("typo", "誤字", "指摘内容", "修正案")));
        when(aiAssistService.proofreadContent(request)).thenReturn(expected);

        AiProofreadResponse response = controller.proofread(request);

        assertEquals(expected, response);
    }
}
