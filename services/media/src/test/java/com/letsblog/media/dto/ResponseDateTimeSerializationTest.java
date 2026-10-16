package com.letsblog.media.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.letsblog.media.controller.DiagramController;
import com.letsblog.media.controller.GeneratedImageController;
import com.letsblog.media.controller.ImageGenerationController;
import com.letsblog.media.controller.ProjectMediaGarbageCollectionController;
import com.letsblog.media.domain.Diagram;
import com.letsblog.media.domain.GeneratedImage;
import com.letsblog.media.testsupport.MediaWorld;
import com.letsblog.media.repository.DiagramRepository;
import com.letsblog.media.repository.GeneratedImageRepository;
import com.letsblog.media.service.ImageGenerationJobStarter;
import com.letsblog.media.testsupport.AsyncProxies;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * issue #1539: openapi/media.json が format: date-time(RFC 3339、オフセット必須)と宣言する
 * 公開レスポンスの日時が、UTC の Z 終端で出力されること。
 *
 * <p>API の文字列形式は画面から観測できないため、Gherkin ではなくサービスレベルの
 * シリアライズテストで表現する。コントローラの既存の組み立て経路を通した戻り値を、
 * Spring MVC の既定コンバータと同じ Jackson 3 の JsonMapper で JSON にする。
 * DB / エンティティは UTC の壁時計 LocalDateTime のまま、DTO への変換時に UTC を付けるだけ。
 */
class ResponseDateTimeSerializationTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 9, 8, 20, 3, 35);
    private static final LocalDateTime UPDATED = LocalDateTime.of(2026, 9, 9, 1, 2, 3);
    private static final String CREATED_Z = "2026-09-08T20:03:35Z";
    private static final String UPDATED_Z = "2026-09-09T01:02:03Z";

    private final JsonMapper mapper = JsonMapper.builder().build();

    /** 認可・保存・各サービスは実物。DB(リポジトリ)・MQ・各サービスへのHTTPだけを差し替える(issue #1820)。 */
    private final MediaWorld world = new MediaWorld();

    @BeforeEach
    void actAsAdmin() {
        world.actingAsAdmin(1L);
    }

    @AfterEach
    void close() {
        world.close();
    }

    private JsonNode json(Object value) {
        return mapper.valueToTree(value);
    }

    private static Diagram diagram(LocalDateTime createdAt, LocalDateTime updatedAt) {
        Diagram d = new Diagram();
        d.setId(1L);
        d.setProjectId(2L);
        d.setName("flow");
        d.setXml("<x/>");
        d.setSvg("<svg/>");
        d.setCreatedAt(createdAt);
        d.setUpdatedAt(updatedAt);
        return d;
    }

    private static GeneratedImage image(LocalDateTime createdAt) {
        GeneratedImage i = new GeneratedImage();
        i.setId(3L);
        i.setProjectId(2L);
        i.setPrompt("a cat");
        i.setFilePath("p.png");
        i.setMimeType("image/png");
        i.setCreatedAt(createdAt);
        return i;
    }

    private DiagramController diagramController(DiagramRepository repository) {
        return new DiagramController(repository, world.adminAuthorizationService);
    }

    private GeneratedImageController imageController(GeneratedImageRepository repository) {
        return new GeneratedImageController(
                repository, world.storageService, world.objectMapper, world.domainEventPublisher,
                world.adminAuthorizationService, world.creationService, world.folderService);
    }

    @Test
    void diagram_詳細と一覧はZ終端のRFC3339で返る() {
        DiagramRepository repository = mock(DiagramRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(diagram(CREATED, UPDATED)));
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(diagram(CREATED, UPDATED)));
        DiagramController controller = diagramController(repository);

        JsonNode detail = json(controller.get(1L));
        JsonNode summary = json(controller.list(null)).get(0);

        for (JsonNode node : List.of(detail, summary)) {
            assertEquals(CREATED_Z, node.get("createdAt").asString());
            assertEquals(UPDATED_Z, node.get("updatedAt").asString());
            assertEquals("flow", node.get("name").asString());
        }
        assertEquals("<svg/>", detail.get("svg").asString());
    }

    @Test
    void diagram_日時がnullならnullのまま返る() {
        DiagramRepository repository = mock(DiagramRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(diagram(null, null)));

        JsonNode detail = json(diagramController(repository).get(1L));

        assertTrue(detail.get("createdAt").isNull());
        assertTrue(detail.get("updatedAt").isNull());
    }

    @Test
    void generatedImage_詳細と一覧はZ終端のRFC3339で返る() {
        GeneratedImageRepository repository = mock(GeneratedImageRepository.class);
        when(repository.findById(3L)).thenReturn(Optional.of(image(CREATED)));
        when(repository.findAllByOrderByCreatedAtDescIdDesc()).thenReturn(List.of(image(CREATED)));
        GeneratedImageController controller = imageController(repository);

        JsonNode detail = json(controller.get(3L));
        JsonNode summary = json(controller.list(null, null, null, null, null, null, null)).get(0);

        assertEquals(CREATED_Z, detail.get("createdAt").asString());
        assertEquals(CREATED_Z, summary.get("createdAt").asString());
        assertEquals("a cat", detail.get("prompt").asString());
        assertEquals("a cat", summary.get("prompt").asString());
    }

    @Test
    void generatedImage_日時がnullならnullのまま返る() {
        GeneratedImageRepository repository = mock(GeneratedImageRepository.class);
        when(repository.findById(3L)).thenReturn(Optional.of(image(null)));

        assertTrue(json(imageController(repository).get(3L)).get("createdAt").isNull());
    }

    @Test
    void 画像生成ジョブ受理の応答はZ終端のRFC3339で返る() {
        world.http.json("POST", "/api/internal/ai/generation-jobs",
                "{\"id\":7,\"type\":\"image_generation\",\"status\":\"running\","
                        + "\"createdAt\":\"2026-09-08T20:03:35\",\"updatedAt\":\"2026-09-09T01:02:03\"}");
        ImageGenerationJobStarter starter = new ImageGenerationJobStarter(
                world.imageGenerationService, AsyncProxies.async(world.jobRunner, "imageGenerationExecutor", task -> { }),
                world.generationJobClient, world.objectMapper, world.tracker);
        ImageGenerationController controller = new ImageGenerationController(
                world.imageGenerationService, world.adminAuthorizationService, starter);

        ResponseEntity<?> response = controller.imageJob(
                AiImageRequest.withDefaults("a cat"), "Bearer t");

        JsonNode node = json(response.getBody());
        assertEquals(CREATED_Z, node.get("createdAt").asString());
        assertEquals(UPDATED_Z, node.get("updatedAt").asString());
        assertEquals("image_generation", node.get("type").asString());
    }

    @Test
    void 未使用メディア削除ジョブの応答はZ終端のRFC3339で返る() {
        world.http.json("POST", "/api/internal/ai/generation-jobs",
                "{\"id\":5,\"type\":\"media_garbage_collection_delete\",\"status\":\"running\","
                        + "\"createdAt\":\"2026-09-08T20:03:35\",\"updatedAt\":null}");
        ProjectMediaGarbageCollectionController controller = new ProjectMediaGarbageCollectionController(
                world.gcService, world.adminAuthorizationService, world.currentActorService);

        Object response = controller.delete(1L, "local", new MediaGarbageCollectionDeleteRequest(List.of("10")));

        JsonNode node = json(response);
        assertEquals(CREATED_Z, node.get("createdAt").asString());
        assertTrue(node.get("updatedAt").isNull());
        assertEquals(5, node.get("id").asInt());
    }
}
