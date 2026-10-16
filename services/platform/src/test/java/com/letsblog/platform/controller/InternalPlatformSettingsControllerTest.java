package com.letsblog.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.platform.config.GlobalExceptionHandler;
import com.letsblog.platform.testsupport.StatusFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * InternalPlatformSettingsController(issue #1499のai-connections-config ほか)のテスト。
 *
 * <p>issue #1825: コントローラ・SystemSettingService・AppSettingServiceは実物で、設定はsystem_settings
 * (メモリ上のリポジトリ)に保存して読み戻す。HTTPはSpringの{@link MockMvc}(スタンドアロン)を通す。
 */
class InternalPlatformSettingsControllerTest {

    private StatusFixture fixture;
    private InternalPlatformSettingsController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        fixture = new StatusFixture();
        controller = new InternalPlatformSettingsController(fixture.systemSettingService, fixture.appSettingService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(
                        new org.springframework.boot.servlet.autoconfigure.MultipartProperties()))
                .build();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void aiConnectionsConfig_接続先URLと取得元とキー設定有無を返す() {
        fixture.setting("llm_ollama_base_url", "http://ollama:11434/v1");
        fixture.setting("comfyui_base_url", "http://comfy:8188");

        var response = controller.aiConnectionsConfig();

        assertEquals("http://ollama:11434/v1", response.ollama().baseUrl());
        assertEquals("DATABASE", response.ollama().source());
        assertTrue(response.ollama().configured());
        assertEquals("http://comfy:8188", response.comfyui().baseUrl());
        assertEquals("DATABASE", response.comfyui().source());
        // ChatGPT / ClaudeのAPIキーはプロジェクト単位だけで、システム側に出所は無い(issue #1568)。
        assertNull(response.openai().baseUrl());
        assertEquals("NONE", response.openai().source());
        assertFalse(response.openai().configured());
        assertEquals("NONE", response.claude().source());
        assertFalse(response.claude().configured());
    }

    @Test
    void aiConnectionsConfig_APIキーの値をシリアライズ結果に含めない() throws Exception {
        fixture.setting("llm_ollama_base_url", "http://ollama:11434/v1");
        fixture.setting("comfyui_base_url", "http://comfy:8188");

        String json = new ObjectMapper().writeValueAsString(controller.aiConnectionsConfig());

        assertFalse(json.contains("sk-"), json);
        assertFalse(json.toLowerCase().contains("apikey"), json);
    }

    @Test
    void aiConnectionsConfig_URL未設定なら未設定として返す() throws Exception {
        mvc.perform(get("/api/internal/platform/ai-connections-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ollama.baseUrl").doesNotExist())
                .andExpect(jsonPath("$.ollama.source").value("NONE"))
                .andExpect(jsonPath("$.ollama.configured").value(false))
                .andExpect(jsonPath("$.comfyui.baseUrl").doesNotExist())
                .andExpect(jsonPath("$.comfyui.configured").value(false));
    }

    @Test
    void imageGenerationConfig_APIキーを応答に含めない_issue1521() throws Exception {
        fixture.setting("comfyui_base_url", "http://comfy:8188");

        String json = new ObjectMapper().writeValueAsString(controller.imageGenerationConfig());

        assertTrue(json.contains("http://comfy:8188"), json);
        assertFalse(json.toLowerCase().contains("apikey"), json);
        // ChatGPTのベースURLはコード内の定数で、platformは配らない(issue #1569)。
        assertFalse(json.contains("chatGptBaseUrl"), json);
    }

    @Test
    void llmConfig_システム全体のAPIキーを持たないのでapiKeyは常に空_issue1568() {
        var response = controller.llmConfig("OPENAI");

        assertEquals("OPENAI", response.provider());
        assertEquals("https://api.openai.com/v1", response.baseUrl());
        assertEquals("gpt-4o-mini", response.defaultModel());
        assertEquals(120L, response.requestTimeoutSeconds());
        assertEquals("", response.apiKey());
    }

    @Test
    void llmConfig_providerを省略するとシステム設定の既定プロバイダーを使う() throws Exception {
        fixture.llmProvider("CLAUDE");

        assertEquals("CLAUDE", controller.llmConfig(null).provider());
        assertEquals("CLAUDE", controller.llmConfig("  ").provider());
        mvc.perform(get("/api/internal/platform/llm-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("CLAUDE"))
                .andExpect(jsonPath("$.apiKey").value(""));
    }

    @Test
    void llmConfig_不明なプロバイダーは409() throws Exception {
        mvc.perform(get("/api/internal/platform/llm-config").param("provider", "NOPE"))
                .andExpect(status().isConflict());
    }

    @Test
    void gitLabConfig_設定済みなら値とconfiguredをtrueで返す() {
        fixture.setting("gitlab_base_url", "https://gitlab.example.com");

        var response = controller.gitLabConfig();

        assertEquals("https://gitlab.example.com", response.baseUrl());
        assertTrue(response.configured());
    }

    @Test
    void gitLabConfig_未設定ならnullとconfiguredをfalseで返す() throws Exception {
        mvc.perform(get("/api/internal/platform/gitlab-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").doesNotExist())
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void systemBraveSearchApiKey_DBの値を返し_未設定ならnullを返す() throws Exception {
        mvc.perform(get("/api/internal/platform/system-settings/brave-search-api-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").doesNotExist());

        fixture.braveKey(true);

        mvc.perform(get("/api/internal/platform/system-settings/brave-search-api-key"))
                .andExpect(jsonPath("$.apiKey").value("brave-key"));
    }

    @Test
    void systemBraveSearchApiKey_環境変数の既定値がnullなら未設定として返す() {
        var withNullDefault = new InternalPlatformSettingsController(
                new com.letsblog.platform.service.SystemSettingService(
                        com.letsblog.platform.testsupport.InMemoryRepositories.systemSettings(),
                        fixture.world.credentialCipher, fixture.world.adminAuthorizationService, null),
                fixture.appSettingService);

        assertNull(withNullDefault.systemBraveSearchApiKey().apiKey());
    }

    @Test
    void systemBraveSearchApiKey_環境変数の既定値が空白なら未設定として返す() {
        var withBlankDefault = new InternalPlatformSettingsController(
                new com.letsblog.platform.service.SystemSettingService(
                        com.letsblog.platform.testsupport.InMemoryRepositories.systemSettings(),
                        fixture.world.credentialCipher, fixture.world.adminAuthorizationService, "  "),
                fixture.appSettingService);

        assertNull(withBlankDefault.systemBraveSearchApiKey().apiKey());
    }
}
