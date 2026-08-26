package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.client.AiProjectSettingsClient;
import com.letsblog.api.client.AnalyticsProjectSettingsClient;
import com.letsblog.api.client.ProjectServiceClient;
import com.letsblog.common.crypto.CredentialCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectApiKeyServiceの回帰テスト(issue #184)。プロジェクト設定の優先とユーザー設定への
 * フォールバック、暗号化保存、admin権限ゲートを検証する。Brave Search APIキー(project_ai_settings)は
 * issue #574でai-serviceへ、GA/AdSense(analytics_credentials)はissue #578でanalytics-serviceへ
 * それぞれ移管され、いずれも内部ブリッジ呼び出しに変わったため、その回帰テストは
 * {@link AiProjectSettingsClient}/{@link AnalyticsProjectSettingsClient}のモックで委譲を検証する形に
 * している。レポート取得可否判定に使うresolveGoogleAnalyticsServiceAccountKey/resolveAdSenseRefreshToken/
 * resolveAdSenseOauthClientSecretは、GoogleAnalyticsReportService/AdSenseReportServiceごと
 * analytics-serviceへ移設されたためこのサービスからは削除された。GitHubトークン(projects.
 * github_token_encrypted)の所有権はproject-serviceへ移った(issue #577 stage2)ため、
 * {@link ProjectServiceClient}経由の内部ブリッジ(モック)で読み書きを検証する(issue #577 stage3)。
 */
@ExtendWith(MockitoExtension.class)
class ProjectApiKeyServiceTest {

    @Mock
    private ProjectServiceClient projectServiceClient;
    @Mock
    private AiProjectSettingsClient aiProjectSettingsClient;
    @Mock
    private AnalyticsProjectSettingsClient analyticsProjectSettingsClient;
    @Mock
    private UserService userService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private final CredentialCipher credentialCipher = new CredentialCipher(
            java.util.Base64.getEncoder().encodeToString(new byte[32]));
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProjectApiKeyService service() {
        return new ProjectApiKeyService(
                projectServiceClient, aiProjectSettingsClient, analyticsProjectSettingsClient, credentialCipher,
                userService, adminAuthorizationService, objectMapper);
    }

    private static final String VALID_SERVICE_ACCOUNT_JSON =
            "{\"client_email\":\"svc@example.iam.gserviceaccount.com\",\"private_key\":\"-----BEGIN PRIVATE KEY-----\\nabc\\n-----END PRIVATE KEY-----\\n\"}";

    private ProjectServiceClient.ProjectBridge existingProject(Long id) {
        LocalDateTime now = LocalDateTime.now();
        return new ProjectServiceClient.ProjectBridge(id, "テストプロジェクト", "test", "test", null, null, null, null, now, now);
    }

    private void stubProjectExists() {
        lenient().when(projectServiceClient.getProject(1L)).thenReturn(existingProject(1L));
    }

    @Test
    void resolveGithubToken_プロジェクト設定があればそれを優先する() {
        lenient().when(projectServiceClient.getGithubToken(1L))
                .thenReturn(new ProjectServiceClient.GithubTokenBridge(true, credentialCipher.encrypt("project-token")));

        String token = service().resolveGithubToken(1L, 10L);

        assertEquals("project-token", token);
    }

    @Test
    void resolveGithubToken_未設定ならユーザー設定にフォールバックする() {
        lenient().when(projectServiceClient.getGithubToken(1L))
                .thenReturn(new ProjectServiceClient.GithubTokenBridge(false, null));
        when(userService.getDecryptedGithubToken(10L)).thenReturn("user-token");

        String token = service().resolveGithubToken(1L, 10L);

        assertEquals("user-token", token);
    }

    @Test
    void setBraveSearchApiKey_ai_serviceへ委譲する() {
        stubProjectExists();

        service().setBraveSearchApiKey(1L, "project-key");

        verify(aiProjectSettingsClient).setBraveSearchApiKey(1L, "project-key");
    }

    @Test
    void clearBraveSearchApiKey_ai_serviceへ委譲する() {
        stubProjectExists();

        service().clearBraveSearchApiKey(1L);

        verify(aiProjectSettingsClient).clearBraveSearchApiKey(1L);
    }

    @Test
    void setGithubToken_暗号化して保存する() {
        service().setGithubToken(1L, "new-token");

        ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
        verify(projectServiceClient).setGithubToken(org.mockito.ArgumentMatchers.eq(1L), captor.capture());
        assertEquals("new-token", credentialCipher.decrypt(captor.getValue()));
    }

    @Test
    void setGithubToken_プロジェクトメンバーでなければForbidden() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> service().setGithubToken(1L, "token"));
    }

    @Test
    void clearGithubToken_nullにして保存する() {
        service().clearGithubToken(1L);

        verify(projectServiceClient).setGithubToken(1L, null);
    }

    @Test
    void isBraveSearchApiKeyConfigured_設定有無を返す() {
        stubProjectExists();
        when(aiProjectSettingsClient.isBraveSearchApiKeyConfigured(1L)).thenReturn(true);

        assertTrue(service().isBraveSearchApiKeyConfigured(1L));
    }

    // ---- Google Analytics (issue #386、issue #578でanalytics-serviceへブリッジ) ----

    @Test
    void setGoogleAnalyticsCredentials_analytics_serviceへ委譲する() {
        stubProjectExists();

        service().setGoogleAnalyticsCredentials(1L, "123456789", VALID_SERVICE_ACCOUNT_JSON);

        verify(analyticsProjectSettingsClient)
                .setGoogleAnalyticsCredentials(1L, "123456789", VALID_SERVICE_ACCOUNT_JSON);
    }

    @Test
    void setGoogleAnalyticsCredentials_JSONとして解析できなければ例外() {
        assertThrows(IllegalArgumentException.class,
                () -> service().setGoogleAnalyticsCredentials(1L, "123456789", "not-json"));
    }

    @Test
    void setGoogleAnalyticsCredentials_client_emailやprivate_keyが無ければ例外() {
        assertThrows(IllegalArgumentException.class,
                () -> service().setGoogleAnalyticsCredentials(1L, "123456789", "{\"client_email\":\"a@b.com\"}"));
    }

    @Test
    void setGoogleAnalyticsCredentials_プロジェクトメンバーでなければForbidden() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class,
                () -> service().setGoogleAnalyticsCredentials(1L, "123456789", VALID_SERVICE_ACCOUNT_JSON));
    }

    @Test
    void isGoogleAnalyticsConfigured_設定有無を返す() {
        stubProjectExists();
        when(analyticsProjectSettingsClient.getGoogleAnalyticsStatus(1L))
                .thenReturn(new AnalyticsProjectSettingsClient.GoogleAnalyticsStatus(true, "123456789"));

        assertTrue(service().isGoogleAnalyticsConfigured(1L));
    }

    @Test
    void clearGoogleAnalyticsCredentials_サービス側のクリアを呼ぶ() {
        stubProjectExists();

        service().clearGoogleAnalyticsCredentials(1L);

        verify(analyticsProjectSettingsClient).clearGoogleAnalyticsCredentials(1L);
    }

    // ---- AdSense (issue #387、OAuthクライアントのプロジェクト単位化はissue #407、issue #578でブリッジ化) ----

    @Test
    void setAdSenseSettings_アカウントIDとクライアントIDをanalytics_serviceへ委譲する() {
        stubProjectExists();

        service().setAdSenseSettings(1L, "pub-1234567890123456", "client-id");

        verify(analyticsProjectSettingsClient).setAdSenseSettings(1L, "pub-1234567890123456", "client-id");
    }

    @Test
    void setAdSenseClientSecret_analytics_serviceへ委譲する() {
        stubProjectExists();

        service().setAdSenseClientSecret(1L, "client-secret");

        verify(analyticsProjectSettingsClient).setAdSenseClientSecret(1L, "client-secret");
    }

    @Test
    void completeAdSenseOAuth_analytics_serviceへ委譲する() {
        stubProjectExists();

        service().completeAdSenseOAuth(1L, "auth-code", "https://example.com/callback");

        verify(analyticsProjectSettingsClient)
                .completeAdSenseOAuth(1L, "auth-code", "https://example.com/callback");
    }

    @Test
    void getAdSenseStatus_analytics_serviceの応答をそのまま返す() {
        stubProjectExists();
        when(analyticsProjectSettingsClient.getAdSenseStatus(1L)).thenReturn(
                new AnalyticsProjectSettingsClient.AdSenseStatus(true, "pub-1234567890123456", "client-id", true));

        ProjectApiKeyService.AdSenseStatus status = service().getAdSenseStatus(1L);

        assertTrue(status.configured());
        assertEquals("pub-1234567890123456", status.accountId());
    }

    @Test
    void clearAdSenseCredentials_サービス側のクリアを呼ぶ() {
        stubProjectExists();

        service().clearAdSenseCredentials(1L);

        verify(analyticsProjectSettingsClient).clearAdSenseCredentials(1L);
    }

    @Test
    void setAdSenseSettings_プロジェクトメンバーでなければForbidden() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class,
                () -> service().setAdSenseSettings(1L, "pub-1234567890123456", "client-id"));
    }

}
