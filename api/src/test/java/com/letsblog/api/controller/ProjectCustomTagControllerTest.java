package com.letsblog.api.controller;

import com.letsblog.api.dto.CustomTagResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.CustomTagService;
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

@ExtendWith(MockitoExtension.class)
class ProjectCustomTagControllerTest {

    @Mock
    private CustomTagService customTagService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private ProjectCustomTagController controller() {
        return new ProjectCustomTagController(customTagService, adminAuthorizationService);
    }

    @Test
    void list_認可後にプロジェクトスコープのみを返す() {
        ProjectCustomTagController controller = controller();
        when(customTagService.listByProject(5L)).thenReturn(List.of());

        List<CustomTagResponse> response = controller.list(5L);

        assertEquals(0, response.size());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(5L);
        verify(customTagService).listByProject(5L);
    }

    @Test
    void list_認可拒否ならForbidden() {
        ProjectCustomTagController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(5L);

        assertThrows(ForbiddenException.class, () -> controller.list(5L));
        verify(customTagService, never()).listByProject(5L);
    }

    @Test
    void cssBundle_認可後にプロジェクトスコープのCSSを返す() {
        ProjectCustomTagController controller = controller();
        when(customTagService.buildProjectCssBundle(5L)).thenReturn(".alert { color: red; }");

        var response = controller.cssBundle(5L);

        assertEquals(200, response.getStatusCode().value());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(5L);
    }

    @Test
    void cssBundle_認可拒否ならForbidden() {
        ProjectCustomTagController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(5L);

        assertThrows(ForbiddenException.class, () -> controller.cssBundle(5L));
        verify(customTagService, never()).buildProjectCssBundle(5L);
    }
}
