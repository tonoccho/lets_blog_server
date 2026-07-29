package com.letsblog.api.controller;

import com.letsblog.api.dto.AddProjectUserRequest;
import com.letsblog.api.dto.ProjectCreateRequest;
import com.letsblog.api.dto.ProjectEnvironmentBindRequest;
import com.letsblog.api.dto.ProjectResponse;
import com.letsblog.api.dto.ProjectUpdateRequest;
import com.letsblog.api.dto.ProjectUserResponse;
import com.letsblog.api.dto.UpdateProjectUserRequest;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.ForbiddenException;
import com.letsblog.api.service.ProjectService;
import com.letsblog.api.service.ProjectUserSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectUserSyncService projectUserSyncService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private ProjectController controller() {
        return new ProjectController(projectService, projectUserSyncService, adminAuthorizationService);
    }

    private ProjectResponse buildResponse() {
        return new ProjectResponse(1L, "テスト", "test", null, null, null, LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void create_admin権限があれば作成できる() {
        ProjectController controller = controller();
        ProjectCreateRequest request = new ProjectCreateRequest("テスト", "test");
        when(projectService.createProject("テスト", "test")).thenReturn(buildResponse());

        ResponseEntity<ProjectResponse> response = controller.create(request);

        assertEquals(201, response.getStatusCode().value());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void create_admin権限がなければForbidden() {
        ProjectController controller = controller();
        ProjectCreateRequest request = new ProjectCreateRequest("テスト", "test");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.create(request));
    }

    @Test
    void get_権限チェックなしで取得できる() {
        ProjectController controller = controller();
        when(projectService.getProject(1L)).thenReturn(buildResponse());

        ProjectResponse response = controller.get(1L);

        assertEquals("test", response.slug());
    }

    @Test
    void update_admin権限があれば更新できる() {
        ProjectController controller = controller();
        ProjectUpdateRequest request = new ProjectUpdateRequest("新しい名前");
        when(projectService.updateProject(1L, "新しい名前")).thenReturn(buildResponse());

        controller.update(1L, request);

        verify(adminAuthorizationService).requireAdmin();
        verify(projectService).updateProject(1L, "新しい名前");
    }

    @Test
    void delete_admin権限があれば削除できる() {
        ProjectController controller = controller();

        ResponseEntity<Void> response = controller.delete(1L);

        assertEquals(204, response.getStatusCode().value());
        verify(projectService).deleteProject(1L);
    }

    @Test
    void bindEnvironment_admin権限があれば紐付できる() {
        ProjectController controller = controller();
        ProjectEnvironmentBindRequest request = new ProjectEnvironmentBindRequest("local", 10L);
        when(projectService.bindEnvironment(1L, "local", 10L)).thenReturn(buildResponse());

        controller.bindEnvironment(1L, request);

        verify(adminAuthorizationService).requireAdmin();
        verify(projectService).bindEnvironment(1L, "local", 10L);
    }

    @Test
    void unbindEnvironment_admin権限があれば切離しできる() {
        ProjectController controller = controller();
        when(projectService.unbindEnvironment(1L, "local")).thenReturn(buildResponse());

        controller.unbindEnvironment(1L, "local");

        verify(adminAuthorizationService).requireAdmin();
        verify(projectService).unbindEnvironment(1L, "local");
    }

    @Test
    void listUsers_admin権限があれば取得できる() {
        ProjectController controller = controller();
        when(projectUserSyncService.getProjectUsers(1L))
                .thenReturn(List.of(new ProjectUserResponse(2L, "user@example.com", "山田太郎", "editor")));

        List<ProjectUserResponse> response = controller.listUsers(1L);

        assertEquals(1, response.size());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void addUser_admin権限があれば追加できる() {
        ProjectController controller = controller();
        AddProjectUserRequest request = new AddProjectUserRequest(2L, "editor");

        ResponseEntity<Void> response = controller.addUser(1L, request);

        assertEquals(201, response.getStatusCode().value());
        verify(adminAuthorizationService).requireAdmin();
        verify(projectUserSyncService).addUserToProject(1L, 2L, "editor");
    }

    @Test
    void addUser_admin権限がなければForbidden() {
        ProjectController controller = controller();
        AddProjectUserRequest request = new AddProjectUserRequest(2L, "editor");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.addUser(1L, request));
    }

    @Test
    void updateUserRole_admin権限があれば変更できる() {
        ProjectController controller = controller();
        UpdateProjectUserRequest request = new UpdateProjectUserRequest("author");

        ResponseEntity<Void> response = controller.updateUserRole(1L, 2L, request);

        assertEquals(204, response.getStatusCode().value());
        verify(projectUserSyncService).updateUserProjectRole(1L, 2L, "author");
    }

    @Test
    void removeUser_admin権限があれば削除できる() {
        ProjectController controller = controller();

        ResponseEntity<Void> response = controller.removeUser(1L, 2L);

        assertEquals(204, response.getStatusCode().value());
        verify(projectUserSyncService).removeUserFromProject(1L, 2L);
    }
}
