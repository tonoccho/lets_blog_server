package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.common.client.GenerationJobBridgeException;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.domain.StaticContentType;
import com.letsblog.project.repository.StaticContentRepository;
import com.letsblog.project.repository.TagDesignSettingRepository;
import com.letsblog.project.testsupport.AsyncProxies;
import com.letsblog.project.testsupport.ProjectWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * テキスト生成(静的コンテンツ・タグデザイン)ジョブの受理側(issue #1409)。
 *
 * <p>issue #1817: ランナー・StaticContentGenerationService・GenerationJobClient・CurrentActorService は実物。
 * ランナーはSpringの実際のAOPプロキシ(@Async)で包み、Executorを差し替えて「実行を後に回す」「満杯で拒否する」を
 * 再現する。DB(リポジトリ)とHTTPの向こう(ai-service)だけを差し替える。
 */
@DisplayName("project-service: テキスト生成ジョブの受理(issue #1409)")
class TextGenerationJobStarterTest {

    private final ProjectWorld world = new ProjectWorld();
    private final StaticContentRepository staticContentRepository = Mockito.mock(StaticContentRepository.class);
    private final TagDesignSettingRepository tagDesignSettingRepository = Mockito.mock(TagDesignSettingRepository.class);
    private final List<Runnable> deferred = new ArrayList<>();
    private TextGenerationJobRunner realRunner;
    private StaticContentGenerationService staticContent;
    private TextGenerationJobStarter starter;

    @BeforeEach
    void setUp() {
        staticContent = new StaticContentGenerationService(
                world.siteRepository, world.siteService, world.wordPressAgentPluginsClient, world.cmsBridgeClient,
                world.aiGenerationClient, staticContentRepository);
        realRunner = new TextGenerationJobRunner(staticContent, new TagDesignGenerationService(world.aiGenerationClient),
                new TagDesignSettingService(tagDesignSettingRepository), world.generationJobClient, world.objectMapper);
        starterWith(deferred::add);
        world.actingAs(5L, "admin", "a@example.com");
    }

    private void starterWith(java.util.concurrent.Executor executor) {
        TextGenerationJobRunner asyncRunner = AsyncProxies.async(realRunner, "textGenerationExecutor", executor);
        starter = new TextGenerationJobStarter(
                world.generationJobClient, asyncRunner, staticContent, world.currentActorService, world.objectMapper);
    }

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    @DisplayName("ジョブ種別は安定した名前")
    void jobTypesAreStable() {
        assertEquals("static_content_generation", TextGenerationJobStarter.JOB_TYPE_STATIC_CONTENT);
        assertEquals("tag_design_generation", TextGenerationJobStarter.JOB_TYPE_TAG_DESIGN);
    }

    @Test
    @DisplayName("静的コンテンツ: サイトの存在を確かめ、ユーザーのBearerでジョブを作り、同じBearerでランナーを起動する")
    void startsStaticContentJob() throws Exception {
        when(world.siteRepository.existsById(5L)).thenReturn(true);
        world.jobCreated(11L, "static_content_generation");

        GenerationJobSummary result = starter.startStaticContent(5L, StaticContentType.PRIVACY_POLICY);

        assertEquals(11L, result.id());
        assertEquals("running", result.status());
        var create = world.http.requests("POST", "/api/internal/ai/generation-jobs").get(0);
        assertEquals("Bearer token-5", create.header("Authorization"));
        JsonNode body = world.bodyOf(create);
        assertEquals("static_content_generation", body.get("type").asText());
        JsonNode json = world.objectMapper.readTree(body.get("requestPayload").asText());
        assertEquals(5L, json.get("siteId").asLong());
        assertEquals("PRIVACY_POLICY", json.get("contentType").asText());
        assertEquals(1, deferred.size(), "ランナーは受理のスレッドでは動かさず、Executorへ渡す");
    }

    @Test
    @DisplayName("静的コンテンツ: サイトが無ければジョブを作らず例外を伝える")
    void staticContentSiteMissing() {
        assertThrows(SiteNotFoundException.class,
                () -> starter.startStaticContent(5L, StaticContentType.PRIVACY_POLICY));

        assertTrue(world.http.requests("POST", "/api/internal/ai/generation-jobs").isEmpty());
    }

    @Test
    @DisplayName("タグデザイン(プロジェクト個別): プロジェクト・種別・プロンプトをジョブの要求に残して起動する")
    void startsTagDesignJobForProject() throws Exception {
        world.jobCreated(12L, "tag_design_generation");

        GenerationJobSummary result = starter.startTagDesign(3L, EmbedTagType.TOC, "淡いグレー");

        assertEquals(12L, result.id());
        assertEquals(1, deferred.size());
        var create = world.http.requests("POST", "/api/internal/ai/generation-jobs").get(0);
        assertEquals("Bearer token-5", create.header("Authorization"));
        JsonNode json = world.objectMapper.readTree(world.bodyOf(create).get("requestPayload").asText());
        assertEquals(3L, json.get("projectId").asLong());
        assertEquals("TOC", json.get("tagType").asText());
        assertEquals("淡いグレー", json.get("prompt").asText());
        // 取り置いたランナーを実際に流すと、このジョブIDへ結果が書かれる。
        world.aiReplies("```css\n.a{}\n```");
        deferred.forEach(Runnable::run);
        assertEquals(1, world.jobUpdates(12L, "done").size());
    }

    @Test
    @DisplayName("タグデザイン(グローバル): projectId は null で残る")
    void startsTagDesignJobForGlobal() throws Exception {
        world.jobCreated(13L, "tag_design_generation");

        starter.startTagDesign(null, EmbedTagType.BLOGCARD, "p");

        var create = world.http.requests("POST", "/api/internal/ai/generation-jobs").get(0);
        JsonNode json = world.objectMapper.readTree(world.bodyOf(create).get("requestPayload").asText());
        assertTrue(json.get("projectId").isNull());
        assertEquals(1, deferred.size());
    }

    @Test
    @DisplayName("実行枠と待ち行列が満杯なら、ジョブを queue_full の failed にして返す")
    void queueFull() {
        world.jobCreated(14L, "tag_design_generation");
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolTaskExecutor saturated = AsyncProxies.saturated(release);
        try {
            starterWith(saturated);

            GenerationJobSummary result = starter.startTagDesign(3L, EmbedTagType.TOC, "p");

            assertEquals("failed", result.status());
            var failed = world.jobUpdates(14L, "failed");
            assertEquals(1, failed.size());
            assertEquals("queue_full", failed.get(0).payload().get("errorType").asText());
            assertFalse(failed.get(0).payload().get("error").asText().isBlank());
        } finally {
            release.countDown();
            saturated.shutdown();
        }
    }

    @Test
    @DisplayName("静的コンテンツでも満杯なら queue_full の failed")
    void queueFullForStaticContent() {
        when(world.siteRepository.existsById(5L)).thenReturn(true);
        world.jobCreated(15L, "static_content_generation");
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolTaskExecutor saturated = AsyncProxies.saturated(release);
        try {
            starterWith(saturated);

            assertEquals("failed", starter.startStaticContent(5L, StaticContentType.OPERATOR_INFO).status());
            assertEquals("queue_full", world.jobUpdates(15L, "failed").get(0).payload().get("errorType").asText());
        } finally {
            release.countDown();
            saturated.shutdown();
        }
    }

    @Test
    @DisplayName("ジョブを作れなければ(ai-service 不達)ランナーを起動せず例外を伝える")
    void createFailurePropagates() {
        world.fail("POST", "/api/internal/ai/generation-jobs", 500);

        assertThrows(GenerationJobBridgeException.class, () -> starter.startTagDesign(3L, EmbedTagType.TOC, "p"));

        assertTrue(deferred.isEmpty());
    }
}
