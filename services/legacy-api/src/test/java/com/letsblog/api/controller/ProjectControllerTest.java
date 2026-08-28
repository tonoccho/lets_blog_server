package com.letsblog.api.controller;

import com.letsblog.api.dto.AddProjectUserRequest;
import com.letsblog.api.dto.ProjectResponse;
import com.letsblog.api.dto.ProjectUserResponse;
import com.letsblog.api.dto.UpdateArticleImageResizeDefaultRequest;
import com.letsblog.api.dto.UpdateImageContentFilterSettingsRequest;
import com.letsblog.api.dto.UpdateImageGenerationPromptDefaultsRequest;
import com.letsblog.api.dto.UpdateImageGenerationSizeDefaultsRequest;
import com.letsblog.api.dto.UpdateProjectCssSelectorPrefixRequest;
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

/**
 * issue #577 stage2でプロジェクトのCRUD・環境紐付け・環境同期がproject-serviceへ移設されたことに伴い、
 * それらのコントローラメソッド(create/get/update/delete/bindEnvironment/unbindEnvironment/
 * updateMasterEnvironment/updateGithubRepository/updateCssSelectorPrefix/
 * updateImageGenerationPromptDefaults/updateImageGenerationSizeDefaults/
 * updateArticleImageResizeDefault/updateImageContentFilterSettings/syncEnvironment)に対応する
 * テストは削除した(それらのテストはservices/project側へ移設)。一括管理(bulk-management)・
 * アセット画像アップロードのテストは、それらのエンドポイント自体がpublishing-serviceへ移設された
 * ことに伴い削除した(services/publishing側のBulkManagementControllerTestへ移設。issue #708)。
 * css-selector-prefix・画像生成デフォルト設定・プロジェクトユーザー管理のテストのみ残す。
 */
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
        return new ProjectResponse(
                1L, "テスト", "test", null, null, null, "test", null, null, null, null, null, null, null,
                null, null, null,
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void updateCssSelectorPrefix_admin権限があれば更新できる() {
        ProjectController controller = controller();
        UpdateProjectCssSelectorPrefixRequest request = new UpdateProjectCssSelectorPrefixRequest("prefix");
        when(projectService.updateCssSelectorPrefix(1L, request)).thenReturn(buildResponse());

        controller.updateCssSelectorPrefix(1L, request);

        verify(adminAuthorizationService).requireAdmin();
        verify(projectService).updateCssSelectorPrefix(1L, request);
    }

    @Test
    void updateCssSelectorPrefix_admin権限がなければForbidden() {
        ProjectController controller = controller();
        UpdateProjectCssSelectorPrefixRequest request = new UpdateProjectCssSelectorPrefixRequest("prefix");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.updateCssSelectorPrefix(1L, request));
    }

    @Test
    void updateImageGenerationPromptDefaults_admin権限があれば更新できる() {
        ProjectController controller = controller();
        UpdateImageGenerationPromptDefaultsRequest request =
                new UpdateImageGenerationPromptDefaultsRequest("negative", "quality");
        when(projectService.updateImageGenerationPromptDefaults(1L, request)).thenReturn(buildResponse());

        controller.updateImageGenerationPromptDefaults(1L, request);

        verify(adminAuthorizationService).requireAdmin();
        verify(projectService).updateImageGenerationPromptDefaults(1L, request);
    }

    @Test
    void updateImageGenerationPromptDefaults_admin権限がなければForbidden() {
        ProjectController controller = controller();
        UpdateImageGenerationPromptDefaultsRequest request =
                new UpdateImageGenerationPromptDefaultsRequest("negative", "quality");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller.updateImageGenerationPromptDefaults(1L, request));
    }

    @Test
    void updateImageGenerationSizeDefaults_admin権限があれば更新できる() {
        ProjectController controller = controller();
        UpdateImageGenerationSizeDefaultsRequest request =
                new UpdateImageGenerationSizeDefaultsRequest(512, 512);
        when(projectService.updateImageGenerationSizeDefaults(1L, request)).thenReturn(buildResponse());

        controller.updateImageGenerationSizeDefaults(1L, request);

        verify(adminAuthorizationService).requireAdmin();
        verify(projectService).updateImageGenerationSizeDefaults(1L, request);
    }

    @Test
    void updateImageGenerationSizeDefaults_admin権限がなければForbidden() {
        ProjectController controller = controller();
        UpdateImageGenerationSizeDefaultsRequest request =
                new UpdateImageGenerationSizeDefaultsRequest(512, 512);
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller.updateImageGenerationSizeDefaults(1L, request));
    }

    @Test
    void updateArticleImageResizeDefault_admin権限があれば更新できる() {
        ProjectController controller = controller();
        UpdateArticleImageResizeDefaultRequest request = new UpdateArticleImageResizeDefaultRequest(1200);
        when(projectService.updateArticleImageResizeDefault(1L, request)).thenReturn(buildResponse());

        controller.updateArticleImageResizeDefault(1L, request);

        verify(adminAuthorizationService).requireAdmin();
        verify(projectService).updateArticleImageResizeDefault(1L, request);
    }

    @Test
    void updateArticleImageResizeDefault_admin権限がなければForbidden() {
        ProjectController controller = controller();
        UpdateArticleImageResizeDefaultRequest request = new UpdateArticleImageResizeDefaultRequest(1200);
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller.updateArticleImageResizeDefault(1L, request));
    }

    @Test
    void updateImageContentFilterSettings_admin権限があれば更新できる() {
        ProjectController controller = controller();
        UpdateImageContentFilterSettingsRequest request =
                new UpdateImageContentFilterSettingsRequest(false, true, true);
        when(projectService.updateImageContentFilterSettings(1L, request)).thenReturn(buildResponse());

        controller.updateImageContentFilterSettings(1L, request);

        verify(adminAuthorizationService).requireAdmin();
        verify(projectService).updateImageContentFilterSettings(1L, request);
    }

    @Test
    void updateImageContentFilterSettings_admin権限がなければForbidden() {
        ProjectController controller = controller();
        UpdateImageContentFilterSettingsRequest request =
                new UpdateImageContentFilterSettingsRequest(false, true, true);
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller.updateImageContentFilterSettings(1L, request));
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
    void listUsers_admin権限がなければForbidden() {
        ProjectController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.listUsers(1L));
    }

    @Test
    void addUser_admin権限があれば追加できる() {
        ProjectController controller = controller();
        AddProjectUserRequest request = new AddProjectUserRequest(2L, "editor");

        ResponseEntity<Void> response = controller.addUser(1L, request);

        assertEquals(204, response.getStatusCode().value());
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
    void updateUserRole_admin権限がなければForbidden() {
        ProjectController controller = controller();
        UpdateProjectUserRequest request = new UpdateProjectUserRequest("author");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.updateUserRole(1L, 2L, request));
    }

    @Test
    void removeUser_admin権限があれば削除できる() {
        ProjectController controller = controller();

        ResponseEntity<Void> response = controller.removeUser(1L, 2L);

        assertEquals(204, response.getStatusCode().value());
        verify(projectUserSyncService).removeUserFromProject(1L, 2L);
    }

    @Test
    void removeUser_admin権限がなければForbidden() {
        ProjectController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.removeUser(1L, 2L));
    }
}
