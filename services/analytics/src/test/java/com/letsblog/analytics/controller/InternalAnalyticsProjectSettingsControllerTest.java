package com.letsblog.analytics.controller;

import com.letsblog.analytics.service.AdminAuthorizationService;
import com.letsblog.analytics.service.ForbiddenException;
import com.letsblog.analytics.service.ProjectAnalyticsSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 内部ブリッジの GA 認証情報(issue #1578)。project-service が本番サイトのプラグインへ送るために、復号済みの
 * 認証情報を読む。公開 API には載せず、呼び出し元ユーザーがプロジェクトのメンバーかadminであることを検査する。
 */
@ExtendWith(MockitoExtension.class)
class InternalAnalyticsProjectSettingsControllerTest {

    @Mock
    private ProjectAnalyticsSettingsService settings;
    @Mock
    private AdminAuthorizationService authorization;

    private InternalAnalyticsProjectSettingsController controller() {
        return new InternalAnalyticsProjectSettingsController(settings, authorization);
    }

    @Test
    void 権限を確かめてから復号済みの認証情報を返す() {
        when(settings.googleAnalyticsCredentials(1L)).thenReturn(
                new ProjectAnalyticsSettingsService.GoogleAnalyticsCredentialsView(true, "987", "cid", "cs", "rt"));

        var response = controller().googleAnalyticsCredentials(1L);

        assertTrue(response.configured());
        assertEquals("987", response.propertyId());
        assertEquals("cid", response.clientId());
        assertEquals("cs", response.clientSecret());
        assertEquals("rt", response.refreshToken());
        InOrder order = inOrder(authorization, settings);
        order.verify(authorization).requireProjectMemberOrAdmin(1L);
        order.verify(settings).googleAnalyticsCredentials(1L);
    }

    @Test
    void メンバーでもadminでもなければ秘密を読ませない() {
        doThrow(new ForbiddenException("forbidden")).when(authorization).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> controller().googleAnalyticsCredentials(1L));

        verifyNoInteractions(settings);
    }
}
