package com.letsblog.ai.controller;

import com.letsblog.ai.dto.PullOllamaModelRequest;
import com.letsblog.ai.dto.PullOllamaModelResponse;
import com.letsblog.ai.service.AdminAuthorizationService;
import com.letsblog.ai.service.ForbiddenException;
import com.letsblog.ai.service.OllamaPullService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** OllamaPullController(issue #1675)。管理者だけが開始でき、判定を通ってからサービスへ委譲する。 */
@ExtendWith(MockitoExtension.class)
class OllamaPullControllerTest {

    @Mock
    private OllamaPullService ollamaPullService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private OllamaPullController controller() {
        return new OllamaPullController(ollamaPullService, adminAuthorizationService);
    }

    @Test
    void pull_管理者判定後にサービスへ委譲する() {
        when(ollamaPullService.start(7L, "qwen2.5:7b")).thenReturn(new PullOllamaModelResponse(11L, false));

        PullOllamaModelResponse response = controller().pull(7L, new PullOllamaModelRequest("qwen2.5:7b"));

        assertEquals(new PullOllamaModelResponse(11L, false), response);
        InOrder order = inOrder(adminAuthorizationService, ollamaPullService);
        order.verify(adminAuthorizationService).requireAdmin();
        order.verify(ollamaPullService).start(7L, "qwen2.5:7b");
    }

    @Test
    void pull_管理者でなければ403でサービスを呼ばない() {
        doThrow(new ForbiddenException("admin only")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().pull(7L, new PullOllamaModelRequest("llama3")));

        verifyNoInteractions(ollamaPullService);
    }
}
