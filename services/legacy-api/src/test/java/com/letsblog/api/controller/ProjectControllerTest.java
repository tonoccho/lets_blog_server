package com.letsblog.api.controller;

import com.letsblog.api.domain.BulkOperationLog;
import com.letsblog.api.domain.BulkOperationSourceType;
import com.letsblog.api.domain.BulkOperationStatus;
import com.letsblog.api.domain.BulkOperationType;
import com.letsblog.api.dto.AddProjectUserRequest;
import com.letsblog.api.dto.ApplyToAllEnvironmentsRequest;
import com.letsblog.api.dto.ApplyToEnvironmentRequest;
import com.letsblog.api.dto.DeleteSlugRequest;
import com.letsblog.api.dto.EditTermRequest;
import com.letsblog.api.dto.PostComparisonPage;
import com.letsblog.api.dto.ProjectResponse;
import com.letsblog.api.dto.ProjectUserResponse;
import com.letsblog.api.dto.ReconcileStateRequest;
import com.letsblog.api.dto.StatusComparisonPage;
import com.letsblog.api.dto.TermComparisonPage;
import com.letsblog.api.dto.TermNameRequest;
import com.letsblog.api.dto.UpdateArticleImageResizeDefaultRequest;
import com.letsblog.api.dto.UpdateImageContentFilterSettingsRequest;
import com.letsblog.api.dto.UpdateImageGenerationPromptDefaultsRequest;
import com.letsblog.api.dto.UpdateImageGenerationSizeDefaultsRequest;
import com.letsblog.api.dto.UpdatePostStatusRequest;
import com.letsblog.api.dto.UpdateProjectCssSelectorPrefixRequest;
import com.letsblog.api.dto.UpdateProjectUserRequest;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.BulkManagementService;
import com.letsblog.api.service.CurrentActorService;
import com.letsblog.api.service.ForbiddenException;
import com.letsblog.api.service.PluginThemeComparisonService;
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

/**
 * issue #577 stage2でプロジェクトのCRUD・環境紐付け・環境同期がproject-serviceへ移設されたことに伴い、
 * それらのコントローラメソッド(create/get/update/delete/bindEnvironment/unbindEnvironment/
 * updateMasterEnvironment/updateGithubRepository/updateCssSelectorPrefix/
 * updateImageGenerationPromptDefaults/updateImageGenerationSizeDefaults/
 * updateArticleImageResizeDefault/updateImageContentFilterSettings/syncEnvironment)に対応する
 * テストは削除した(それらのテストはservices/project側へ移設)。一括管理・プロジェクトユーザー管理の
 * テストのみ残す。
 */
@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectUserSyncService projectUserSyncService;

    @Mock
    private BulkManagementService bulkManagementService;

    @Mock
    private TermComparisonService termComparisonService;

    @Mock
    private PluginThemeComparisonService pluginThemeComparisonService;

    @Mock
    private com.letsblog.api.service.PostComparisonService postComparisonService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    @Mock
    private CurrentActorService currentActorService;

    @Mock
    private com.letsblog.api.ai.MediaGeneratedImageClient mediaGeneratedImageClient;

    private ProjectController controller() {
        return new ProjectController(
                projectService, projectUserSyncService, bulkManagementService,
                termComparisonService, pluginThemeComparisonService, postComparisonService,
                adminAuthorizationService, currentActorService,
                mediaGeneratedImageClient);
    }

    private BulkOperationLog buildLog() {
        BulkOperationLog log = new BulkOperationLog();
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
    void applyBulkOperationToAllEnvironments_admin権限があれば全環境へ実行できる() {
        ProjectController controller = controller();
        ApplyToAllEnvironmentsRequest request =
                new ApplyToAllEnvironmentsRequest(BulkOperationType.PLUGIN_INSTALL, "akismet");
        when(bulkManagementService.applyToAllEnvironments(1L, BulkOperationType.PLUGIN_INSTALL, "akismet", 0L))
                .thenReturn(List.of(buildLog()));

        List<?> response = controller.applyBulkOperationToAllEnvironments(1L, request);

        assertEquals(1, response.size());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void applyBulkOperationToAllEnvironments_admin権限がなければForbidden() {
        ProjectController controller = controller();
        ApplyToAllEnvironmentsRequest request =
                new ApplyToAllEnvironmentsRequest(BulkOperationType.PLUGIN_INSTALL, "akismet");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.applyBulkOperationToAllEnvironments(1L, request));
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
    void uploadAssetImage_admin権限があれば全環境アップロードを実行できる() {
        ProjectController controller = controller();
        when(mediaGeneratedImageClient.fetchImageFile(5L)).thenReturn(new byte[]{1, 2, 3});
        when(bulkManagementService.uploadImageToAllEnvironments(1L, new byte[]{1, 2, 3}, "comfyui-5.png", "image/png", 0L))
                .thenReturn(List.of(buildLog()));

        List<?> response = controller.uploadAssetImage(1L, 5L);

        assertEquals(1, response.size());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void uploadAssetImage_存在しない画像は例外() {
        ProjectController controller = controller();
        when(mediaGeneratedImageClient.fetchImageFile(99L))
                .thenThrow(new com.letsblog.api.ai.AiServiceException("media-serviceの生成画像取得呼び出しに失敗しました", null));

        assertThrows(com.letsblog.api.ai.AiServiceException.class,
                () -> controller.uploadAssetImage(1L, 99L));
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

    // ---- issue #568: 認可マトリクス整備に伴う、requireAdmin()を呼ぶ全メソッドのForbiddenパス網羅 ----

    @Test
    void runBulkOperationUpload_admin権限がなければForbidden() {
        ProjectController controller = controller();
        MockMultipartFile file = new MockMultipartFile("file", "custom-plugin.zip", "application/zip", new byte[]{1, 2, 3});
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller.runBulkOperationUpload(1L, BulkOperationType.PLUGIN_INSTALL, file));
    }

    @Test
    void uploadAssetImage_admin権限がなければForbidden() {
        ProjectController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.uploadAssetImage(1L, 5L));
    }

    @Test
    void tagComparison_admin権限がなければForbidden() {
        ProjectController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.tagComparison(1L, 0));
    }

    @Test
    void syncCategory_admin権限がなければForbidden() {
        ProjectController controller = controller();
        TermNameRequest request = new TermNameRequest("お知らせ");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.syncCategory(1L, request));
    }

    @Test
    void deleteCategoryEverywhere_admin権限がなければForbidden() {
        ProjectController controller = controller();
        TermNameRequest request = new TermNameRequest("お知らせ");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.deleteCategoryEverywhere(1L, request));
    }

    @Test
    void editCategoryAndSync_admin権限がなければForbidden() {
        ProjectController controller = controller();
        EditTermRequest request = new EditTermRequest("old-slug", "新しい名前", "new-slug", null, null);
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.editCategoryAndSync(1L, request));
    }

    @Test
    void syncAllCategoriesToMaster_admin権限がなければForbidden() {
        ProjectController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.syncAllCategoriesToMaster(1L));
    }

    @Test
    void syncTag_admin権限がなければForbidden() {
        ProjectController controller = controller();
        TermNameRequest request = new TermNameRequest("お知らせ");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.syncTag(1L, request));
    }

    @Test
    void deleteTagEverywhere_admin権限がなければForbidden() {
        ProjectController controller = controller();
        TermNameRequest request = new TermNameRequest("お知らせ");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.deleteTagEverywhere(1L, request));
    }

    @Test
    void editTagAndSync_admin権限がなければForbidden() {
        ProjectController controller = controller();
        EditTermRequest request = new EditTermRequest("old-slug", "新しい名前", "new-slug", null, null);
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.editTagAndSync(1L, request));
    }

    @Test
    void syncAllTagsToMaster_admin権限がなければForbidden() {
        ProjectController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.syncAllTagsToMaster(1L));
    }

    @Test
    void reconcilePlugin_admin権限がなければForbidden() {
        ProjectController controller = controller();
        ReconcileStateRequest request = new ReconcileStateRequest(
                "akismet", List.of(new ReconcileStateRequest.StateChangeRequest("local", "ACTIVE")));
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.reconcilePlugin(1L, request));
    }

    @Test
    void deletePluginEverywhere_admin権限がなければForbidden() {
        ProjectController controller = controller();
        DeleteSlugRequest request = new DeleteSlugRequest("akismet");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.deletePluginEverywhere(1L, request));
    }

    @Test
    void themeComparison_admin権限がなければForbidden() {
        ProjectController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.themeComparison(1L, 0));
    }

    @Test
    void reconcileTheme_admin権限がなければForbidden() {
        ProjectController controller = controller();
        ReconcileStateRequest request = new ReconcileStateRequest(
                "twentytwentyfour", List.of(new ReconcileStateRequest.StateChangeRequest("local", "ACTIVE")));
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.reconcileTheme(1L, request));
    }

    @Test
    void deleteThemeEverywhere_admin権限がなければForbidden() {
        ProjectController controller = controller();
        DeleteSlugRequest request = new DeleteSlugRequest("twentytwentyfour");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.deleteThemeEverywhere(1L, request));
    }

    @Test
    void postComparison_admin権限がなければForbidden() {
        ProjectController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.postComparison(1L, "post", 0));
    }

    @Test
    void deletePostEverywhere_admin権限がなければForbidden() {
        ProjectController controller = controller();
        DeleteSlugRequest request = new DeleteSlugRequest("some-post");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.deletePostEverywhere(1L, "post", request));
    }

    @Test
    void updatePostStatusEverywhere_admin権限がなければForbidden() {
        ProjectController controller = controller();
        UpdatePostStatusRequest request = new UpdatePostStatusRequest("some-post", "publish");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.updatePostStatusEverywhere(1L, "post", request));
    }

    @Test
    void listUsers_admin権限がなければForbidden() {
        ProjectController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.listUsers(1L));
    }

    @Test
    void updateUserRole_admin権限がなければForbidden() {
        ProjectController controller = controller();
        UpdateProjectUserRequest request = new UpdateProjectUserRequest("author");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.updateUserRole(1L, 2L, request));
    }

    @Test
    void removeUser_admin権限がなければForbidden() {
        ProjectController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.removeUser(1L, 2L));
    }
}
