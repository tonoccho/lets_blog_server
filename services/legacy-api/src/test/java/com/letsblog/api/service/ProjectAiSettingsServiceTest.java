package com.letsblog.api.service;

import com.letsblog.api.domain.ProjectAiSettings;
import com.letsblog.api.repository.ProjectAiSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectAiSettingsServiceの回帰テスト(issue #571)。project_ai_settingsは初回書き込み時に
 * 遅延作成されること、未設定プロジェクトの読み取りはnull/falseへフォールバックすることを検証する。
 */
@ExtendWith(MockitoExtension.class)
class ProjectAiSettingsServiceTest {

    @Mock
    private ProjectAiSettingsRepository repository;

    private ProjectAiSettingsService service() {
        return new ProjectAiSettingsService(repository);
    }

    @Test
    void getLlmModel_未設定なら行が無くてもnullを返す() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());

        assertNull(service().getLlmModel(1L));
    }

    @Test
    void setLlmModel_行が無ければ新規作成して保存する() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(repository.save(any(ProjectAiSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service().setLlmModel(1L, "gpt-4o");

        ArgumentCaptor<ProjectAiSettings> captor = ArgumentCaptor.forClass(ProjectAiSettings.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        ProjectAiSettings saved = captor.getValue();
        assertEquals(1L, saved.getProjectId());
        assertEquals("gpt-4o", saved.getLlmModel());
    }

    @Test
    void setLlmModel_既存行があれば更新する() {
        ProjectAiSettings existing = new ProjectAiSettings(1L);
        existing.setLlmModel("old-model");
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(ProjectAiSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service().setLlmModel(1L, "new-model");

        assertEquals("new-model", existing.getLlmModel());
    }

    @Test
    void hasBraveSearchApiKey_未設定行ならfalse() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());

        assertFalse(service().hasBraveSearchApiKey(1L));
    }

    @Test
    void setBraveSearchApiKeyEncrypted_暗号化済みバイト列を保存する() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(repository.save(any(ProjectAiSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service().setBraveSearchApiKeyEncrypted(1L, new byte[]{1, 2, 3});

        ArgumentCaptor<ProjectAiSettings> captor = ArgumentCaptor.forClass(ProjectAiSettings.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertTrue(captor.getValue().hasBraveSearchApiKey());
    }

    @Test
    void getLlmProvider_設定済みならその値を返す() {
        ProjectAiSettings existing = new ProjectAiSettings(1L);
        existing.setLlmProvider("OPENAI");
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));

        assertEquals("OPENAI", service().getLlmProvider(1L));
    }
}
