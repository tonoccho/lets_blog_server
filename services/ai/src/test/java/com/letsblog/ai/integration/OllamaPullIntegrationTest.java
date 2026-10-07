package com.letsblog.ai.integration;

import com.letsblog.ai.client.IdentityBridgeClient;
import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.ai.client.PlatformServiceClient.AiConnectionsConfig;
import com.letsblog.ai.client.PlatformServiceClient.ProviderConnectionConfig;
import com.letsblog.ai.repository.GenerationJobRepository;
import com.letsblog.ai.service.OllamaPullJobRunner;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1675: Ollamaのモデルpull開始API(POST /api/projects/{id}/ai-models/ollama/pull)を、
 * 実際のController・DB(generation_jobs)・例外ハンドラを通して検証する。ネットワークへ出る実行部
 * ({@link OllamaPullJobRunner})だけをモックにする(実行の中身はOllamaPullJobRunnerTestが固定する)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ai-service: Ollamaモデルpullの開始(issue #1675)")
class OllamaPullIntegrationTest {

    private static final String ADMIN = "pull-admin-token";
    private static final String MEMBER = "pull-member-token";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private GenerationJobRepository generationJobRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private IdentityClient identityClient;
    @MockitoBean
    private IdentityBridgeClient identityBridgeClient;
    @MockitoBean
    private PlatformServiceClient platformServiceClient;
    @MockitoBean
    private OllamaPullJobRunner runner;

    @BeforeEach
    void stubActors() {
        // 実行部はモックなので、開始したジョブはrunningのまま残る。DBが再実行をまたぐので、毎回片付けてから始める。
        generationJobRepository.deleteAll(generationJobRepository.findByTypeAndStatus("ollama_model_pull", "running"));
        when(jwtDecoder.decode(ADMIN)).thenReturn(JwtTestFixtures.jwt("sub-pull-admin", "admin"));
        when(identityClient.lookupProfile("Bearer " + ADMIN)).thenReturn(Optional.of(new ActorProfile(1L, "admin")));
        when(jwtDecoder.decode(MEMBER)).thenReturn(JwtTestFixtures.jwt("sub-pull-member", "user"));
        when(identityClient.lookupProfile("Bearer " + MEMBER)).thenReturn(Optional.of(new ActorProfile(2L, "user")));
        when(identityBridgeClient.isProjectMember(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), anyString())).thenReturn(true);
        when(platformServiceClient.resolveAiConnectionsConfig(anyString())).thenReturn(new AiConnectionsConfig(
                new ProviderConnectionConfig("http://ollama:11434/v1", "DATABASE", true),
                new ProviderConnectionConfig("http://comfyui:8188", "DATABASE", true),
                new ProviderConnectionConfig(null, "NONE", false),
                new ProviderConnectionConfig(null, "NONE", false)));
    }

    private ResultActions pull(long projectId, String token, String body) throws Exception {
        return mockMvc.perform(post("/api/projects/" + projectId + "/ai-models/ollama/pull")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    @DisplayName("管理者でない利用者(プロジェクトのメンバーでも)は403で、ジョブも作られず実行も起動しない")
    void 管理者以外は403() throws Exception {
        long before = generationJobRepository.count();

        pull(96001L, MEMBER, "{\"model\":\"llama3\"}").andExpect(status().isForbidden());

        assertEquals(before, generationJobRepository.count());
        verify(runner, never()).run(org.mockito.ArgumentMatchers.anyLong(), anyString(),
                org.mockito.ArgumentMatchers.anyBoolean(), anyString());
    }

    @Test
    @DisplayName("空・不正な文字のモデル名は400でジョブを作らない")
    void 不正なモデル名は400() throws Exception {
        long before = generationJobRepository.count();

        pull(96002L, ADMIN, "{\"model\":\"\"}").andExpect(status().isBadRequest());
        pull(96002L, ADMIN, "{\"model\":\"a b;c\"}").andExpect(status().isBadRequest());
        pull(96002L, ADMIN, "{}").andExpect(status().isBadRequest());

        assertEquals(before, generationJobRepository.count());
    }

    @Test
    @DisplayName("管理者が開始すると、running のジョブを作って jobId を返し、実効接続先で実行を起動する。同じモデルの二重開始は同じジョブを返す")
    void 開始するとジョブを作って返し_二重開始は同じジョブ() throws Exception {
        String first = pull(96003L, ADMIN, "{\"model\":\"qwen2.5:7b-instruct\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").isNumber())
                .andExpect(jsonPath("$.alreadyRunning").value(false))
                .andReturn().getResponse().getContentAsString();
        long jobId = Long.parseLong(first.replaceAll(".*\"jobId\":(\\d+).*", "$1"));

        var job = generationJobRepository.findById(jobId).orElseThrow();
        assertEquals("running", job.getStatus());
        assertEquals(1L, job.getOwnerUserId());
        assertTrue(job.getRequestPayload().contains("qwen2.5:7b-instruct"));
        verify(runner).run(jobId, "http://ollama:11434/v1", false, "qwen2.5:7b-instruct");

        pull(96003L, ADMIN, "{\"model\":\"qwen2.5:7b-instruct\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.alreadyRunning").value(true));
    }
}
