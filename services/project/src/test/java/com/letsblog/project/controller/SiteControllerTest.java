package com.letsblog.project.controller;

import com.letsblog.project.cms.CmsType;
import com.letsblog.project.crypto.SshKeyGenerationService;
import com.letsblog.project.dto.SiteConnectionCheckResult;
import com.letsblog.project.dto.SiteRegisterRequest;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.service.ProvisioningService;
import com.letsblog.project.service.SiteService;
import com.letsblog.project.service.WordPressSiteProvisioningService;
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
import static org.mockito.Mockito.verify;
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

    private SiteController controller() {
        return new SiteController(
                siteService, adminAuthorizationService, wordPressSiteProvisioningService, sshKeyGenerationService);
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
}
