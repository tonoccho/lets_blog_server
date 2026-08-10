package com.letsblog.api.service;

import com.letsblog.api.crypto.CredentialCipher;
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

    private final CredentialCipher credentialCipher = new CredentialCipher(
            java.util.Base64.getEncoder().encodeToString(new byte[32]));

    private ProjectApiKeyService service() {
        return new ProjectApiKeyService(
                projectRepository, credentialCipher, userService, systemSettingService, adminAuthorizationService);
    }

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
}
