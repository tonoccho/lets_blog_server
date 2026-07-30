package com.letsblog.api.controller;

import com.letsblog.api.dto.ProjectUserSummaryResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.ForbiddenException;
import com.letsblog.api.service.ProjectUserSyncService;
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

@ExtendWith(MockitoExtension.class)
class ProjectUserControllerTest {

    @Mock
    private ProjectUserSyncService projectUserSyncService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private ProjectUserController controller() {
        return new ProjectUserController(projectUserSyncService, adminAuthorizationService);
    }

    @Test
    void listAll_admin権限があれば全ペアを返す() {
        ProjectUserController controller = controller();
        when(projectUserSyncService.listAllProjectUsers()).thenReturn(List.of(
                new ProjectUserSummaryResponse(1L, 2L, "editor"),
                new ProjectUserSummaryResponse(2L, 2L, "contributor")));

        List<ProjectUserSummaryResponse> response = controller.listAll();

        assertEquals(2, response.size());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void listAll_admin権限がなければForbidden() {
        ProjectUserController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, controller::listAll);
    }
}
