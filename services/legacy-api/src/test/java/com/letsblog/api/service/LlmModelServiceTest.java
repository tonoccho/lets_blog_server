package com.letsblog.api.service;

import com.letsblog.api.ai.AiProvider;
import com.letsblog.api.ai.LlmConfigProvider;
import com.letsblog.api.dto.LlmModelListResponse;
import com.letsblog.api.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * LlmModelServiceの回帰テスト(issue #571)。データがproject_ai_settings(ProjectAiSettingsService)に
 * 移った後も、未選択時のグローバルデフォルトへのフォールバックと、存在しないプロジェクトIDに対する
 * 例外送出が維持されていることを検証する。
 */
@ExtendWith(MockitoExtension.class)
class LlmModelServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ProjectAiSettingsService projectAiSettingsService;
    @Mock
    private LlmConfigProvider llmConfigProvider;

    private LlmModelService service() {
        return new LlmModelService(projectRepository, projectAiSettingsService, llmConfigProvider,
                "gpt-4o-mini", "gpt-4o-mini,gpt-4o");
    }

    @Test
    void getSelectedModel_未選択ならグローバルデフォルトを返す() {
        lenient().when(projectRepository.existsById(1L)).thenReturn(true);
        when(projectAiSettingsService.getLlmModel(1L)).thenReturn(null);

        assertEquals("gpt-4o-mini", service().getSelectedModel(1L));
    }

    @Test
    void getSelectedModel_選択済みならその値を返す() {
        lenient().when(projectRepository.existsById(1L)).thenReturn(true);
        when(projectAiSettingsService.getLlmModel(1L)).thenReturn("gpt-4o");

        assertEquals("gpt-4o", service().getSelectedModel(1L));
    }

    @Test
    void getSelectedModel_存在しないプロジェクトは例外() {
        when(projectRepository.existsById(99L)).thenReturn(false);

        assertThrows(ProjectNotFoundException.class, () -> service().getSelectedModel(99L));
    }

    @Test
    void selectModel_ProjectAiSettingsServiceへ保存する() {
        when(projectRepository.existsById(1L)).thenReturn(true);
        when(projectAiSettingsService.getLlmModel(1L)).thenReturn("gpt-4o");

        LlmModelListResponse response = service().selectModel(1L, "gpt-4o");

        org.mockito.Mockito.verify(projectAiSettingsService).setLlmModel(1L, "gpt-4o");
        assertEquals("gpt-4o", response.selected());
    }

    @Test
    void getSelectedProvider_未選択ならグローバル設定のプロバイダーを返す() {
        when(projectRepository.existsById(1L)).thenReturn(true);
        when(projectAiSettingsService.getLlmProvider(1L)).thenReturn(null);
        when(llmConfigProvider.provider()).thenReturn(AiProvider.OLLAMA);

        assertEquals(AiProvider.OLLAMA, service().getSelectedProvider(1L));
    }

    @Test
    void getSelectedProvider_選択済みならその値を返す() {
        when(projectRepository.existsById(1L)).thenReturn(true);
        when(projectAiSettingsService.getLlmProvider(1L)).thenReturn("OPENAI");

        assertEquals(AiProvider.OPENAI, service().getSelectedProvider(1L));
    }

    @Test
    void selectProvider_空文字はプロジェクト単位の上書きを解除する() {
        when(projectRepository.existsById(1L)).thenReturn(true);
        when(projectAiSettingsService.getLlmProvider(1L)).thenReturn(null);

        service().selectProvider(1L, "");

        org.mockito.Mockito.verify(projectAiSettingsService).setLlmProvider(1L, null);
    }
}
