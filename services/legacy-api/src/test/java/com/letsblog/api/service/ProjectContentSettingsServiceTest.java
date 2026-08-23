package com.letsblog.api.service;

import com.letsblog.api.domain.ProjectContentSettings;
import com.letsblog.api.repository.ProjectContentSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ProjectContentSettingsServiceの回帰テスト(issue #571)。project_content_settingsは初回書き込み時に
 * 遅延作成されることを検証する。
 */
@ExtendWith(MockitoExtension.class)
class ProjectContentSettingsServiceTest {

    @Mock
    private ProjectContentSettingsRepository repository;

    private ProjectContentSettingsService service() {
        return new ProjectContentSettingsService(repository);
    }

    @Test
    void getCssSelectorPrefix_未設定なら行が無くてもnullを返す() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());

        assertNull(service().getCssSelectorPrefix(1L));
    }

    @Test
    void updateCssSelectorPrefix_新規行を作成して保存する() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(repository.save(any(ProjectContentSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectContentSettings result = service().updateCssSelectorPrefix(1L, "custom-prefix");

        assertEquals(1L, result.getProjectId());
        assertEquals("custom-prefix", result.getCssSelectorPrefix());
    }

    @Test
    void updateCssSelectorPrefix_既存行を更新する() {
        ProjectContentSettings existing = new ProjectContentSettings(1L);
        existing.setCssSelectorPrefix("old-prefix");
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(ProjectContentSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service().updateCssSelectorPrefix(1L, "new-prefix");

        assertEquals("new-prefix", existing.getCssSelectorPrefix());
    }
}
