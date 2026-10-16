package com.letsblog.project.controller;

import com.fasterxml.jackson.databind.JsonNode;
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
 * GlobalTagDesignSettingController(プロジェクト未紐付けサイト向けのグローバル既定。issue #763)の回帰テスト。
 *
 * <p>{@code AuthorizationMatrixIntegrationTest}が見るのは「JWTが無ければ401」までで、
 * 「有効だがadminでないJWTなら403」は見ていない。{@code requireAdmin()}の呼び出しが
 * 将来消えても既存テストでは検知できないため、{@link TagDesignSettingControllerTest}と
 * 同じ形でここに固定する。
 *
 * <p>プロジェクト単位が{@code requireProjectMemberOrAdmin}なのに対しこちらが
 * {@code requireAdmin}なのは、グローバル既定には判定に使えるプロジェクトメンバーシップが無く、
 * 影響範囲も未紐付けサイト全体に及ぶため。
 *
 * <p>issue #1818: TagDesignSettingService・TagDesignGenerationService・LetsblogSyncService・TextGenerationJobStarter・
 * AdminAuthorizationService は実物。identity-service・ai-service・content-service・CMS ブリッジの代わりに
 * フェイクHTTPサーバーが応答し、差し替えるのはDBのリポジトリだけ。
 */
class GlobalTagDesignSettingControllerTest {

    private static final String GENERATE = "/api/internal/ai/generate";

    private final ProjectApp app = new ProjectApp();

    @AfterEach
    void close() {
        app.close();
    }

    private GlobalTagDesignSettingController controller() {
        return new GlobalTagDesignSettingController(app.tagDesignSettingService, app.tagDesignGenerationService,
                app.world.adminAuthorizationService, app.letsblogSyncService, app.textGenerationJobStarter);
    }

    private void asNonAdmin() {
        app.world.actingAsUser(5L);
    }

    private static SaveTagDesignSettingRequest saveRequest() {
        return new SaveTagDesignSettingRequest("light", "#111111", "#eeeeee", "#60a5fa", null, null);
    }

    @Test
    void getOverview_認可後にグローバル既定行を引く() {
        app.world.actingAsAdmin(1L);
        TagDesignSetting global = new TagDesignSetting();
        global.setTagType(EmbedTagType.BLOGCARD);
        global.setPresetId("dark");
        global.setBackgroundColor("#000000");
        global.setTextColor("#ffffff");
        global.setAccentColor("#ff00ff");
        when(app.tagDesignSettingRepository.findByProjectIdIsNull()).thenReturn(List.of(global));

        TagDesignSettingsOverviewResponse response = controller().getOverview();

        TagDesignSettingResponse blogcard = response.settings().stream()
                .filter(s -> s.tagType() == EmbedTagType.BLOGCARD).findFirst().orElseThrow();
        assertEquals("#ff00ff", blogcard.accentColor());
        verify(app.tagDesignSettingRepository, never()).findByProjectId(any());
    }

    @Test
    void save_認可後にprojectId未指定で保存する() {
        app.world.actingAsAdmin(1L);
        when(app.tagDesignSettingRepository.findByProjectIdIsNullAndTagType(EmbedTagType.TOC)).thenReturn(Optional.empty());
        when(app.tagDesignSettingRepository.save(any(TagDesignSetting.class))).thenAnswer(i -> i.getArgument(0));

        TagDesignSettingResponse response = controller().save(EmbedTagType.TOC, saveRequest());

        assertEquals(new TagDesignSettingResponse(EmbedTagType.TOC, "light", "#111111", "#eeeeee", "#60a5fa", null, null), response);
        org.mockito.ArgumentCaptor<TagDesignSetting> saved = org.mockito.ArgumentCaptor.forClass(TagDesignSetting.class);
        verify(app.tagDesignSettingRepository).save(saved.capture());
        assertEquals(null, saved.getValue().getProjectId(), "グローバル既定行は projectId が null");
    }

    @Test
    void generate_認可後にprojectId未指定で生成する() throws Exception {
        app.world.actingAsAdmin(1L);
        when(app.tagDesignSettingRepository.findByProjectIdIsNullAndTagType(EmbedTagType.TOC)).thenReturn(Optional.empty());
        app.world.aiReplies("```css\n.a{}\n```\n\n```html\n<div></div>\n```");

        GenerateTagDesignResponse response = controller().generate(EmbedTagType.TOC, new GenerateTagDesignRequest("青くして"));

        assertTrue(response.cssContent().contains(".a{}"));
        assertTrue(response.htmlTemplate().contains("<div></div>"));
        JsonNode sent = app.world.bodiesOf("POST", GENERATE).get(0);
        assertTrue(sent.get("projectId").isNull());
        assertTrue(sent.get("prompt").asText().contains("青くして"));
    }

    // ------------------------------------------------------------ 認可で弾かれる場合

    @Test
    void getOverview_非adminはForbiddenでサービスに到達しない() {
        asNonAdmin();

        assertThrows(ForbiddenException.class, () -> controller().getOverview());

        verify(app.tagDesignSettingRepository, never()).findByProjectIdIsNull();
    }

    @Test
    void save_非adminはForbiddenでサービスに到達しない() {
        asNonAdmin();
        app.syncableProject(1L, 10L);

        assertThrows(ForbiddenException.class, () -> controller().save(EmbedTagType.TOC, saveRequest()));

        verify(app.tagDesignSettingRepository, never()).save(any());
        assertTrue(app.jobs.isEmpty());
    }

    @Test
    void generate_非adminはForbiddenでサービスに到達しない() {
        asNonAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller().generate(EmbedTagType.TOC, new GenerateTagDesignRequest("青くして")));

        assertTrue(app.world.http.requests("POST", GENERATE).isEmpty());
    }

    // ---- issue #1558: グローバルのデザイン変更はすべてのプロジェクトのサイトへ同期する ----

    @Test
    void save_保存したらすべてのプロジェクトのサイトへの同期を依頼する() {
        app.world.actingAsAdmin(1L);
        app.syncableProject(1L, 10L);
        when(app.tagDesignSettingRepository.findByProjectIdIsNullAndTagType(EmbedTagType.TOC)).thenReturn(Optional.empty());
        when(app.tagDesignSettingRepository.save(any(TagDesignSetting.class))).thenAnswer(i -> i.getArgument(0));

        controller().save(EmbedTagType.TOC, new SaveTagDesignSettingRequest("dark", "#fff", "#000", "#f00", null, null));

        assertEquals(1, app.jobs.size());
        app.drainJobs();
        assertEquals(1, app.pluginSyncRequests(), "全プロジェクトのサイトへプラグイン同期が送られる");
        verify(app.projectRepository).findAll();
    }

    // ---- issue #1409: 生成を非同期ジョブとして受理する ----

    @Test
    void generateJob_admin確認後にprojectId未指定でジョブとして受理し202を返す() throws Exception {
        app.world.actingAsAdmin(1L);
        app.world.jobCreated(9L, "tag_design_generation");

        ResponseEntity<GenerationJobResponse> response =
                controller().generateJob(EmbedTagType.BLOGCARD, new GenerateTagDesignRequest("p"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(9L, response.getBody().id());
        JsonNode created = app.world.objectMapper.readTree(
                app.world.bodyOf(app.world.http.requests("POST", "/api/internal/ai/generation-jobs").get(0))
                        .get("requestPayload").asText());
        assertTrue(created.get("projectId").isNull());
        assertEquals("BLOGCARD", created.get("tagType").asText());
        assertEquals("p", created.get("prompt").asText());
    }

    @Test
    void generateJob_adminでなければジョブを作らない() {
        asNonAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller().generateJob(EmbedTagType.TOC, new GenerateTagDesignRequest("p")));

        assertTrue(app.world.http.requests("POST", "/api/internal/ai/generation-jobs").isEmpty());
    }
}
