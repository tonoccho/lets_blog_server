package com.letsblog.ai.service;

import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.ai.client.PlatformServiceClient.AiConnectionsConfig;
import com.letsblog.ai.client.PlatformServiceClient.ProviderConnectionConfig;
import com.letsblog.ai.dto.AiConnectionResponse.Source;
import com.letsblog.ai.dto.ProjectConnectionsResponse;
import com.letsblog.ai.dto.UpdateProjectConnectionsRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectConnectionService(issue #1503)。解決順は プロジェクト上書き → システム設定(DB) → 環境変数既定 で、
 * 上書きが無い(null/空)ときは従来どおりplatform-serviceの解決結果をそのまま返す。
 */
@ExtendWith(MockitoExtension.class)
class ProjectConnectionServiceTest {

    private static final String SYSTEM_OLLAMA = "http://ollama:11434/v1";
    private static final String SYSTEM_COMFY = "http://comfy:8188";

    @Mock
    private ProjectAiSettingsService settingsService;
    @Mock
    private PlatformServiceClient platformServiceClient;
    @Mock
    private CurrentActorService currentActorService;

    private ProjectConnectionService service;

    @BeforeEach
    void setUp() {
        service = new ProjectConnectionService(settingsService, platformServiceClient, currentActorService);
    }

    private void stubPlatform() {
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer t");
        when(platformServiceClient.resolveAiConnectionsConfig("Bearer t")).thenReturn(new AiConnectionsConfig(
                new ProviderConnectionConfig(SYSTEM_OLLAMA, "ENVIRONMENT", true),
                new ProviderConnectionConfig(SYSTEM_COMFY, "DATABASE", true),
                new ProviderConnectionConfig(null, "NONE", false),
                new ProviderConnectionConfig(null, "NONE", false)));
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
    }

    @Test
    void get_上書きがあればそのURLがPROJECTとして解決される() {
        stubPlatform();
        when(settingsService.getOllamaBaseUrl(7L)).thenReturn("http://gpu:11434/v1");
        when(settingsService.getComfyuiBaseUrl(7L)).thenReturn("http://gpu:8188");

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
        when(settingsService.getOllamaBaseUrl(7L)).thenReturn("http://gpu:11434/v1");
        when(settingsService.getComfyuiBaseUrl(7L)).thenReturn("");

        ProjectConnectionsResponse response = service.get(7L);

        assertEquals(Source.PROJECT, response.ollama().source());
        assertEquals(SYSTEM_COMFY, response.comfyui().baseUrl());
        assertEquals(Source.DATABASE, response.comfyui().source());
        assertNull(response.comfyui().overrideBaseUrl());
    }

    @Test
    void update_保存してから解決結果を返す() {
        stubPlatform();

        ProjectConnectionsResponse response =
                service.update(7L, new UpdateProjectConnectionsRequest("", null));

        InOrder order = inOrder(settingsService, platformServiceClient);
        order.verify(settingsService).setConnectionUrls(7L, "", null);
        order.verify(platformServiceClient).resolveAiConnectionsConfig("Bearer t");
        assertEquals(SYSTEM_OLLAMA, response.ollama().baseUrl());
    }

    @Test
    void update_不正なURLなら例外を伝播しplatformを呼ばない() {
        doThrow(new InvalidConnectionUrlException("bad")).when(settingsService)
                .setConnectionUrls(7L, "ftp://x", null);

        assertThrows(InvalidConnectionUrlException.class,
                () -> service.update(7L, new UpdateProjectConnectionsRequest("ftp://x", null)));

        verify(platformServiceClient, never()).resolveAiConnectionsConfig(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void applyOverrides_ollamaとcomfyui以外は素通し() {
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
        assertEquals(base.openai(), merged.openai());
        assertEquals(base.claude(), merged.claude());
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
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer t");
        when(platformServiceClient.resolveAiConnectionsConfig("Bearer t"))
                .thenReturn(new AiConnectionsConfig(null, null, null, null));

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
        assertEquals(base.claude(), merged.claude());
    }

    @Test
    void applyOverrides_openaiキーが無ければChatGPTは素通しでplatform設定が無くてもキーがあれば設定済み() {
        AiConnectionsConfig base = new AiConnectionsConfig(
                null, null, new ProviderConnectionConfig("https://api.openai.com/v1", "DATABASE", true), null);

        assertEquals(base.openai(), ProjectConnectionService.applyOverrides(base, null, null, false).openai());

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
        assertEquals(base.openai(), merged.openai());
    }

    @Test
    void applyOverrides_claudeキーが無ければClaudeは素通しでplatform設定が無くてもキーがあれば設定済み() {
        AiConnectionsConfig base = new AiConnectionsConfig(
                null, null, null, new ProviderConnectionConfig("https://api.anthropic.com/v1", "DATABASE", true));

        assertEquals(base.claude(), ProjectConnectionService.applyOverrides(base, null, null, false, false).claude());

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
