package com.letsblog.ai.controller;

import com.letsblog.ai.dto.AiConnectionResponse.Source;
import com.letsblog.ai.dto.ProjectConnectionsResponse;
import com.letsblog.ai.dto.ProjectConnectionsResponse.Entry;
import com.letsblog.ai.dto.UpdateProjectConnectionsRequest;
import com.letsblog.ai.service.AdminAuthorizationService;
import com.letsblog.ai.service.ForbiddenException;
import com.letsblog.ai.service.ProjectConnectionService;
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

/** ProjectConnectionController(issue #1503)。メンバー判定を通ってからサービスへ委譲する。 */
@ExtendWith(MockitoExtension.class)
class ProjectConnectionControllerTest {

    @Mock
    private ProjectConnectionService projectConnectionService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private ProjectConnectionController controller() {
        return new ProjectConnectionController(projectConnectionService, adminAuthorizationService);
    }

    private static ProjectConnectionsResponse sample() {
        return new ProjectConnectionsResponse(
                new Entry(null, "http://o", Source.ENVIRONMENT), new Entry("http://c", "http://c", Source.PROJECT));
    }

    @Test
    void get_メンバー判定後にサービスへ委譲する() {
        when(projectConnectionService.get(7L)).thenReturn(sample());

        assertEquals(sample(), controller().get(7L));

        InOrder order = inOrder(adminAuthorizationService, projectConnectionService);
        order.verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
        order.verify(projectConnectionService).get(7L);
    }

    @Test
    void put_メンバー判定後にサービスへ委譲する() {
        UpdateProjectConnectionsRequest request = new UpdateProjectConnectionsRequest("", "http://c");
        when(projectConnectionService.update(7L, request)).thenReturn(sample());

        assertEquals(sample(), controller().put(7L, request));

        InOrder order = inOrder(adminAuthorizationService, projectConnectionService);
        order.verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
        order.verify(projectConnectionService).update(7L, request);
    }

    @Test
    void 非メンバーはForbiddenでサービスを呼ばない() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        assertThrows(ForbiddenException.class, () -> controller().get(7L));
        assertThrows(ForbiddenException.class,
                () -> controller().put(7L, new UpdateProjectConnectionsRequest("http://x", null)));

        verifyNoInteractions(projectConnectionService);
    }
}
