package com.letsblog.api.controller;

import com.letsblog.api.cms.CmsType;
import com.letsblog.api.dto.SiteResponse;
import com.letsblog.api.dto.SiteUpdateRequest;
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

    private SiteController controller() {
        return new SiteController(siteService, currentActorService, adminAuthorizationService, wordPressSiteProvisioningService);
    }

    private SiteResponse buildResponse() {
        return new SiteResponse(1L, "My Blog", "main", CmsType.WORDPRESS, "https://example.com",
                LocalDateTime.now(), LocalDateTime.now(), "SUCCESS", false);
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
        when(siteService.checkConnection(1L)).thenReturn(true);

        Map<String, Object> response = controller.testConnection(1L);

        assertEquals("SUCCESS", response.get("connectionCheckStatus"));
    }

    @Test
    void testConnection_失敗時FAILEDを返す() {
        SiteController controller = controller();
        when(siteService.checkConnection(1L)).thenReturn(false);

        Map<String, Object> response = controller.testConnection(1L);

        assertEquals("FAILED", response.get("connectionCheckStatus"));
    }

    @Test
    void testConnection_admin権限不問で呼べる() {
        SiteController controller = controller();
        when(siteService.checkConnection(1L)).thenReturn(true);

        controller.testConnection(1L);

        verifyNoAdminCheck();
    }

    private void verifyNoAdminCheck() {
        org.mockito.Mockito.verifyNoInteractions(adminAuthorizationService);
    }
}
