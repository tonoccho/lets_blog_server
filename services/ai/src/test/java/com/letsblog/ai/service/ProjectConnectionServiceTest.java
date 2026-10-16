package com.letsblog.ai.service;

import com.letsblog.ai.client.PlatformServiceClient.AiConnectionsConfig;
import com.letsblog.ai.client.PlatformServiceClient.ProviderConnectionConfig;
import com.letsblog.ai.dto.AiConnectionResponse.Source;
import com.letsblog.ai.dto.ProjectConnectionsResponse;
import com.letsblog.ai.dto.UpdateProjectConnectionsRequest;
import com.letsblog.ai.testsupport.AiWorld;
import com.letsblog.ai.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProjectConnectionService(issue #1503)。解決順は プロジェクト上書き → システム設定(DB) → 環境変数既定 で、
 * 上書きが無い(null/空)ときは従来どおりplatform-serviceの解決結果をそのまま返す。
 */
/**
 * 設定の読み書きはProjectAiSettingsService(実物)、システム設定の解決はPlatformServiceClient(実物)がplatform-serviceの
 * HTTPフェイクへ問い合わせる(issue #1823。自前のクラスはモックしない)。
 */
class ProjectConnectionServiceTest {

    private static final String SYSTEM_OLLAMA = "http://ollama:11434/v1";
    private static final String SYSTEM_COMFY = "http://comfy:8188";
    private static final String CONFIG_PATH = "/api/internal/platform/ai-connections-config";

    private AiWorld world;
    private ProjectConnectionService service;

    @BeforeEach
    void setUp() {
        world = new AiWorld().actor(5L, "user");
        service = world.projectConnectionService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private void stubPlatform() {
        world.http.json("GET", CONFIG_PATH, """
                {"ollama":{"baseUrl":"%s","source":"ENVIRONMENT","configured":true},
                 "comfyui":{"baseUrl":"%s","source":"DATABASE","configured":true},
                 "openai":{"baseUrl":null,"source":"NONE","configured":false},
                 "claude":{"baseUrl":null,"source":"NONE","configured":false}}
                """.formatted(SYSTEM_OLLAMA, SYSTEM_COMFY));
    }

    @Test
    void get_上書きが無ければシステム設定の解決結果を返しoverrideはnull() {
        stubPlatform();

        ProjectConnectionsResponse response = service.get(7L);

        assertNull(response.ollama().overrideBaseUrl());
        assertEquals(SYSTEM_OLLAMA, response.ollama().baseUrl());
        assertEquals(Source.ENVIRONMENT, response.ollama().source());
        assertNull(response.comfyui().overrideBaseUrl());
        assertEquals(SYSTEM_COMFY, response.comfyui().baseUrl());
        assertEquals(Source.DATABASE, response.comfyui().source());
        assertEquals(AiWorld.BEARER, world.http.requests("GET", CONFIG_PATH).get(0).header("Authorization"));
    }

    @Test
    void get_上書きがあればそのURLがPROJECTとして解決される() {
        stubPlatform();
        world.projectAiSettingsService.setConnectionUrls(7L, "http://gpu:11434/v1", "http://gpu:8188");

        ProjectConnectionsResponse response = service.get(7L);

        assertEquals("http://gpu:11434/v1", response.ollama().overrideBaseUrl());
        assertEquals("http://gpu:11434/v1", response.ollama().baseUrl());
        assertEquals(Source.PROJECT, response.ollama().source());
        assertEquals("http://gpu:8188", response.comfyui().baseUrl());
        assertEquals(Source.PROJECT, response.comfyui().source());
    }

    @Test
    void get_片方だけ上書きしても他方は影響を受けない() {
        stubPlatform();
        world.projectAiSettingsService.setConnectionUrls(7L, "http://gpu:11434/v1", "");

        ProjectConnectionsResponse response = service.get(7L);

        assertEquals(Source.PROJECT, response.ollama().source());
        assertEquals(SYSTEM_COMFY, response.comfyui().baseUrl());
        assertEquals(Source.DATABASE, response.comfyui().source());
        assertNull(response.comfyui().overrideBaseUrl());
    }

    @Test
    void get_保存済みの上書きが空白だけなら上書き無しとして扱う() {
        stubPlatform();
        var row = world.projectAiSettingsService.getOrCreate(7L);
        row.setOllamaBaseUrl("   ");
        row.setComfyuiBaseUrl("");

        ProjectConnectionsResponse response = service.get(7L);

        assertNull(response.ollama().overrideBaseUrl());
        assertEquals(SYSTEM_OLLAMA, response.ollama().baseUrl());
        assertNull(response.comfyui().overrideBaseUrl());
        assertEquals(SYSTEM_COMFY, response.comfyui().baseUrl());
    }

    @Test
    void update_保存してから解決結果を返す() {
        stubPlatform();

        // 保存が先なら、解決結果にその上書きが反映される。
        ProjectConnectionsResponse saved =
                service.update(7L, new UpdateProjectConnectionsRequest("http://gpu:11434/v1", null));

        assertEquals("http://gpu:11434/v1", world.projectAiSettingsRows.rows.values().iterator().next().getOllamaBaseUrl());
        assertEquals("http://gpu:11434/v1", saved.ollama().baseUrl());
        assertEquals(Source.PROJECT, saved.ollama().source());

        // 空文字は上書きを解除し、システム設定の解決結果へ戻る。
        ProjectConnectionsResponse cleared = service.update(7L, new UpdateProjectConnectionsRequest("", null));

        assertNull(world.projectAiSettingsRows.rows.values().iterator().next().getOllamaBaseUrl());
        assertEquals(SYSTEM_OLLAMA, cleared.ollama().baseUrl());
        assertEquals(2, world.http.requests("GET", CONFIG_PATH).size());
    }

    @Test
    void update_不正なURLなら例外を伝播しplatformを呼ばない() {
        assertThrows(InvalidConnectionUrlException.class,
                () -> service.update(7L, new UpdateProjectConnectionsRequest("ftp://x", null)));

        assertEquals(0, world.http.requests("GET", CONFIG_PATH).size());
        assertTrue(world.projectAiSettingsRows.saved.isEmpty());
    }

    @Test
    void applyOverrides_ChatGPTとClaudeはプロジェクトにキーが無ければplatformの値によらず未設定のNONEになる() {
        AiConnectionsConfig base = new AiConnectionsConfig(
                new ProviderConnectionConfig(SYSTEM_OLLAMA, "ENVIRONMENT", true),
                new ProviderConnectionConfig(SYSTEM_COMFY, "ENVIRONMENT", true),
                new ProviderConnectionConfig(null, "DATABASE", true),
                new ProviderConnectionConfig(null, "NONE", false));

        AiConnectionsConfig merged = ProjectConnectionService.applyOverrides(base, "http://o", "http://c");

        assertEquals("http://o", merged.ollama().baseUrl());
        assertEquals("PROJECT", merged.ollama().source());
        assertEquals(true, merged.ollama().configured());
        assertEquals("http://c", merged.comfyui().baseUrl());
        assertEquals(new ProviderConnectionConfig(null, "NONE", false), merged.openai());
        assertEquals(new ProviderConnectionConfig(null, "NONE", false), merged.claude());
    }

    @Test
    void applyOverrides_platformの設定が無くても上書きがあれば設定済みになる() {
        AiConnectionsConfig merged = ProjectConnectionService.applyOverrides(
                new AiConnectionsConfig(null, null, null, null), "http://o", null);

        assertEquals("http://o", merged.ollama().baseUrl());
        assertNull(merged.comfyui());
    }

    @Test
    void get_platformが接続先を返さない場合は解決結果がnullでsourceはNONE() {
        world.http.json("GET", CONFIG_PATH, "{}");

        ProjectConnectionsResponse response = service.get(7L);

        assertNull(response.ollama().baseUrl());
        assertEquals(Source.NONE, response.ollama().source());
        assertNull(response.comfyui().baseUrl());
    }

    @Test
    void applyOverrides_openaiキーがあればChatGPTだけPROJECTかつ設定済みになる() {
        AiConnectionsConfig base = new AiConnectionsConfig(
                new ProviderConnectionConfig(SYSTEM_OLLAMA, "ENVIRONMENT", true),
                new ProviderConnectionConfig(SYSTEM_COMFY, "ENVIRONMENT", true),
                new ProviderConnectionConfig(null, "NONE", false),
                new ProviderConnectionConfig(null, "DATABASE", true));

        AiConnectionsConfig merged = ProjectConnectionService.applyOverrides(base, null, null, true);

        assertEquals("PROJECT", merged.openai().source());
        assertEquals(true, merged.openai().configured());
        assertEquals(base.ollama(), merged.ollama());
        assertEquals(new ProviderConnectionConfig(null, "NONE", false), merged.claude());
    }

    @Test
    void applyOverrides_openaiキーが無ければplatformがDATABASEを返してもChatGPTは未設定でキーがあれば設定済み() {
        AiConnectionsConfig base = new AiConnectionsConfig(
                null, null, new ProviderConnectionConfig("https://api.openai.com/v1", "DATABASE", true), null);

        assertEquals(new ProviderConnectionConfig(null, "NONE", false),
                ProjectConnectionService.applyOverrides(base, null, null, false).openai());

        AiConnectionsConfig merged = ProjectConnectionService.applyOverrides(
                new AiConnectionsConfig(null, null, null, null), null, null, true);
        assertEquals("PROJECT", merged.openai().source());
        assertEquals(true, merged.openai().configured());
    }

    @Test
    void applyOverrides_claudeキーがあればClaudeだけPROJECTかつ設定済みになる() {
        AiConnectionsConfig base = new AiConnectionsConfig(
                new ProviderConnectionConfig(SYSTEM_OLLAMA, "ENVIRONMENT", true),
                new ProviderConnectionConfig(SYSTEM_COMFY, "ENVIRONMENT", true),
                new ProviderConnectionConfig(null, "DATABASE", true),
                new ProviderConnectionConfig(null, "NONE", false));

        AiConnectionsConfig merged = ProjectConnectionService.applyOverrides(base, null, null, false, true);

        assertEquals("PROJECT", merged.claude().source());
        assertEquals(true, merged.claude().configured());
        assertEquals(base.ollama(), merged.ollama());
        assertEquals(new ProviderConnectionConfig(null, "NONE", false), merged.openai());
    }

    @Test
    void applyOverrides_claudeキーが無ければplatformがDATABASEを返してもClaudeは未設定でキーがあれば設定済み() {
        AiConnectionsConfig base = new AiConnectionsConfig(
                null, null, null, new ProviderConnectionConfig("https://api.anthropic.com/v1", "DATABASE", true));

        assertEquals(new ProviderConnectionConfig(null, "NONE", false),
                ProjectConnectionService.applyOverrides(base, null, null, false, false).claude());

        AiConnectionsConfig merged = ProjectConnectionService.applyOverrides(
                new AiConnectionsConfig(null, null, null, null), null, null, false, true);
        assertEquals("PROJECT", merged.claude().source());
        assertEquals(true, merged.claude().configured());
    }

    @Test
    void applyOverrides_openaiとclaudeの両方のキーがあれば両方PROJECTになる() {
        AiConnectionsConfig base = new AiConnectionsConfig(null, null, null, null);

        AiConnectionsConfig merged = ProjectConnectionService.applyOverrides(base, null, null, true, true);

        assertEquals("PROJECT", merged.openai().source());
        assertEquals("PROJECT", merged.claude().source());
    }
}
