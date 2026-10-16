package com.letsblog.ai.integration;

import com.letsblog.ai.repository.GenerationJobRepository;
import com.letsblog.ai.testsupport.FakeHttpServer;
import com.letsblog.ai.testsupport.BackendIntegrationTest;
import com.letsblog.ai.testsupport.IntegrationBackends;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1675: Ollamaのモデルpull開始API(POST /api/projects/{id}/ai-models/ollama/pull)を、
 * 実際のController・DB(generation_jobs)・例外ハンドラ・実行部({@code OllamaPullJobRunner})を通して検証する。
 *
 * <p>issue #1824: 実行部はモックにせず実物を動かし、実行部が呼ぶOllama({@code POST /api/pull})はHTTPフェイクで受ける。
 * フェイクは、テストが解放するまで応答を返さない。ジョブが{@code running}の間の状態(二重開始の判定)を確かめ、
 * 解放したあとにジョブが{@code done}になることまでを確かめるため。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ai-service: Ollamaモデルpullの開始(issue #1675)")
class OllamaPullIntegrationTest extends BackendIntegrationTest {

    private static final String ADMIN = "pull-admin-token";
    private static final String MEMBER = "pull-member-token";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private GenerationJobRepository generationJobRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** テストが解放するまで、Ollamaのpullを終えない。 */
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void stubActors() {
        IntegrationBackends.reset();
        // DBが再実行をまたぐので、前回の実行で残ったrunningのジョブを片付けてから始める。
        generationJobRepository.deleteAll(generationJobRepository.findByTypeAndStatus("ollama_model_pull", "running"));
        when(jwtDecoder.decode(ADMIN)).thenReturn(JwtTestFixtures.jwt("sub-pull-admin", "admin"));
        when(jwtDecoder.decode(MEMBER)).thenReturn(JwtTestFixtures.jwt("sub-pull-member", "user"));
        IntegrationBackends.actor(ADMIN, 1, "admin");
        IntegrationBackends.actor(MEMBER, 2, "user");
        IntegrationBackends.membership(96001L, 2L, true);
        // システム設定のOllamaの接続先は、HTTPフェイク自身。
        IntegrationBackends.aiConnectionsConfig(
                IntegrationBackends.server().baseUrl() + "/v1", "DATABASE", "http://comfyui:8188", "DATABASE");
        IntegrationBackends.server().on("POST", "/api/pull", request -> {
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return FakeHttpServer.Response.bytes(200, "application/x-ndjson", "{\"status\":\"success\"}".getBytes());
        });
    }

    @AfterEach
    void releasePull() {
        release.countDown();
    }

    private ResultActions pull(long projectId, String token, String body) throws Exception {
        return mockMvc.perform(post("/api/projects/" + projectId + "/ai-models/ollama/pull")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    @Test
    @DisplayName("管理者でない利用者(プロジェクトのメンバーでも)は403で、ジョブも作られず実行も起動しない")
    void 管理者以外は403() throws Exception {
        long before = generationJobRepository.count();

        pull(96001L, MEMBER, "{\"model\":\"llama3\"}").andExpect(status().isForbidden());

        assertEquals(before, generationJobRepository.count());
        assertTrue(IntegrationBackends.server().requests("POST", "/api/pull").isEmpty());
    }

    @Test
    @DisplayName("空・不正な文字のモデル名は400でジョブを作らない")
    void 不正なモデル名は400() throws Exception {
        long before = generationJobRepository.count();

        pull(96002L, ADMIN, "{\"model\":\"\"}").andExpect(status().isBadRequest());
        pull(96002L, ADMIN, "{\"model\":\"a b;c\"}").andExpect(status().isBadRequest());
        pull(96002L, ADMIN, "{}").andExpect(status().isBadRequest());

        assertEquals(before, generationJobRepository.count());
        assertTrue(IntegrationBackends.server().requests("POST", "/api/pull").isEmpty());
    }

    @Test
    @DisplayName("管理者が開始すると、running のジョブを作って jobId を返し、実効接続先で実行を起動する。同じモデルの二重開始は同じジョブを返し、終わるとdoneになる")
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
        // 実行が、システム設定の接続先(フェイクのOllama)へpullを送る。
        assertTrue(waitUntil(() -> !IntegrationBackends.server().requests("POST", "/api/pull").isEmpty()),
                "pullがOllamaへ送られていない");
        assertTrue(IntegrationBackends.server().requests("POST", "/api/pull").get(0).bodyText()
                .contains("qwen2.5:7b-instruct"));

        pull(96003L, ADMIN, "{\"model\":\"qwen2.5:7b-instruct\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.alreadyRunning").value(true));

        // Ollamaが完了を返すと、ジョブはdoneになる。
        release.countDown();
        assertTrue(waitUntil(() -> "done".equals(generationJobRepository.findById(jobId).orElseThrow().getStatus())),
                "ジョブがdoneにならない: " + generationJobRepository.findById(jobId).orElseThrow().getStatus());
    }
}
