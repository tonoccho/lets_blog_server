package com.letsblog.api.controller;

import com.letsblog.api.dto.AiImagePromptRequest;
import com.letsblog.api.dto.AiImagePromptResponse;
import com.letsblog.api.dto.PlanChatMessage;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.AiAssistService;
import com.letsblog.api.service.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * generateImagePromptがadminAuthorizationService.requireProjectMemberOrAdmin(projectId)を
 * 呼び出したうえでサービスに委譲すること、認可拒否時にサービスを呼び出さないことを検証する。
 *
 * <p>issue #574でテキスト生成系エンドポイント(draft/ask/tags/proofread/section)はai-serviceへ
 * 移設したため、それらの回帰テストはai-service側のAiControllerTestへ移設した。
 */
@ExtendWith(MockitoExtension.class)
class AiControllerTest {

    @Mock
    private AiAssistService aiAssistService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private AiController controller() {
        return new AiController(aiAssistService, adminAuthorizationService);
    }

    @Test
    void generateImagePrompt_認可後にサービスへ委譲する() {
        AiController controller = controller();
        List<PlanChatMessage> history = List.of(new PlanChatMessage("user", "猫の画像がほしい"));
        when(aiAssistService.generateImagePrompt(1L, history, "もっと可愛くして", null))
                .thenReturn(new AiImagePromptResponse("a cute cat, high quality"));

        AiImagePromptResponse response =
                controller.generateImagePrompt(1L, new AiImagePromptRequest(history, "もっと可愛くして", null));

        assertEquals("a cute cat, high quality", response.prompt());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void generateImagePrompt_認可拒否ならForbiddenでサービスは呼ばれない() {
        AiController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class,
                () -> controller.generateImagePrompt(1L, new AiImagePromptRequest(List.of(), "犬の画像", null)));

        verify(aiAssistService, never()).generateImagePrompt(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }
}
