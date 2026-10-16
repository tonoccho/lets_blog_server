package com.letsblog.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.testsupport.AiWorld;
import com.letsblog.common.auth.ServiceTokenUnavailableException;
import com.letsblog.common.auth.ToolsTokenProvider;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.letsblog.ai.testsupport.FakeHttpServer;

/**
 * モデル一覧の取得でextensionsのOllamaへ付けるトークン(#1804)。設定の解決はRemoteLlmConfigProvider(実物)、
 * 宛先検査はConnectionDestinationGuard(実物)で、システム設定の接続先とプロジェクトの上書き先を実際に区別する(issue #1823)。
 */
class ProviderModelCatalogToolsTokenTest {

    private AiWorld world;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    private FakeHttpServer.Response tags(FakeHttpServer.Request request) {
        String header = request.header("Authorization");
        authorizations.add(header == null ? "(none)" : header);
        return FakeHttpServer.Response.ok("{\"models\":[{\"name\":\"llama3\"}]}");
    }

    @BeforeEach
    void setUp() {
        world = new AiWorld().admin();
        world.llmConfig(AiProvider.OLLAMA, world.http.baseUrl(), "", "llama3", List.of("llama3"), true);
        world.http.on("GET", "/api/tags", this::tags);
        world.lan().on("GET", "/api/tags", this::tags);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private ProviderModelCatalog catalog(ToolsTokenProvider tools) {
        return new ProviderModelCatalog(world.llmConfigProvider, Duration.ofSeconds(3), tools);
    }

    /** プロジェクト1がOllamaの接続先を上書きした状態にする。 */
    private void projectOverride() {
        world.projectAiSettingsService.setConnectionUrls(1L, world.lan().lanBaseUrl(), null);
        world.llmConfigProvider.useProject(1L);
    }

    @Test
    @DisplayName("システム設定の接続先への一覧取得に、専用クライアントのトークンが付く")
    void システム設定の接続先にはトークンが付く() {
        Optional<List<String>> models = catalog(ToolsTokenProvider.fromSupplier(() -> "tok-1")).fetch(AiProvider.OLLAMA);

        assertThat(models).contains(List.of("llama3"));
        assertThat(authorizations).containsExactly("Bearer tok-1");
    }

    @Test
    @DisplayName("プロジェクト単位の上書き先には付かない")
    void 上書き先には付かない() {
        projectOverride();

        Optional<List<String>> models = catalog(ToolsTokenProvider.fromSupplier(() -> "tok-1")).fetch(AiProvider.OLLAMA);

        assertThat(models).contains(List.of("llama3"));
        assertThat(world.lan().requests("GET", "/api/tags")).hasSize(1);
        assertThat(authorizations).containsExactly("(none)");
    }

    @Test
    @DisplayName("secret未設定(無効)なら付かない")
    void 無効なら付かない() {
        catalog(ToolsTokenProvider.disabled()).fetch(AiProvider.OLLAMA);

        assertThat(authorizations).containsExactly("(none)");
    }

    @Test
    @DisplayName("トークンが取れないときは、問い合わせず空を返す(一覧はシステム設定へ戻る。理由はログに出る)")
    void 取れないときは問い合わせない() {
        Optional<List<String>> models = catalog(ToolsTokenProvider.fromSupplier(() -> {
            throw new ServiceTokenUnavailableException("down");
        })).fetch(AiProvider.OLLAMA);

        assertThat(models).isEmpty();
        assertThat(authorizations).isEmpty();
    }
}
