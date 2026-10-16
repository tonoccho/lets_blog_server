package com.letsblog.ai.controller;

import com.letsblog.ai.dto.PullOllamaModelRequest;
import com.letsblog.ai.dto.PullOllamaModelResponse;
import com.letsblog.ai.service.ForbiddenException;
import com.letsblog.ai.service.OllamaPullJobRunner;
import com.letsblog.ai.service.OllamaPullService;
import com.letsblog.ai.testsupport.AiWorld;
import com.letsblog.ai.testsupport.FakeHttpServer;
import com.letsblog.common.auth.ToolsTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OllamaPullController(issue #1675)。管理者だけが開始でき、判定を通ってからサービスへ委譲する。
 *
 * <p>issue #1824: OllamaPullService・OllamaPullJobRunner・AdminAuthorizationServiceは実物で、
 * platform-service・identity-service・Ollama(POST /api/pull)はHTTPフェイク、DBはメモリ上のリポジトリで差し替える。
 */
class OllamaPullControllerTest {

    private AiWorld world;
    private OllamaPullController controller;

    @BeforeEach
    void setUp() {
        world = new AiWorld();
        OllamaPullJobRunner runner = new OllamaPullJobRunner(
                world.generationJobRepository, world.objectMapper, ToolsTokenProvider.disabled());
        OllamaPullService service = new OllamaPullService(
                world.projectConnectionService, world.generationJobRepository, world.currentActorService, runner,
                world.objectMapper);
        controller = new OllamaPullController(service, world.adminAuthorizationService);
        world.http.json("GET", "/api/internal/platform/ai-connections-config",
                "{\"ollama\":{\"baseUrl\":\"" + world.http.baseUrl() + "/v1\",\"source\":\"DATABASE\",\"configured\":true},"
                        + "\"comfyui\":{\"baseUrl\":\"http://c\",\"source\":\"DATABASE\",\"configured\":true}}");
        world.http.on("POST", "/api/pull",
                FakeHttpServer.Response.bytes(200, "application/x-ndjson", "{\"status\":\"success\"}".getBytes()));
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void pull_管理者判定後にサービスへ委譲する() {
        world.admin();

        PullOllamaModelResponse response = controller.pull(7L, new PullOllamaModelRequest("qwen2.5:7b"));

        assertEquals(new PullOllamaModelResponse(1L, false), response);
        assertEquals(1, world.http.requests("POST", "/api/pull").size());
        assertTrue(world.http.requests("POST", "/api/pull").get(0).bodyText().contains("qwen2.5:7b"));
        assertEquals("done", world.jobRows.rows.get(1L).getStatus());
    }

    @Test
    void pull_管理者でなければ403でサービスを呼ばない() {
        world.member(10L);

        assertThrows(ForbiddenException.class, () -> controller.pull(7L, new PullOllamaModelRequest("llama3")));

        assertTrue(world.jobRows.saved.isEmpty());
        assertTrue(world.http.requests("POST", "/api/pull").isEmpty());
    }
}
