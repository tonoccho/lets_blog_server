package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.adsense.AdSenseClient;
import com.letsblog.api.adsense.GoogleOAuthTokens;
import com.letsblog.api.analytics.GoogleServiceAccountKey;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectApiKeyServiceの回帰テスト(issue #184)。プロジェクト設定の優先とユーザー/システム全体設定への
 * フォールバック、暗号化保存、admin権限ゲートを検証する。
 */
@ExtendWith(MockitoExtension.class)
class ProjectApiKeyServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private UserService userService;
    @Mock
    private SystemSettingService systemSettingService;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;
    @Mock
    private AdSenseClient adSenseClient;

    private final CredentialCipher credentialCipher = new CredentialCipher(
            java.util.Base64.getEncoder().encodeToString(new byte[32]));
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ProjectApiKeyService service() {
        return new ProjectApiKeyService(
                projectRepository, credentialCipher, userService, systemSettingService, adminAuthorizationService,
                objectMapper, adSenseClient);
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
    void resolveBraveSearchApiKey_プロジェクト設定があればそれを優先する() {
        Project project = projectWithId(1L);
        project.setBraveSearchApiKeyEncrypted(credentialCipher.encrypt("project-key"));
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        String key = service().resolveBraveSearchApiKey(1L);

        assertEquals("project-key", key);
    }

    @Test
    void resolveBraveSearchApiKey_未設定ならシステム全体設定にフォールバックする() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(systemSettingService.getBraveSearchApiKey()).thenReturn("system-key");

        String key = service().resolveBraveSearchApiKey(1L);

        assertEquals("system-key", key);
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
        project.setBraveSearchApiKeyEncrypted(credentialCipher.encrypt("key"));
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertTrue(service().isBraveSearchApiKeyConfigured(1L));
    }

    // ---- Google Analytics (issue #386) ----

    @Test
    void setGoogleAnalyticsCredentials_暗号化して保存する() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        service().setGoogleAnalyticsCredentials(1L, "123456789", VALID_SERVICE_ACCOUNT_JSON);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        assertEquals("123456789", captor.getValue().getGaPropertyId());
        assertEquals(VALID_SERVICE_ACCOUNT_JSON,
                credentialCipher.decrypt(captor.getValue().getGaServiceAccountJsonEncrypted()));
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
        project.setGaPropertyId("123456789");
        project.setGaServiceAccountJsonEncrypted(credentialCipher.encrypt(VALID_SERVICE_ACCOUNT_JSON));
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertTrue(service().isGoogleAnalyticsConfigured(1L));
    }

    @Test
    void clearGoogleAnalyticsCredentials_両方nullにして保存する() {
        Project project = projectWithId(1L);
        project.setGaPropertyId("123456789");
        project.setGaServiceAccountJsonEncrypted(credentialCipher.encrypt(VALID_SERVICE_ACCOUNT_JSON));
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        service().clearGoogleAnalyticsCredentials(1L);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        assertFalse(captor.getValue().hasGoogleAnalyticsCredentials());
    }

    @Test
    void resolveGoogleAnalyticsServiceAccountKey_未設定ならnull() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertNull(service().resolveGoogleAnalyticsServiceAccountKey(1L));
    }

    @Test
    void resolveGoogleAnalyticsServiceAccountKey_設定済みなら復号して解析する() {
        Project project = projectWithId(1L);
        project.setGaPropertyId("123456789");
        project.setGaServiceAccountJsonEncrypted(credentialCipher.encrypt(VALID_SERVICE_ACCOUNT_JSON));
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        GoogleServiceAccountKey key = service().resolveGoogleAnalyticsServiceAccountKey(1L);

        assertEquals("svc@example.iam.gserviceaccount.com", key.clientEmail());
    }

    // ---- AdSense (issue #387、OAuthクライアントのプロジェクト単位化はissue #407) ----

    @Test
    void setAdSenseSettings_アカウントIDとクライアントIDを保存する() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        service().setAdSenseSettings(1L, "pub-1234567890123456", "client-id");

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        assertEquals("pub-1234567890123456", captor.getValue().getAdsenseAccountId());
        assertEquals("client-id", captor.getValue().getAdsenseOauthClientId());
    }

    @Test
    void setAdSenseClientSecret_暗号化して保存する() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        service().setAdSenseClientSecret(1L, "client-secret");

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        assertEquals("client-secret",
                credentialCipher.decrypt(captor.getValue().getAdsenseOauthClientSecretEncrypted()));
    }

    @Test
    void completeAdSenseOAuth_プロジェクトのクライアント資格情報で認可コードをリフレッシュトークンに交換して暗号化保存する() {
        Project project = projectWithId(1L);
        project.setAdsenseOauthClientId("client-id");
        project.setAdsenseOauthClientSecretEncrypted(credentialCipher.encrypt("client-secret"));
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(adSenseClient.exchangeAuthorizationCode(
                "client-id", "client-secret", "auth-code", "https://example.com/callback"))
                .thenReturn(new GoogleOAuthTokens("access-token", "refresh-token"));

        service().completeAdSenseOAuth(1L, "auth-code", "https://example.com/callback");

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        assertEquals("refresh-token",
                credentialCipher.decrypt(captor.getValue().getAdsenseRefreshTokenEncrypted()));
    }

    @Test
    void getAdSenseStatus_accountIdとrefreshTokenの両方が必要() {
        Project project = projectWithId(1L);
        project.setAdsenseAccountId("pub-1234567890123456");
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertFalse(service().getAdSenseStatus(1L).configured());

        project.setAdsenseRefreshTokenEncrypted(credentialCipher.encrypt("refresh-token"));
        assertTrue(service().getAdSenseStatus(1L).configured());
    }

    @Test
    void clearAdSenseCredentials_全項目nullにして保存する() {
        Project project = projectWithId(1L);
        project.setAdsenseAccountId("pub-1234567890123456");
        project.setAdsenseRefreshTokenEncrypted(credentialCipher.encrypt("refresh-token"));
        project.setAdsenseOauthClientId("client-id");
        project.setAdsenseOauthClientSecretEncrypted(credentialCipher.encrypt("client-secret"));
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        service().clearAdSenseCredentials(1L);

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectRepository).save(captor.capture());
        Project saved = captor.getValue();
        assertFalse(saved.hasAdsenseCredentials());
        assertFalse(saved.hasAdsenseOauthClient());
    }

    @Test
    void resolveAdSenseRefreshToken_未設定ならnull() {
        Project project = projectWithId(1L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertNull(service().resolveAdSenseRefreshToken(1L));
    }

    @Test
    void resolveAdSenseRefreshToken_設定済みなら復号する() {
        Project project = projectWithId(1L);
        project.setAdsenseAccountId("pub-1234567890123456");
        project.setAdsenseRefreshTokenEncrypted(credentialCipher.encrypt("refresh-token"));
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertEquals("refresh-token", service().resolveAdSenseRefreshToken(1L));
    }

    @Test
    void resolveAdSenseOauthClientSecret_設定済みなら復号する() {
        Project project = projectWithId(1L);
        project.setAdsenseOauthClientSecretEncrypted(credentialCipher.encrypt("client-secret"));
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertEquals("client-secret", service().resolveAdSenseOauthClientSecret(1L));
    }

    @Test
    void setAdSenseSettings_プロジェクトメンバーでなければForbidden() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class,
                () -> service().setAdSenseSettings(1L, "pub-1234567890123456", "client-id"));
    }

}
