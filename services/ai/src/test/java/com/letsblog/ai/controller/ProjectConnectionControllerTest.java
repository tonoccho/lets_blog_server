package com.letsblog.ai.controller;

import com.letsblog.ai.dto.AiConnectionResponse.Source;
import com.letsblog.ai.dto.ProjectConnectionsResponse;
import com.letsblog.ai.dto.UpdateProjectConnectionsRequest;
import com.letsblog.ai.service.ForbiddenException;
import com.letsblog.ai.testsupport.AiWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProjectConnectionController(issue #1503)。メンバー判定を通ってからサービスへ委譲する。
 *
 * <p>issue #1824: ProjectConnectionService・AdminAuthorizationServiceは実物で、platform-service・identity-serviceは
 * HTTPフェイク、DBはメモリ上のリポジトリで差し替える。
 */
class ProjectConnectionControllerTest {

    private static final String CONFIG_PATH = "/api/internal/platform/ai-connections-config";
    private static final String MEMBER_PATH = "/api/internal/identity/projects/7/members/10";

    private AiWorld world;
    private ProjectConnectionController controller;

    @BeforeEach
    void setUp() {
        world = new AiWorld();
        controller = new ProjectConnectionController(world.projectConnectionService, world.adminAuthorizationService);
        world.http.json("GET", CONFIG_PATH,
                "{\"ollama\":{\"baseUrl\":\"http://o\",\"source\":\"ENVIRONMENT\",\"configured\":true},"
                        + "\"comfyui\":{\"baseUrl\":\"http://sys-c\",\"source\":\"DATABASE\",\"configured\":true}}");
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private void member() {
        world.member(10L);
        world.http.json("GET", MEMBER_PATH, "true");
    }

    @Test
    void get_メンバー判定後にサービスへ委譲する() {
        member();
        world.projectAiSettingsService.setConnectionUrls(7L, null, "http://c");

        ProjectConnectionsResponse response = controller.get(7L);

        assertEquals("http://o", response.ollama().baseUrl());
        assertEquals(Source.ENVIRONMENT, response.ollama().source());
        assertNull(response.ollama().overrideBaseUrl());
        assertEquals("http://c", response.comfyui().overrideBaseUrl());
        assertEquals(Source.PROJECT, response.comfyui().source());
        assertEquals(1, world.http.requests("GET", MEMBER_PATH).size());
    }

    @Test
    void put_メンバー判定後にサービスへ委譲する() {
        member();

        ProjectConnectionsResponse response = controller.put(7L, new UpdateProjectConnectionsRequest("", "http://c"));

        assertEquals("http://c", response.comfyui().baseUrl());
        assertEquals(Source.PROJECT, response.comfyui().source());
        assertEquals("http://c", world.projectAiSettingsService.getComfyuiBaseUrl(7L));
        assertEquals(1, world.http.requests("GET", MEMBER_PATH).size());
    }

    @Test
    void 非メンバーはForbiddenでサービスを呼ばない() {
        world.member(10L);
        world.http.json("GET", MEMBER_PATH, "false");

        assertThrows(ForbiddenException.class, () -> controller.get(7L));
        assertThrows(ForbiddenException.class,
                () -> controller.put(7L, new UpdateProjectConnectionsRequest("http://x", null)));

        assertTrue(world.http.requests("GET", CONFIG_PATH).isEmpty());
        assertTrue(world.projectAiSettingsRows.saved.isEmpty());
    }
}
