package com.letsblog.ai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.ai.service.ProjectAiSettingsService;
import com.letsblog.ai.service.CurrentActorService;
import com.letsblog.common.crypto.CredentialCipher;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** provider別の選択可能モデル一覧の解決(issue #1088)。 */
class RemoteLlmConfigProviderTest {

    private final PlatformServiceClient client = mock(PlatformServiceClient.class);
    private final CurrentActorService actor = mock(CurrentActorService.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final ProjectAiSettingsService projectSettings = mock(ProjectAiSettingsService.class);
    private final CredentialCipher cipher = mock(CredentialCipher.class);
    private final RemoteLlmConfigProvider provider =
            new RemoteLlmConfigProvider(client, actor, request, projectSettings, cipher);

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

    // ---- ChatGPT(OPENAI)のベースURLはコード内の定数(issue #1569) ----

    @Test
    void baseUrlFor_OPENAIはplatformが別のURLを返しても固定のOpenAIを返す() {
        stubSystemConfig("OPENAI", "https://example.test/v1");

        assertEquals("https://api.openai.com/v1", provider.baseUrlFor(AiProvider.OPENAI));
        assertEquals("https://api.openai.com/v1", LlmClient.OPENAI_BASE_URL);
    }

    @Test
    void targetFor_OPENAIも固定のOpenAIへ向く() {
        stubSystemConfig("OPENAI", "https://example.test/v1");

        assertEquals("https://api.openai.com/v1", provider.targetFor(AiProvider.OPENAI).baseUrl());
    }

    // ---- 受け入れテスト環境だけがスタブへ向く(issue #1569) ----

    @Test
    void e2e_stubsプロファイルでだけOPENAIの接続先がスタブになる() {
        org.springframework.mock.env.MockEnvironment production = new org.springframework.mock.env.MockEnvironment();
        org.springframework.mock.env.MockEnvironment stubs = new org.springframework.mock.env.MockEnvironment();
        stubs.setActiveProfiles("e2e-stubs");

        assertEquals("https://api.openai.com/v1",
                new RemoteLlmConfigProvider(client, actor, request, projectSettings, cipher, production)
                        .baseUrlFor(AiProvider.OPENAI));
        RemoteLlmConfigProvider underStubs =
                new RemoteLlmConfigProvider(client, actor, request, projectSettings, cipher, stubs);
        assertEquals("http://llm-stub:8080", underStubs.baseUrlFor(AiProvider.OPENAI));
        assertEquals("http://llm-stub:8080", underStubs.targetFor(AiProvider.OPENAI).baseUrl());
        // Claudeはスタブ化しない
        assertEquals(LlmClient.ANTHROPIC_BASE_URL, underStubs.baseUrlFor(AiProvider.CLAUDE));
    }

    // ---- ChatGPT(OPENAI)のAPIキーのプロジェクト単位上書き(issue #1506) ----

    private void stubSystemOpenAiKey(String systemKey) {
        when(actor.getAuthorizationHeader()).thenReturn("Bearer t");
        when(client.resolveLlmConfig("OPENAI", "Bearer t")).thenReturn(new PlatformServiceClient.LlmConfig(
                "OPENAI", "https://api.openai.com/v1", systemKey, "gpt", List.of("gpt"), 10L));
    }

    @Test
    void apiKeyFor_OPENAIでプロジェクトのキーがあればシステム設定より優先して復号した値を使う() {
        stubSystemOpenAiKey("sk-system");
        byte[] encrypted = {1, 2, 3};
        when(projectSettings.getOpenAiApiKeyEncrypted(7L)).thenReturn(encrypted);
        when(cipher.decrypt(encrypted)).thenReturn("sk-project");

        provider.useProject(7L);

        assertEquals("sk-project", provider.apiKeyFor(AiProvider.OPENAI));
    }

    @Test
    void apiKeyFor_OPENAIでキーを保存していないプロジェクトはシステム設定のキーへ落ちずエラーになる() {
        stubSystemOpenAiKey("sk-system");
        provider.useProject(8L);

        when(projectSettings.getOpenAiApiKeyEncrypted(8L)).thenReturn(null);
        AiServiceException e1 = assertThrows(AiServiceException.class, () -> provider.apiKeyFor(AiProvider.OPENAI));
        assertTrue(e1.getMessage().contains("このプロジェクトでAPIキーを設定してください"), "実際: " + e1.getMessage());
        assertFalse(e1.getMessage().contains("sk-system"));

        when(projectSettings.getOpenAiApiKeyEncrypted(8L)).thenReturn(new byte[0]);
        assertThrows(AiServiceException.class, () -> provider.apiKeyFor(AiProvider.OPENAI));

        verify(cipher, never()).decrypt(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void apiKeyFor_OPENAIでプロジェクト未指定ならシステム設定のキーへ落ちずエラーになる() {
        stubSystemOpenAiKey("sk-system");

        AiServiceException e = assertThrows(AiServiceException.class, () -> provider.apiKeyFor(AiProvider.OPENAI));
        assertTrue(e.getMessage().contains("このプロジェクトでAPIキーを設定してください"), "実際: " + e.getMessage());

        verify(projectSettings, never()).getOpenAiApiKeyEncrypted(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void apiKeyFor_OPENAI以外のプロバイダーにはOPENAIのプロジェクトのキーを適用しない() {
        provider.useProject(7L);
        when(projectSettings.getOpenAiApiKeyEncrypted(7L)).thenReturn(new byte[] {1});
        when(projectSettings.getClaudeApiKeyEncrypted(7L)).thenReturn(null);

        assertThrows(AiServiceException.class, () -> provider.apiKeyFor(AiProvider.CLAUDE));

        verify(projectSettings, never()).getOpenAiApiKeyEncrypted(7L);
    }

    // ---- Claude(CLAUDE)のAPIキーのプロジェクト単位上書き(issue #1507) ----

    private void stubSystemClaudeKey(String systemKey) {
        when(actor.getAuthorizationHeader()).thenReturn("Bearer t");
        when(client.resolveLlmConfig("CLAUDE", "Bearer t")).thenReturn(new PlatformServiceClient.LlmConfig(
                "CLAUDE", "https://api.anthropic.com/v1", systemKey, "claude", List.of("claude"), 10L));
    }

    @Test
    void apiKeyFor_CLAUDEでプロジェクトのキーがあればシステム設定より優先して復号した値を使う() {
        stubSystemClaudeKey("sk-ant-system");
        byte[] encrypted = {7, 8, 9};
        when(projectSettings.getClaudeApiKeyEncrypted(7L)).thenReturn(encrypted);
        when(cipher.decrypt(encrypted)).thenReturn("sk-ant-project");

        provider.useProject(7L);

        assertEquals("sk-ant-project", provider.apiKeyFor(AiProvider.CLAUDE));
    }

    @Test
    void apiKeyFor_CLAUDEでキーを保存していないプロジェクトはシステム設定のキーへ落ちずエラーになる() {
        stubSystemClaudeKey("sk-ant-system");
        provider.useProject(8L);

        when(projectSettings.getClaudeApiKeyEncrypted(8L)).thenReturn(null);
        AiServiceException e = assertThrows(AiServiceException.class, () -> provider.apiKeyFor(AiProvider.CLAUDE));
        assertTrue(e.getMessage().contains("このプロジェクトでAPIキーを設定してください"), "実際: " + e.getMessage());

        when(projectSettings.getClaudeApiKeyEncrypted(8L)).thenReturn(new byte[0]);
        assertThrows(AiServiceException.class, () -> provider.apiKeyFor(AiProvider.CLAUDE));

        verify(cipher, never()).decrypt(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void apiKeyFor_CLAUDEでプロジェクト未指定ならシステム設定のキーへ落ちずエラーになる() {
        stubSystemClaudeKey("sk-ant-system");

        assertThrows(AiServiceException.class, () -> provider.apiKeyFor(AiProvider.CLAUDE));

        verify(projectSettings, never()).getClaudeApiKeyEncrypted(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void apiKeyFor_CLAUDEのプロジェクトのキーはOPENAIには適用されない() {
        provider.useProject(7L);
        when(projectSettings.getOpenAiApiKeyEncrypted(7L)).thenReturn(null);
        when(projectSettings.getClaudeApiKeyEncrypted(7L)).thenReturn(new byte[] {1});

        assertThrows(AiServiceException.class, () -> provider.apiKeyFor(AiProvider.OPENAI));

        verify(projectSettings, never()).getClaudeApiKeyEncrypted(7L);
    }

    @Test
    void apiKeyFor_OLLAMAはプロジェクトが指定されてもキーの上書きを引かずシステム設定の値を使う() {
        when(actor.getAuthorizationHeader()).thenReturn("Bearer t");
        when(client.resolveLlmConfig("OLLAMA", "Bearer t")).thenReturn(
                new PlatformServiceClient.LlmConfig("OLLAMA", "http://o", "ollama-key", "m", List.of("m"), 10L));
        provider.useProject(7L);

        assertEquals("ollama-key", provider.apiKeyFor(AiProvider.OLLAMA));

        verify(projectSettings, never()).getOpenAiApiKeyEncrypted(7L);
        verify(projectSettings, never()).getClaudeApiKeyEncrypted(7L);
    }
}
