package com.letsblog.content.controller;

import com.letsblog.content.dto.GenerateCustomTagRequest;
import com.letsblog.content.dto.GenerationJobResponse;
import com.letsblog.content.service.ForbiddenException;
import com.letsblog.content.testsupport.ContentWorld;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** POST /api/custom-tags/generate/jobs(issue #1409)。受理は202で、ジョブの種別と状態を返す。 */
@DisplayName("content-service: カスタムタグ生成をジョブとして受理するエンドポイント(issue #1409)")
class CustomTagControllerGenerateJobTest {

    private ContentWorld world;
    private CustomTagController controller;

    @BeforeEach
    void setUp() {
        // コントローラ・各サービスは実物。差し替えるのは外部との通信(ai-service・identity-serviceのHTTP、DB)だけ。
        world = new ContentWorld().actingAsAdmin(1L);
        controller = new CustomTagController(
                world.customTagService, world.customTagGenerationService,
                world.customTagValidationService, world.customTagGenerationJobStarter);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    @DisplayName("ジョブを受理して 202 とジョブ(UTCのInstant)を返す")
    void acceptsJob() {
        GenerateCustomTagRequest request = new GenerateCustomTagRequest("p", "t", null, null);
        world.http.json("POST", "/api/internal/ai/generation-jobs",
                "{\"id\":41,\"type\":\"custom_tag_generation\",\"status\":\"running\","
                        + "\"createdAt\":\"2026-10-06T01:02:03\",\"updatedAt\":\"2026-10-06T01:02:03\"}");

        ResponseEntity<GenerationJobResponse> response = controller.generateJob(request, "Bearer abc");

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        GenerationJobResponse body = response.getBody();
        assertEquals(41L, body.id());
        assertEquals("custom_tag_generation", body.type());
        assertEquals("running", body.status());
        assertEquals(Instant.parse("2026-10-06T01:02:03Z"), body.createdAt());
        assertEquals("Bearer abc", world.http.requests("POST", "/api/internal/ai/generation-jobs").get(0)
                .header("Authorization"));
    }

    @Test
    @DisplayName("管理者でなければ受理せず、ジョブも作らない")
    void rejectsNonAdmin() {
        world.actingAsUser(2L);

        assertThrows(ForbiddenException.class,
                () -> controller.generateJob(new GenerateCustomTagRequest("p", "t", null, null), "Bearer abc"));

        assertTrue(world.http.requests("POST", "/api/internal/ai/generation-jobs").isEmpty());
    }
}
