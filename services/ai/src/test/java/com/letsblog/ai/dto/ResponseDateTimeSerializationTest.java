package com.letsblog.ai.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.letsblog.ai.controller.GenerationJobController;
import com.letsblog.ai.controller.InternalGenerationJobController;
import com.letsblog.ai.domain.ArticlePlanSession;
import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.repository.ArticlePlanSessionRepository;
import com.letsblog.ai.repository.GenerationJobRepository;
import com.letsblog.ai.service.ArticlePlanService;
import com.letsblog.ai.service.CurrentActorService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

    private final GenerationJobRepository jobRepository = mock(GenerationJobRepository.class);
    private final CurrentActorService actor = mock(CurrentActorService.class);

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

    private ArticlePlanService planService(ArticlePlanSessionRepository repo) {
        return new ArticlePlanService(
                null, null, null, jobRepository, new com.fasterxml.jackson.databind.ObjectMapper(),
                null, repo, null, null, actor);
    }

    private void assertZ(JsonNode node) {
        assertEquals(CREATED_Z, node.get("createdAt").asString());
        assertEquals(UPDATED_Z, node.get("updatedAt").asString());
    }

    @Test
    void generationJobResponse_一覧はZ終端のRFC3339で返る() {
        when(actor.isAdmin()).thenReturn(true);
        when(jobRepository.findAll()).thenReturn(List.of(job()));
        List<GenerationJobResponse> list = new GenerationJobController(jobRepository, actor).list();
        assertZ(json(list.get(0)));
    }

    @Test
    void generationJobResponse_内部APIの更新もZ終端のRFC3339で返る() {
        when(jobRepository.findById(7L)).thenReturn(Optional.of(job()));
        when(jobRepository.save(any(GenerationJob.class))).thenAnswer(i -> i.getArgument(0));
        GenerationJobResponse response = new InternalGenerationJobController(jobRepository, actor)
                .update(7L, new UpdateGenerationJobRequest("done", null));
        assertZ(json(response));
    }

    @Test
    void generationJobResponse_内部APIの作成もZ終端のRFC3339で返る() {
        when(jobRepository.save(any(GenerationJob.class))).thenAnswer(i -> {
            GenerationJob saved = i.getArgument(0);
            saved.setId(8L);
            saved.setCreatedAt(CREATED);
            saved.setUpdatedAt(UPDATED);
            return saved;
        });
        GenerationJobResponse response = new InternalGenerationJobController(jobRepository, actor)
                .create(new CreateGenerationJobRequest("t", null));
        assertZ(json(response));
    }

    @Test
    void generationJobDetailResponse_はZ終端のRFC3339で返る() {
        when(actor.isAdmin()).thenReturn(true);
        when(jobRepository.findById(7L)).thenReturn(Optional.of(job()));
        assertZ(json(new GenerationJobController(jobRepository, actor).get(7L)));
    }

    @Test
    void articlePlanSessionSummaryResponse_はZ終端のRFC3339で返る() {
        ArticlePlanSessionRepository repo = mock(ArticlePlanSessionRepository.class);
        when(repo.findByProjectIdOrderByUpdatedAtDescIdDesc(2L)).thenReturn(List.of(session()));
        assertZ(json(planService(repo).listSessions(2L).get(0)));
    }

    @Test
    void articlePlanSessionDetailResponse_はZ終端のRFC3339で返る() {
        ArticlePlanSessionRepository repo = mock(ArticlePlanSessionRepository.class);
        when(repo.findById(1L)).thenReturn(Optional.of(session()));
        assertZ(json(planService(repo).getSession(2L, 1L)));
    }

    @Test
    void 日時が未設定ならnullのまま返る() {
        ArticlePlanSession session = session();
        session.setCreatedAt(null);
        session.setUpdatedAt(null);
        ArticlePlanSessionRepository repo = mock(ArticlePlanSessionRepository.class);
        when(repo.findById(1L)).thenReturn(Optional.of(session));
        JsonNode node = json(planService(repo).getSession(2L, 1L));
        assertTrue(node.get("createdAt").isNull());
        assertTrue(node.get("updatedAt").isNull());
    }
}
