package com.letsblog.project.controller;

import com.letsblog.project.cms.CmsType;
import com.letsblog.project.crypto.SshKeyGenerationService;
import com.letsblog.project.dto.SiteConnectionCheckResult;
import com.letsblog.project.dto.SiteRegisterRequest;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.service.ProvisioningService;
import com.letsblog.project.service.ProjectService;
import com.letsblog.project.service.SiteService;
import com.letsblog.project.service.WordPressSiteProvisioningService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** SiteControllerの回帰テスト(issue #577 stage2、legacy-apiから移設)。 */
@ExtendWith(MockitoExtension.class)
class SiteControllerTest {

    @Mock
    private SiteService siteService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;
    @Mock
    private WordPressSiteProvisioningService wordPressSiteProvisioningService;
    @Mock
    private SshKeyGenerationService sshKeyGenerationService;
    @Mock
    private ProjectService projectService;

    private SiteController controller() {
        return new SiteController(
                siteService, adminAuthorizationService, wordPressSiteProvisioningService, sshKeyGenerationService,
                projectService);
    }

    private SiteResponse buildResponse() {
        return new SiteResponse(1L, "Name", "my-site", CmsType.WORDPRESS, "https://example.com",
                LocalDateTime.now(), LocalDateTime.now(), "SUCCESS", false, false);
    }

    @Test
    void register_登録できる() {
        SiteRegisterRequest request = new SiteRegisterRequest("Name", "my-site", CmsType.WORDPRESS,
                Map.of("baseUrl", "https://example.com"));
        when(siteService.register(request)).thenReturn(buildResponse());

        ResponseEntity<SiteResponse> response = controller().register(request);

        assertEquals(201, response.getStatusCode().value());
    }

    @Test
    void getDetail_admin権限がなければForbidden() {
        doThrow(new ForbiddenException("admin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().getDetail(1L));
    }

    @Test
    void delete_admin権限があれば削除できる() {
        ResponseEntity<Void> response = controller().delete(1L);

        assertEquals(204, response.getStatusCode().value());
        verify(wordPressSiteProvisioningService).deleteSite(1L);
    }

    @Test
    void testConnection_結果をmapで返す() {
        when(siteService.checkConnection(1L)).thenReturn(new SiteConnectionCheckResult(true, true, null, "detail"));

        Map<String, Object> response = controller().testConnection(1L);

        assertEquals("SUCCESS", response.get("connectionCheckStatus"));
        assertEquals(true, response.get("hasAdminCapability"));
    }

    @Test
    void reprovision_admin権限があれば実行できる() {
        when(siteService.reprovision(1L)).thenReturn(
                new ProvisioningService.Result("cat-1", null, "tag-1", null, "author-1", null));

        ResponseEntity<Map<String, String>> response = controller().reprovision(1L);

        assertEquals(200, response.getStatusCode().value());
        verify(adminAuthorizationService).requireAdmin();
    }

    // ---- issue #830: 「認証済みなら誰でも」だった4件をadmin限定にした回帰テスト ----

    @Test
    void register_admin以外は拒否しサイトを登録しない() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller().register(new SiteRegisterRequest(
                        "Name", "my-site", CmsType.WORDPRESS, Map.of("baseUrl", "https://example.com"))));

        verifyNoInteractions(siteService);
    }

    @Test
    void createManagedWordPress_admin以外は拒否しプロビジョニングしない() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller().createManagedWordPress(null));

        verifyNoInteractions(wordPressSiteProvisioningService);
    }

    @Test
    void adoptManagedWordPress_admin以外は拒否し取り込まない() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller().adoptManagedWordPress(null));

        verifyNoInteractions(wordPressSiteProvisioningService);
    }

    @Test
    void testConnection_admin以外は拒否し接続確認を行わない() {
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です"))
                .when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller().testConnection(1L));

        verify(siteService, never()).checkConnection(1L);
    }

    // ---- issue #830: 一覧は所属プロジェクトに紐付くサイトだけ ----

    @Test
    void list_adminは全件を返す() {
        when(adminAuthorizationService.accessibleProjectIds()).thenReturn(Optional.empty());
        when(siteService.list(null, null)).thenReturn(List.of(buildResponse()));

        assertEquals(1, controller().list(null, null).size());
    }

    @Test
    void list_非adminは所属プロジェクトのサイトだけに絞られる() {
        // buildResponse() の id は 1。所属プロジェクトのサイトが {9} なら 1 は落ちる。
        when(adminAuthorizationService.accessibleProjectIds()).thenReturn(Optional.of(Set.of(3L)));
        when(projectService.siteIdsOfProjects(Set.of(3L))).thenReturn(Set.of(9L));
        when(siteService.list(null, null)).thenReturn(List.of(buildResponse()));

        assertEquals(List.of(), controller().list(null, null));
    }

    @Test
    void list_所属プロジェクトのサイトなら残る() {
        when(adminAuthorizationService.accessibleProjectIds()).thenReturn(Optional.of(Set.of(3L)));
        when(projectService.siteIdsOfProjects(Set.of(3L))).thenReturn(Set.of(1L));
        when(siteService.list(null, null)).thenReturn(List.of(buildResponse()));

        assertEquals(1, controller().list(null, null).size());
    }
}
