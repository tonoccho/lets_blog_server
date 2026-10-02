package com.letsblog.platform.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.platform.service.AppSettingService;
import com.letsblog.platform.service.AppSettingService.SettingSource;
import com.letsblog.platform.service.SystemSettingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** InternalPlatformSettingsController#aiConnectionsConfig(issue #1499)のテスト。 */
@ExtendWith(MockitoExtension.class)
class InternalPlatformSettingsControllerTest {

    @Mock
    private SystemSettingService systemSettingService;
    @Mock
    private AppSettingService appSettingService;

    private InternalPlatformSettingsController controller() {
        return new InternalPlatformSettingsController(systemSettingService, appSettingService);
    }

    @Test
    void aiConnectionsConfig_接続先URLと取得元とキー設定有無を返す() {
        when(appSettingService.getLlmOllamaBaseUrl()).thenReturn("http://ollama:11434/v1");
        when(appSettingService.ollamaBaseUrlSource()).thenReturn(SettingSource.ENVIRONMENT);
        when(appSettingService.getComfyUiBaseUrl()).thenReturn("http://comfy:8188");
        when(appSettingService.comfyUiBaseUrlSource()).thenReturn(SettingSource.DATABASE);
        when(appSettingService.openAiApiKeySource()).thenReturn(SettingSource.DATABASE);
        when(appSettingService.claudeApiKeySource()).thenReturn(SettingSource.NONE);

        var response = controller().aiConnectionsConfig();

        assertEquals("http://ollama:11434/v1", response.ollama().baseUrl());
        assertEquals("ENVIRONMENT", response.ollama().source());
        assertTrue(response.ollama().configured());
        assertEquals("http://comfy:8188", response.comfyui().baseUrl());
        assertEquals("DATABASE", response.comfyui().source());
        assertNull(response.openai().baseUrl());
        assertEquals("DATABASE", response.openai().source());
        assertTrue(response.openai().configured());
        assertEquals("NONE", response.claude().source());
        assertFalse(response.claude().configured());
    }

    @Test
    void aiConnectionsConfig_APIキーの値をシリアライズ結果に含めない() throws Exception {
        when(appSettingService.getLlmOllamaBaseUrl()).thenReturn("http://ollama:11434/v1");
        when(appSettingService.ollamaBaseUrlSource()).thenReturn(SettingSource.ENVIRONMENT);
        when(appSettingService.getComfyUiBaseUrl()).thenReturn("http://comfy:8188");
        when(appSettingService.comfyUiBaseUrlSource()).thenReturn(SettingSource.ENVIRONMENT);
        when(appSettingService.openAiApiKeySource()).thenReturn(SettingSource.DATABASE);
        when(appSettingService.claudeApiKeySource()).thenReturn(SettingSource.DATABASE);
        // 万一コントローラがキーを参照しても、この値は応答に現れてはならない。
        org.mockito.Mockito.lenient().when(appSettingService.getLlmApiKey()).thenReturn("sk-secret-openai");
        org.mockito.Mockito.lenient().when(appSettingService.getLlmClaudeApiKey()).thenReturn("sk-ant-secret");

        String json = new ObjectMapper().writeValueAsString(controller().aiConnectionsConfig());

        assertFalse(json.contains("sk-secret-openai"), json);
        assertFalse(json.contains("sk-ant-secret"), json);
    }

    @Test
    void aiConnectionsConfig_URL未設定なら未設定として返す() {
        when(appSettingService.getLlmOllamaBaseUrl()).thenReturn("");
        when(appSettingService.ollamaBaseUrlSource()).thenReturn(SettingSource.NONE);
        when(appSettingService.getComfyUiBaseUrl()).thenReturn("");
        when(appSettingService.comfyUiBaseUrlSource()).thenReturn(SettingSource.NONE);
        when(appSettingService.openAiApiKeySource()).thenReturn(SettingSource.NONE);
        when(appSettingService.claudeApiKeySource()).thenReturn(SettingSource.NONE);

        var response = controller().aiConnectionsConfig();

        assertNull(response.ollama().baseUrl());
        assertFalse(response.ollama().configured());
        assertNull(response.comfyui().baseUrl());
        assertFalse(response.comfyui().configured());
    }

    @Test
    void aiConnectionsConfig_URLがnullでも未設定として返す() {
        when(appSettingService.getLlmOllamaBaseUrl()).thenReturn(null);
        when(appSettingService.ollamaBaseUrlSource()).thenReturn(SettingSource.NONE);
        when(appSettingService.getComfyUiBaseUrl()).thenReturn(null);
        when(appSettingService.comfyUiBaseUrlSource()).thenReturn(SettingSource.NONE);
        when(appSettingService.openAiApiKeySource()).thenReturn(SettingSource.NONE);
        when(appSettingService.claudeApiKeySource()).thenReturn(SettingSource.NONE);

        var response = controller().aiConnectionsConfig();

        assertNull(response.ollama().baseUrl());
        assertFalse(response.ollama().configured());
        assertFalse(response.comfyui().configured());
    }

    @Test
    void imageGenerationConfig_APIキーを応答に含めない_issue1521() throws Exception {
        when(appSettingService.comfyUiBaseUrl()).thenReturn("http://comfy:8188");
        when(appSettingService.chatGptBaseUrl()).thenReturn("https://api.openai.com/v1");

        String json = new ObjectMapper().writeValueAsString(controller().imageGenerationConfig());

        assertTrue(json.contains("http://comfy:8188"), json);
        assertTrue(json.contains("https://api.openai.com/v1"), json);
        assertFalse(json.toLowerCase().contains("apikey"), json);
    }
}
