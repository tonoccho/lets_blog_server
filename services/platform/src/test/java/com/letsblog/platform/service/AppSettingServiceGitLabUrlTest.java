package com.letsblog.platform.service;

import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.platform.domain.SystemSetting;
import com.letsblog.platform.repository.SystemSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GitLabの接続先(system_settingsのgitlab_base_url、issue #1796)。ComfyUI / Penpotと同じくシステム全体で1つ、
 * DBだけで決まる(環境変数既定なし)。lbx-* のホスト名を指定でき、ConnectionDestinationGuardの検査は通さない。
 */
@ExtendWith(MockitoExtension.class)
class AppSettingServiceGitLabUrlTest {

    @Mock
    private SystemSettingRepository repository;
    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private final CredentialCipher credentialCipher = new CredentialCipher(
            java.util.Base64.getEncoder().encodeToString(new byte[32]));

    private AppSettingService service;

    @BeforeEach
    void setUp() {
        service = new AppSettingService(
                repository, credentialCipher, adminAuthorizationService,
                "gpt-4o-mini", "gpt-4o-mini,gpt-4o", "120",
                "OPENAI", "claude-3-5-haiku-20241022",
                "qwen2.5:7b-instruct",
                "smtp.example.com", "587", "env-user", "env-pass", "noreply@example.com",
                "http://localhost:3000", "10", "wp-admin");
        lenient().when(repository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void システム設定の一覧にgitlab_base_urlの項目があり未設定として返る() {
        AppSettingService.SettingStatus status = service.getAllSettings().stream()
                .filter(s -> s.key().equals("gitlab_base_url")).findFirst().orElseThrow();

        assertEquals(AppSettingService.SettingSource.NONE, status.source());
        assertEquals("GitLab ベースURL", status.label());
    }

    @Test
    void gitlab_base_urlはDBに無ければ空文字で環境変数へは落ちない() {
        assertEquals("", service.getGitLabBaseUrl());
    }

    @Test
    void gitlab_base_urlにlbxのホスト名のURLを保存でき解決される() {
        service.updateSettings(Map.of("gitlab_base_url", "http://lbx-gitlab"));

        ArgumentCaptor<SystemSetting> saved = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository).save(saved.capture());
        assertEquals("gitlab_base_url", saved.getValue().getSettingKey());
        when(repository.findById("gitlab_base_url")).thenReturn(Optional.of(saved.getValue()));
        assertEquals("http://lbx-gitlab", service.getGitLabBaseUrl());
    }

    @Test
    void gitlab_base_urlがURL形式でなければ例外で保存しない() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.updateSettings(Map.of("gitlab_base_url", "not-a-url")));

        assertTrue(e.getMessage().contains("gitlab_base_url"), e.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    void gitlab_base_urlを空で保存すると削除される() {
        service.updateSettings(Map.of("gitlab_base_url", ""));

        verify(repository).deleteById("gitlab_base_url");
    }
}
