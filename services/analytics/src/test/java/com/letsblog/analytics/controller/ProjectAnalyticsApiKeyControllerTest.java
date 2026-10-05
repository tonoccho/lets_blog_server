package com.letsblog.analytics.controller;

import com.letsblog.analytics.adsense.AdSenseAccountSummary;
import com.letsblog.analytics.analytics.GoogleAnalyticsPropertySummary;
import com.letsblog.analytics.client.ProjectBridgeClient;
import com.letsblog.analytics.service.AdminAuthorizationService;
import com.letsblog.analytics.service.CurrentActorService;
import com.letsblog.analytics.service.ProjectAnalyticsSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectAnalyticsApiKeyControllerのGoogle Analytics OAuth連携エンドポイントの回帰テスト(issue #1231)。
 * 各エンドポイントが認可を先に行うこと、状態応答が秘密情報(クライアントシークレット/リフレッシュトークン)を
 * 含まず「あるか否か」だけを返すことを確かめる。
 */
@ExtendWith(MockitoExtension.class)
class ProjectAnalyticsApiKeyControllerTest {

    @Mock
    private ProjectAnalyticsSettingsService settings;
    @Mock
    private AdminAuthorizationService authorization;
    @Mock
    private ProjectBridgeClient projectBridgeClient;
    @Mock
    private CurrentActorService currentActorService;

    private ProjectAnalyticsApiKeyController controller() {
        return new ProjectAnalyticsApiKeyController(settings, authorization, projectBridgeClient, currentActorService);
    }

    @Test
    void getGoogleAnalyticsStatus_連携状態を返しシークレットは含まない() {
        when(settings.hasGoogleAnalytics(1L)).thenReturn(true);
        when(settings.googleAnalyticsPropertyId(1L)).thenReturn("123");
        when(settings.googleAnalyticsClientId(1L)).thenReturn("cid");
        when(settings.hasGoogleAnalyticsClientSecret(1L)).thenReturn(true);
        when(settings.isGoogleAnalyticsConnected(1L)).thenReturn(true);

        var response = controller().getGoogleAnalyticsStatus(1L);

        assertTrue(response.configured());
        assertEquals("123", response.propertyId());
        assertEquals("cid", response.clientId());
        assertTrue(response.hasClientSecret());
        assertTrue(response.connected());
        verify(authorization).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void setGoogleAnalyticsClient_認可後にクライアントを保存する() {
        var response = controller().setGoogleAnalyticsClient(
                1L, new ProjectAnalyticsApiKeyController.SetProjectGoogleAnalyticsClientRequest("cid", "secret"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        InOrder order = inOrder(authorization, settings);
        order.verify(authorization).requireProjectMemberOrAdmin(1L);
        order.verify(settings).setGoogleAnalyticsClient(1L, "cid", "secret");
    }

    @Test
    void completeGoogleAnalyticsOAuth_認可コードを渡す() {
        var response = controller().completeGoogleAnalyticsOAuth(
                1L, new ProjectAnalyticsApiKeyController.CompleteGoogleAnalyticsOAuthRequest("code", "https://x/cb"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        InOrder order = inOrder(authorization, settings);
        order.verify(authorization).requireProjectMemberOrAdmin(1L);
        order.verify(settings).completeGoogleAnalyticsOAuth(1L, "code", "https://x/cb");
    }

    @Test
    void listGoogleAnalyticsProperties_プロパティIDと表示名の一覧を返す() {
        when(settings.listGoogleAnalyticsProperties(1L))
                .thenReturn(List.of(new GoogleAnalyticsPropertySummary("111", "Site A", "Account One")));

        var response = controller().listGoogleAnalyticsProperties(1L);

        assertEquals(1, response.size());
        assertEquals("111", response.get(0).propertyId());
        assertEquals("Site A", response.get(0).displayName());
        assertEquals("Account One", response.get(0).accountDisplayName());
        verify(authorization).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void selectGoogleAnalyticsProperty_選択を保存する() {
        var response = controller().selectGoogleAnalyticsProperty(
                1L, new ProjectAnalyticsApiKeyController.SelectGoogleAnalyticsPropertyRequest("111"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        InOrder order = inOrder(authorization, settings);
        order.verify(authorization).requireProjectMemberOrAdmin(1L);
        order.verify(settings).selectGoogleAnalyticsProperty(1L, "111");
    }

    @Test
    void selectGoogleAnalyticsProperty_保存したあと_本番サイトへ送るようproject_serviceへ知らせる() {
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer t");

        controller().selectGoogleAnalyticsProperty(
                1L, new ProjectAnalyticsApiKeyController.SelectGoogleAnalyticsPropertyRequest("111"));

        InOrder order = inOrder(authorization, settings, projectBridgeClient);
        order.verify(settings).selectGoogleAnalyticsProperty(1L, "111");
        order.verify(projectBridgeClient).notifyGoogleAnalyticsConnected(1L, "Bearer t");
    }

    @Test
    void selectGoogleAnalyticsProperty_保存が拒否されたらproject_serviceへは知らせない() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("未連携"))
                .when(settings).selectGoogleAnalyticsProperty(1L, "111");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> controller()
                .selectGoogleAnalyticsProperty(
                        1L, new ProjectAnalyticsApiKeyController.SelectGoogleAnalyticsPropertyRequest("111")));

        org.mockito.Mockito.verifyNoInteractions(projectBridgeClient);
    }

    @Test
    void clearGoogleAnalyticsCredentials_連携を解除する() {
        var response = controller().clearGoogleAnalyticsCredentials(1L);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertFalse(response.hasBody());
        InOrder order = inOrder(authorization, settings);
        order.verify(authorization).requireProjectMemberOrAdmin(1L);
        order.verify(settings).clearGoogleAnalyticsCredentials(1L);
    }

    @Test
    void getAdSenseStatus_連携状態を返しシークレットは含まない() {
        when(settings.hasAdSense(1L)).thenReturn(false);
        when(settings.adSenseAccountId(1L)).thenReturn(null);
        when(settings.adSenseClientId(1L)).thenReturn("cid");
        when(settings.hasAdSenseClientSecret(1L)).thenReturn(true);
        when(settings.isAdSenseConnected(1L)).thenReturn(true);

        var response = controller().getAdSenseStatus(1L);

        assertFalse(response.configured());
        assertEquals(null, response.accountId());
        assertEquals("cid", response.clientId());
        assertTrue(response.hasClientSecret());
        assertTrue(response.connected());
        verify(authorization).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void setAdSenseSettings_パブリッシャーIDは省略できる() {
        var response = controller().setAdSenseSettings(
                1L, new ProjectAnalyticsApiKeyController.SetProjectAdSenseSettingsRequest(null, "cid"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        InOrder order = inOrder(authorization, settings);
        order.verify(authorization).requireProjectMemberOrAdmin(1L);
        order.verify(settings).setAdSenseSettings(1L, null, "cid");
    }

    @Test
    void listAdSenseAccounts_パブリッシャーIDと表示名の一覧を返す() {
        when(settings.listAdSenseAccounts(1L))
                .thenReturn(List.of(new AdSenseAccountSummary("pub-1", "Site A")));

        var response = controller().listAdSenseAccounts(1L);

        assertEquals(1, response.size());
        assertEquals("pub-1", response.get(0).accountId());
        assertEquals("Site A", response.get(0).displayName());
        verify(authorization).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void selectAdSenseAccount_認可後に選択したIDを保存する() {
        var response = controller().selectAdSenseAccount(
                1L, new ProjectAnalyticsApiKeyController.SelectAdSenseAccountRequest("pub-2"));

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        InOrder order = inOrder(authorization, settings);
        order.verify(authorization).requireProjectMemberOrAdmin(1L);
        order.verify(settings).selectAdSenseAccount(1L, "pub-2");
    }
}
