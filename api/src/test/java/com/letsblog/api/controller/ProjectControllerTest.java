package com.letsblog.api.controller;

import com.letsblog.api.domain.BulkOperationLog;
import com.letsblog.api.domain.BulkOperationSourceType;
import com.letsblog.api.domain.BulkOperationStatus;
import com.letsblog.api.domain.BulkOperationType;
import com.letsblog.api.dto.AddProjectUserRequest;
import com.letsblog.api.dto.ApplyToEnvironmentRequest;
import com.letsblog.api.dto.DeleteSlugRequest;
import com.letsblog.api.dto.ProjectCreateRequest;
import com.letsblog.api.dto.ProjectEnvironmentBindRequest;
import com.letsblog.api.dto.ProjectResponse;
import com.letsblog.api.dto.ProjectUpdateRequest;
import com.letsblog.api.dto.ProjectUserResponse;
import com.letsblog.api.dto.ReconcileStateRequest;
import com.letsblog.api.dto.ReplayBulkOperationRequest;
import com.letsblog.api.dto.StatusComparisonPage;
import com.letsblog.api.dto.SyncEnvironmentRequest;
import com.letsblog.api.dto.TermComparisonPage;
import com.letsblog.api.dto.TermNameRequest;
import com.letsblog.api.dto.UpdateMasterEnvironmentRequest;
import com.letsblog.api.dto.UpdateProjectUserRequest;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.BulkManagementService;
import com.letsblog.api.service.CurrentActorService;
import com.letsblog.api.service.ForbiddenException;
import com.letsblog.api.service.PluginThemeComparisonService;
import com.letsblog.api.service.ProjectEnvironmentSyncService;
import com.letsblog.api.service.ProjectService;
import com.letsblog.api.service.ProjectUserSyncService;
import com.letsblog.api.service.TermComparisonService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

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
    private ProjectEnvironmentSyncService projectEnvironmentSyncService;

    @Mock
    private BulkManagementService bulkManagementService;

    @Mock
    private TermComparisonService termComparisonService;

    @Mock
    private PluginThemeComparisonService pluginThemeComparisonService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    @Mock
    private CurrentActorService currentActorService;

    private ProjectController controller() {
        return new ProjectController(
                projectService, projectUserSyncService, projectEnvironmentSyncService, bulkManagementService,
                termComparisonService, pluginThemeComparisonService, adminAuthorizationService, currentActorService);
    }

    private BulkOperationLog buildLog() {
        BulkOperationLog log = new BulkOperationLog();
        log.setId(1L);
        log.setProjectId(1L);
        log.setOperationType(BulkOperationType.CATEGORY_CREATE);
        log.setSourceType(BulkOperationSourceType.SLUG);
        log.setValue("お知らせ");
        log.setEnvironment("local");
        log.setStatus(BulkOperationStatus.SUCCESS);
        log.setCreatedAt(LocalDateTime.now());
        return log;
    }

    private ProjectResponse buildResponse() {
        return new ProjectResponse(1L, "テスト", "test", null, null, null, "test", null, LocalDateTime.now(), LocalDateTime.now());
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
    void updateMasterEnvironment_admin権限があれば更新できる() {
        ProjectController controller = controller();
        UpdateMasterEnvironmentRequest request = new UpdateMasterEnvironmentRequest("production");
        when(projectService.updateMasterEnvironment(1L, "production")).thenReturn(buildResponse());

        controller.updateMasterEnvironment(1L, request);

        verify(adminAuthorizationService).requireAdmin();
        verify(projectService).updateMasterEnvironment(1L, "production");
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
    void syncEnvironment_admin権限があれば同期できる() {
        ProjectController controller = controller();
        SyncEnvironmentRequest request = new SyncEnvironmentRequest("local", "test", List.of("themes", "db"));

        ResponseEntity<Void> response = controller.syncEnvironment(1L, request);

        assertEquals(204, response.getStatusCode().value());
        verify(adminAuthorizationService).requireAdmin();
        verify(projectEnvironmentSyncService).sync(1L, "local", "test", List.of("themes", "db"));
    }

    @Test
    void syncEnvironment_admin権限がなければForbidden() {
        ProjectController controller = controller();
        SyncEnvironmentRequest request = new SyncEnvironmentRequest("local", "test", List.of("db"));
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.syncEnvironment(1L, request));
    }

    @Test
    void applyBulkOperation_マスター環境へのカテゴリ作成は実行できる() {
        ProjectController controller = controller();
        ApplyToEnvironmentRequest request = new ApplyToEnvironmentRequest(
                "test", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null);
        when(projectService.getProject(1L)).thenReturn(buildResponse());
        when(bulkManagementService.applyToEnvironment(
                1L, "test", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null, 0L))
                .thenReturn(buildLog());

        controller.applyBulkOperation(1L, request);

        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void applyBulkOperation_マスター環境以外へのカテゴリ作成は例外() {
        ProjectController controller = controller();
        ApplyToEnvironmentRequest request = new ApplyToEnvironmentRequest(
                "production", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null);
        when(projectService.getProject(1L)).thenReturn(buildResponse());

        assertThrows(IllegalArgumentException.class, () -> controller.applyBulkOperation(1L, request));
    }

    @Test
    void applyBulkOperation_admin権限がなければForbidden() {
        ProjectController controller = controller();
        ApplyToEnvironmentRequest request = new ApplyToEnvironmentRequest(
                "test", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null);
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.applyBulkOperation(1L, request));
    }

    @Test
    void runBulkOperationUpload_admin権限があれば実行できる() throws Exception {
        ProjectController controller = controller();
        MockMultipartFile file = new MockMultipartFile("file", "custom-plugin.zip", "application/zip", new byte[]{1, 2, 3});
        when(bulkManagementService.executeFromUpload(1L, BulkOperationType.PLUGIN_INSTALL, file, 0L))
                .thenReturn(List.of(buildLog()));

        List<?> response = controller.runBulkOperationUpload(1L, BulkOperationType.PLUGIN_INSTALL, file);

        assertEquals(1, response.size());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void replayBulkOperations_admin権限があれば実行できる() {
        ProjectController controller = controller();
        ReplayBulkOperationRequest request = new ReplayBulkOperationRequest("local");
        when(bulkManagementService.replay(1L, "local", 0L)).thenReturn(List.of(buildLog()));

        List<?> response = controller.replayBulkOperations(1L, request);

        assertEquals(1, response.size());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void listBulkOperationLogs_admin権限があれば取得できる() {
        ProjectController controller = controller();
        when(bulkManagementService.listLogs(1L)).thenReturn(List.of(buildLog()));

        List<?> response = controller.listBulkOperationLogs(1L, null, null, null);

        assertEquals(1, response.size());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void listBulkOperationLogs_admin権限がなければForbidden() {
        ProjectController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.listBulkOperationLogs(1L, null, null, null));
    }

    @Test
    void categoryComparison_admin権限があれば取得できる() {
        ProjectController controller = controller();
        TermComparisonPage page = new TermComparisonPage(List.of(), 0, 20, 0, "test");
        when(termComparisonService.listCategoryComparison(1L, 0, 20)).thenReturn(page);

        TermComparisonPage response = controller.categoryComparison(1L, 0);

        assertEquals(page, response);
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void categoryComparison_admin権限がなければForbidden() {
        ProjectController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.categoryComparison(1L, 0));
    }

    @Test
    void syncCategory_admin権限があれば実行できる() {
        ProjectController controller = controller();
        TermNameRequest request = new TermNameRequest("お知らせ");
        when(termComparisonService.syncCategory(1L, "お知らせ", 0L)).thenReturn(List.of(buildLog()));

        List<?> response = controller.syncCategory(1L, request);

        assertEquals(1, response.size());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void deleteCategoryEverywhere_admin権限があれば実行できる() {
        ProjectController controller = controller();
        TermNameRequest request = new TermNameRequest("お知らせ");
        when(termComparisonService.deleteCategoryEverywhere(1L, "お知らせ", 0L)).thenReturn(List.of(buildLog()));

        List<?> response = controller.deleteCategoryEverywhere(1L, request);

        assertEquals(1, response.size());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void pluginComparison_admin権限があれば取得できる() {
        ProjectController controller = controller();
        StatusComparisonPage page = new StatusComparisonPage(List.of(), 0, 20, 0, "test");
        when(pluginThemeComparisonService.listPluginComparison(1L, 0, 20)).thenReturn(page);

        StatusComparisonPage response = controller.pluginComparison(1L, 0);

        assertEquals(page, response);
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void pluginComparison_admin権限がなければForbidden() {
        ProjectController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.pluginComparison(1L, 0));
    }

    @Test
    void reconcilePlugin_admin権限があれば実行できる() {
        ProjectController controller = controller();
        ReconcileStateRequest request = new ReconcileStateRequest(
                "akismet", List.of(new ReconcileStateRequest.StateChangeRequest("local", "ACTIVE")));
        when(pluginThemeComparisonService.reconcilePlugin(1L, "akismet", request.changes(), 0L))
                .thenReturn(List.of(buildLog()));

        List<?> response = controller.reconcilePlugin(1L, request);

        assertEquals(1, response.size());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void deletePluginEverywhere_admin権限があれば実行できる() {
        ProjectController controller = controller();
        DeleteSlugRequest request = new DeleteSlugRequest("akismet");
        when(pluginThemeComparisonService.deletePluginEverywhere(1L, "akismet", 0L)).thenReturn(List.of(buildLog()));

        List<?> response = controller.deletePluginEverywhere(1L, request);

        assertEquals(1, response.size());
        verify(adminAuthorizationService).requireAdmin();
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
    void removeUser_admin権限があれば削除できる() {
        ProjectController controller = controller();

        ResponseEntity<Void> response = controller.removeUser(1L, 2L);

        assertEquals(204, response.getStatusCode().value());
        verify(projectUserSyncService).removeUserFromProject(1L, 2L);
    }
}
