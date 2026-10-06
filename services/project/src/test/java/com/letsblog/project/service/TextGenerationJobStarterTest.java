package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.client.GenerationJobBridgeException;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.domain.StaticContentType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 静的コンテンツ・タグデザイン生成ジョブの受理側(issue #1409)。ManagedSiteProvisioningJobStarter相当。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("project-service: テキスト生成ジョブの受理(issue #1409)")
class TextGenerationJobStarterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 1, 2, 3);

    @Mock
    private GenerationJobClient generationJobClient;
    @Mock
    private TextGenerationJobRunner runner;
    @Mock
    private StaticContentGenerationService staticContentGenerationService;
    @Mock
    private CurrentActorService currentActorService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TextGenerationJobStarter starter;

    @BeforeEach
    void setUp() {
        starter = new TextGenerationJobStarter(
                generationJobClient, runner, staticContentGenerationService, currentActorService, objectMapper);
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
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer abc");
        GenerationJobSummary job = new GenerationJobSummary(11L, "static_content_generation", "running", NOW, NOW);
        when(generationJobClient.create(eq("static_content_generation"), any(), eq("Bearer abc"))).thenReturn(job);

        GenerationJobSummary result = starter.startStaticContent(5L, StaticContentType.PRIVACY_POLICY);

        assertSame(job, result);
        InOrder order = inOrder(staticContentGenerationService, generationJobClient, runner);
        order.verify(staticContentGenerationService).requireSite(5L);
        order.verify(generationJobClient).create(eq("static_content_generation"), any(), eq("Bearer abc"));
        order.verify(runner).runStaticContent(11L, 5L, StaticContentType.PRIVACY_POLICY, "Bearer abc");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).create(eq("static_content_generation"), payload.capture(), eq("Bearer abc"));
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertEquals(5L, json.get("siteId").asLong());
        assertEquals("PRIVACY_POLICY", json.get("contentType").asText());
    }

    @Test
    @DisplayName("静的コンテンツ: サイトが無ければジョブを作らず例外を伝える")
    void staticContentSiteMissing() {
        doThrow(new SiteNotFoundException("無い")).when(staticContentGenerationService).requireSite(5L);

        assertThrows(SiteNotFoundException.class,
                () -> starter.startStaticContent(5L, StaticContentType.PRIVACY_POLICY));

        verify(generationJobClient, never()).create(any(), any(), any());
    }

    @Test
    @DisplayName("タグデザイン(プロジェクト個別): プロジェクト・種別・プロンプトをジョブの要求に残して起動する")
    void startsTagDesignJobForProject() throws Exception {
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer abc");
        when(generationJobClient.create(any(), any(), any()))
                .thenReturn(new GenerationJobSummary(12L, "tag_design_generation", "running", NOW, NOW));

        GenerationJobSummary result = starter.startTagDesign(3L, EmbedTagType.TOC, "淡いグレー");

        assertEquals(12L, result.id());
        verify(runner).runTagDesign(12L, 3L, EmbedTagType.TOC, "淡いグレー");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).create(eq("tag_design_generation"), payload.capture(), eq("Bearer abc"));
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertEquals(3L, json.get("projectId").asLong());
        assertEquals("TOC", json.get("tagType").asText());
        assertEquals("淡いグレー", json.get("prompt").asText());
    }

    @Test
    @DisplayName("タグデザイン(グローバル): projectId は null で残る")
    void startsTagDesignJobForGlobal() throws Exception {
        when(generationJobClient.create(any(), any(), any()))
                .thenReturn(new GenerationJobSummary(13L, "tag_design_generation", "running", NOW, NOW));

        starter.startTagDesign(null, EmbedTagType.BLOGCARD, "p");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).create(eq("tag_design_generation"), payload.capture(), any());
        assertTrue(objectMapper.readTree(payload.getValue()).get("projectId").isNull());
        verify(runner).runTagDesign(13L, null, EmbedTagType.BLOGCARD, "p");
    }

    @Test
    @DisplayName("実行枠と待ち行列が満杯なら、ジョブを queue_full の failed にして返す")
    void queueFull() throws Exception {
        when(generationJobClient.create(any(), any(), any()))
                .thenReturn(new GenerationJobSummary(14L, "tag_design_generation", "running", NOW, NOW));
        doThrow(new TaskRejectedException("full")).when(runner).runTagDesign(any(), any(), any(), any());

        GenerationJobSummary result = starter.startTagDesign(3L, EmbedTagType.TOC, "p");

        assertEquals("failed", result.status());
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(14L), eq("failed"), payload.capture());
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertEquals("queue_full", json.get("errorType").asText());
        assertFalse(json.get("error").asText().isBlank());
    }

    @Test
    @DisplayName("静的コンテンツでも満杯なら queue_full の failed")
    void queueFullForStaticContent() {
        when(generationJobClient.create(any(), any(), any()))
                .thenReturn(new GenerationJobSummary(15L, "static_content_generation", "running", NOW, NOW));
        doThrow(new TaskRejectedException("full")).when(runner).runStaticContent(any(), any(), any(), any());

        assertEquals("failed", starter.startStaticContent(5L, StaticContentType.OPERATOR_INFO).status());
    }

    @Test
    @DisplayName("ジョブを作れなければ(ai-service 不達)ランナーを起動せず例外を伝える")
    void createFailurePropagates() {
        when(generationJobClient.create(any(), any(), any())).thenThrow(new GenerationJobBridgeException("down", null));

        assertThrows(GenerationJobBridgeException.class, () -> starter.startTagDesign(3L, EmbedTagType.TOC, "p"));

        verify(runner, never()).runTagDesign(any(), any(), any(), any());
    }
}
