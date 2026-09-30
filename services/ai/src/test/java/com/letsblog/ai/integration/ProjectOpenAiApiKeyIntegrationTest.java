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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1506: プロジェクト単位のChatGPT(OpenAI) APIキーの保存・状態取得・削除を、実際のControllerと
 * DB(Flyway V6)を通して検証する。キーの値が応答に現れないこと、ai-connectionsのOPENAI行が
 * source=PROJECTになること、削除でシステム設定へ戻ること、認可を固定する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ai-service: プロジェクト単位のChatGPT APIキー(issue #1506)")
class ProjectOpenAiApiKeyIntegrationTest {

    private static final String ADMIN = "oai-admin-token";
    private static final String OUTSIDER = "oai-out-token";
    private static final String SECRET = "sk-at-1506-never-shown";

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
        when(jwtDecoder.decode(ADMIN)).thenReturn(JwtTestFixtures.jwt("sub-oai-admin", "admin"));
        when(identityClient.lookupProfile("Bearer " + ADMIN)).thenReturn(Optional.of(new ActorProfile(1L, "admin")));
        when(jwtDecoder.decode(OUTSIDER)).thenReturn(JwtTestFixtures.jwt("sub-oai-out", "user"));
        when(identityClient.lookupProfile("Bearer " + OUTSIDER))
                .thenReturn(Optional.of(new ActorProfile(2L, "user")));
        when(identityBridgeClient.isProjectMember(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), anyString())).thenReturn(false);
        when(platformServiceClient.resolveAiConnectionsConfig(anyString())).thenReturn(new AiConnectionsConfig(
                new ProviderConnectionConfig("http://ollama.invalid:11434/v1", "ENVIRONMENT", true),
                new ProviderConnectionConfig("http://comfyui.invalid:8188", "ENVIRONMENT", true),
                new ProviderConnectionConfig(null, "DATABASE", true),
                new ProviderConnectionConfig(null, "NONE", false)));
    }

    private static String path(long projectId) {
        return "/api/projects/" + projectId + "/api-keys/openai-api-key";
    }

    private ResultActions put(long projectId, String token, String body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path(projectId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions connections(long projectId) throws Exception {
        return mockMvc.perform(get("/api/projects/" + projectId + "/ai-connections")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN));
    }

    @Test
    @DisplayName("AC1/AC3: 保存するとOPENAI行がPROJECT・設定済みになり、キーの値はどの応答にも現れない")
    void 保存するとPROJECTになりキー値は応答に出ない() throws Exception {
        // テスト用DBは実行をまたいで残るため、前回実行の保存値を消してから始める。
        mockMvc.perform(delete(path(96001L)).header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN))
                .andExpect(status().isNoContent());
        connections(96001L).andExpect(jsonPath("$[?(@.provider=='OPENAI')].source").value("DATABASE"));

        put(96001L, ADMIN, "{\"apiKey\":\"" + SECRET + "\"}")
                .andExpect(status().isNoContent())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SECRET))));

        connections(96001L)
                .andExpect(jsonPath("$[?(@.provider=='OPENAI')].source").value("PROJECT"))
                .andExpect(jsonPath("$[?(@.provider=='OPENAI')].configured").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SECRET))));
        mockMvc.perform(get(path(96001L)).header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(SECRET))));

        // 他プロジェクトには影響しない
        connections(96002L).andExpect(jsonPath("$[?(@.provider=='OPENAI')].source").value("DATABASE"));
    }

    @Test
    @DisplayName("AC3: 空のキーは400で何も保存されない")
    void 空のキーは400で保存されない() throws Exception {
        put(96003L, ADMIN, "{\"apiKey\":\"\"}").andExpect(status().isBadRequest());
        put(96003L, ADMIN, "{\"apiKey\":\"   \"}").andExpect(status().isBadRequest());

        mockMvc.perform(get(path(96003L)).header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN))
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    @DisplayName("AC5: 削除するとシステム設定の出所へ戻る")
    void 削除するとシステム設定へ戻る() throws Exception {
        put(96004L, ADMIN, "{\"apiKey\":\"" + SECRET + "\"}").andExpect(status().isNoContent());

        mockMvc.perform(delete(path(96004L)).header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN))
                .andExpect(status().isNoContent());

        connections(96004L).andExpect(jsonPath("$[?(@.provider=='OPENAI')].source").value("DATABASE"));
        mockMvc.perform(get(path(96004L)).header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN))
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    @DisplayName("認可: プロジェクトのメンバーでもadminでもないユーザーのGET/PUT/DELETEは403")
    void 非メンバーは403() throws Exception {
        mockMvc.perform(get(path(96005L)).header(HttpHeaders.AUTHORIZATION, "Bearer " + OUTSIDER))
                .andExpect(status().isForbidden());
        put(96005L, OUTSIDER, "{\"apiKey\":\"" + SECRET + "\"}").andExpect(status().isForbidden());
        mockMvc.perform(delete(path(96005L)).header(HttpHeaders.AUTHORIZATION, "Bearer " + OUTSIDER))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(path(96005L)).header(HttpHeaders.AUTHORIZATION, "Bearer " + ADMIN))
                .andExpect(jsonPath("$.configured").value(false));
    }
}
