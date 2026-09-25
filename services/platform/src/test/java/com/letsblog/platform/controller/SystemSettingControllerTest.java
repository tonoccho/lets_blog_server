package com.letsblog.platform.controller;

import com.letsblog.platform.dto.SiteAdminPathResponse;
import com.letsblog.platform.service.AppSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** SystemSettingController#getSiteAdminPath(issue #1079)。 */
@ExtendWith(MockitoExtension.class)
class SystemSettingControllerTest {

    @Mock
    private com.letsblog.platform.service.SystemSettingService systemSettingService;
    @Mock
    private AppSettingService appSettingService;

    @Test
    void getSiteAdminPath_解決済みの値をpathとして返す() {
        when(appSettingService.getSiteAdminPath()).thenReturn("secret-admin");
        SystemSettingController controller = new SystemSettingController(systemSettingService, appSettingService);

        SiteAdminPathResponse response = controller.getSiteAdminPath();

        assertEquals("secret-admin", response.path());
    }
}
