package com.letsblog.ai.integration;

import com.letsblog.ai.client.IdentityBridgeClient;
import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.ai.client.PlatformServiceClient.AiConnectionsConfig;
import com.letsblog.ai.client.PlatformServiceClient.ProviderConnectionConfig;
import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1503: プロジェクト単位のOllama/ComfyUI接続先の上書きを、実際のControllerとDB(Flyway V4)を
 * 通して検証する。AC1のうちAPIから観測できる部分(解決結果が上書きURLになる)、AC2〜AC5を固定する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ai-service: プロジェクト単位の接続先上書き(issue #1503)")
class ProjectConnectionIntegrationTest {

    private static final String ADMIN = "conn-admin-token";
    private static final String OUTSIDER = "conn-outsider-token";
    private static final String SYSTEM_OLLAMA = "http://ollama:11434/v1";
    private static final String SYSTEM_COMFY = "http://comfyui:8188";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private IdentityClient identityClient;
    @MockitoBean
    private IdentityBridgeClient identityBridgeClient;
    @MockitoBean
    private PlatformServiceClient platformServiceClient;

    @BeforeEach
    void stubActors() {
        when(jwtDecoder.decode(ADMIN)).thenReturn(JwtTestFixtures.jwt("sub-conn-admin", "admin"));
        when(identityClient.lookupProfile("Bearer " + ADMIN)).thenReturn(Optional.of(new ActorProfile(1L, "admin")));
        when(jwtDecoder.decode(OUTSIDER)).thenReturn(JwtTestFixtures.jwt("sub-conn-out", "user"));
        when(identityClient.lookupProfile("Bearer " + OUTSIDER))
                .thenReturn(Optional.of(new ActorProfile(2L, "user")));
        when(identityBridgeClient.isProjectMember(anyLong(), anyLong(), anyString())).thenReturn(false);
        when(platformServiceClient.resolveAiConnectionsConfig(anyString())).thenReturn(new AiConnectionsConfig(
                new ProviderConnectionConfig(SYSTEM_OLLAMA, "ENVIRONMENT", true),
                new ProviderConnectionConfig(SYSTEM_COMFY, "DATABASE", true),
                new ProviderConnectionConfig(null, "NONE", false),
                new ProviderConnectionConfig(null, "NONE", false)));
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    private ResultActions getConnections(long projectId, String token) throws Exception {
        return mockMvc.perform(get("/api/projects/" + projectId + "/ai-models/connections")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions putConnections(long projectId, String token, String body) throws Exception {
        return mockMvc.perform(put("/api/projects/" + projectId + "/ai-models/connections")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("AC1/AC2: 上書きを設定したプロジェクトだけ解決結果が上書きURLになり、他プロジェクトは既定値のまま")
    void 上書きは設定したプロジェクトだけに効く() throws Exception {
        putConnections(95001L, ADMIN, "{\"ollamaBaseUrl\":\"http://gpu:11434/v1\",\"comfyuiBaseUrl\":\"http://gpu:8188\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ollama.baseUrl").value("http://gpu:11434/v1"))
                .andExpect(jsonPath("$.ollama.source").value("PROJECT"))
                .andExpect(jsonPath("$.comfyui.overrideBaseUrl").value("http://gpu:8188"));

        getConnections(95001L, ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ollama.baseUrl").value("http://gpu:11434/v1"))
                .andExpect(jsonPath("$.comfyui.source").value("PROJECT"));

        getConnections(95002L, ADMIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ollama.baseUrl").value(SYSTEM_OLLAMA))
                .andExpect(jsonPath("$.ollama.source").value("ENVIRONMENT"))
                .andExpect(jsonPath("$.ollama.overrideBaseUrl").doesNotExist())
                .andExpect(jsonPath("$.comfyui.baseUrl").value(SYSTEM_COMFY))
                .andExpect(jsonPath("$.comfyui.source").value("DATABASE"));

        mockMvc.perform(get("/api/internal/ai/projects/95001/connections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ollamaBaseUrl").value("http://gpu:11434/v1"))
                .andExpect(jsonPath("$.comfyuiBaseUrl").value("http://gpu:8188"));
    }

    @Test
    @DisplayName("AC3: 空文字で保存すると上書きが解除され既定値に戻る(指定しなかった項目は変わらない)")
    void 空文字で上書きが解除される() throws Exception {
        putConnections(95003L, ADMIN, "{\"ollamaBaseUrl\":\"http://gpu:11434/v1\",\"comfyuiBaseUrl\":\"http://gpu:8188\"}")
                .andExpect(status().isOk());

        putConnections(95003L, ADMIN, "{\"ollamaBaseUrl\":\"\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ollama.baseUrl").value(SYSTEM_OLLAMA))
                .andExpect(jsonPath("$.ollama.source").value("ENVIRONMENT"))
                .andExpect(jsonPath("$.comfyui.baseUrl").value("http://gpu:8188"));

        mockMvc.perform(get("/api/internal/ai/projects/95003/connections")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN))
                .andExpect(jsonPath("$.ollamaBaseUrl").doesNotExist());
    }

    @Test
    @DisplayName("AC4: http/https以外・空白/制御文字を含むURLは400で、保存済みの値は変わらない")
    void 不正なURLは400で保存済みの値は変わらない() throws Exception {
        putConnections(95004L, ADMIN, "{\"ollamaBaseUrl\":\"http://gpu:11434/v1\"}").andExpect(status().isOk());

        putConnections(95004L, ADMIN, "{\"ollamaBaseUrl\":\"ftp://evil\"}").andExpect(status().isBadRequest());
        putConnections(95004L, ADMIN, "{\"ollamaBaseUrl\":\"http://a b\"}").andExpect(status().isBadRequest());
        putConnections(95004L, ADMIN, "{\"ollamaBaseUrl\":\"http://ok:1/v1\",\"comfyuiBaseUrl\":\"http://a\\nb\"}")
                .andExpect(status().isBadRequest());

        getConnections(95004L, ADMIN)
                .andExpect(jsonPath("$.ollama.baseUrl").value("http://gpu:11434/v1"))
                .andExpect(jsonPath("$.comfyui.overrideBaseUrl").doesNotExist());
    }

    @Test
    @DisplayName("AC5: プロジェクトのメンバーでもadminでもないユーザーのGET/PUTは403")
    void 非メンバーのGETとPUTは403() throws Exception {
        getConnections(95005L, OUTSIDER).andExpect(status().isForbidden());
        putConnections(95005L, OUTSIDER, "{\"ollamaBaseUrl\":\"http://gpu:11434/v1\"}")
                .andExpect(status().isForbidden());

        getConnections(95005L, ADMIN).andExpect(jsonPath("$.ollama.source").value("ENVIRONMENT"));
    }
}
