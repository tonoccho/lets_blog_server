package com.letsblog.ai.service;

import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.dto.LlmModelListResponse;
import com.letsblog.ai.dto.LlmProviderListResponse;
import com.letsblog.ai.testsupport.AiWorld;
import com.letsblog.ai.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LlmModelServiceの回帰テスト(issue #571/#574)。データがproject_ai_settings(ProjectAiSettingsService)に
 * 移った後も、未選択時のグローバルデフォルトへのフォールバックが維持されていることを検証する。
 *
 * <p>issue #1823: ProjectAiSettingsService・RemoteLlmConfigProvider・ProviderModelCatalogは実物を使い、
 * platform-serviceと各プロバイダーのモデル一覧APIはHTTPフェイク、project_ai_settingsはメモリ上のリポジトリで差し替える。
 *
 * <p>issue #574でai-serviceへ移設した際、projectId存在チェック(ProjectRepository、legacy-apiに
 * 残る)は削除した(LlmModelServiceのJavadoc参照)。存在しないプロジェクトIDに対する
 * ProjectNotFoundExceptionのテストケースはそのため削除している。
 */
class LlmModelServiceTest {

    private AiWorld world;
    private LlmModelService service;

    @BeforeEach
    void setUp() {
        world = new AiWorld().admin();
        service = world.llmModelService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private void systemConfig(AiProvider provider, String defaultModel, boolean isDefault) {
        world.llmConfig(provider, world.http.baseUrl() + "/v1", "", defaultModel, List.of(defaultModel), isDefault);
    }

    private void projectKey(AiProvider provider, String key) {
        byte[] encrypted = world.credentialCipher.encrypt(key);
        if (provider == AiProvider.OPENAI) {
            world.projectAiSettingsService.setOpenAiApiKeyEncrypted(1L, encrypted);
        } else {
            world.projectAiSettingsService.setClaudeApiKeyEncrypted(1L, encrypted);
        }
    }

    @Test
    void getSelectedModel_プロバイダー指定で未選択ならそのプロバイダーのシステム既定を返す() {
        systemConfig(AiProvider.CLAUDE, "claude-3-5-haiku-20241022", false);

        assertEquals("claude-3-5-haiku-20241022", service.getSelectedModel(1L, AiProvider.CLAUDE));
    }

    @Test
    void getSelectedModel_プロバイダー指定で空白ならそのプロバイダーのシステム既定を返す() {
        systemConfig(AiProvider.CLAUDE, "claude-3-5-haiku-20241022", false);
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.CLAUDE, "  ");

        assertEquals("claude-3-5-haiku-20241022", service.getSelectedModel(1L, AiProvider.CLAUDE));
    }

    @Test
    void getSelectedModel_プロバイダー別の値が無く旧値が空白ならそのプロバイダーのシステム既定を返す() {
        // 旧値(全プロバイダー共通のllm_model)が空白だけの行。プロバイダー別の値が無いので旧値が使われ、空白なら既定へ戻る。
        systemConfig(AiProvider.OPENAI, "gpt-default", false);
        world.projectAiSettingsService.getOrCreate(1L).setLlmModel("  ");

        assertEquals("gpt-default", service.getSelectedModel(1L, AiProvider.OPENAI));
    }

    @Test
    void getSelectedModel_プロバイダー指定で選択済みならその値を返す() {
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.OPENAI, "gpt-4o");

        assertEquals("gpt-4o", service.getSelectedModel(1L, AiProvider.OPENAI));
        assertEquals(0, world.http.requests().stream().filter(r -> r.path().contains("llm-config")).count());
    }

    @Test
    void getSelectedModel_プロバイダーがnullならシステム既定プロバイダーで解決する() {
        systemConfig(AiProvider.OLLAMA, "qwen2.5:7b-instruct", true);

        assertEquals("qwen2.5:7b-instruct", service.getSelectedModel(1L, null));
    }

    @Test
    void getSelectedModel_プロバイダー未指定ならプロジェクトの選択中プロバイダーのモデルを返す() {
        world.projectAiSettingsService.setLlmProvider(1L, "CLAUDE");
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.CLAUDE, "claude-opus");

        assertEquals("claude-opus", service.getSelectedModel(1L));
    }

    @Test
    void getSelectedModel_プロバイダー未指定で未選択ならシステム既定プロバイダーの既定モデルを返す() {
        systemConfig(AiProvider.OLLAMA, "qwen2.5:7b-instruct", true);

        assertEquals("qwen2.5:7b-instruct", service.getSelectedModel(1L));
    }

    @Test
    void listModelsForProject_プロバイダーから取得した一覧とそのproviderのモデルを返す() {
        world.projectAiSettingsService.setLlmProvider(1L, "OPENAI");
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.OPENAI, "gpt-4o");
        projectKey(AiProvider.OPENAI, "sk-project");
        world.http.json("GET", "/cloud/v1/models", "{\"data\":[{\"id\":\"gpt-4o-mini\"},{\"id\":\"gpt-4o\"}]}");

        LlmModelListResponse response = service.listModelsForProject(1L);

        assertEquals(List.of("gpt-4o-mini", "gpt-4o"), response.availableModels());
        assertEquals("gpt-4o", response.selected());
        assertFalse(response.fallback());
        // 取得できたので、システム設定の一覧(platform-serviceへの問い合わせ)には戻らない。
        assertEquals(0, world.http.requests("GET", "/api/internal/platform/llm-config").size());
    }

    @Test
    void listModelsForProject_問い合わせ前に対象プロジェクトを宣言する() {
        // 対象プロジェクトが宣言されていなければ、プロジェクトのAPIキーを引けず問い合わせ自体が行われない。
        world.projectAiSettingsService.setLlmProvider(1L, "OPENAI");
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.OPENAI, "m");
        projectKey(AiProvider.OPENAI, "sk-project");
        world.http.json("GET", "/cloud/v1/models", "{\"data\":[{\"id\":\"m\"}]}");

        service.listModelsForProject(1L);

        List<FakeHttpServer.Request> asked = world.http.requests("GET", "/cloud/v1/models");
        assertEquals(1, asked.size());
        assertEquals("Bearer sk-project", asked.get(0).header("Authorization"));
    }

    @Test
    void listModelsForProject_取得に失敗したらシステム設定の一覧に戻しfallbackを立てる() {
        // CLAUDEのキーが無いので問い合わせずに失敗し、システム設定(platform-service)の一覧に戻る。
        world.projectAiSettingsService.setLlmProvider(1L, "CLAUDE");
        world.llmConfig(AiProvider.CLAUDE, null, "", "claude-3-5-haiku-20241022",
                List.of("claude-3-5-haiku-20241022"), false);

        LlmModelListResponse response = service.listModelsForProject(1L);

        assertEquals(List.of("claude-3-5-haiku-20241022"), response.availableModels());
        assertEquals("claude-3-5-haiku-20241022", response.selected());
        assertTrue(response.fallback());
        assertEquals(0, world.http.requests("GET", "/cloud/v1/models").size());
    }

    @Test
    void listModelsForProject_上書きなしならシステム既定providerで問い合わせる() {
        systemConfig(AiProvider.OLLAMA, "qwen2.5:7b-instruct", true);
        world.http.json("GET", "/api/tags", "{\"models\":[{\"name\":\"qwen2.5:7b-instruct\"}]}");

        assertEquals(List.of("qwen2.5:7b-instruct"), service.listModelsForProject(1L).availableModels());
    }

    @Test
    void selectModel_プロジェクトの選択中プロバイダーのモデルとして保存する() {
        world.projectAiSettingsService.setLlmProvider(1L, "CLAUDE");
        projectKey(AiProvider.CLAUDE, "sk-ant");
        world.http.json("GET", "/cloud/v1/models", "{\"data\":[{\"id\":\"claude-opus\"}]}");

        LlmModelListResponse response = service.selectModel(1L, "claude-opus");

        assertEquals("claude-opus", world.projectAiSettingsRows.rows.values().iterator().next().getLlmModelClaude());
        assertEquals("claude-opus", response.selected());
        assertEquals(List.of("claude-opus"), response.availableModels());
    }

    @Test
    void getSelectedProvider_未選択ならグローバル設定のプロバイダーを返す() {
        systemConfig(AiProvider.OLLAMA, "m", true);

        assertEquals(AiProvider.OLLAMA, service.getSelectedProvider(1L));
    }

    @Test
    void getSelectedProvider_選択済みならその値を返す() {
        world.projectAiSettingsService.setLlmProvider(1L, "OPENAI");

        assertEquals(AiProvider.OPENAI, service.getSelectedProvider(1L));
    }

    @Test
    void selectProvider_空文字はプロジェクト単位の上書きを解除する() {
        world.projectAiSettingsService.setLlmProvider(1L, "OPENAI");

        LlmProviderListResponse response = service.selectProvider(1L, "");

        assertNull(world.projectAiSettingsService.getLlmProvider(1L));
        assertNull(response.selected());
    }

    @Test
    void selectProvider_指定したプロバイダーを保存して一覧を返す() {
        LlmProviderListResponse response = service.selectProvider(1L, "OPENAI");

        assertEquals("OPENAI", world.projectAiSettingsService.getLlmProvider(1L));
        assertEquals("OPENAI", response.selected());
        assertEquals(List.of("OLLAMA", "OPENAI", "CLAUDE"), response.availableProviders());
    }
}
