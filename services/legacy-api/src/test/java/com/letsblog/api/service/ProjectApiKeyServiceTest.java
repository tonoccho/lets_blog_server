package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.client.AiProjectSettingsClient;
import com.letsblog.api.client.AnalyticsProjectSettingsClient;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.api.domain.Project;
import com.letsblog.api.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

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
 * フォールバック、暗号化保存、admin権限ゲートを検証する。GitHubトークンはprojects自体に保持する
 * (issue #571のprojects god-table分割)。Brave Search APIキー(project_ai_settings)はissue #574で
 * ai-serviceへ、GA/AdSense(analytics_credentials)はissue #578でanalytics-serviceへそれぞれ移管され、
 * いずれも内部ブリッジ呼び出しに変わったため、その回帰テストは{@link AiProjectSettingsClient}/
 * {@link AnalyticsProjectSettingsClient}のモックで委譲を検証する形にしている。レポート取得可否判定に
 * 使うresolveGoogleAnalyticsServiceAccountKey/resolveAdSenseRefreshToken/resolveAdSenseOauthClientSecret
 * は、GoogleAnalyticsReportService/AdSenseReportServiceごとanalytics-serviceへ移設されたため
 * このサービスからは削除された。
 */
@ExtendWith(MockitoExtension.class)
class ProjectApiKeyServiceTest {

    @Mock
    private ProjectRepository projectRepository;
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
                projectRepository, aiProjectSettingsClient, analyticsProjectSettingsClient, credentialCipher,
                userService, adminAuthorizationService, objectMapper);
    }

    private static final String VALID_SERVICE_ACCOUNT_JSON =
            "{\"client_email\":\"svc@example.iam.gserviceaccount.com\",\"private_key\":\"-----BEGIN PRIVATE KEY-----\\nabc\\n-----END PRIVATE KEY-----\\n\"}";

    private Project projectWithId(Long id) {
        Project project = new Project();
        project.setId(id);
        return project;
    }

    @Test
    void resolveGithubToken_プロジェクト設定があればそれを優先する() {
        Project project = projectWithId(1L);
        project.setGithubTokenEncrypted(credentialCipher.encrypt("project-token"));
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        String token = service().resolveGithubToken(1L, 10L);

        assertEquals("project-token", token);
    }

    @Test
    void resolveGithubToken_未設定ならユーザー設定にフォールバックする() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(userService.getDecryptedGithubToken(10L)).thenReturn("user-token");

        String token = service().resolveGithubToken(1L, 10L);

        assertEquals("user-token", token);
    }

    @Test
    void setBraveSearchApiKey_ai_serviceへ委譲する() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        service().setBraveSearchApiKey(1L, "project-key");

        verify(aiProjectSettingsClient).setBraveSearchApiKey(1L, "project-key");
    }

    @Test
    void clearBraveSearchApiKey_ai_serviceへ委譲する() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        service().clearBraveSearchApiKey(1L);

        verify(aiProjectSettingsClient).clearBraveSearchApiKey(1L);
    }

    @Test
    void setGithubToken_暗号化して保存する() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        service().setGithubToken(1L, "new-token");

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        assertEquals("new-token", credentialCipher.decrypt(captor.getValue().getGithubTokenEncrypted()));
    }

    @Test
    void setGithubToken_プロジェクトメンバーでなければForbidden() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> service().setGithubToken(1L, "token"));
    }

    @Test
    void clearGithubToken_nullにして保存する() {
        Project project = projectWithId(1L);
        project.setGithubTokenEncrypted(credentialCipher.encrypt("old-token"));
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        service().clearGithubToken(1L);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        assertFalse(captor.getValue().hasGithubToken());
    }

    @Test
    void isBraveSearchApiKeyConfigured_設定有無を返す() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(aiProjectSettingsClient.isBraveSearchApiKeyConfigured(1L)).thenReturn(true);

        assertTrue(service().isBraveSearchApiKeyConfigured(1L));
    }

    // ---- Google Analytics (issue #386、issue #578でanalytics-serviceへブリッジ) ----

    @Test
    void setGoogleAnalyticsCredentials_analytics_serviceへ委譲する() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

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
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(analyticsProjectSettingsClient.getGoogleAnalyticsStatus(1L))
                .thenReturn(new AnalyticsProjectSettingsClient.GoogleAnalyticsStatus(true, "123456789"));

        assertTrue(service().isGoogleAnalyticsConfigured(1L));
    }

    @Test
    void clearGoogleAnalyticsCredentials_サービス側のクリアを呼ぶ() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        service().clearGoogleAnalyticsCredentials(1L);

        verify(analyticsProjectSettingsClient).clearGoogleAnalyticsCredentials(1L);
    }

    // ---- AdSense (issue #387、OAuthクライアントのプロジェクト単位化はissue #407、issue #578でブリッジ化) ----

    @Test
    void setAdSenseSettings_アカウントIDとクライアントIDをanalytics_serviceへ委譲する() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        service().setAdSenseSettings(1L, "pub-1234567890123456", "client-id");

        verify(analyticsProjectSettingsClient).setAdSenseSettings(1L, "pub-1234567890123456", "client-id");
    }

    @Test
    void setAdSenseClientSecret_analytics_serviceへ委譲する() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        service().setAdSenseClientSecret(1L, "client-secret");

        verify(analyticsProjectSettingsClient).setAdSenseClientSecret(1L, "client-secret");
    }

    @Test
    void completeAdSenseOAuth_analytics_serviceへ委譲する() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        service().completeAdSenseOAuth(1L, "auth-code", "https://example.com/callback");

        verify(analyticsProjectSettingsClient)
                .completeAdSenseOAuth(1L, "auth-code", "https://example.com/callback");
    }

    @Test
    void getAdSenseStatus_analytics_serviceの応答をそのまま返す() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(analyticsProjectSettingsClient.getAdSenseStatus(1L)).thenReturn(
                new AnalyticsProjectSettingsClient.AdSenseStatus(true, "pub-1234567890123456", "client-id", true));

        ProjectApiKeyService.AdSenseStatus status = service().getAdSenseStatus(1L);

        assertTrue(status.configured());
        assertEquals("pub-1234567890123456", status.accountId());
    }

    @Test
    void clearAdSenseCredentials_サービス側のクリアを呼ぶ() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

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
