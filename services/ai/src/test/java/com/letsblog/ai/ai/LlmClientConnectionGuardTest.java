package com.letsblog.ai.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.letsblog.ai.testsupport.AiWorld;
import com.letsblog.ai.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 接続時検査で拒否されたOllama接続先へ、LlmClientは接続せずに生成を失敗させる(issue #1547)。
 *
 * <p>issue #1824: LlmClientとRemoteLlmConfigProvider(宛先検査を含む)は実物で、platform-serviceとLLMの相手は
 * HTTPフェイク、DBはメモリ上のリポジトリで差し替える。宛先検査は、ループバックを必ず拒否する実物の規則をそのまま使う。
 */
class LlmClientConnectionGuardTest {

    private static final String OK = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";

    private AiWorld world;

    @BeforeEach
    void setUp() {
        world = new AiWorld().ollamaDefault("m");
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void forbiddenDestinationFailsGenerationWithoutConnecting() {
        // ループバックのサーバーを、プロジェクト単位の上書き先にする。実物の宛先検査は必ず拒否する。
        world.http.on("POST", ".*/chat/completions", FakeHttpServer.Response.ok(OK));
        world.projectAiSettingsService.getOrCreate(7L).setOllamaBaseUrl(world.http.baseUrl() + "/v1");
        world.llmClient.useProject(7L);

        assertThatThrownBy(() -> world.llmClient.generate("hi"))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("Ollama")
                .hasMessageContaining("サーバ内部のネットワーク");
        assertThat(world.llmRequests()).isEmpty();
    }

    @Test
    void allowedTargetIsConnectedTo() {
        FakeHttpServer lan = world.lan();
        lan.on("POST", ".*/chat/completions", FakeHttpServer.Response.ok(OK));
        world.projectAiSettingsService.getOrCreate(7L).setOllamaBaseUrl(lan.lanBaseUrl() + "/v1");
        world.llmClient.useProject(7L);

        assertThat(world.llmClient.generate("hi")).isEqualTo("ok");
        assertThat(lan.requests("POST", ".*/chat/completions")).hasSize(1);
    }

    @Test
    void defaultTargetForFallsBackToBaseUrlFor() {
        // LlmConfigProviderのtargetForの既定実装は、検査をせずbaseUrlForをそのまま使う。
        // この既定実装を使う実装は、LlmClientが固定値のために自分で組み立てる匿名の実装(テスト専用コンストラクタ)だけ。
        world.http.on("POST", ".*/chat/completions", FakeHttpServer.Response.ok(OK));
        String url = world.http.baseUrl() + "/v1";
        LlmClient plain = new LlmClient(url, "k", "m", 5L);

        assertThat(plain.generate("hi")).isEqualTo("ok");
        assertThat(world.llmRequests()).hasSize(1);
        assertThat(world.llmRequests().get(0).header("Authorization")).isEqualTo("Bearer k");
    }
}
