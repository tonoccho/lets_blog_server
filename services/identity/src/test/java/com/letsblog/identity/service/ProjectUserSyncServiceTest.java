package com.letsblog.identity.service;

import com.letsblog.identity.client.ProjectServiceClient;
import com.letsblog.identity.client.PublishingServiceException;
import com.letsblog.identity.domain.ProjectUser;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.domain.UserSiteAuthor;
import com.letsblog.identity.dto.ProjectUserSyncSiteResult;
import com.letsblog.identity.repository.ProjectUserRepository;
import com.letsblog.identity.repository.UserRepository;
import com.letsblog.identity.repository.UserSiteAuthorRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Optional;
import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.identity.testsupport.FakeHttpServer;
import com.letsblog.identity.testsupport.IdentityWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #1242: メンバー個別のユーザー情報再同期({@code syncUserProfileToProjectSites})。
 *
 * <p>{@code addUserToProject}/{@code updateUserProjectRole}と異なりこの経路は
 * {@code @Transactional}を持たない(要件3: 一部の環境が失敗しても成功した環境の結果を保持する)。
 * サイトごとに独立して結果を積み上げ、監査ログには成功/失敗の一覧を渡すことを検証する。
 */
class ProjectUserSyncServiceTest {

    private IdentityWorld world;
    private ProjectUserRepository projectUserRepository;
    private UserRepository userRepository;
    private UserSiteAuthorRepository userSiteAuthorRepository;
    private FakeHttpServer bridges;

    @BeforeEach
    void setUp() {
        world = new IdentityWorld();
        projectUserRepository = world.projectUserRepository;
        userRepository = world.userRepository;
        userSiteAuthorRepository = world.userSiteAuthorRepository;
        bridges = world.bridges;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private ProjectUserSyncService service() {
        return world.projectUserSyncService;
    }

    // ---- project-service / publishing-service(内部ブリッジ)のサーバー側フェイク ----

    private void stubProject(ProjectServiceClient.ProjectBridge project) {
        bridges.json("GET", "/api/internal/project/projects/" + project.id(), "{\"id\":" + project.id()
                + ",\"name\":\"" + project.name() + "\",\"slug\":\"" + project.slug() + "\""
                + ",\"masterEnvironment\":\"" + project.masterEnvironment() + "\""
                + ",\"localSiteId\":" + project.localSiteId() + ",\"testSiteId\":" + project.testSiteId()
                + ",\"productionSiteId\":" + project.productionSiteId() + ",\"githubRepository\":null}");
    }

    private void stubSite(ProjectServiceClient.SiteBridge site) {
        bridges.json("GET", "/api/internal/project/sites/" + site.id(), "{\"id\":" + site.id()
                + ",\"siteKey\":\"" + site.siteKey() + "\",\"name\":\"" + site.name()
                + "\",\"baseUrl\":\"" + site.baseUrl() + "\"}");
    }

    private String provisionPath(String siteKey) {
        return "/api/internal/publishing/sites/" + siteKey + "/authors";
    }

    private void stubProvision(String siteKey, String cmsAuthorId) {
        bridges.json("POST", provisionPath(siteKey), "{\"cmsAuthorId\":\"" + cmsAuthorId + "\"}");
    }

    private void stubProvisionFailure(String siteKey, String reason) {
        bridges.status("POST", provisionPath(siteKey), 502, reason);
    }

    private List<FakeHttpServer.Request> provisions(String siteKey) {
        return bridges.requests("POST", provisionPath(siteKey));
    }

    private List<AuditLogMessage> syncAudits() {
        return world.auditMessages().stream().filter(m -> "PROJECT_USER_SYNCED".equals(m.action())).toList();
    }

    private User buildUser() {
        User user = new User();
        user.setId(42L);
        user.setEmail("member@example.com");
        user.setFirstName("Taro");
        user.setLastName("Yamada");
        user.setDisplayName("Taro Yamada");
        user.setWebsiteUrl("https://example.com/taro");
        user.setBio("bio");
        user.setLocale("ja");
        return user;
    }

    private ProjectServiceClient.ProjectBridge buildProject() {
        return new ProjectServiceClient.ProjectBridge(1L, "p", "p-slug", "test", 10L, 20L, null, null);
    }

    private ProjectServiceClient.SiteBridge site(long id, String key, String name) {
        ProjectServiceClient.SiteBridge site =
                new ProjectServiceClient.SiteBridge(id, key, name, "https://" + key + ".example.com");
        stubSite(site);
        return site;
    }

    @Test
    void 対象がプロジェクトメンバーでなければ例外を投げ何も同期しない() {
        when(projectUserRepository.findByProjectIdAndUserId(1L, 42L)).thenReturn(Optional.empty());

        assertThrows(ProjectUserNotFoundException.class, () -> service().syncUserProfileToProjectSites(1L, 42L));

        assertTrue(bridges.requests("POST", ".*").isEmpty());
        assertTrue(syncAudits().isEmpty());
    }

    @Test
    void 全サイトへの同期に成功すると全件successで監査ログに記録する() {
        ProjectUser projectUser = new ProjectUser(1L, 42L, "author");
        when(projectUserRepository.findByProjectIdAndUserId(1L, 42L)).thenReturn(Optional.of(projectUser));
        stubProject(buildProject());
        when(userRepository.findById(42L)).thenReturn(Optional.of(buildUser()));
        site(10L, "local-key", "ローカル");
        site(20L, "test-key", "テスト");
        stubProvision("local-key", "101");
        stubProvision("test-key", "202");
        when(userSiteAuthorRepository.findByUserIdAndSiteId(anyLong(), anyLong())).thenReturn(Optional.empty());

        List<ProjectUserSyncSiteResult> results = service().syncUserProfileToProjectSites(1L, 42L);

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(ProjectUserSyncSiteResult::success));

        String body = provisions("local-key").get(0).bodyText();
        assertTrue(body.contains("\"wpRole\":\"author\""), body);
        assertTrue(body.contains("\"email\":\"member@example.com\""), body);
        assertEquals(1, provisions("test-key").size());

        List<AuditLogMessage> audits = syncAudits();
        assertEquals(1, audits.size());
        assertEquals(1L, audits.get(0).resourceId());
        assertTrue(audits.get(0).changes().contains("\"userId\":42"), audits.get(0).changes());
        assertTrue(audits.get(0).changes().contains("local-key") && audits.get(0).changes().contains("test-key"),
                audits.get(0).changes());
    }

    @Test
    void 一部のサイトが失敗しても他方の結果は保持され両方が監査ログへ渡る() {
        ProjectUser projectUser = new ProjectUser(1L, 42L, "editor");
        when(projectUserRepository.findByProjectIdAndUserId(1L, 42L)).thenReturn(Optional.of(projectUser));
        stubProject(buildProject());
        when(userRepository.findById(42L)).thenReturn(Optional.of(buildUser()));
        site(10L, "ok-key", "OK");
        site(20L, "ng-key", "NG");
        stubProvision("ok-key", "101");
        stubProvisionFailure("ng-key", "接続に失敗しました");
        when(userSiteAuthorRepository.findByUserIdAndSiteId(anyLong(), anyLong())).thenReturn(Optional.empty());

        List<ProjectUserSyncSiteResult> results = service().syncUserProfileToProjectSites(1L, 42L);

        assertEquals(2, results.size());
        ProjectUserSyncSiteResult okResult = results.stream().filter(r -> r.siteId().equals(10L)).findFirst().orElseThrow();
        ProjectUserSyncSiteResult ngResult = results.stream().filter(r -> r.siteId().equals(20L)).findFirst().orElseThrow();
        assertTrue(okResult.success());
        assertFalse(ngResult.success());
        assertTrue(ngResult.errorMessage().contains("接続に失敗しました"), ngResult.errorMessage());

        // 失敗したサイト分のuser_site_authors書き込みは行われない(保存できるcmsAuthorIdが無いため)。
        verify(userSiteAuthorRepository, times(1)).save(any());
        List<AuditLogMessage> audits = syncAudits();
        assertEquals(1, audits.size());
        assertTrue(audits.get(0).changes().contains("ng-key"), audits.get(0).changes());
        assertTrue(audits.get(0).changes().contains("接続に失敗しました"), audits.get(0).changes());
    }

    // ------------------------------------------------------------------
    // issue #1069: メンバー追加時のWordPressユーザー作成は、サイトが紐付いている場合にだけ起きる。
    // ------------------------------------------------------------------

    @Test
    void サイト紐付け済みのプロジェクトへメンバーを追加すると各サイトへ著者を作成しuser_site_authorsへ保存する() {
        stubProject(buildProject());
        when(userRepository.findById(42L)).thenReturn(Optional.of(buildUser()));
        site(10L, "local-key", "ローカル");
        site(20L, "test-key", "テスト");
        stubProvision("local-key", "101");
        stubProvision("test-key", "202");
        when(userSiteAuthorRepository.findByUserIdAndSiteId(anyLong(), anyLong())).thenReturn(Optional.empty());

        service().addUserToProject(1L, 42L, "author");

        String localBody = provisions("local-key").get(0).bodyText();
        assertTrue(localBody.contains("\"email\":\"member@example.com\""), localBody);
        assertTrue(localBody.contains("\"wpRole\":\"author\""), localBody);
        assertEquals(1, provisions("test-key").size());

        ArgumentCaptor<UserSiteAuthor> mappingCaptor = ArgumentCaptor.forClass(UserSiteAuthor.class);
        verify(userSiteAuthorRepository, times(2)).save(mappingCaptor.capture());
        List<UserSiteAuthor> saved = mappingCaptor.getAllValues();
        assertTrue(saved.stream().allMatch(m -> m.getUserId().equals(42L)));
        assertEquals("101", saved.stream().filter(m -> m.getSiteId().equals(10L)).findFirst().orElseThrow().getCmsAuthorId());
        assertEquals("202", saved.stream().filter(m -> m.getSiteId().equals(20L)).findFirst().orElseThrow().getCmsAuthorId());

        ArgumentCaptor<ProjectUser> memberCaptor = ArgumentCaptor.forClass(ProjectUser.class);
        verify(projectUserRepository).save(memberCaptor.capture());
        assertEquals(1L, memberCaptor.getValue().getProjectId());
        assertEquals(42L, memberCaptor.getValue().getUserId());
        assertEquals("PROJECT_USER_ADDED", world.auditMessages().get(0).action());
    }

    @Test
    void サイトが1つも紐付いていないプロジェクトへメンバーを追加してもWordPress著者もuser_site_authorsも作られない() {
        stubProject(new ProjectServiceClient.ProjectBridge(1L, "p", "p-slug", "test", null, null, null, null));
        when(userRepository.findById(42L)).thenReturn(Optional.of(buildUser()));

        service().addUserToProject(1L, 42L, "author");

        // project_users の行だけが残る。後からサイトを紐付けても遡って作られない(補填は #1324)。
        verify(projectUserRepository).save(any(ProjectUser.class));
        assertTrue(bridges.requests("GET", "/api/internal/project/sites/.*").isEmpty());
        assertTrue(bridges.requests("POST", ".*").isEmpty());
        verify(userSiteAuthorRepository, never()).save(any());
    }

    @Test
    void メンバー追加の対象ユーザーが存在しなければ例外() {
        stubProject(buildProject());
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> service().addUserToProject(1L, 42L, "author"));

        verify(projectUserRepository, never()).save(any(ProjectUser.class));
    }

    @Test
    void メンバー追加の対象プロジェクトが存在しなければ例外() {
        // project-serviceが404を返す(ルート未登録)。
        assertThrows(ProjectNotFoundException.class, () -> service().addUserToProject(1L, 42L, "author"));

        verify(projectUserRepository, never()).save(any(ProjectUser.class));
    }

    @Test
    void 紐付いたサイトがproject_serviceに存在しなければそのサイトは飛ばす() {
        stubProject(buildProject());
        when(userRepository.findById(42L)).thenReturn(Optional.of(buildUser()));
        site(10L, "local-key", "ローカル");
        stubProvision("local-key", "101");
        when(userSiteAuthorRepository.findByUserIdAndSiteId(anyLong(), anyLong())).thenReturn(Optional.empty());

        service().addUserToProject(1L, 42L, "author");

        // site 20 は404(登録なし)。ローカルだけが同期される。
        assertEquals(1, provisions("local-key").size());
        verify(userSiteAuthorRepository, times(1)).save(any());
    }

    // ------------------------------------------------------------------
    // issue #1302: 追加/ロール変更の失敗メッセージに、失敗した環境名と元の理由の両方を含める。
    // ------------------------------------------------------------------

    private void stubTwoSitesSecondFails() {
        stubProject(buildProject());
        when(userRepository.findById(42L)).thenReturn(Optional.of(buildUser()));
        site(10L, "local-key", "ローカル");
        site(20L, "test-key", "テスト環境");
        stubProvision("local-key", "101");
        when(userSiteAuthorRepository.findByUserIdAndSiteId(anyLong(), anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void メンバー追加で環境の著者登録が失敗すると例外メッセージに環境名と元の理由が含まれ何も保存されない() {
        stubTwoSitesSecondFails();
        stubProvisionFailure("test-key", "connect timed out");

        PublishingServiceException thrown = assertThrows(PublishingServiceException.class,
                () -> service().addUserToProject(1L, 42L, "author"));

        assertTrue(thrown.getMessage().contains("テスト環境"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("connect timed out"), thrown.getMessage());
        // 元の例外(RestClientExceptionを包んだもの)は原因として残る。
        assertTrue(thrown.getCause() instanceof PublishingServiceException);
        assertTrue(thrown.getCause().getCause() instanceof org.springframework.web.client.RestClientException);
        verify(projectUserRepository, never()).save(any(ProjectUser.class));
    }

    @Test
    void ロール変更で環境の著者登録が失敗すると例外メッセージに環境名と元の理由が含まれロールは変わらない() {
        ProjectUser existing = new ProjectUser(1L, 42L, "author");
        when(projectUserRepository.findByProjectIdAndUserId(1L, 42L)).thenReturn(Optional.of(existing));
        stubTwoSitesSecondFails();
        stubProvisionFailure("test-key", "connect timed out");

        PublishingServiceException thrown = assertThrows(PublishingServiceException.class,
                () -> service().updateUserProjectRole(1L, 42L, "editor"));

        assertTrue(thrown.getMessage().contains("テスト環境"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("connect timed out"), thrown.getMessage());
        assertEquals("author", existing.getWpRole());
        verify(projectUserRepository, never()).save(any(ProjectUser.class));
    }

    @Test
    void ロール変更に成功するとロールを保存し監査ログを記録する() {
        ProjectUser existing = new ProjectUser(1L, 42L, "author");
        when(projectUserRepository.findByProjectIdAndUserId(1L, 42L)).thenReturn(Optional.of(existing));
        stubProject(buildProject());
        when(userRepository.findById(42L)).thenReturn(Optional.of(buildUser()));
        site(10L, "local-key", "ローカル");
        site(20L, "test-key", "テスト");
        stubProvision("local-key", "101");
        stubProvision("test-key", "202");
        when(userSiteAuthorRepository.findByUserIdAndSiteId(anyLong(), anyLong())).thenReturn(Optional.empty());

        service().updateUserProjectRole(1L, 42L, "editor");

        assertEquals("editor", existing.getWpRole());
        verify(projectUserRepository).save(existing);
        assertTrue(provisions("local-key").get(0).bodyText().contains("\"wpRole\":\"editor\""));
        assertEquals("PROJECT_USER_ROLE_UPDATED", world.auditMessages().get(0).action());
    }

    @Test
    void ロール変更の対象がメンバーでなければ例外() {
        when(projectUserRepository.findByProjectIdAndUserId(1L, 42L)).thenReturn(Optional.empty());

        assertThrows(ProjectUserNotFoundException.class, () -> service().updateUserProjectRole(1L, 42L, "editor"));
    }

    @Test
    void 環境同期結果のerrorMessageには環境名を重ねず元の理由だけを返す() {
        when(projectUserRepository.findByProjectIdAndUserId(1L, 42L))
                .thenReturn(Optional.of(new ProjectUser(1L, 42L, "author")));
        stubProject(buildProject());
        when(userRepository.findById(42L)).thenReturn(Optional.of(buildUser()));
        site(10L, "local-key", "ローカル");
        stubProvisionFailure("local-key", "接続に失敗しました");

        List<ProjectUserSyncSiteResult> results = service().syncUserProfileToProjectSites(1L, 42L);

        assertEquals(1, results.size());
        assertTrue(results.get(0).errorMessage().contains("接続に失敗しました"), results.get(0).errorMessage());
        assertFalse(results.get(0).errorMessage().contains("ローカル:"), results.get(0).errorMessage());
    }

    @Test
    void 環境同期の対象ユーザーが存在しなければ例外() {
        when(projectUserRepository.findByProjectIdAndUserId(1L, 42L))
                .thenReturn(Optional.of(new ProjectUser(1L, 42L, "author")));
        stubProject(buildProject());
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> service().syncUserProfileToProjectSites(1L, 42L));
    }

    // ------------------------------------------------------------------
    // メンバーの削除・照会
    // ------------------------------------------------------------------

    @Test
    void メンバーを外すとproject_usersから削除し監査ログを記録する() {
        service().removeUserFromProject(1L, 42L);

        verify(projectUserRepository).deleteByProjectIdAndUserId(1L, 42L);
        assertEquals("PROJECT_USER_REMOVED", world.auditMessages().get(0).action());
    }

    @Test
    void getProjectUsers_ユーザー情報を結合し_ユーザー行が無ければメールと表示名はnull() {
        when(projectUserRepository.findByProjectId(1L)).thenReturn(List.of(
                new ProjectUser(1L, 42L, "author"), new ProjectUser(1L, 43L, "editor")));
        when(userRepository.findById(42L)).thenReturn(Optional.of(buildUser()));
        when(userRepository.findById(43L)).thenReturn(Optional.empty());

        var result = service().getProjectUsers(1L);

        assertEquals(2, result.size());
        assertEquals("member@example.com", result.get(0).email());
        assertEquals("Taro Yamada", result.get(0).displayName());
        assertEquals(null, result.get(1).email());
        assertEquals(null, result.get(1).displayName());
        assertEquals("editor", result.get(1).wpRole());
    }

    @Test
    void listAllProjectUsers_全メンバーの要約を返す() {
        when(projectUserRepository.findAll()).thenReturn(List.of(new ProjectUser(1L, 42L, "author")));

        var result = service().listAllProjectUsers();

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).projectId());
        assertEquals(42L, result.get(0).userId());
    }

    @Test
    void projectIdsForUser_参加プロジェクトIDを返す() {
        when(projectUserRepository.findByUserId(42L)).thenReturn(List.of(
                new ProjectUser(1L, 42L, "author"), new ProjectUser(2L, 42L, "editor")));

        assertEquals(List.of(1L, 2L), service().projectIdsForUser(42L));
    }

    @Test
    void isProjectMember_参加していればtrueしていなければfalse() {
        when(projectUserRepository.findByProjectIdAndUserId(1L, 42L))
                .thenReturn(Optional.of(new ProjectUser(1L, 42L, "author")));
        when(projectUserRepository.findByProjectIdAndUserId(1L, 43L)).thenReturn(Optional.empty());

        assertTrue(service().isProjectMember(1L, 42L));
        assertFalse(service().isProjectMember(1L, 43L));
    }

    @Test
    void findCmsAuthorId_対応表があれば返し無ければempty() {
        when(userSiteAuthorRepository.findByUserIdAndSiteId(42L, 10L))
                .thenReturn(Optional.of(new UserSiteAuthor(42L, 10L, "101")));
        when(userSiteAuthorRepository.findByUserIdAndSiteId(42L, 20L)).thenReturn(Optional.empty());

        assertEquals(Optional.of("101"), service().findCmsAuthorId(42L, 10L));
        assertEquals(Optional.empty(), service().findCmsAuthorId(42L, 20L));
    }

    @Test
    void cacheAuthorMapping_新規なら作成し既存なら更新して保存する() {
        UserSiteAuthor existing = new UserSiteAuthor(42L, 20L, "old");
        when(userSiteAuthorRepository.findByUserIdAndSiteId(42L, 10L)).thenReturn(Optional.empty());
        when(userSiteAuthorRepository.findByUserIdAndSiteId(42L, 20L)).thenReturn(Optional.of(existing));

        service().cacheAuthorMapping(42L, 10L, "101");
        service().cacheAuthorMapping(42L, 20L, "new");

        assertEquals("new", existing.getCmsAuthorId());
        ArgumentCaptor<UserSiteAuthor> saved = ArgumentCaptor.forClass(UserSiteAuthor.class);
        verify(userSiteAuthorRepository, times(2)).save(saved.capture());
        assertEquals("101", saved.getAllValues().get(0).getCmsAuthorId());
    }

    @Test
    void cacheAuthorMapping_cmsAuthorIdがnullなら保存しない() {
        service().cacheAuthorMapping(42L, 10L, null);

        verify(userSiteAuthorRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // reconcileRolesForSite(#514)
    // ------------------------------------------------------------------

    @Test
    void reconcileRolesForSite_メンバーのロールで同期先サイトの著者を上書きし_ユーザー行が無いメンバーは飛ばす() {
        site(30L, "bound-key", "本番環境");
        stubProvision("bound-key", "901");
        when(projectUserRepository.findByProjectId(1L)).thenReturn(List.of(
                new ProjectUser(1L, 42L, "editor"), new ProjectUser(1L, 43L, "author")));
        when(userRepository.findById(42L)).thenReturn(Optional.of(buildUser()));
        when(userRepository.findById(43L)).thenReturn(Optional.empty());
        when(userSiteAuthorRepository.findByUserIdAndSiteId(anyLong(), anyLong())).thenReturn(Optional.empty());

        service().reconcileRolesForSite(1L, 30L);

        assertEquals(1, provisions("bound-key").size());
        assertTrue(provisions("bound-key").get(0).bodyText().contains("\"wpRole\":\"editor\""));
    }

    @Test
    void reconcileRolesForSite_サイトが存在しなければ例外() {
        assertThrows(SiteNotFoundException.class, () -> service().reconcileRolesForSite(1L, 30L));
    }

    // ------------------------------------------------------------------
    // issue #1324: サイト(環境)を後から紐付けたとき、その時点のメンバー全員のWordPressユーザーを補填する。
    // ------------------------------------------------------------------

    private static final ProjectServiceClient.SiteBridge BOUND_SITE =
            new ProjectServiceClient.SiteBridge(30L, "bound-key", "本番環境", "https://prod.example.com");

    private User userWithId(Long id, String email) {
        User user = buildUser();
        user.setId(id);
        user.setEmail(email);
        return user;
    }

    private ListAppender<ILoggingEvent> captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProjectUserSyncService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detach(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(ProjectUserSyncService.class)).detachAppender(appender);
    }

    @Test
    void サイト紐付け時の補填で既存メンバー全員の著者が紐付けたサイトに作られuser_site_authorsへ保存される() {
        stubSite(BOUND_SITE);
        when(projectUserRepository.findByProjectId(1L)).thenReturn(List.of(
                new ProjectUser(1L, 42L, "author"), new ProjectUser(1L, 43L, "editor")));
        when(userSiteAuthorRepository.findByUserIdAndSiteId(anyLong(), eq(30L))).thenReturn(Optional.empty());
        when(userRepository.findById(42L)).thenReturn(Optional.of(userWithId(42L, "a@example.com")));
        when(userRepository.findById(43L)).thenReturn(Optional.of(userWithId(43L, "b@example.com")));
        bridges.sequence("POST", provisionPath("bound-key"),
                FakeHttpServer.Response.ok("{\"cmsAuthorId\":\"901\"}"),
                FakeHttpServer.Response.ok("{\"cmsAuthorId\":\"902\"}"));

        service().backfillMembersToSite(1L, 30L);

        List<FakeHttpServer.Request> requests = provisions("bound-key");
        assertEquals(2, requests.size());
        assertTrue(requests.get(0).bodyText().contains("\"email\":\"a@example.com\""));
        assertTrue(requests.get(0).bodyText().contains("\"wpRole\":\"author\""));
        assertTrue(requests.get(1).bodyText().contains("\"email\":\"b@example.com\""));
        assertTrue(requests.get(1).bodyText().contains("\"wpRole\":\"editor\""));

        ArgumentCaptor<UserSiteAuthor> saved = ArgumentCaptor.forClass(UserSiteAuthor.class);
        verify(userSiteAuthorRepository, times(2)).save(saved.capture());
        assertTrue(saved.getAllValues().stream().allMatch(m -> m.getSiteId().equals(30L)));
        assertEquals("901", saved.getAllValues().get(0).getCmsAuthorId());
        assertEquals("902", saved.getAllValues().get(1).getCmsAuthorId());
    }

    @Test
    void すでにそのサイトの対応表があるメンバーは補填で重複して作られない() {
        stubSite(BOUND_SITE);
        stubProvision("bound-key", "902");
        when(projectUserRepository.findByProjectId(1L)).thenReturn(List.of(
                new ProjectUser(1L, 42L, "author"), new ProjectUser(1L, 43L, "author")));
        when(userSiteAuthorRepository.findByUserIdAndSiteId(42L, 30L))
                .thenReturn(Optional.of(new UserSiteAuthor(42L, 30L, "700")));
        when(userSiteAuthorRepository.findByUserIdAndSiteId(43L, 30L)).thenReturn(Optional.empty());
        when(userRepository.findById(43L)).thenReturn(Optional.of(userWithId(43L, "b@example.com")));

        service().backfillMembersToSite(1L, 30L);

        assertEquals(1, provisions("bound-key").size());
        verify(userRepository, never()).findById(42L);
        verify(userSiteAuthorRepository, times(1)).save(any(UserSiteAuthor.class));
    }

    @Test
    void 補填で一部のメンバーが失敗しても例外を投げず残りを処理し失敗したメンバーと理由をログに残す() {
        stubSite(BOUND_SITE);
        when(projectUserRepository.findByProjectId(1L)).thenReturn(List.of(
                new ProjectUser(1L, 42L, "author"), new ProjectUser(1L, 43L, "author")));
        when(userSiteAuthorRepository.findByUserIdAndSiteId(anyLong(), eq(30L))).thenReturn(Optional.empty());
        when(userRepository.findById(42L)).thenReturn(Optional.of(userWithId(42L, "a@example.com")));
        when(userRepository.findById(43L)).thenReturn(Optional.of(userWithId(43L, "b@example.com")));
        bridges.sequence("POST", provisionPath("bound-key"),
                FakeHttpServer.Response.json(502, "connect timed out"),
                FakeHttpServer.Response.ok("{\"cmsAuthorId\":\"902\"}"));

        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            service().backfillMembersToSite(1L, 30L);
        } finally {
            detach(logs);
        }

        ArgumentCaptor<UserSiteAuthor> saved = ArgumentCaptor.forClass(UserSiteAuthor.class);
        verify(userSiteAuthorRepository, times(1)).save(saved.capture());
        assertEquals(43L, saved.getValue().getUserId());
        List<String> errors = logs.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("42"), errors.get(0));
        assertTrue(errors.get(0).contains("connect timed out"), errors.get(0));
        assertTrue(errors.get(0).contains("本番環境"), errors.get(0));
    }

    @Test
    void 補填でユーザー自体が見つからないメンバーも失敗としてログに残し他のメンバーは処理する() {
        stubSite(BOUND_SITE);
        stubProvision("bound-key", "902");
        when(projectUserRepository.findByProjectId(1L)).thenReturn(List.of(
                new ProjectUser(1L, 42L, "author"), new ProjectUser(1L, 43L, "author")));
        when(userSiteAuthorRepository.findByUserIdAndSiteId(anyLong(), eq(30L))).thenReturn(Optional.empty());
        when(userRepository.findById(42L)).thenReturn(Optional.empty());
        when(userRepository.findById(43L)).thenReturn(Optional.of(userWithId(43L, "b@example.com")));

        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            service().backfillMembersToSite(1L, 30L);
        } finally {
            detach(logs);
        }

        assertEquals(1, provisions("bound-key").size());
        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN)
                && e.getFormattedMessage().contains("42")));
    }

    @Test
    void 補填の対象サイトが存在しなければ何も作らず例外も投げない() {
        // project-serviceが404を返す(ルート未登録)。
        service().backfillMembersToSite(1L, 30L);

        verify(projectUserRepository, never()).findByProjectId(anyLong());
        assertTrue(bridges.requests("POST", ".*").isEmpty());
    }

    @Test
    void 補填の対象プロジェクトにメンバーが居なければ何も作らない() {
        stubSite(BOUND_SITE);
        when(projectUserRepository.findByProjectId(1L)).thenReturn(List.of());

        service().backfillMembersToSite(1L, 30L);

        assertTrue(bridges.requests("POST", ".*").isEmpty());
        verify(userSiteAuthorRepository, never()).save(any());
    }
}
