package com.letsblog.project.controller;

import com.letsblog.project.cms.LetsblogPluginStatus.State;
import com.letsblog.project.client.AnalyticsBridgeClient.GoogleAnalyticsCredentials;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.ProjectPvSyncState;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.testsupport.ProjectApp;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * analytics-service が、GA のプロパティを選び終えた(連携が完了した)ことを知らせる内部API(issue #1578)。
 * 呼び出し元ユーザーがプロジェクトのメンバーかadminであることを検査してから、本番サイトへ GA の認証情報とルールを送る。
 *
 * <p>issue #1818: PvRuleService・AdminAuthorizationService は実物。identity-service・analytics-service・CMS ブリッジの
 * 代わりにフェイクHTTPサーバーが応答し、差し替えるのはDBのリポジトリだけ。
 */
class ProjectPvInternalControllerTest {

    private static final String CMS_SNS = "/api/internal/project/cms/letsblog-sns";

    private final ProjectApp app = new ProjectApp();

    @AfterEach
    void close() {
        app.close();
    }

    private ProjectPvInternalController controller() {
        return new ProjectPvInternalController(app.pvRuleService, app.world.adminAuthorizationService);
    }

    private void productionSiteReady() {
        Project project = new Project();
        project.setId(7L);
        project.setProductionSiteId(3L);
        when(app.projectRepository.findById(7L)).thenReturn(Optional.of(project));
        app.world.addSite(3L, "本番サイト");
        app.world.pluginState(State.INSTALLED);
        app.world.analytics(new GoogleAnalyticsCredentials(true, "987654321", "cid.apps", "csecret", "rtoken"));
        app.world.letsblogSns("pv-config-set", null, "{}");
        app.world.letsblogSns("pv-status", null, "{\"configured\":true,\"state\":\"接続済み\",\"property_id\":\"987654321\"}");
        app.world.letsblogSns("pv-rules-set", null, "{}");
        when(app.pvRuleRepository.findByProjectIdOrderByIdAsc(7L)).thenReturn(List.of());
        when(app.pvSyncStateRepository.save(any(ProjectPvSyncState.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void 権限を確かめてから送信を依頼し204を返す() {
        app.world.actingAsAdmin(1L);
        productionSiteReady();

        var response = controller().googleAnalyticsConnected(7L);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        List<String> commands = app.world.bodiesOf("POST", CMS_SNS).stream()
                .map(b -> b.path("command").asText()).toList();
        assertEquals(List.of("pv-config-set", "pv-status", "pv-rules-set"), commands);
        verify(app.pvSyncStateRepository).save(any(ProjectPvSyncState.class));
    }

    @Test
    void メンバーなら権限を通り送信を依頼する() {
        app.world.actingAsUser(5L);
        app.world.http.json("GET", "/api/internal/identity/projects/7/members/5", "true");
        productionSiteReady();

        assertEquals(HttpStatus.NO_CONTENT, controller().googleAnalyticsConnected(7L).getStatusCode());
        assertEquals(1, app.world.snsRequests("pv-config-set").size());
    }

    @Test
    void メンバーでもadminでもなければ拒否し送信しない() {
        app.world.actingAsUser(5L);
        app.world.http.json("GET", "/api/internal/identity/projects/7/members/5", "false");
        productionSiteReady();

        assertThrows(ForbiddenException.class, () -> controller().googleAnalyticsConnected(7L));

        assertTrue(app.world.http.requests("POST", CMS_SNS).isEmpty());
        verify(app.pvSyncStateRepository, never()).save(any());
    }
}
