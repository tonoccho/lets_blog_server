package com.letsblog.ai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.letsblog.ai.testsupport.AiWorld;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * provider別の選択可能モデル一覧の解決(issue #1088)。
 *
 * <p>issue #1824: PlatformServiceClient・CurrentActorService・ProjectAiSettingsService・CredentialCipherは実物で、
 * platform-service・identity-serviceはHTTPフェイク、DBはメモリ上のリポジトリで差し替える。
 */
class RemoteLlmConfigProviderTest {

    private static final String LLM_CONFIG = "/api/internal/platform/llm-config";

    private AiWorld world;
    private RemoteLlmConfigProvider provider;

    @BeforeEach
    void setUp() {
        world = new AiWorld().admin();
        provider = new RemoteLlmConfigProvider(
                world.platformServiceClient, world.currentActorService, world.request,
                world.projectAiSettingsService, world.credentialCipher);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private void stubSystemConfig(String provider, String baseUrl) {
        world.llmConfig(AiProvider.valueOf(provider), baseUrl, "", "m", List.of("m"), false);
    }

    private void projectOllama(long projectId, String override) {
        world.projectAiSettingsService.getOrCreate(projectId).setOllamaBaseUrl(override);
    }

    @Test
    void availableModelsFor_指定providerで解決した一覧を返す() {
        world.llmConfig(AiProvider.OLLAMA, "u", "", "qwen", List.of("qwen"), false);

        assertEquals(List.of("qwen"), provider.availableModelsFor(AiProvider.OLLAMA));
        assertEquals("provider=OLLAMA", world.http.requests("GET", LLM_CONFIG).get(0).query());
        assertEquals(AiWorld.BEARER, world.http.requests("GET", LLM_CONFIG).get(0).header("Authorization"));
    }

    @Test
    void availableModelsFor_同一リクエスト内では往復を増やさない() {
        world.llmConfig(AiProvider.CLAUDE, "u", "k", "c", List.of("c"), false);

        provider.availableModelsFor(AiProvider.CLAUDE);
        provider.availableModelsFor(AiProvider.CLAUDE);

        assertEquals(1, world.http.requests("GET", LLM_CONFIG).size());
    }

    // ---- Ollama接続先のプロジェクト単位上書き(issue #1503) ----

    @Test
    void baseUrlFor_OLLAMAでプロジェクトの上書きがあればそれを使う() {
        stubSystemConfig("OLLAMA", "http://ollama:11434/v1");
        projectOllama(7L, "http://gpu:11434/v1");

        provider.useProject(7L);

        assertEquals("http://gpu:11434/v1", provider.baseUrlFor(AiProvider.OLLAMA));
    }

    @Test
    void baseUrlFor_上書きがnullまたは空ならシステム設定の解決結果を使う() {
        stubSystemConfig("OLLAMA", "http://ollama:11434/v1");
        provider.useProject(7L);

        projectOllama(7L, null);
        assertEquals("http://ollama:11434/v1", provider.baseUrlFor(AiProvider.OLLAMA));

        projectOllama(7L, "  ");
        assertEquals("http://ollama:11434/v1", provider.baseUrlFor(AiProvider.OLLAMA));
    }

    @Test
    void baseUrlFor_プロジェクト未指定ならシステム設定を使い上書きを引かない() {
        stubSystemConfig("OLLAMA", "http://ollama:11434/v1");

        assertEquals("http://ollama:11434/v1", provider.baseUrlFor(AiProvider.OLLAMA));

        verify(world.projectAiSettingsRepository, never()).findByProjectId(any());
    }

    @Test
    void useProject_nullで範囲を解除する() {
        stubSystemConfig("OLLAMA", "http://ollama:11434/v1");
        projectOllama(7L, "http://gpu:11434/v1");
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

        // OLLAMA以外では、プロジェクトの設定行を読みにも行かない。
        verify(world.projectAiSettingsRepository, never()).findByProjectId(any());
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
                new RemoteLlmConfigProvider(
                        world.platformServiceClient, world.currentActorService, world.request,
                        world.projectAiSettingsService, world.credentialCipher, production)
                        .baseUrlFor(AiProvider.OPENAI));
        RemoteLlmConfigProvider underStubs =
                new RemoteLlmConfigProvider(
                world.platformServiceClient, world.currentActorService, world.request,
                world.projectAiSettingsService, world.credentialCipher, stubs);
        assertEquals("http://llm-stub:8080", underStubs.baseUrlFor(AiProvider.OPENAI));
        assertEquals("http://llm-stub:8080", underStubs.targetFor(AiProvider.OPENAI).baseUrl());
        // Claudeはスタブ化しない
        assertEquals(LlmClient.ANTHROPIC_BASE_URL, underStubs.baseUrlFor(AiProvider.CLAUDE));
    }

    // ---- ChatGPT(OPENAI)のAPIキーのプロジェクト単位上書き(issue #1506) ----

    private void stubSystemOpenAiKey(String systemKey) {
        world.llmConfig(AiProvider.OPENAI, "https://api.openai.com/v1", systemKey, "gpt", List.of("gpt"), false);
    }

    @Test
    void apiKeyFor_OPENAIでプロジェクトのキーがあればシステム設定より優先して復号した値を使う() {
        stubSystemOpenAiKey("sk-system");
        world.projectAiSettingsService.setOpenAiApiKeyEncrypted(7L, world.credentialCipher.encrypt("sk-project"));

        provider.useProject(7L);

        assertEquals("sk-project", provider.apiKeyFor(AiProvider.OPENAI));
    }

    @Test
    void apiKeyFor_OPENAIでキーを保存していないプロジェクトはシステム設定のキーへ落ちずエラーになる() {
        stubSystemOpenAiKey("sk-system");
        provider.useProject(8L);

        AiServiceException e1 = assertThrows(AiServiceException.class, () -> provider.apiKeyFor(AiProvider.OPENAI));
        assertTrue(e1.getMessage().contains("このプロジェクトでAPIキーを設定してください"), "実際: " + e1.getMessage());
        assertFalse(e1.getMessage().contains("sk-system"));

        world.projectAiSettingsService.setOpenAiApiKeyEncrypted(8L, new byte[0]);
        AiServiceException e2 = assertThrows(AiServiceException.class, () -> provider.apiKeyFor(AiProvider.OPENAI));
        assertFalse(e2.getMessage().contains("sk-system"));
    }

    @Test
    void apiKeyFor_OPENAIでプロジェクト未指定ならシステム設定のキーへ落ちずエラーになる() {
        stubSystemOpenAiKey("sk-system");

        AiServiceException e = assertThrows(AiServiceException.class, () -> provider.apiKeyFor(AiProvider.OPENAI));
        assertTrue(e.getMessage().contains("このプロジェクトでAPIキーを設定してください"), "実際: " + e.getMessage());

        verify(world.projectAiSettingsRepository, never()).findByProjectId(any());
    }

    @Test
    void apiKeyFor_OPENAI以外のプロバイダーにはOPENAIのプロジェクトのキーを適用しない() {
        provider.useProject(7L);
        world.projectAiSettingsService.setOpenAiApiKeyEncrypted(7L, world.credentialCipher.encrypt("sk-openai"));

        // CLAUDEのキーは未設定。OPENAIのキーがあっても、CLAUDEには使われずエラーになる。
        AiServiceException e = assertThrows(AiServiceException.class, () -> provider.apiKeyFor(AiProvider.CLAUDE));
        assertFalse(e.getMessage().contains("sk-openai"));
    }

    // ---- Claude(CLAUDE)のAPIキーのプロジェクト単位上書き(issue #1507) ----

    private void stubSystemClaudeKey(String systemKey) {
        world.llmConfig(AiProvider.CLAUDE, "https://api.anthropic.com/v1", systemKey, "claude", List.of("claude"), false);
    }

    @Test
    void apiKeyFor_CLAUDEでプロジェクトのキーがあればシステム設定より優先して復号した値を使う() {
        stubSystemClaudeKey("sk-ant-system");
        world.projectAiSettingsService.setClaudeApiKeyEncrypted(7L, world.credentialCipher.encrypt("sk-ant-project"));

        provider.useProject(7L);

        assertEquals("sk-ant-project", provider.apiKeyFor(AiProvider.CLAUDE));
    }

    @Test
    void apiKeyFor_CLAUDEでキーを保存していないプロジェクトはシステム設定のキーへ落ちずエラーになる() {
        stubSystemClaudeKey("sk-ant-system");
        provider.useProject(8L);

        AiServiceException e = assertThrows(AiServiceException.class, () -> provider.apiKeyFor(AiProvider.CLAUDE));
        assertTrue(e.getMessage().contains("このプロジェクトでAPIキーを設定してください"), "実際: " + e.getMessage());
        assertFalse(e.getMessage().contains("sk-ant-system"));

        world.projectAiSettingsService.setClaudeApiKeyEncrypted(8L, new byte[0]);
        assertThrows(AiServiceException.class, () -> provider.apiKeyFor(AiProvider.CLAUDE));
    }

    @Test
    void apiKeyFor_CLAUDEでプロジェクト未指定ならシステム設定のキーへ落ちずエラーになる() {
        stubSystemClaudeKey("sk-ant-system");

        assertThrows(AiServiceException.class, () -> provider.apiKeyFor(AiProvider.CLAUDE));

        verify(world.projectAiSettingsRepository, never()).findByProjectId(any());
    }

    @Test
    void apiKeyFor_CLAUDEのプロジェクトのキーはOPENAIには適用されない() {
        provider.useProject(7L);
        world.projectAiSettingsService.setClaudeApiKeyEncrypted(7L, world.credentialCipher.encrypt("sk-ant-project"));

        AiServiceException e = assertThrows(AiServiceException.class, () -> provider.apiKeyFor(AiProvider.OPENAI));
        assertFalse(e.getMessage().contains("sk-ant-project"));
    }

    @Test
    void apiKeyFor_OLLAMAはプロジェクトが指定されてもキーの上書きを引かずシステム設定の値を使う() {
        world.llmConfig(AiProvider.OLLAMA, "http://o", "ollama-key", "m", List.of("m"), false);
        world.projectAiSettingsService.setOpenAiApiKeyEncrypted(7L, world.credentialCipher.encrypt("sk-openai"));
        world.projectAiSettingsService.setClaudeApiKeyEncrypted(7L, world.credentialCipher.encrypt("sk-ant"));
        provider.useProject(7L);

        assertEquals("ollama-key", provider.apiKeyFor(AiProvider.OLLAMA));
    }

    // ---- extensionsのツールへのトークンを付ける宛先(#1804) ----

    @Test
    void toolsAuthorized_OLLAMAでプロジェクトの上書きが無ければ付ける() {
        stubSystemConfig("OLLAMA", "http://ollama:11434/v1");
        provider.useProject(7L);

        assertTrue(provider.toolsAuthorized(AiProvider.OLLAMA));
    }

    @Test
    void toolsAuthorized_プロジェクト指定が無い呼び出しはシステム設定の宛先なので付ける() {
        assertTrue(provider.toolsAuthorized(AiProvider.OLLAMA));
    }

    @Test
    void toolsAuthorized_OLLAMAでプロジェクトの上書きがあれば付けない() {
        projectOllama(7L, "http://gpu:11434/v1");
        provider.useProject(7L);

        assertFalse(provider.toolsAuthorized(AiProvider.OLLAMA));
    }

    @Test
    void toolsAuthorized_上書きが空文字ならシステム設定の宛先として付ける() {
        projectOllama(7L, " ");
        provider.useProject(7L);

        assertTrue(provider.toolsAuthorized(AiProvider.OLLAMA));
    }

    @Test
    void toolsAuthorized_OLLAMA以外には付けない() {
        assertFalse(provider.toolsAuthorized(AiProvider.OPENAI));
        assertFalse(provider.toolsAuthorized(AiProvider.CLAUDE));
    }

    // ---- プロバイダーがnull(=システム既定のプロバイダー)の解決(issue #1824) ----

    @Test
    void プロバイダーがnullならシステム既定のプロバイダーの設定で解決する() {
        world.llmConfig(AiProvider.OLLAMA, "http://default-ollama/v1", "key-d", "m-default", List.of("m-default"), true);

        assertEquals("http://default-ollama/v1", provider.baseUrlFor(null));
        assertEquals("m-default", provider.defaultModelFor(null));
        assertEquals(List.of("m-default"), provider.availableModelsFor(null));
        // provider指定なしの問い合わせ(クエリ無し)としてplatform-serviceに届く。
        assertTrue(world.http.requests("GET", LLM_CONFIG).stream().allMatch(request -> request.query() == null));
    }

    @Test
    void 解決結果がプロバイダー名を持たなくてもキャッシュできる() {
        // 古いplatform-serviceのように、応答が「provider」を持たない場合。
        world.http.json("GET", LLM_CONFIG, "{\"baseUrl\":\"http://o/v1\",\"apiKey\":\"\",\"defaultModel\":\"m\","
                + "\"availableModels\":[\"m\"],\"requestTimeoutSeconds\":10}");

        assertEquals("m", provider.defaultModelFor(AiProvider.OLLAMA));
        assertEquals("m", provider.defaultModelFor(AiProvider.OLLAMA));
        assertEquals(1, world.http.requests("GET", LLM_CONFIG).size());
    }
}
