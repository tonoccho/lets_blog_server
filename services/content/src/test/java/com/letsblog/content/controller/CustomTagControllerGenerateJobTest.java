package com.letsblog.content.controller;

import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.content.dto.GenerateCustomTagRequest;
import com.letsblog.content.dto.GenerationJobResponse;
import com.letsblog.content.service.CustomTagGenerationJobStarter;
import com.letsblog.content.service.CustomTagGenerationService;
import com.letsblog.content.service.CustomTagService;
import com.letsblog.content.service.CustomTagValidationService;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** POST /api/custom-tags/generate/jobs(issue #1409)。受理は202で、ジョブの種別と状態を返す。 */
@DisplayName("content-service: カスタムタグ生成をジョブとして受理するエンドポイント(issue #1409)")
class CustomTagControllerGenerateJobTest {

    private final CustomTagGenerationJobStarter starter = mock(CustomTagGenerationJobStarter.class);
    private final CustomTagController controller = new CustomTagController(
            mock(CustomTagService.class), mock(CustomTagGenerationService.class),
            mock(CustomTagValidationService.class), starter);

    @Test
    @DisplayName("ジョブを受理して 202 とジョブ(UTCのInstant)を返す")
    void acceptsJob() {
        GenerateCustomTagRequest request = new GenerateCustomTagRequest("p", "t", null, null);
        LocalDateTime created = LocalDateTime.of(2026, 10, 6, 1, 2, 3);
        when(starter.start(request, "Bearer abc"))
                .thenReturn(new GenerationJobSummary(41L, "custom_tag_generation", "running", created, created));

        ResponseEntity<GenerationJobResponse> response = controller.generateJob(request, "Bearer abc");

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        GenerationJobResponse body = response.getBody();
        assertEquals(41L, body.id());
        assertEquals("custom_tag_generation", body.type());
        assertEquals("running", body.status());
        assertEquals(Instant.parse("2026-10-06T01:02:03Z"), body.createdAt());
    }
}
