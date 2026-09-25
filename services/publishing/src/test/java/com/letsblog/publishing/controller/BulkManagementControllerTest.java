package com.letsblog.publishing.controller;

import com.letsblog.publishing.client.MediaGeneratedImageClient;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationSourceType;
import com.letsblog.publishing.domain.BulkOperationStatus;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.domain.Project;
import com.letsblog.publishing.dto.ApplyToAllEnvironmentsRequest;
import com.letsblog.publishing.dto.ApplyToEnvironmentRequest;
import com.letsblog.publishing.dto.DeleteSlugRequest;
import com.letsblog.publishing.dto.EditTermRequest;
import com.letsblog.publishing.dto.ReconcileStateRequest;
import com.letsblog.publishing.dto.StatusComparisonPage;
import com.letsblog.publishing.dto.TermComparisonPage;
import com.letsblog.publishing.dto.TermNameRequest;
import com.letsblog.publishing.dto.UpdatePostStatusRequest;
import com.letsblog.publishing.render.MediaRenderException;
import com.letsblog.publishing.service.AdminAuthorizationService;
import com.letsblog.publishing.service.BulkManagementService;
import com.letsblog.publishing.service.CurrentActorService;
import com.letsblog.publishing.service.ForbiddenException;
import com.letsblog.publishing.service.PluginThemeComparisonService;
import com.letsblog.publishing.service.PostComparisonService;
import com.letsblog.publishing.service.ProjectService;
import com.letsblog.publishing.service.TermComparisonService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * legacy-apiの{@code ProjectControllerTest}のうち、publishing-serviceへ移設した一括管理サブリソース
 * (BulkManagementController)相当のテストを移設したもの(issue #708)。プロジェクトのCRUD・
 * ユーザー管理・css-selector-prefix等は移設していないため、それらのテストは含めない。
 */
@ExtendWith(MockitoExtension.class)
class BulkManagementControllerTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private BulkManagementService bulkManagementService;

    @Mock
    private TermComparisonService termComparisonService;

    @Mock
    private PluginThemeComparisonService pluginThemeComparisonService;

    @Mock
    private PostComparisonService postComparisonService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    @Mock
    private CurrentActorService currentActorService;

    @Mock
    private MediaGeneratedImageClient mediaGeneratedImageClient;

    private BulkManagementController controller() {
        return new BulkManagementController(
                projectService, bulkManagementService, termComparisonService, pluginThemeComparisonService,
                postComparisonService, adminAuthorizationService, currentActorService, mediaGeneratedImageClient);
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

    private Project buildProject(String masterEnvironment) {
        Project project = new Project();
        project.setId(1L);
        project.setMasterEnvironment(masterEnvironment);
        return project;
    }

    @Test
    void applyBulkOperation_マスター環境へのカテゴリ作成は実行できる() {
        BulkManagementController controller = controller();
        ApplyToEnvironmentRequest request = new ApplyToEnvironmentRequest(
                "test", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null);
        when(projectService.getProjectEntity(1L)).thenReturn(buildProject("test"));
        when(bulkManagementService.applyToEnvironment(
                1L, "test", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null, 0L))
                .thenReturn(buildLog());

        controller.applyBulkOperation(1L, request);

        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void applyBulkOperation_マスター環境以外へのカテゴリ作成は例外() {
        BulkManagementController controller = controller();
        ApplyToEnvironmentRequest request = new ApplyToEnvironmentRequest(
                "production", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null);
        when(projectService.getProjectEntity(1L)).thenReturn(buildProject("test"));

        assertThrows(IllegalArgumentException.class, () -> controller.applyBulkOperation(1L, request));
    }

    @Test
    void applyBulkOperation_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        ApplyToEnvironmentRequest request = new ApplyToEnvironmentRequest(
                "test", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null);
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.applyBulkOperation(1L, request));
    }

    @Test
    void applyBulkOperationToAllEnvironments_admin権限があれば全環境へ実行できる() {
        BulkManagementController controller = controller();
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
        BulkManagementController controller = controller();
        ApplyToAllEnvironmentsRequest request =
                new ApplyToAllEnvironmentsRequest(BulkOperationType.PLUGIN_INSTALL, "akismet");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.applyBulkOperationToAllEnvironments(1L, request));
    }

    @Test
    void runBulkOperationUpload_admin権限があれば実行できる() throws Exception {
        BulkManagementController controller = controller();
        MockMultipartFile file = new MockMultipartFile("file", "custom-plugin.zip", "application/zip", new byte[]{1, 2, 3});
        when(bulkManagementService.executeFromUpload(1L, BulkOperationType.PLUGIN_INSTALL, file, 0L))
                .thenReturn(List.of(buildLog()));

        List<?> response = controller.runBulkOperationUpload(1L, BulkOperationType.PLUGIN_INSTALL, file);

        assertEquals(1, response.size());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void runBulkOperationUpload_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        MockMultipartFile file = new MockMultipartFile("file", "custom-plugin.zip", "application/zip", new byte[]{1, 2, 3});
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller.runBulkOperationUpload(1L, BulkOperationType.PLUGIN_INSTALL, file));
    }

    @Test
    void uploadAssetImage_admin権限があれば全環境アップロードを実行できる() {
        BulkManagementController controller = controller();
        when(mediaGeneratedImageClient.fetchImageFile(5L)).thenReturn(new byte[]{1, 2, 3});
        when(bulkManagementService.uploadImageToAllEnvironments(1L, new byte[]{1, 2, 3}, "comfyui-5.png", "image/png", 0L))
                .thenReturn(List.of(buildLog()));

        List<?> response = controller.uploadAssetImage(1L, 5L);

        assertEquals(1, response.size());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void uploadAssetImage_存在しない画像は例外() {
        BulkManagementController controller = controller();
        when(mediaGeneratedImageClient.fetchImageFile(99L))
                .thenThrow(new MediaRenderException("media-serviceの生成画像取得呼び出しに失敗しました", null));

        assertThrows(MediaRenderException.class, () -> controller.uploadAssetImage(1L, 99L));
    }

    @Test
    void uploadAssetImage_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.uploadAssetImage(1L, 5L));
    }

    @Test
    void categoryComparison_admin権限があれば取得できる() {
        BulkManagementController controller = controller();
        TermComparisonPage page = new TermComparisonPage(List.of(), 0, 20, 0, "test");
        when(termComparisonService.listCategoryComparison(1L, 0, 20)).thenReturn(page);

        TermComparisonPage response = controller.categoryComparison(1L, 0);

        assertEquals(page, response);
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void categoryComparison_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.categoryComparison(1L, 0));
    }

    @Test
    void tagComparison_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.tagComparison(1L, 0));
    }

    @Test
    void syncCategory_admin権限があれば実行できる() {
        BulkManagementController controller = controller();
        TermNameRequest request = new TermNameRequest("お知らせ");
        when(termComparisonService.syncCategory(1L, "お知らせ", 0L)).thenReturn(List.of(buildLog()));

        List<?> response = controller.syncCategory(1L, request);

        assertEquals(1, response.size());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void syncCategory_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        TermNameRequest request = new TermNameRequest("お知らせ");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.syncCategory(1L, request));
    }

    @Test
    void deleteCategoryEverywhere_admin権限があれば実行できる() {
        BulkManagementController controller = controller();
        TermNameRequest request = new TermNameRequest("お知らせ");
        when(termComparisonService.deleteCategoryEverywhere(1L, "お知らせ", 0L)).thenReturn(List.of(buildLog()));

        List<?> response = controller.deleteCategoryEverywhere(1L, request);

        assertEquals(1, response.size());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void deleteCategoryEverywhere_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        TermNameRequest request = new TermNameRequest("お知らせ");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.deleteCategoryEverywhere(1L, request));
    }

    @Test
    void editCategoryAndSync_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        EditTermRequest request = new EditTermRequest("old-slug", "新しい名前", "new-slug", null, null);
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.editCategoryAndSync(1L, request));
    }

    @Test
    void syncAllCategoriesToMaster_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.syncAllCategoriesToMaster(1L));
    }

    @Test
    void syncTag_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        TermNameRequest request = new TermNameRequest("お知らせ");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.syncTag(1L, request));
    }

    @Test
    void deleteTagEverywhere_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        TermNameRequest request = new TermNameRequest("お知らせ");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.deleteTagEverywhere(1L, request));
    }

    @Test
    void editTagAndSync_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        EditTermRequest request = new EditTermRequest("old-slug", "新しい名前", "new-slug", null, null);
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.editTagAndSync(1L, request));
    }

    @Test
    void syncAllTagsToMaster_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.syncAllTagsToMaster(1L));
    }

    @Test
    void pluginComparison_admin権限があれば取得できる() {
        BulkManagementController controller = controller();
        StatusComparisonPage page = new StatusComparisonPage(List.of(), 0, 20, 0, "test");
        when(pluginThemeComparisonService.listPluginComparison(1L, 0, 20)).thenReturn(page);

        StatusComparisonPage response = controller.pluginComparison(1L, 0);

        assertEquals(page, response);
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void pluginComparison_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.pluginComparison(1L, 0));
    }

    @Test
    void themeComparison_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.themeComparison(1L, 0));
    }

    @Test
    void reconcilePlugin_admin権限があれば実行できる() {
        BulkManagementController controller = controller();
        ReconcileStateRequest request = new ReconcileStateRequest(
                "akismet", List.of(new ReconcileStateRequest.StateChangeRequest("local", "ACTIVE")));
        when(pluginThemeComparisonService.reconcilePlugin(1L, "akismet", request.changes(), 0L))
                .thenReturn(List.of(buildLog()));

        List<?> response = controller.reconcilePlugin(1L, request);

        assertEquals(1, response.size());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void reconcilePlugin_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        ReconcileStateRequest request = new ReconcileStateRequest(
                "akismet", List.of(new ReconcileStateRequest.StateChangeRequest("local", "ACTIVE")));
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.reconcilePlugin(1L, request));
    }

    @Test
    void reconcileTheme_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        ReconcileStateRequest request = new ReconcileStateRequest(
                "twentytwentyfour", List.of(new ReconcileStateRequest.StateChangeRequest("local", "ACTIVE")));
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.reconcileTheme(1L, request));
    }

    @Test
    void deletePluginEverywhere_admin権限があれば実行できる() {
        BulkManagementController controller = controller();
        DeleteSlugRequest request = new DeleteSlugRequest("akismet");
        when(pluginThemeComparisonService.deletePluginEverywhere(1L, "akismet", 0L)).thenReturn(List.of(buildLog()));

        List<?> response = controller.deletePluginEverywhere(1L, request);

        assertEquals(1, response.size());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void deletePluginEverywhere_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        DeleteSlugRequest request = new DeleteSlugRequest("akismet");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.deletePluginEverywhere(1L, request));
    }

    @Test
    void deleteThemeEverywhere_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        DeleteSlugRequest request = new DeleteSlugRequest("twentytwentyfour");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.deleteThemeEverywhere(1L, request));
    }

    @Test
    void postComparison_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.postComparison(1L, "post", 0));
    }

    @Test
    void deletePostEverywhere_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        DeleteSlugRequest request = new DeleteSlugRequest("some-post");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.deletePostEverywhere(1L, "post", request));
    }

    @Test
    void updatePostStatusEverywhere_admin権限がなければForbidden() {
        BulkManagementController controller = controller();
        UpdatePostStatusRequest request = new UpdatePostStatusRequest("some-post", "publish");
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.updatePostStatusEverywhere(1L, "post", request));
    }
}
