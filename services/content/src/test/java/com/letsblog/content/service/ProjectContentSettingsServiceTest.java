package com.letsblog.content.service;

import com.letsblog.content.client.ProjectBridgeClient;
import com.letsblog.content.domain.ProjectContentSettings;
import com.letsblog.content.repository.ProjectContentSettingsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ProjectContentSettingsServiceの回帰テスト(issue #571、issue #576でcontent-serviceへ移管)。
 * project_content_settingsは初回書き込み時に遅延作成されることに加え、cssSelectorPrefix未設定時に
 * legacy-api側のプロジェクトslugへフォールバックする解決ロジック(旧ProjectService#resolveCssSelectorPrefix)
 * を検証する。
 */
@ExtendWith(MockitoExtension.class)
class ProjectContentSettingsServiceTest {

    @Mock
    private ProjectContentSettingsRepository repository;

    @Mock
    private ProjectBridgeClient projectBridgeClient;

    @Mock
    private CurrentActorService currentActorService;

    @Mock
    private LetsblogSyncNotifier letsblogSyncNotifier;

    private ProjectContentSettingsService service() {
        return new ProjectContentSettingsService(repository, projectBridgeClient, currentActorService, letsblogSyncNotifier);
    }

    @Test
    void updateCssSelectorPrefix_保存したらそのプロジェクトのサイトへの同期を依頼する() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(repository.save(any(ProjectContentSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service().updateCssSelectorPrefix(1L, "custom-prefix");

        org.mockito.Mockito.verify(letsblogSyncNotifier).notifyProjectChanged(1L);
    }

    @Test
    void getOrCreate_だけでは同期を依頼しない() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(repository.save(any(ProjectContentSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service().getOrCreate(1L);

        verifyNoInteractions(letsblogSyncNotifier);
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

    @Test
    void resolveCssSelectorPrefix_設定済みならlegacy_apiへ問い合わせずその値を返す() {
        ProjectContentSettings existing = new ProjectContentSettings(1L);
        existing.setCssSelectorPrefix("custom-prefix");
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));

        assertEquals("custom-prefix", service().resolveCssSelectorPrefix(1L));
        verifyNoInteractions(projectBridgeClient);
    }

    @Test
    void resolveCssSelectorPrefix_未設定時はlegacy_api経由でプロジェクトslugへフォールバックする() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());
        lenient().when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer token");
        when(projectBridgeClient.resolveProjectSlug(1L, "Bearer token")).thenReturn("proj-a");

        assertEquals("proj-a", service().resolveCssSelectorPrefix(1L));
    }
}
