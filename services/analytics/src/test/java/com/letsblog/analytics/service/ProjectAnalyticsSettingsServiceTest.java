package com.letsblog.analytics.service;

import com.letsblog.analytics.adsense.AdSenseClient;
import com.letsblog.analytics.adsense.GoogleOAuthTokens;
import com.letsblog.analytics.analytics.GoogleAnalyticsClient;
import com.letsblog.analytics.analytics.GoogleAnalyticsPropertySummary;
import com.letsblog.common.crypto.CredentialCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectAnalyticsSettingsServiceのGoogle Analytics OAuth連携の回帰テスト(issue #1231)。
 * サービスアカウントJSONの検証・保存経路は廃止され、OAuthクライアントの保存 → 認可コード交換 →
 * プロパティ一覧 → プロパティ選択 → 解除、が唯一の経路になった。
 */
@ExtendWith(MockitoExtension.class)
class ProjectAnalyticsSettingsServiceTest {

    @Mock
    private AnalyticsCredentialsService analyticsCredentialsService;
    @Mock
    private AdSenseClient adSenseClient;
    @Mock
    private GoogleAnalyticsClient googleAnalyticsClient;

    private final CredentialCipher credentialCipher =
            new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));

    private ProjectAnalyticsSettingsService service() {
        return new ProjectAnalyticsSettingsService(
                analyticsCredentialsService, adSenseClient, googleAnalyticsClient, credentialCipher);
    }

    @Test
    void setGoogleAnalyticsClient_シークレットは暗号化して保存する() {
        service().setGoogleAnalyticsClient(1L, "cid", "plain-secret");

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(analyticsCredentialsService).setGaOauthClient(eq(1L), eq("cid"), captor.capture());
        assertEquals("plain-secret", credentialCipher.decrypt(captor.getValue()));
    }

    @Test
    void setGoogleAnalyticsClient_シークレットが空なら既存を変更しない() {
        service().setGoogleAnalyticsClient(1L, "cid", "  ");
        service().setGoogleAnalyticsClient(1L, "cid", null);

        verify(analyticsCredentialsService, org.mockito.Mockito.times(2)).setGaOauthClient(1L, "cid", null);
    }

    @Test
    void completeGoogleAnalyticsOAuth_認可コードを交換してリフレッシュトークンを暗号化保存する() {
        when(analyticsCredentialsService.getGaOauthClientId(1L)).thenReturn("cid");
        when(analyticsCredentialsService.hasGaOauthClientSecret(1L)).thenReturn(true);
        when(analyticsCredentialsService.getGaOauthClientSecretEncrypted(1L))
                .thenReturn(credentialCipher.encrypt("secret"));
        when(googleAnalyticsClient.exchangeAuthorizationCode("cid", "secret", "code", "https://x/cb"))
                .thenReturn(new GoogleOAuthTokens("access", "refresh-xyz"));

        service().completeGoogleAnalyticsOAuth(1L, "code", "https://x/cb");

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(analyticsCredentialsService).setGaRefreshTokenEncrypted(eq(1L), captor.capture());
        assertEquals("refresh-xyz", credentialCipher.decrypt(captor.getValue()));
    }

    @Test
    void completeGoogleAnalyticsOAuth_クライアントシークレット未保存ならnullを渡してクライアント側で拒否する() {
        when(analyticsCredentialsService.getGaOauthClientId(1L)).thenReturn(null);
        when(analyticsCredentialsService.hasGaOauthClientSecret(1L)).thenReturn(false);
        when(googleAnalyticsClient.exchangeAuthorizationCode(null, null, "code", "https://x/cb"))
                .thenThrow(new com.letsblog.analytics.analytics.GoogleAnalyticsException("未設定", null));

        assertThrows(com.letsblog.analytics.analytics.GoogleAnalyticsException.class,
                () -> service().completeGoogleAnalyticsOAuth(1L, "code", "https://x/cb"));
        verify(analyticsCredentialsService, never()).setGaRefreshTokenEncrypted(eq(1L), any());
    }

    @Test
    void listGoogleAnalyticsProperties_保存済みのリフレッシュトークンで一覧を取得する() {
        stubConnected();
        List<GoogleAnalyticsPropertySummary> expected =
                List.of(new GoogleAnalyticsPropertySummary("111", "Site A", "Account One"));
        when(googleAnalyticsClient.refreshAccessToken("cid", "secret", "refresh")).thenReturn("access");
        when(googleAnalyticsClient.listProperties("access")).thenReturn(expected);

        assertEquals(expected, service().listGoogleAnalyticsProperties(1L));
    }

    @Test
    void listGoogleAnalyticsProperties_未連携なら拒否してGoogleへ問い合わせない() {
        when(analyticsCredentialsService.hasGaRefreshToken(1L)).thenReturn(false);

        assertThrows(IllegalArgumentException.class, () -> service().listGoogleAnalyticsProperties(1L));
        verify(googleAnalyticsClient, never()).listProperties(any());
    }

    @Test
    void selectGoogleAnalyticsProperty_数字のIDを保存する() {
        when(analyticsCredentialsService.hasGaRefreshToken(1L)).thenReturn(true);

        service().selectGoogleAnalyticsProperty(1L, "987654321");

        verify(analyticsCredentialsService).setGaPropertyId(1L, "987654321");
    }

    @Test
    void selectGoogleAnalyticsProperty_propertiesプレフィックスは取り除く() {
        when(analyticsCredentialsService.hasGaRefreshToken(1L)).thenReturn(true);

        service().selectGoogleAnalyticsProperty(1L, "properties/555");

        verify(analyticsCredentialsService).setGaPropertyId(1L, "555");
    }

    @Test
    void selectGoogleAnalyticsProperty_未連携や数字でないIDは拒否する() {
        when(analyticsCredentialsService.hasGaRefreshToken(1L)).thenReturn(false);
        assertThrows(IllegalArgumentException.class, () -> service().selectGoogleAnalyticsProperty(1L, "123"));

        when(analyticsCredentialsService.hasGaRefreshToken(2L)).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> service().selectGoogleAnalyticsProperty(2L, "abc"));
        assertThrows(IllegalArgumentException.class, () -> service().selectGoogleAnalyticsProperty(2L, ""));
        verify(analyticsCredentialsService, never()).setGaPropertyId(any(), any());
    }

    @Test
    void 状態の読み取りは資格情報サービスへ委譲する() {
        when(analyticsCredentialsService.hasGoogleAnalyticsCredentials(1L)).thenReturn(true);
        when(analyticsCredentialsService.getGaPropertyId(1L)).thenReturn("123");
        when(analyticsCredentialsService.getGaOauthClientId(1L)).thenReturn("cid");
        when(analyticsCredentialsService.hasGaOauthClientSecret(1L)).thenReturn(true);
        when(analyticsCredentialsService.hasGaRefreshToken(1L)).thenReturn(true);

        ProjectAnalyticsSettingsService service = service();
        assertTrue(service.hasGoogleAnalytics(1L));
        assertEquals("123", service.googleAnalyticsPropertyId(1L));
        assertEquals("cid", service.googleAnalyticsClientId(1L));
        assertTrue(service.hasGoogleAnalyticsClientSecret(1L));
        assertTrue(service.isGoogleAnalyticsConnected(1L));
    }

    @Test
    void clearGoogleAnalyticsCredentials_資格情報サービスへ委譲する() {
        service().clearGoogleAnalyticsCredentials(1L);

        verify(analyticsCredentialsService).clearGoogleAnalyticsCredentials(1L);
        assertNull(analyticsCredentialsService.getGaPropertyId(1L));
        assertFalse(analyticsCredentialsService.hasGaRefreshToken(1L));
    }

    private void stubConnected() {
        when(analyticsCredentialsService.hasGaRefreshToken(1L)).thenReturn(true);
        when(analyticsCredentialsService.getGaOauthClientId(1L)).thenReturn("cid");
        when(analyticsCredentialsService.hasGaOauthClientSecret(1L)).thenReturn(true);
        when(analyticsCredentialsService.getGaOauthClientSecretEncrypted(1L))
                .thenReturn(credentialCipher.encrypt("secret"));
        when(analyticsCredentialsService.getGaRefreshTokenEncrypted(1L))
                .thenReturn(credentialCipher.encrypt("refresh"));
    }
}
