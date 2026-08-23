package com.letsblog.api.controller;

import com.letsblog.api.cms.CmsType;
import com.letsblog.api.crypto.SshKeyGenerationService;
import com.letsblog.api.crypto.SshKeyGenerationService.SshKeyPair;
import com.letsblog.api.dto.SiteConnectionCheckResult;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.dto.SiteUpdateRequest;
import com.letsblog.api.dto.SshKeyPairRequest;
import com.letsblog.api.dto.SshKeyPairResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.CurrentActorService;
import com.letsblog.api.service.ForbiddenException;
import com.letsblog.api.service.SiteService;
import com.letsblog.api.service.WordPressSiteProvisioningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SiteControllerTest {

    @Mock
    private SiteService siteService;

    @Mock
    private CurrentActorService currentActorService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    @Mock
    private WordPressSiteProvisioningService wordPressSiteProvisioningService;

    @Mock
    private SshKeyGenerationService sshKeyGenerationService;

    private SiteController controller() {
        return new SiteController(siteService, currentActorService, adminAuthorizationService,
                wordPressSiteProvisioningService, sshKeyGenerationService);
    }

    private SiteResponse buildResponse() {
        return new SiteResponse(1L, "My Blog", "main", CmsType.WORDPRESS, "https://example.com",
                LocalDateTime.now(), LocalDateTime.now(), "SUCCESS", false, false);
    }

    @Test
    void update_admin権限があれば更新できる() {
        SiteController controller = controller();
        SiteUpdateRequest request = new SiteUpdateRequest("New Name", null);
        when(siteService.update(1L, request)).thenReturn(buildResponse());

        SiteResponse response = controller.update(1L, request);

        assertEquals("My Blog", response.name());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void update_admin権限がなければForbidden() {
        SiteController controller = controller();
        SiteUpdateRequest request = new SiteUpdateRequest("New Name", null);
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.update(1L, request));
    }

    @Test
    void testConnection_成功時SUCCESSを返す() {
        SiteController controller = controller();
        when(siteService.checkConnection(1L)).thenReturn(new SiteConnectionCheckResult(true, true, null, null));

        Map<String, Object> response = controller.testConnection(1L);

        assertEquals("SUCCESS", response.get("connectionCheckStatus"));
        assertEquals(true, response.get("hasAdminCapability"));
    }

    @Test
    void testConnection_失敗時FAILEDを返す() {
        SiteController controller = controller();
        when(siteService.checkConnection(1L)).thenReturn(new SiteConnectionCheckResult(false, null, "HTTP 403 Forbidden", null));

        Map<String, Object> response = controller.testConnection(1L);

        assertEquals("FAILED", response.get("connectionCheckStatus"));
        assertEquals("HTTP 403 Forbidden", response.get("failureReason"));
    }

    @Test
    void testConnection_admin権限不問で呼べる() {
        SiteController controller = controller();
        when(siteService.checkConnection(1L)).thenReturn(new SiteConnectionCheckResult(true, true, null, null));

        controller.testConnection(1L);

        verifyNoAdminCheck();
    }

    private void verifyNoAdminCheck() {
        org.mockito.Mockito.verifyNoInteractions(adminAuthorizationService);
    }

    @Test
    void generateSshKeyPair_admin権限があれば鍵ペアを返す() {
        SiteController controller = controller();
        when(sshKeyGenerationService.generateEd25519(any()))
                .thenReturn(new SshKeyPair("PRIVATE-KEY-PEM", "ssh-ed25519 AAAA... letsblog"));

        SshKeyPairResponse response = controller.generateSshKeyPair(new SshKeyPairRequest("my-site"));

        assertEquals("PRIVATE-KEY-PEM", response.privateKeyPem());
        assertEquals("ssh-ed25519 AAAA... letsblog", response.publicKeyLine());
        verify(adminAuthorizationService).requireAdmin();
        verify(sshKeyGenerationService).generateEd25519("my-site");
    }

    @Test
    void generateSshKeyPair_コメント未指定時は既定値を使う() {
        SiteController controller = controller();
        when(sshKeyGenerationService.generateEd25519(any()))
                .thenReturn(new SshKeyPair("PRIVATE-KEY-PEM", "ssh-ed25519 AAAA..."));

        controller.generateSshKeyPair(null);

        verify(sshKeyGenerationService).generateEd25519("letsblog");
    }

    @Test
    void generateSshKeyPair_admin権限がなければForbidden() {
        SiteController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.generateSshKeyPair(null));
    }

    // issue #568: 認可マトリクス整備に伴う、requireAdmin()を呼ぶ全メソッドのForbiddenパス網羅
    @Test
    void getDetail_admin権限があれば詳細を返す() {
        SiteController controller = controller();
        com.letsblog.api.dto.SiteDetailResponse detail = new com.letsblog.api.dto.SiteDetailResponse(
                1L, "My Blog", "main", CmsType.WORDPRESS, "https://example.com",
                LocalDateTime.now(), LocalDateTime.now(), false, false, Map.of(), java.util.List.of());
        when(siteService.getDetail(1L)).thenReturn(detail);

        com.letsblog.api.dto.SiteDetailResponse response = controller.getDetail(1L);

        assertEquals("My Blog", response.name());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void getDetail_admin権限がなければForbidden() {
        SiteController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.getDetail(1L));
    }

    @Test
    void delete_admin権限があれば削除できる() {
        SiteController controller = controller();

        var response = controller.delete(1L);

        assertEquals(204, response.getStatusCode().value());
        verify(adminAuthorizationService).requireAdmin();
        verify(wordPressSiteProvisioningService).deleteSite(1L);
    }

    @Test
    void delete_admin権限がなければForbidden() {
        SiteController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.delete(1L));
    }

    @Test
    void installWpCli_admin権限があれば実行できる() {
        SiteController controller = controller();
        when(siteService.installWpCli(1L)).thenReturn(new com.letsblog.api.cms.WpCliInstallResult("インストールしました"));

        var response = controller.installWpCli(1L);

        assertEquals("インストールしました", response.message());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void installWpCli_admin権限がなければForbidden() {
        SiteController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.installWpCli(1L));
    }

    @Test
    void reprovision_admin権限があれば実行できる() {
        SiteController controller = controller();
        when(siteService.reprovision(1L, 0L))
                .thenReturn(new com.letsblog.api.service.ProvisioningService.ProvisioningResult());

        var response = controller.reprovision(1L);

        assertEquals(200, response.getStatusCode().value());
        verify(adminAuthorizationService).requireAdmin();
    }

    @Test
    void reprovision_admin権限がなければForbidden() {
        SiteController controller = controller();
        doThrow(new ForbiddenException("この操作にはadmin権限が必要です")).when(adminAuthorizationService).requireAdmin();

        assertThrows(ForbiddenException.class, () -> controller.reprovision(1L));
    }
}
