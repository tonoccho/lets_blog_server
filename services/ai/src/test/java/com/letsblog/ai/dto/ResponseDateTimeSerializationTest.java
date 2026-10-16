package com.letsblog.ai.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.letsblog.ai.controller.GenerationJobController;
import com.letsblog.ai.controller.InternalGenerationJobController;
import com.letsblog.ai.domain.ArticlePlanSession;
import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.service.ArticlePlanService;
import com.letsblog.ai.testsupport.AiWorld;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * issue #1535: openapi/ai.json が format: date-time(RFC 3339、オフセット必須)と宣言する
 * 公開レスポンスの日時が、UTC の Z 終端で出力されること。
 *
 * <p>API の文字列形式は画面から観測できないため、Gherkin ではなくサービスレベルの
 * シリアライズテストで表現する。DTO は既存の組み立て経路(コントローラ / サービス)を通して作り、
 * Spring MVC の既定コンバータと同じ Jackson 3 の JsonMapper で JSON にする。
 *
 * <p>DB / エンティティは UTC の壁時計 LocalDateTime のまま、DTO への変換時に UTC を付けるだけ。
 */
class ResponseDateTimeSerializationTest {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 9, 8, 20, 3, 35);
    private static final LocalDateTime UPDATED = LocalDateTime.of(2026, 9, 9, 1, 2, 3);
    private static final String CREATED_Z = "2026-09-08T20:03:35Z";
    private static final String UPDATED_Z = "2026-09-09T01:02:03Z";

    private final JsonMapper mapper = JsonMapper.builder().build();

    /** 外部との通信(identity-service・DB)だけを差し替えた世界(issue #1824)。コントローラとサービスは実物。 */
    private final AiWorld world = new AiWorld();

    @AfterEach
    void tearDown() {
        world.close();
    }

    private JsonNode json(Object value) {
        return mapper.valueToTree(value);
    }

    private static GenerationJob job() {
        GenerationJob job = new GenerationJob();
        job.setId(7L);
        job.setType("t");
        job.setStatus("done");
        job.setCreatedAt(CREATED);
        job.setUpdatedAt(UPDATED);
        return job;
    }

    private static ArticlePlanSession session() {
        ArticlePlanSession session = new ArticlePlanSession();
        session.setId(1L);
        session.setProjectId(2L);
        session.setTitle("t");
        session.setHistory("[]");
        session.setCreatedAt(CREATED);
        session.setUpdatedAt(UPDATED);
        return session;
    }

    private ArticlePlanService planService() {
        return world.articlePlanService;
    }

    private GenerationJobController jobs() {
        return new GenerationJobController(world.generationJobRepository, world.currentActorService);
    }

    private InternalGenerationJobController internalJobs() {
        return new InternalGenerationJobController(world.generationJobRepository, world.currentActorService);
    }

    private void assertZ(JsonNode node) {
        assertEquals(CREATED_Z, node.get("createdAt").asString());
        assertEquals(UPDATED_Z, node.get("updatedAt").asString());
    }

    @Test
    void generationJobResponse_一覧はZ終端のRFC3339で返る() {
        world.admin();
        world.generationJobRepository.save(job());
        List<GenerationJobResponse> list = jobs().list();
        assertZ(json(list.get(0)));
    }

    @Test
    void generationJobResponse_内部APIの更新もZ終端のRFC3339で返る() {
        GenerationJob saved = world.generationJobRepository.save(job());
        GenerationJobResponse response = internalJobs().update(saved.getId(), new UpdateGenerationJobRequest("done", null));
        assertZ(json(response));
    }

    @Test
    void generationJobResponse_内部APIの作成もZ終端のRFC3339で返る() {
        // 作成時刻はリポジトリ(DB)が付ける。メモリ上のリポジトリは保存時の現在時刻を付けるので、
        // 形式(Z終端のRFC3339)だけを確かめる。
        GenerationJobResponse response = internalJobs().create(new CreateGenerationJobRequest("t", null));
        JsonNode node = json(response);
        assertTrue(node.get("createdAt").asString().endsWith("Z"), node.toString());
        assertTrue(node.get("updatedAt").asString().endsWith("Z"), node.toString());
    }

    @Test
    void generationJobDetailResponse_はZ終端のRFC3339で返る() {
        world.admin();
        GenerationJob saved = world.generationJobRepository.save(job());
        assertZ(json(jobs().get(saved.getId())));
    }

    @Test
    void articlePlanSessionSummaryResponse_はZ終端のRFC3339で返る() {
        world.articlePlanSessionRepository.save(session());
        assertZ(json(planService().listSessions(2L).get(0)));
    }

    @Test
    void articlePlanSessionDetailResponse_はZ終端のRFC3339で返る() {
        ArticlePlanSession saved = world.articlePlanSessionRepository.save(session());
        assertZ(json(planService().getSession(2L, saved.getId())));
    }

    @Test
    void 日時が未設定ならnullのまま返る() {
        // メモリ上のリポジトリは保存時に日時を補うので、保存したあとの行(DBから読み出した状態)で未設定にする。
        ArticlePlanSession saved = world.articlePlanSessionRepository.save(session());
        saved.setCreatedAt(null);
        saved.setUpdatedAt(null);
        JsonNode node = json(planService().getSession(2L, saved.getId()));
        assertTrue(node.get("createdAt").isNull());
        assertTrue(node.get("updatedAt").isNull());
    }
}
