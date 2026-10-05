package com.letsblog.project.controller;

import com.letsblog.project.cms.CmsType;
import com.letsblog.project.dto.ProjectCreateRequest;
import com.letsblog.project.dto.ProjectEnvironmentBindRequest;
import com.letsblog.project.dto.ProjectResponse;
import com.letsblog.project.dto.ProjectUpdateRequest;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.dto.SyncEnvironmentRequest;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.service.ProjectEnvironmentSyncService;
import com.letsblog.project.service.ProjectService;
import com.letsblog.project.service.SnsXService;
import java.time.Instant;
import java.util.Set;
import java.util.Optional;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ProjectControllerの回帰テスト(issue #577 stage2、legacy-apiから移設)。 */
@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

    @Mock
    private ProjectService projectService;
    @Mock
    private ProjectEnvironmentSyncService projectEnvironmentSyncService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;
    @Mock
    private SnsXService snsXService;

    private ProjectController controller() {
        return new ProjectController(
                projectService, projectEnvironmentSyncService, adminAuthorizationService, snsXService);
    }

    private ProjectResponse responseWithProductionSite(Long siteId) {
        SiteResponse site = siteId == null ? null : new SiteResponse(siteId, "本番", "prod", CmsType.WORDPRESS,
                "https://example.com", Instant.now(), Instant.now(), null, true, false, null);
        return new ProjectResponse(1L, "テスト", "test", null, null, site, "test", null,
                Instant.now(), Instant.now());
    }

    private ProjectResponse buildResponse() {
        return new ProjectResponse(1L, "テスト", "test", null, null, null, "test", null,
                Instant.now(), Instant.now());
    }

    @Test
    void create_admin権限があれば作成できる() {
        ProjectCreateRequest request = new ProjectCreateRequest("テスト", "test");
        when(projectService.createProject("テスト", "test")).thenReturn(buildResponse());

        ResponseEntity<ProjectResponse> response = controller().create(request);

        assertEquals(201, response.getStatusCode().value());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void create_admin権限がなければForbidden() {
        ProjectCreateRequest request = new ProjectCreateRequest("テスト", "test");
        doThrow(new ForbiddenException("admin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().create(request));
    }

    @Test
    void get_プロジェクトメンバーなら取得できる() {
        // issue #830 以前は認可チェックが無く、認証済みなら誰でも他人のプロジェクト構成を読めた。
        when(projectService.getProject(1L)).thenReturn(buildResponse());

        ProjectResponse response = controller().get(1L);

        assertEquals("test", response.slug());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void get_メンバーでもadminでもなければ拒否しプロジェクトを読まない() {
        doThrow(new ForbiddenException("この操作にはプロジェクトメンバーまたはadmin権限が必要です"))
                .when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> controller().get(1L));

        verify(projectService, never()).getProject(1L);
    }

    @Test
    void list_一覧を返す() {
        when(projectService.listProjects(null, null)).thenReturn(List.of(buildResponse()));

        List<ProjectResponse> response = controller().list(null, null);

        assertEquals(1, response.size());
    }

    @Test
    void delete_admin権限があれば削除できる() {
        ResponseEntity<Void> response = controller().delete(1L);

        assertEquals(204, response.getStatusCode().value());
        verify(projectService).deleteProject(1L);
    }

    @Test
    void bindEnvironment_admin権限があれば紐付できる() {
        ProjectEnvironmentBindRequest request = new ProjectEnvironmentBindRequest("local", 10L);
        when(projectService.bindEnvironment(1L, "local", 10L)).thenReturn(buildResponse());

        controller().bindEnvironment(1L, request);

        verify(adminAuthorizationService).requireAdmin();
        verify(projectService).bindEnvironment(1L, "local", 10L);
    }

    @Test
    void syncEnvironment_admin権限があれば同期を実行する() {
        SyncEnvironmentRequest request = new SyncEnvironmentRequest("test", "local", List.of("db"));

        ResponseEntity<Void> response = controller().syncEnvironment(1L, request);

        assertEquals(204, response.getStatusCode().value());
        verify(projectEnvironmentSyncService).sync(1L, "test", "local", List.of("db"));
    }

    @Test
    void syncEnvironment_admin権限がなければForbidden() {
        SyncEnvironmentRequest request = new SyncEnvironmentRequest("test", "local", List.of("db"));
        doThrow(new ForbiddenException("admin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().syncEnvironment(1L, request));
    }

    @Test
    void update_admin権限があれば更新できる() {
        ProjectUpdateRequest request = new ProjectUpdateRequest("新しい名前");
        when(projectService.updateProject(1L, "新しい名前")).thenReturn(buildResponse());

        controller().update(1L, request);

        verify(adminAuthorizationService).requireAdmin();
        verify(projectService).updateProject(1L, "新しい名前");
    }

    // ---- issue #830: 一覧は操作者が所属するプロジェクトだけ ----

    @Test
    void list_adminは全件を返す() {
        when(adminAuthorizationService.accessibleProjectIds()).thenReturn(Optional.empty());
        when(projectService.listProjects(null, null)).thenReturn(List.of(buildResponse()));

        assertEquals(1, controller().list(null, null).size());
    }

    @Test
    void list_非adminは所属プロジェクトだけに絞られる() {
        // buildResponse() の id は 1。所属が {2} なら 1 は落ちる。
        when(adminAuthorizationService.accessibleProjectIds()).thenReturn(Optional.of(Set.of(2L)));
        when(projectService.listProjects(null, null)).thenReturn(List.of(buildResponse()));

        assertEquals(List.of(), controller().list(null, null));
    }

    @Test
    void list_所属していれば残る() {
        when(adminAuthorizationService.accessibleProjectIds()).thenReturn(Optional.of(Set.of(1L)));
        when(projectService.listProjects(null, null)).thenReturn(List.of(buildResponse()));

        assertEquals(1, controller().list(null, null).size());
    }

    // ---- issue #1574: 本番サイトを変えたら旧サイトの X 接続を消す ----

    @Test
    void bindEnvironment_本番を別のサイトへ変えたら旧サイトを渡して接続の後始末を依頼する() {
        when(projectService.getProject(1L)).thenReturn(responseWithProductionSite(5L));
        when(projectService.bindEnvironment(1L, "production", 10L)).thenReturn(responseWithProductionSite(10L));

        controller().bindEnvironment(1L, new ProjectEnvironmentBindRequest("production", 10L));

        verify(snsXService).onProductionSiteChanged(5L, 10L);
    }

    @Test
    void bindEnvironment_本番が未設定だったときは旧サイト無しで依頼する() {
        when(projectService.getProject(1L)).thenReturn(responseWithProductionSite(null));
        when(projectService.bindEnvironment(1L, "production", 10L)).thenReturn(responseWithProductionSite(10L));

        controller().bindEnvironment(1L, new ProjectEnvironmentBindRequest("production", 10L));

        verify(snsXService).onProductionSiteChanged(null, 10L);
    }

    @Test
    void bindEnvironment_本番以外の環境ではSNSの後始末を依頼しない() {
        when(projectService.bindEnvironment(1L, "test", 10L)).thenReturn(buildResponse());

        controller().bindEnvironment(1L, new ProjectEnvironmentBindRequest("test", 10L));

        verifyNoInteractions(snsXService);
        verify(projectService, never()).getProject(1L);
    }

    @Test
    void bindEnvironment_紐付けに失敗したら後始末を依頼しない() {
        when(projectService.getProject(1L)).thenReturn(responseWithProductionSite(5L));
        when(projectService.bindEnvironment(1L, "production", 10L)).thenThrow(new IllegalArgumentException("重複"));

        assertThrows(IllegalArgumentException.class,
                () -> controller().bindEnvironment(1L, new ProjectEnvironmentBindRequest("production", 10L)));

        verifyNoInteractions(snsXService);
    }

    @Test
    void unbindEnvironment_本番を外したら旧サイトを渡して後始末を依頼する() {
        when(projectService.getProject(1L)).thenReturn(responseWithProductionSite(5L));
        when(projectService.unbindEnvironment(1L, "production")).thenReturn(responseWithProductionSite(null));

        controller().unbindEnvironment(1L, "production");

        verify(adminAuthorizationService).requireAdmin();
        verify(snsXService).onProductionSiteChanged(5L, null);
    }

    @Test
    void unbindEnvironment_本番以外ではSNSの後始末を依頼しない() {
        when(projectService.unbindEnvironment(1L, "local")).thenReturn(buildResponse());

        controller().unbindEnvironment(1L, "local");

        verifyNoInteractions(snsXService);
    }
}
