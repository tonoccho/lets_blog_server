package com.letsblog.ai.controller;

import com.letsblog.ai.dto.LlmModelListResponse;
import com.letsblog.ai.dto.LlmProviderListResponse;
import com.letsblog.ai.dto.SelectLlmModelRequest;
import com.letsblog.ai.dto.SelectLlmProviderRequest;
import com.letsblog.ai.service.AdminAuthorizationService;
import com.letsblog.ai.service.ForbiddenException;
import com.letsblog.ai.service.LlmModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectLlmModelControllerの回帰テスト(issue #574、legacy-apiのProjectAiModelControllerから
 * {@code /llm/**}部分を分割したもの)。requireAdmin()を呼び出したうえでサービスへ委譲すること、
 * 認可拒否時にサービスを呼び出さないことを検証する。
 */
@ExtendWith(MockitoExtension.class)
class ProjectLlmModelControllerTest {

    @Mock
    private LlmModelService llmModelService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private ProjectLlmModelController controller() {
        return new ProjectLlmModelController(llmModelService, adminAuthorizationService);
    }

    @Test
    void listLlmModels_認可後にサービスへ委譲する() {
        ProjectLlmModelController controller = controller();
        when(llmModelService.listModelsForProject(1L))
                .thenReturn(new LlmModelListResponse(List.of("gpt-4o-mini"), "gpt-4o-mini"));

        LlmModelListResponse response = controller.listLlmModels(1L);

        assertEquals("gpt-4o-mini", response.selected());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void listLlmModels_認可拒否ならForbidden() {
        ProjectLlmModelController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.listLlmModels(1L));
    }

    @Test
    void selectLlmModel_認可後にサービスへ委譲する() {
        ProjectLlmModelController controller = controller();
        when(llmModelService.selectModel(1L, "gpt-4o"))
                .thenReturn(new LlmModelListResponse(List.of("gpt-4o"), "gpt-4o"));

        LlmModelListResponse response = controller.selectLlmModel(1L, new SelectLlmModelRequest("gpt-4o"));

        assertEquals("gpt-4o", response.selected());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void listLlmProvider_認可後にサービスへ委譲する() {
        ProjectLlmModelController controller = controller();
        when(llmModelService.listProvidersForProject(1L))
                .thenReturn(new LlmProviderListResponse(List.of("OLLAMA", "OPENAI", "CLAUDE"), null));

        LlmProviderListResponse response = controller.listLlmProvider(1L);

        assertEquals(3, response.availableProviders().size());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void selectLlmProvider_認可後にサービスへ委譲する() {
        ProjectLlmModelController controller = controller();
        when(llmModelService.selectProvider(1L, "CLAUDE"))
                .thenReturn(new LlmProviderListResponse(List.of("OLLAMA", "OPENAI", "CLAUDE"), "CLAUDE"));

        LlmProviderListResponse response = controller.selectLlmProvider(1L, new SelectLlmProviderRequest("CLAUDE"));

        assertEquals("CLAUDE", response.selected());
        verify(adminAuthorizationService).requireAdmin();
    }
}
