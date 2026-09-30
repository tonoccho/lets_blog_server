package com.letsblog.ai.controller;

import com.letsblog.ai.dto.AiConnectionResponse;
import com.letsblog.ai.service.AdminAuthorizationService;
import com.letsblog.ai.service.AiConnectionService;
import com.letsblog.ai.service.ForbiddenException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** AiConnectionController(issue #1499)。プロジェクトメンバー判定を通ってからサービスへ委譲する。 */
@ExtendWith(MockitoExtension.class)
class AiConnectionControllerTest {

    @Mock
    private AiConnectionService aiConnectionService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private AiConnectionController controller() {
        return new AiConnectionController(aiConnectionService, adminAuthorizationService);
    }

    @Test
    void listAiConnections_メンバー判定後にサービスへ委譲する() {
        List<AiConnectionResponse> rows = List.of();
        when(aiConnectionService.listConnections(7L)).thenReturn(rows);

        assertEquals(rows, controller().listAiConnections(7L));

        verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
    }

    @Test
    void listAiConnections_メンバーでなければForbiddenでサービスを呼ばない() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        assertThrows(ForbiddenException.class, () -> controller().listAiConnections(7L));

        verifyNoInteractions(aiConnectionService);
    }
}
