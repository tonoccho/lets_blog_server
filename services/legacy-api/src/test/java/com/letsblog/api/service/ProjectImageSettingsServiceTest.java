package com.letsblog.api.service;

import com.letsblog.api.domain.ProjectImageSettings;
import com.letsblog.api.repository.ProjectImageSettingsRepository;
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
 * ProjectImageSettingsServiceの回帰テスト(issue #571)。project_image_settingsは初回書き込み時に
 * 遅延作成されること、各種一括更新メソッドが正しく保存することを検証する。
 */
@ExtendWith(MockitoExtension.class)
class ProjectImageSettingsServiceTest {

    @Mock
    private ProjectImageSettingsRepository repository;

    private ProjectImageSettingsService service() {
        return new ProjectImageSettingsService(repository);
    }

    @Test
    void getComfyuiCheckpoint_未設定なら行が無くてもnullを返す() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());

        assertNull(service().getComfyuiCheckpoint(1L));
    }

    @Test
    void updateImageGenerationPromptDefaults_新規行を作成して保存する() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(repository.save(any(ProjectImageSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectImageSettings result = service().updateImageGenerationPromptDefaults(1L, "bad hands", "vivid colors");

        assertEquals(1L, result.getProjectId());
        assertEquals("bad hands", result.getDefaultNegativePrompt());
        assertEquals("vivid colors", result.getDefaultQualityPrompt());
    }

    @Test
    void updateImageGenerationSizeDefaults_既存行を更新する() {
        ProjectImageSettings existing = new ProjectImageSettings(1L);
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(ProjectImageSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectImageSettings result = service().updateImageGenerationSizeDefaults(1L, 1024, 768);

        assertEquals(1024, result.getDefaultGeneratedImageWidth());
        assertEquals(768, result.getDefaultGeneratedImageHeight());
    }

    @Test
    void updateImageContentFilterSettings_値を保存する() {
        when(repository.findByProjectId(1L)).thenReturn(Optional.empty());
        when(repository.save(any(ProjectImageSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        ProjectImageSettings result = service().updateImageContentFilterSettings(1L, false, true, false);

        assertEquals(false, result.getBlockSexualContent());
        assertEquals(true, result.getBlockViolentContent());
        assertEquals(false, result.getBlockDiscriminatoryContent());
    }

    @Test
    void setImageProvider_既存行があれば更新する() {
        ProjectImageSettings existing = new ProjectImageSettings(1L);
        existing.setImageProvider("COMFYUI");
        when(repository.findByProjectId(1L)).thenReturn(Optional.of(existing));
        when(repository.save(any(ProjectImageSettings.class))).thenAnswer(inv -> inv.getArgument(0));

        service().setImageProvider(1L, "CHATGPT");

        assertEquals("CHATGPT", existing.getImageProvider());
    }
}
