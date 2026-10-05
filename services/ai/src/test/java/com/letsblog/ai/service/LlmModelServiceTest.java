package com.letsblog.ai.service;

import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.ai.LlmConfigProvider;
import com.letsblog.ai.dto.LlmModelListResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * LlmModelServiceの回帰テスト(issue #571/#574)。データがproject_ai_settings(ProjectAiSettingsService)に
 * 移った後も、未選択時のグローバルデフォルトへのフォールバックが維持されていることを検証する。
 *
 * <p>issue #574でai-serviceへ移設した際、projectId存在チェック(ProjectRepository、legacy-apiに
 * 残る)は削除した(LlmModelServiceのJavadoc参照)。存在しないプロジェクトIDに対する
 * ProjectNotFoundExceptionのテストケースはそのため削除している。
 */
@ExtendWith(MockitoExtension.class)
class LlmModelServiceTest {

    @Mock
    private ProjectAiSettingsService projectAiSettingsService;
    @Mock
    private LlmConfigProvider llmConfigProvider;

    private LlmModelService service() {
        return new LlmModelService(projectAiSettingsService, llmConfigProvider);
    }

    @Test
    void getSelectedModel_プロバイダー指定で未選択ならそのプロバイダーのシステム既定を返す() {
        when(projectAiSettingsService.getLlmModel(1L, AiProvider.CLAUDE)).thenReturn(null);
        when(llmConfigProvider.defaultModelFor(AiProvider.CLAUDE)).thenReturn("claude-3-5-haiku-20241022");

        assertEquals("claude-3-5-haiku-20241022", service().getSelectedModel(1L, AiProvider.CLAUDE));
    }

    @Test
    void getSelectedModel_プロバイダー指定で空白ならそのプロバイダーのシステム既定を返す() {
        when(projectAiSettingsService.getLlmModel(1L, AiProvider.CLAUDE)).thenReturn("  ");
        when(llmConfigProvider.defaultModelFor(AiProvider.CLAUDE)).thenReturn("claude-3-5-haiku-20241022");

        assertEquals("claude-3-5-haiku-20241022", service().getSelectedModel(1L, AiProvider.CLAUDE));
    }

    @Test
    void getSelectedModel_プロバイダー指定で選択済みならその値を返す() {
        when(projectAiSettingsService.getLlmModel(1L, AiProvider.OPENAI)).thenReturn("gpt-4o");

        assertEquals("gpt-4o", service().getSelectedModel(1L, AiProvider.OPENAI));
    }

    @Test
    void getSelectedModel_プロバイダーがnullならシステム既定プロバイダーで解決する() {
        when(llmConfigProvider.provider()).thenReturn(AiProvider.OLLAMA);
        when(projectAiSettingsService.getLlmModel(1L, AiProvider.OLLAMA)).thenReturn(null);
        when(llmConfigProvider.defaultModelFor(AiProvider.OLLAMA)).thenReturn("qwen2.5:7b-instruct");

        assertEquals("qwen2.5:7b-instruct", service().getSelectedModel(1L, null));
    }

    @Test
    void getSelectedModel_プロバイダー未指定ならプロジェクトの選択中プロバイダーのモデルを返す() {
        when(projectAiSettingsService.getLlmProvider(1L)).thenReturn("CLAUDE");
        when(projectAiSettingsService.getLlmModel(1L, AiProvider.CLAUDE)).thenReturn("claude-opus");

        assertEquals("claude-opus", service().getSelectedModel(1L));
    }

    @Test
    void getSelectedModel_プロバイダー未指定で未選択ならシステム既定プロバイダーの既定モデルを返す() {
        when(projectAiSettingsService.getLlmProvider(1L)).thenReturn(null);
        when(llmConfigProvider.provider()).thenReturn(AiProvider.OLLAMA);
        when(projectAiSettingsService.getLlmModel(1L, AiProvider.OLLAMA)).thenReturn(null);
        when(llmConfigProvider.defaultModelFor(AiProvider.OLLAMA)).thenReturn("qwen2.5:7b-instruct");

        assertEquals("qwen2.5:7b-instruct", service().getSelectedModel(1L));
    }

    @Test
    void listModelsForProject_プロジェクトのproviderに対応する一覧とそのproviderのモデルを返す() {
        when(projectAiSettingsService.getLlmProvider(1L)).thenReturn("OPENAI");
        when(projectAiSettingsService.getLlmModel(1L, AiProvider.OPENAI)).thenReturn("gpt-4o");
        when(llmConfigProvider.availableModelsFor(AiProvider.OPENAI)).thenReturn(List.of("gpt-4o-mini", "gpt-4o"));

        LlmModelListResponse response = service().listModelsForProject(1L);

        assertEquals(List.of("gpt-4o-mini", "gpt-4o"), response.availableModels());
        assertEquals("gpt-4o", response.selected());
    }

    @Test
    void listModelsForProject_CLAUDE上書きで未指定ならClaudeの既定モデルを選択中として返す() {
        when(projectAiSettingsService.getLlmProvider(1L)).thenReturn("CLAUDE");
        when(projectAiSettingsService.getLlmModel(1L, AiProvider.CLAUDE)).thenReturn(null);
        when(llmConfigProvider.defaultModelFor(AiProvider.CLAUDE)).thenReturn("claude-3-5-haiku-20241022");
        when(llmConfigProvider.availableModelsFor(AiProvider.CLAUDE)).thenReturn(List.of("claude-3-5-haiku-20241022"));

        LlmModelListResponse response = service().listModelsForProject(1L);

        assertEquals(List.of("claude-3-5-haiku-20241022"), response.availableModels());
        assertEquals("claude-3-5-haiku-20241022", response.selected());
    }

    @Test
    void listModelsForProject_上書きなしならシステム既定providerの一覧を返す() {
        when(projectAiSettingsService.getLlmProvider(1L)).thenReturn(null);
        when(llmConfigProvider.provider()).thenReturn(AiProvider.OLLAMA);
        when(projectAiSettingsService.getLlmModel(1L, AiProvider.OLLAMA)).thenReturn(null);
        when(llmConfigProvider.defaultModelFor(AiProvider.OLLAMA)).thenReturn("qwen2.5:7b-instruct");
        when(llmConfigProvider.availableModelsFor(AiProvider.OLLAMA)).thenReturn(List.of("qwen2.5:7b-instruct"));

        assertEquals(List.of("qwen2.5:7b-instruct"), service().listModelsForProject(1L).availableModels());
    }

    @Test
    void selectModel_プロジェクトの選択中プロバイダーのモデルとして保存する() {
        when(projectAiSettingsService.getLlmProvider(1L)).thenReturn("CLAUDE");
        when(projectAiSettingsService.getLlmModel(1L, AiProvider.CLAUDE)).thenReturn("claude-opus");

        LlmModelListResponse response = service().selectModel(1L, "claude-opus");

        org.mockito.Mockito.verify(projectAiSettingsService).setLlmModel(1L, AiProvider.CLAUDE, "claude-opus");
        assertEquals("claude-opus", response.selected());
    }

    @Test
    void getSelectedProvider_未選択ならグローバル設定のプロバイダーを返す() {
        when(projectAiSettingsService.getLlmProvider(1L)).thenReturn(null);
        when(llmConfigProvider.provider()).thenReturn(AiProvider.OLLAMA);

        assertEquals(AiProvider.OLLAMA, service().getSelectedProvider(1L));
    }

    @Test
    void getSelectedProvider_選択済みならその値を返す() {
        when(projectAiSettingsService.getLlmProvider(1L)).thenReturn("OPENAI");

        assertEquals(AiProvider.OPENAI, service().getSelectedProvider(1L));
    }

    @Test
    void selectProvider_空文字はプロジェクト単位の上書きを解除する() {
        when(projectAiSettingsService.getLlmProvider(1L)).thenReturn(null);

        service().selectProvider(1L, "");

        org.mockito.Mockito.verify(projectAiSettingsService).setLlmProvider(1L, null);
    }
}
