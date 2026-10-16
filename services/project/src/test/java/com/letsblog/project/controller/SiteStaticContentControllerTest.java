package com.letsblog.project.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.project.domain.Site;
import com.letsblog.project.domain.StaticContent;
import com.letsblog.project.domain.StaticContentType;
import com.letsblog.project.dto.GenerateStaticContentRequest;
import com.letsblog.project.dto.GenerationJobResponse;
import com.letsblog.project.dto.SaveStaticContentRequest;
import com.letsblog.project.dto.StaticContentResponse;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.testsupport.ProjectApp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SiteStaticContentControllerの回帰テスト(issue #577 stage2、legacy-apiから移設)。
 *
 * <p>issue #1818: StaticContentGenerationService・TextGenerationJobStarter・AdminAuthorizationService は実物。
 * identity-service・ai-service・provision-agent・CMS ブリッジの代わりにフェイクHTTPサーバーが応答し、
 * 差し替えるのはDBのリポジトリだけ。
 */
class SiteStaticContentControllerTest {

    private final ProjectApp app = new ProjectApp();

    @AfterEach
    void close() {
        app.close();
    }

    private SiteStaticContentController controller() {
        return new SiteStaticContentController(app.staticContentGenerationService,
                app.world.adminAuthorizationService, app.textGenerationJobStarter);
    }

    private void asNonAdmin() {
        app.world.actingAsUser(5L);
    }

    private void managedSite() {
        Site site = app.world.addManagedSite(1L, "my-slug", "wp_my-slug");
        site.setName("マイサイト");
        site.setBaseUrl("https://example.com");
        app.world.respond("POST", "/plugins", Map.of("plugins", List.of(Map.of("name", "Akismet", "status", "active"))));
    }

    @Test
    void list_一覧を返す() {
        app.world.actingAsAdmin(1L);
        StaticContent entity = new StaticContent();
        entity.setId(1L);
        entity.setSiteId(1L);
        entity.setContentType(StaticContentType.PRIVACY_POLICY);
        entity.setBody("本文");
        when(app.staticContentRepository.findBySiteId(1L)).thenReturn(List.of(entity));

        List<StaticContentResponse> result = controller().list(1L);

        assertEquals(1, result.size());
        assertEquals("本文", result.get(0).body());
    }

    @Test
    void list_admin権限がなければForbidden() {
        asNonAdmin();

        assertThrows(ForbiddenException.class, () -> controller().list(1L));

        verify(app.staticContentRepository, never()).findBySiteId(any());
    }

    @Test
    void generate_admin権限があれば生成して保存する() {
        app.world.actingAsAdmin(1L);
        managedSite();
        app.world.aiReplies("前置き\n```text\n生成された本文\n```\n");
        when(app.staticContentRepository.findBySiteIdAndContentType(1L, StaticContentType.PRIVACY_POLICY))
                .thenReturn(Optional.empty());
        when(app.staticContentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        StaticContentResponse response = controller().generate(1L, new GenerateStaticContentRequest(StaticContentType.PRIVACY_POLICY));

        assertEquals("生成された本文", response.body());
        assertTrue(app.world.bodiesOf("POST", "/api/internal/ai/generate").get(0).get("prompt").asText().contains("Akismet"));
    }

    @Test
    void generate_admin権限がなければForbidden() {
        asNonAdmin();
        GenerateStaticContentRequest request = new GenerateStaticContentRequest(StaticContentType.PRIVACY_POLICY);

        assertThrows(ForbiddenException.class, () -> controller().generate(1L, request));

        assertTrue(app.world.http.requests("POST", "/api/internal/ai/generate").isEmpty());
    }

    // ---- issue #1409: 生成を非同期ジョブとして受理する / 「保存」で既存のstatic_contentへ書く ----

    @Test
    void generateJob_admin確認後にジョブとして受理し202を返す() throws Exception {
        app.world.actingAsAdmin(1L);
        when(app.world.siteRepository.existsById(1L)).thenReturn(true);
        app.world.jobCreated(9L, "static_content_generation");

        ResponseEntity<GenerationJobResponse> response = controller().generateJob(
                1L, new GenerateStaticContentRequest(StaticContentType.PRIVACY_POLICY));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(9L, response.getBody().id());
        assertEquals("static_content_generation", response.getBody().type());
        JsonNode created = app.world.objectMapper.readTree(
                app.world.bodyOf(app.world.http.requests("POST", "/api/internal/ai/generation-jobs").get(0))
                        .get("requestPayload").asText());
        assertEquals(1L, created.get("siteId").asLong());
        assertEquals("PRIVACY_POLICY", created.get("contentType").asText());
        assertEquals(1, app.jobs.size(), "生成は実行枠へ依頼されただけ");
    }

    @Test
    void generateJob_admin権限がなければジョブを作らない() {
        asNonAdmin();

        assertThrows(ForbiddenException.class, () -> controller().generateJob(
                1L, new GenerateStaticContentRequest(StaticContentType.PRIVACY_POLICY)));

        assertTrue(app.world.http.requests("POST", "/api/internal/ai/generation-jobs").isEmpty());
        assertTrue(app.jobs.isEmpty());
    }

    @Test
    void save_admin確認後に既存のstatic_contentへ保存する() {
        app.world.actingAsAdmin(1L);
        when(app.world.siteRepository.existsById(1L)).thenReturn(true);
        when(app.staticContentRepository.findBySiteIdAndContentType(1L, StaticContentType.OPERATOR_INFO))
                .thenReturn(Optional.empty());
        when(app.staticContentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        StaticContentResponse response = controller().save(
                1L, StaticContentType.OPERATOR_INFO, new SaveStaticContentRequest("保存する本文"));

        assertEquals("保存する本文", response.body());
        assertEquals(StaticContentType.OPERATOR_INFO, response.contentType());
        verify(app.staticContentRepository).save(any(StaticContent.class));
    }

    @Test
    void save_admin権限がなければ保存しない() {
        asNonAdmin();

        assertThrows(ForbiddenException.class, () -> controller().save(
                1L, StaticContentType.OPERATOR_INFO, new SaveStaticContentRequest("本文")));

        verify(app.staticContentRepository, never()).save(any());
    }
}
