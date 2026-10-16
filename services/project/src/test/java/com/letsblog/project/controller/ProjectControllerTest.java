package com.letsblog.project.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.project.domain.Project;
import com.letsblog.project.dto.GenerationJobResponse;
import com.letsblog.project.dto.ProjectCreateRequest;
import com.letsblog.project.dto.ProjectEnvironmentBindRequest;
import com.letsblog.project.dto.ProjectResponse;
import com.letsblog.project.dto.ProjectUpdateRequest;
import com.letsblog.project.dto.SyncEnvironmentRequest;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.service.ProjectNotFoundException;
import com.letsblog.project.testsupport.FakeHttpServer;
import com.letsblog.project.testsupport.ProjectApp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ProjectControllerの回帰テスト(issue #577 stage2、legacy-apiから移設)。
 *
 * <p>issue #1818: ProjectService・ProjectEnvironmentSyncService・ProjectEnvironmentSyncJobStarter・SnsXService・
 * AdminAuthorizationService・SiteService は実物。identity-service・ai-service・provision-agent・CMS ブリッジの
 * 代わりにフェイクHTTPサーバーが応答し、差し替えるのはDBのリポジトリとMQ({@code RabbitTemplate})だけ。
 */
class ProjectControllerTest {

    private static final String MEMBER = "/api/internal/identity/projects/1/members/5";
    private static final String PROJECT_IDS = "/api/internal/identity/users/5/project-ids";

    private final ProjectApp app = new ProjectApp();

    @AfterEach
    void close() {
        app.close();
    }

    private ProjectController controller() {
        return new ProjectController(app.projectService, app.projectEnvironmentSyncService,
                app.projectEnvironmentSyncJobStarter, app.world.adminAuthorizationService, app.snsXService);
    }

    private void asNonAdmin() {
        app.world.actingAsUser(5L);
    }

    private Project project(Long id, String slug) {
        Project project = new Project();
        project.setId(id);
        project.setName("テスト");
        project.setSlug(slug);
        project.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0).plusDays(id));
        when(app.projectRepository.findById(id)).thenReturn(Optional.of(project));
        when(app.projectRepository.save(any(Project.class))).thenAnswer(inv -> inv.getArgument(0));
        return project;
    }

    /** サイト{@code siteId}を、他のプロジェクトへ紐付いていない紐付け可能な状態にする。 */
    private void bindableSite(Long siteId) {
        app.world.addSite(siteId, "サイト" + siteId);
        when(app.world.siteRepository.existsById(siteId)).thenReturn(true);
        when(app.projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(siteId, siteId, siteId))
                .thenReturn(Optional.empty());
    }

    @Test
    void create_admin権限があれば作成できる() {
        app.world.actingAsAdmin(1L);
        when(app.projectRepository.existsBySlug("test")).thenReturn(false);
        when(app.projectRepository.save(any(Project.class))).thenAnswer(inv -> {
            Project saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        ResponseEntity<ProjectResponse> response = controller().create(new ProjectCreateRequest("テスト", "test"));

        assertEquals(201, response.getStatusCode().value());
        assertEquals("test", response.getBody().slug());
    }

    @Test
    void create_admin権限がなければForbiddenで作成しない() {
        asNonAdmin();

        assertThrows(ForbiddenException.class, () -> controller().create(new ProjectCreateRequest("テスト", "test")));

        verify(app.projectRepository, never()).save(any());
    }

    @Test
    void get_プロジェクトメンバーなら取得できる() {
        // issue #830 以前は認可チェックが無く、認証済みなら誰でも他人のプロジェクト構成を読めた。
        app.world.actingAsUser(5L);
        app.world.http.json("GET", MEMBER, "true");
        project(1L, "test");

        ProjectResponse response = controller().get(1L);

        assertEquals("test", response.slug());
        assertEquals("Bearer token-5", app.world.http.requests("GET", MEMBER).get(0).header("Authorization"));
    }

    @Test
    void get_メンバーでもadminでもなければ拒否しプロジェクトを読まない() {
        app.world.actingAsUser(5L);
        app.world.http.json("GET", MEMBER, "false");
        project(1L, "test");

        assertThrows(ForbiddenException.class, () -> controller().get(1L));

        verify(app.projectRepository, never()).findById(1L);
    }

    @Test
    void list_adminは全件を返す() {
        app.world.actingAsAdmin(1L);
        List<Project> all = new java.util.ArrayList<>(List.of(project(1L, "a"), project(2L, "b")));
        when(app.projectRepository.findAll()).thenReturn(all);

        assertEquals(2, controller().list(null, null).size());
    }

    @Test
    void list_非adminは所属プロジェクトだけに絞られる() {
        app.world.actingAsUser(5L);
        app.world.respond("GET", PROJECT_IDS, List.of(2L));
        List<Project> all = new java.util.ArrayList<>(List.of(project(1L, "a"), project(2L, "b")));
        when(app.projectRepository.findAll()).thenReturn(all);

        List<ProjectResponse> result = controller().list(null, null);

        assertEquals(List.of(2L), result.stream().map(ProjectResponse::id).toList());
    }

    @Test
    void list_所属がなければ空() {
        app.world.actingAsUser(5L);
        app.world.respond("GET", PROJECT_IDS, List.of());
        List<Project> all = new java.util.ArrayList<>(List.of(project(1L, "a")));
        when(app.projectRepository.findAll()).thenReturn(all);

        assertEquals(List.of(), controller().list(null, null));
    }

    @Test
    void delete_admin権限があれば削除できる() {
        app.world.actingAsAdmin(1L);
        when(app.projectRepository.existsById(1L)).thenReturn(true);

        ResponseEntity<Void> response = controller().delete(1L);

        assertEquals(204, response.getStatusCode().value());
        verify(app.projectRepository).deleteById(1L);
    }

    @Test
    void delete_admin権限がなければForbidden() {
        asNonAdmin();

        assertThrows(ForbiddenException.class, () -> controller().delete(1L));

        verify(app.projectRepository, never()).deleteById(any());
    }

    @Test
    void update_admin権限があれば更新できる() {
        app.world.actingAsAdmin(1L);
        project(1L, "test");

        ProjectResponse response = controller().update(1L, new ProjectUpdateRequest("新しい名前"));

        assertEquals("新しい名前", response.name());
    }

    @Test
    void update_存在しなければNotFound() {
        app.world.actingAsAdmin(1L);
        when(app.projectRepository.findById(9L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> controller().update(9L, new ProjectUpdateRequest("x")));
    }

    @Test
    void updateMasterEnvironment_とupdateGithubRepository_はadminが更新できる() {
        app.world.actingAsAdmin(1L);
        Project project = project(1L, "test");

        ProjectResponse master = controller().updateMasterEnvironment(1L,
                new com.letsblog.project.dto.UpdateMasterEnvironmentRequest("test"));
        ProjectResponse repo = controller().updateGithubRepository(1L,
                new com.letsblog.project.dto.UpdateProjectGithubRepositoryRequest("owner/repo"));

        assertEquals("test", master.masterEnvironment());
        assertEquals("owner/repo", repo.githubRepository());
        assertEquals("owner/repo", project.getGithubRepository());
    }

    @Test
    void updateMasterEnvironment_とupdateGithubRepository_はadmin以外は拒否する() {
        asNonAdmin();
        project(1L, "test");

        assertThrows(ForbiddenException.class, () -> controller().updateMasterEnvironment(1L,
                new com.letsblog.project.dto.UpdateMasterEnvironmentRequest("test")));
        assertThrows(ForbiddenException.class, () -> controller().updateGithubRepository(1L,
                new com.letsblog.project.dto.UpdateProjectGithubRepositoryRequest("owner/repo")));

        verify(app.projectRepository, never()).save(any());
    }

    @Test
    void bindEnvironment_admin権限があれば紐付できる() {
        app.world.actingAsAdmin(1L);
        Project project = project(1L, "test");
        bindableSite(10L);

        ProjectResponse response = controller().bindEnvironment(1L, new ProjectEnvironmentBindRequest("local", 10L));

        assertEquals(10L, project.getLocalSiteId());
        assertEquals("サイト10", response.localSite().name());
    }

    @Test
    void bindEnvironment_admin権限がなければForbiddenで紐付けない() {
        asNonAdmin();
        Project project = project(1L, "test");
        bindableSite(10L);

        assertThrows(ForbiddenException.class,
                () -> controller().bindEnvironment(1L, new ProjectEnvironmentBindRequest("local", 10L)));

        assertNull(project.getLocalSiteId());
    }

    // ---- issue #1574: 本番サイトを変えたら旧サイトの X 接続を消す ----

    @Test
    void bindEnvironment_本番を別のサイトへ変えたら旧サイトの接続を消す() {
        app.world.actingAsAdmin(1L);
        Project project = project(1L, "test");
        project.setProductionSiteId(5L);
        app.world.addSite(5L, "旧本番");
        bindableSite(10L);
        app.world.letsblogSns("config-clear", null, "{}");

        controller().bindEnvironment(1L, new ProjectEnvironmentBindRequest("production", 10L));

        assertEquals(10L, project.getProductionSiteId());
        assertEquals(1, app.world.snsRequests("config-clear").size(), "旧サイトの SNS 設定を消す");
    }

    @Test
    void bindEnvironment_本番が未設定だったときは何も消さない() {
        app.world.actingAsAdmin(1L);
        project(1L, "test");
        bindableSite(10L);

        controller().bindEnvironment(1L, new ProjectEnvironmentBindRequest("production", 10L));

        assertTrue(app.world.snsRequests("config-clear").isEmpty());
    }

    @Test
    void bindEnvironment_本番以外の環境ではSNSの後始末をしない() {
        app.world.actingAsAdmin(1L);
        Project project = project(1L, "test");
        project.setProductionSiteId(5L);
        app.world.addSite(5L, "本番");
        bindableSite(10L);

        controller().bindEnvironment(1L, new ProjectEnvironmentBindRequest("test", 10L));

        assertTrue(app.world.snsRequests("config-clear").isEmpty());
    }

    @Test
    void bindEnvironment_紐付けに失敗したら後始末をしない() {
        app.world.actingAsAdmin(1L);
        Project project = project(1L, "test");
        project.setProductionSiteId(5L);
        app.world.addSite(5L, "旧本番");
        bindableSite(10L);
        Project other = new Project();
        other.setId(2L);
        other.setName("重複");
        when(app.projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(10L, 10L, 10L))
                .thenReturn(Optional.of(other));

        assertThrows(IllegalArgumentException.class,
                () -> controller().bindEnvironment(1L, new ProjectEnvironmentBindRequest("production", 10L)));

        assertTrue(app.world.snsRequests("config-clear").isEmpty());
        assertEquals(5L, project.getProductionSiteId());
    }

    @Test
    void unbindEnvironment_本番を外したら旧サイトの接続を消す() {
        app.world.actingAsAdmin(1L);
        Project project = project(1L, "test");
        project.setProductionSiteId(5L);
        app.world.addSite(5L, "旧本番");
        app.world.letsblogSns("config-clear", null, "{}");

        controller().unbindEnvironment(1L, "production");

        assertNull(project.getProductionSiteId());
        assertEquals(1, app.world.snsRequests("config-clear").size());
    }

    @Test
    void unbindEnvironment_本番以外ではSNSの後始末をしない() {
        app.world.actingAsAdmin(1L);
        Project project = project(1L, "test");
        project.setLocalSiteId(10L);

        controller().unbindEnvironment(1L, "local");

        assertNull(project.getLocalSiteId());
        assertTrue(app.world.snsRequests("config-clear").isEmpty());
    }

    @Test
    void unbindEnvironment_admin権限がなければForbidden() {
        asNonAdmin();
        Project project = project(1L, "test");
        project.setLocalSiteId(10L);

        assertThrows(ForbiddenException.class, () -> controller().unbindEnvironment(1L, "local"));

        assertEquals(10L, project.getLocalSiteId());
    }

    // ---- 環境間の同期 ----

    @Test
    void syncEnvironment_admin権限があれば同期を実行する() {
        app.world.actingAsAdmin(1L);
        Project project = project(1L, "test");
        project.setTestSiteId(10L);
        project.setLocalSiteId(20L);
        app.world.addManagedSite(10L, "from-slug", "wp_from");
        app.world.addManagedSite(20L, "to-slug", "wp_to");
        app.world.http.on("POST", "/sync", FakeHttpServer.Response.empty(200));

        ResponseEntity<Void> response = controller().syncEnvironment(1L, new SyncEnvironmentRequest("test", "local", List.of("media")));

        assertEquals(204, response.getStatusCode().value());
        JsonNode body = app.world.bodyOf(app.world.http.requests("POST", "/sync").get(0));
        assertEquals("from-slug", body.get("fromSlug").asText());
        assertEquals("to-slug", body.get("toSlug").asText());
    }

    @Test
    void syncEnvironment_admin権限がなければForbiddenで同期しない() {
        asNonAdmin();

        assertThrows(ForbiddenException.class,
                () -> controller().syncEnvironment(1L, new SyncEnvironmentRequest("test", "local", List.of("db"))));

        assertTrue(app.world.http.requests("POST", "/sync").isEmpty());
    }

    // ---- issue #1697: 環境間同期の非同期受理 ----

    @Test
    void syncEnvironmentJob_adminなら202とジョブIDを返し同期は待たない() {
        app.world.actingAsAdmin(1L).signedInAs("sub-1");
        app.world.jobCreated(21L, "environment_sync");
        SyncEnvironmentRequest request = new SyncEnvironmentRequest("test", "local", List.of("db"));

        ResponseEntity<GenerationJobResponse> response = controller().syncEnvironmentJob(1L, request);

        assertEquals(202, response.getStatusCode().value());
        assertEquals(21L, response.getBody().id());
        assertEquals("environment_sync", response.getBody().type());
        assertEquals("running", response.getBody().status());
        assertEquals("2026-10-09T01:02:03Z", String.valueOf(response.getBody().createdAt()));
        assertEquals(1, app.jobs.size(), "同期は実行枠へ依頼されただけ");
        assertTrue(app.world.http.requests("POST", "/sync").isEmpty(), "同期は待たない");
    }

    @Test
    void syncEnvironmentJob_admin権限がなければForbiddenでジョブを作らない() {
        asNonAdmin();

        assertThrows(ForbiddenException.class, () -> controller().syncEnvironmentJob(1L,
                new SyncEnvironmentRequest("test", "local", List.of("db"))));

        assertTrue(app.world.http.requests("POST", "/api/internal/ai/generation-jobs").isEmpty());
        assertTrue(app.jobs.isEmpty());
    }
}
