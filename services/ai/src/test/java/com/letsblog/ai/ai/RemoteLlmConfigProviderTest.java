package com.letsblog.ai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.ai.service.ProjectAiSettingsService;
import com.letsblog.ai.service.CurrentActorService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** provider別の選択可能モデル一覧の解決(issue #1088)。 */
class RemoteLlmConfigProviderTest {

    private final PlatformServiceClient client = mock(PlatformServiceClient.class);
    private final CurrentActorService actor = mock(CurrentActorService.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final ProjectAiSettingsService projectSettings = mock(ProjectAiSettingsService.class);
    private final RemoteLlmConfigProvider provider =
            new RemoteLlmConfigProvider(client, actor, request, projectSettings);

    private void stubSystemConfig(String provider, String baseUrl) {
        when(actor.getAuthorizationHeader()).thenReturn("Bearer t");
        when(client.resolveLlmConfig(provider, "Bearer t")).thenReturn(
                new PlatformServiceClient.LlmConfig(provider, baseUrl, "", "m", List.of("m"), 10L));
    }

    @Test
    void availableModelsFor_指定providerで解決した一覧を返す() {
        when(actor.getAuthorizationHeader()).thenReturn("Bearer t");
        when(client.resolveLlmConfig("OLLAMA", "Bearer t")).thenReturn(
                new PlatformServiceClient.LlmConfig("OLLAMA", "u", "", "qwen", List.of("qwen"), 10L));

        assertEquals(List.of("qwen"), provider.availableModelsFor(AiProvider.OLLAMA));
    }

    @Test
    void availableModelsFor_同一リクエスト内では往復を増やさない() {
        when(actor.getAuthorizationHeader()).thenReturn("Bearer t");
        when(client.resolveLlmConfig("CLAUDE", "Bearer t")).thenReturn(
                new PlatformServiceClient.LlmConfig("CLAUDE", "u", "k", "c", List.of("c"), 10L));

        provider.availableModelsFor(AiProvider.CLAUDE);
        provider.availableModelsFor(AiProvider.CLAUDE);

        verify(client, times(1)).resolveLlmConfig("CLAUDE", "Bearer t");
    }

    // ---- Ollama接続先のプロジェクト単位上書き(issue #1503) ----

    @Test
    void baseUrlFor_OLLAMAでプロジェクトの上書きがあればそれを使う() {
        stubSystemConfig("OLLAMA", "http://ollama:11434/v1");
        when(projectSettings.getOllamaBaseUrl(7L)).thenReturn("http://gpu:11434/v1");

        provider.useProject(7L);

        assertEquals("http://gpu:11434/v1", provider.baseUrlFor(AiProvider.OLLAMA));
    }

    @Test
    void baseUrlFor_上書きがnullまたは空ならシステム設定の解決結果を使う() {
        stubSystemConfig("OLLAMA", "http://ollama:11434/v1");
        provider.useProject(7L);

        when(projectSettings.getOllamaBaseUrl(7L)).thenReturn(null);
        assertEquals("http://ollama:11434/v1", provider.baseUrlFor(AiProvider.OLLAMA));

        when(projectSettings.getOllamaBaseUrl(7L)).thenReturn("  ");
        assertEquals("http://ollama:11434/v1", provider.baseUrlFor(AiProvider.OLLAMA));
    }

    @Test
    void baseUrlFor_プロジェクト未指定ならシステム設定を使い上書きを引かない() {
        stubSystemConfig("OLLAMA", "http://ollama:11434/v1");

        assertEquals("http://ollama:11434/v1", provider.baseUrlFor(AiProvider.OLLAMA));

        org.mockito.Mockito.verifyNoInteractions(projectSettings);
    }

    @Test
    void useProject_nullで範囲を解除する() {
        stubSystemConfig("OLLAMA", "http://ollama:11434/v1");
        when(projectSettings.getOllamaBaseUrl(7L)).thenReturn("http://gpu:11434/v1");
        provider.useProject(7L);
        provider.useProject(null);

        assertEquals("http://ollama:11434/v1", provider.baseUrlFor(AiProvider.OLLAMA));
    }

    @Test
    void baseUrlFor_OLLAMA以外のプロバイダーには上書きを適用しない() {
        stubSystemConfig("OPENAI", "https://api.openai.com/v1");
        provider.useProject(7L);

        assertEquals("https://api.openai.com/v1", provider.baseUrlFor(AiProvider.OPENAI));
        assertEquals(LlmClient.ANTHROPIC_BASE_URL, provider.baseUrlFor(AiProvider.CLAUDE));

        verify(projectSettings, never()).getOllamaBaseUrl(7L);
    }
}
