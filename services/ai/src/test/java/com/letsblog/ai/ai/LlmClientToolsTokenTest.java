package com.letsblog.ai.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.letsblog.ai.testsupport.AiWorld;
import com.letsblog.ai.testsupport.FakeHttpServer;
import com.letsblog.common.auth.ServiceTokenUnavailableException;
import com.letsblog.common.auth.ToolsTokenProvider;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LLMの生成でextensionsのOllamaへ付けるトークン(#1804)。システム設定の接続先にだけ付け、プロジェクト単位の
 * 上書き先・secret未設定のときは付けない。secretがあるのに取れないときは、送らずに理由の分かるエラーにする。
 *
 * <p>issue #1824: LlmClientとRemoteLlmConfigProvider(宛先検査を含む)は実物で、platform-serviceとLLMの相手は
 * HTTPフェイク、DBはメモリ上のリポジトリで差し替える。トークンの取得元(Keycloak)だけは{@code Supplier}で差し替える。
 */
class LlmClientToolsTokenTest {

    private AiWorld world;

    @BeforeEach
    void setUp() {
        world = new AiWorld().ollamaDefault("m");
        world.llmReplies("ok");
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private LlmClient client(ToolsTokenProvider tokens) {
        return new LlmClient(world.llmConfigProvider, tokens);
    }

    private List<String> authorizations() {
        return world.llmRequests().stream()
                .map(request -> request.header("Authorization") == null ? "(none)" : request.header("Authorization"))
                .toList();
    }

    @Test
    @DisplayName("システム設定のOllamaへの生成に、専用クライアントのトークンが付く")
    void システム設定の接続先にはトークンが付く() {
        LlmClient client = client(ToolsTokenProvider.fromSupplier(() -> "tok-1"));

        assertThat(client.generate("hi")).isEqualTo("ok");
        assertThat(authorizations()).containsExactly("Bearer tok-1");
    }

    @Test
    @DisplayName("プロジェクト単位の上書き先には付かない")
    void 上書き先には付かない() {
        // 宛先検査(実物)を通る別アドレスの上書き先。
        FakeHttpServer lan = world.lan();
        lan.on("POST", ".*/chat/completions",
                FakeHttpServer.Response.ok("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}"));
        world.projectAiSettingsService.setConnectionUrls(7L, lan.lanBaseUrl() + "/v1", null);
        LlmClient client = client(ToolsTokenProvider.fromSupplier(() -> "tok-1"));
        client.useProject(7L);

        assertThat(client.generate("hi")).isEqualTo("ok");
        assertThat(lan.requests("POST", ".*/chat/completions")).hasSize(1);
        assertThat(lan.requests("POST", ".*/chat/completions").get(0).header("Authorization")).isNull();
        assertThat(world.llmRequests()).isEmpty();
    }

    @Test
    @DisplayName("secret未設定(無効)なら付かない。従来と同じ")
    void 無効なら付かない() {
        LlmClient client = client(ToolsTokenProvider.disabled());

        assertThat(client.generate("hi")).isEqualTo("ok");
        assertThat(authorizations()).containsExactly("(none)");
    }

    @Test
    @DisplayName("トークンが取れないときは、ヘッダー無しで送らず、理由の分かるエラーにする")
    void 取れないときはエラー() {
        LlmClient client = client(ToolsTokenProvider.fromSupplier(() -> {
            throw new ServiceTokenUnavailableException("サービストークンの取得に失敗しました(client_id=letsblog-tools-client): 401");
        }));

        assertThatThrownBy(() -> client.generate("hi"))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("KEYCLOAK_TOOLS_CLIENT_SECRET")
                .hasMessageContaining("401")
                .hasMessageNotContaining("タイムアウト");
        assertThat(authorizations()).isEmpty();
    }

    @Test
    @DisplayName("ChatGPT(OpenAI)にはtoolsのトークンを付けず、プロジェクトのAPIキーのままである")
    void OpenAIはAPIキーのまま() {
        world.llmConfig(AiProvider.OPENAI, null, "", "gpt", List.of("gpt"), false);
        world.projectAiSettingsService.setOpenAiApiKeyEncrypted(7L, world.credentialCipher.encrypt("sk-project"));
        LlmClient client = client(ToolsTokenProvider.fromSupplier(() -> "tok-1"));
        client.useProject(7L);

        assertThat(client.generate("hi", null, AiProvider.OPENAI)).isEqualTo("ok");
        assertThat(authorizations()).containsExactly("Bearer sk-project");
    }
}
