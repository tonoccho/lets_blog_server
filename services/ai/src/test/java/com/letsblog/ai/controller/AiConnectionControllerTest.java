package com.letsblog.ai.controller;

import com.letsblog.ai.dto.AiConnectionResponse;
import com.letsblog.ai.dto.AiConnectionResponse.Provider;
import com.letsblog.ai.dto.AiConnectionResponse.Status;
import com.letsblog.ai.service.AiConnectionService;
import com.letsblog.ai.service.ForbiddenException;
import com.letsblog.ai.testsupport.AiWorld;
import com.letsblog.common.auth.ToolsTokenProvider;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AiConnectionController(issue #1499)。プロジェクトメンバー判定を通ってからサービスへ委譲する。
 *
 * <p>issue #1824: AiConnectionService・AdminAuthorizationServiceは実物で、platform-service・identity-service・
 * 疎通確認の相手(Ollama / ComfyUI)はHTTPフェイク、DBはメモリ上のリポジトリで差し替える。
 */
class AiConnectionControllerTest {

    private static final String MEMBER_PATH = "/api/internal/identity/projects/7/members/10";

    private AiWorld world;
    private AiConnectionController controller;

    @BeforeEach
    void setUp() {
        world = new AiWorld();
        AiConnectionService service = new AiConnectionService(
                world.platformServiceClient, world.currentActorService, world.projectAiSettingsService,
                ToolsTokenProvider.disabled());
        controller = new AiConnectionController(service, world.adminAuthorizationService);
        String root = world.http.baseUrl();
        world.http.json("GET", "/api/internal/platform/ai-connections-config",
                "{\"ollama\":{\"baseUrl\":\"" + root + "/v1\",\"source\":\"ENVIRONMENT\",\"configured\":true},"
                        + "\"comfyui\":{\"baseUrl\":\"" + root + "\",\"source\":\"DATABASE\",\"configured\":true},"
                        + "\"openai\":{\"baseUrl\":null,\"source\":\"NONE\",\"configured\":false},"
                        + "\"claude\":{\"baseUrl\":null,\"source\":\"NONE\",\"configured\":false}}");
        world.http.json("GET", "/v1/models", "{}");
        world.http.json("GET", "/system_stats", "{}");
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void listAiConnections_メンバー判定後にサービスへ委譲する() {
        world.member(10L);
        world.http.json("GET", MEMBER_PATH, "true");

        List<AiConnectionResponse> rows = controller.listAiConnections(7L);

        assertEquals(
                List.of(Provider.OLLAMA, Provider.COMFYUI, Provider.OPENAI, Provider.CLAUDE),
                rows.stream().map(AiConnectionResponse::provider).toList());
        assertEquals(Status.NORMAL, rows.get(0).status());
        assertEquals(1, world.http.requests("GET", MEMBER_PATH).size());
        assertEquals(1, world.http.requests("GET", "/v1/models").size());
    }

    @Test
    void listAiConnections_メンバーでなければForbiddenでサービスを呼ばない() {
        world.member(10L);
        world.http.json("GET", MEMBER_PATH, "false");

        assertThrows(ForbiddenException.class, () -> controller.listAiConnections(7L));

        assertTrue(world.http.requests("GET", "/api/internal/platform/ai-connections-config").isEmpty());
        assertTrue(world.http.requests("GET", "/v1/models").isEmpty());
    }
}
