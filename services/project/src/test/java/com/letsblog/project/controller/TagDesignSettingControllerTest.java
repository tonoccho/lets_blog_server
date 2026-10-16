package com.letsblog.project.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.project.client.AiServiceException;
import com.letsblog.project.domain.EmbedTagType;
import com.letsblog.project.domain.TagDesignSetting;
import com.letsblog.project.dto.GenerateTagDesignRequest;
import com.letsblog.project.dto.GenerateTagDesignResponse;
import com.letsblog.project.dto.GenerationJobResponse;
import com.letsblog.project.dto.SaveTagDesignSettingRequest;
import com.letsblog.project.dto.TagDesignSettingResponse;
import com.letsblog.project.dto.TagDesignSettingsOverviewResponse;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.testsupport.ProjectApp;
import java.util.List;
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
 * TagDesignSettingControllerの回帰テスト。
 *
 * <p>issue #1818: TagDesignSettingService・TagDesignGenerationService・LetsblogSyncService・TextGenerationJobStarter・
 * AdminAuthorizationService は実物。identity-service・ai-service・content-service・CMS ブリッジの代わりに
 * フェイクHTTPサーバーが応答し、差し替えるのはDBのリポジトリだけ。
 */
class TagDesignSettingControllerTest {

    private static final String MEMBER = "/api/internal/identity/projects/1/members/5";
    private static final String GENERATE = "/api/internal/ai/generate";

    private final ProjectApp app = new ProjectApp();

    @AfterEach
    void close() {
        app.close();
    }

    private TagDesignSettingController controller() {
        return new TagDesignSettingController(app.tagDesignSettingService, app.tagDesignGenerationService,
                app.world.adminAuthorizationService, app.letsblogSyncService, app.textGenerationJobStarter);
    }

    private void asNonMember() {
        app.world.actingAsUser(5L);
        app.world.http.json("GET", MEMBER, "false");
    }

    private static SaveTagDesignSettingRequest saveRequest() {
        return new SaveTagDesignSettingRequest("dark", "#111111", "#eeeeee", "#60a5fa", null, null);
    }

    @Test
    void getOverview_認可後にサービスへ委譲する() {
        app.world.actingAsAdmin(1L);
        TagDesignSetting stored = new TagDesignSetting();
        stored.setProjectId(1L);
        stored.setTagType(EmbedTagType.AMAZON);
        stored.setPresetId("vivid");
        stored.setBackgroundColor("#fff7ed");
        stored.setTextColor("#7c2d12");
        stored.setAccentColor("#ea580c");
        when(app.tagDesignSettingRepository.findByProjectId(1L)).thenReturn(List.of(stored));

        TagDesignSettingsOverviewResponse response = controller().getOverview(1L);

        assertEquals(EmbedTagType.values().length, response.settings().size());
        TagDesignSettingResponse amazon = response.settings().stream()
                .filter(s -> s.tagType() == EmbedTagType.AMAZON).findFirst().orElseThrow();
        assertEquals("vivid", amazon.presetId());
        assertEquals("#ea580c", amazon.accentColor());
        assertTrue(response.presets().size() > 0);
    }

    @Test
    void getOverview_メンバーなら読める() {
        app.world.actingAsUser(5L);
        app.world.http.json("GET", MEMBER, "true");
        when(app.tagDesignSettingRepository.findByProjectId(1L)).thenReturn(List.of());

        assertEquals(EmbedTagType.values().length, controller().getOverview(1L).settings().size());
    }

    @Test
    void getOverview_認可拒否ならForbidden() {
        asNonMember();

        assertThrows(ForbiddenException.class, () -> controller().getOverview(1L));

        verify(app.tagDesignSettingRepository, never()).findByProjectId(any());
    }

    @Test
    void save_認可後に保存する() {
        app.world.actingAsAdmin(1L);
        when(app.tagDesignSettingRepository.findByProjectIdAndTagType(1L, EmbedTagType.TOC)).thenReturn(Optional.empty());
        when(app.tagDesignSettingRepository.save(any(TagDesignSetting.class))).thenAnswer(i -> i.getArgument(0));

        TagDesignSettingResponse response = controller().save(1L, EmbedTagType.TOC, saveRequest());

        assertEquals(new TagDesignSettingResponse(EmbedTagType.TOC, "dark", "#111111", "#eeeeee", "#60a5fa", null, null), response);
    }

    @Test
    void save_認可拒否ならForbiddenで保存も同期もしない() {
        asNonMember();
        app.syncableProject(1L, 10L);

        assertThrows(ForbiddenException.class, () -> controller().save(1L, EmbedTagType.TOC, saveRequest()));

        verify(app.tagDesignSettingRepository, never()).save(any());
        assertTrue(app.jobs.isEmpty());
    }

    @Test
    void generate_認可後に現在のHTMLテンプレートを添えて生成する() throws Exception {
        app.world.actingAsAdmin(1L);
        TagDesignSetting stored = new TagDesignSetting();
        stored.setProjectId(1L);
        stored.setTagType(EmbedTagType.TOC);
        stored.setHtmlTemplate("{{toc}}");
        when(app.tagDesignSettingRepository.findByProjectIdAndTagType(1L, EmbedTagType.TOC)).thenReturn(Optional.of(stored));
        app.world.aiReplies("```css\n.lb-toc-list{background:#fff;}\n```");

        GenerateTagDesignResponse response = controller().generate(1L, EmbedTagType.TOC, new GenerateTagDesignRequest("背景を白にして"));

        assertTrue(response.cssContent().contains("lb-toc-list"));
        JsonNode sent = app.world.bodiesOf("POST", GENERATE).get(0);
        assertEquals(1L, sent.get("projectId").asLong());
        assertTrue(sent.get("prompt").asText().contains("背景を白にして"));
        assertTrue(sent.get("prompt").asText().contains("{{toc}}"), "現在のHTMLテンプレートがプロンプトに載る");
    }

    @Test
    void generate_ai_serviceが失敗すればそのまま伝わる() {
        app.world.actingAsAdmin(1L);
        when(app.tagDesignSettingRepository.findByProjectIdAndTagType(1L, EmbedTagType.TOC)).thenReturn(Optional.empty());
        app.world.fail("POST", GENERATE, 500);

        assertThrows(AiServiceException.class,
                () -> controller().generate(1L, EmbedTagType.TOC, new GenerateTagDesignRequest("背景")));
    }

    @Test
    void generate_認可拒否ならForbiddenでai_serviceを呼ばない() {
        asNonMember();

        assertThrows(ForbiddenException.class,
                () -> controller().generate(1L, EmbedTagType.TOC, new GenerateTagDesignRequest("背景を白にして")));

        assertTrue(app.world.http.requests("POST", GENERATE).isEmpty());
    }

    // ---- issue #1558: デザインの変更をそのプロジェクトのサイトへ同期する ----

    @Test
    void save_保存したらそのプロジェクトのサイトへの同期を依頼する() {
        app.world.actingAsAdmin(1L);
        app.syncableProject(1L, 10L);
        when(app.tagDesignSettingRepository.findByProjectIdAndTagType(1L, EmbedTagType.TOC)).thenReturn(Optional.empty());
        when(app.tagDesignSettingRepository.save(any(TagDesignSetting.class))).thenAnswer(i -> i.getArgument(0));

        controller().save(1L, EmbedTagType.TOC, new SaveTagDesignSettingRequest("dark", "#fff", "#000", "#f00", null, null));

        assertEquals(1, app.jobs.size());
        assertEquals(0, app.pluginSyncRequests());
        app.drainJobs();
        assertEquals(1, app.pluginSyncRequests(), "そのプロジェクトのサイトへプラグイン同期が送られる");
    }

    // ---- issue #1409: 生成を非同期ジョブとして受理する ----

    @Test
    void generateJob_認可後にジョブとして受理し202を返す() throws Exception {
        app.world.actingAsAdmin(1L);
        app.world.jobCreated(9L, "tag_design_generation");

        ResponseEntity<GenerationJobResponse> response =
                controller().generateJob(1L, EmbedTagType.TOC, new GenerateTagDesignRequest("淡いグレー"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(9L, response.getBody().id());
        assertEquals("tag_design_generation", response.getBody().type());
        JsonNode created = app.world.objectMapper.readTree(
                app.world.bodyOf(app.world.http.requests("POST", "/api/internal/ai/generation-jobs").get(0))
                        .get("requestPayload").asText());
        assertEquals(1L, created.get("projectId").asLong());
        assertEquals("TOC", created.get("tagType").asText());
        assertEquals("淡いグレー", created.get("prompt").asText());
        assertEquals(1, app.jobs.size(), "生成は実行枠へ依頼されただけ");
    }

    @Test
    void generateJob_認可に失敗したらジョブを作らない() {
        asNonMember();

        assertThrows(ForbiddenException.class,
                () -> controller().generateJob(1L, EmbedTagType.TOC, new GenerateTagDesignRequest("p")));

        assertTrue(app.world.http.requests("POST", "/api/internal/ai/generation-jobs").isEmpty());
        assertTrue(app.jobs.isEmpty());
    }
}
