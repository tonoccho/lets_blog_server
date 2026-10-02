package com.letsblog.identity.service;

import com.letsblog.identity.client.ProjectServiceClient;
import com.letsblog.identity.client.PublishingServiceClient;
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
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
@ExtendWith(MockitoExtension.class)
class ProjectUserSyncServiceTest {

    @Mock
    private ProjectServiceClient projectServiceClient;

    @Mock
    private ProjectUserRepository projectUserRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PublishingServiceClient publishingServiceClient;

    @Mock
    private UserSiteAuthorRepository userSiteAuthorRepository;

    @Mock
    private AuditLogService auditLogService;

    private ProjectUserSyncService service() {
        return new ProjectUserSyncService(
                projectServiceClient, projectUserRepository, userRepository,
                publishingServiceClient, userSiteAuthorRepository, auditLogService);
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

    @Test
    void 対象がプロジェクトメンバーでなければ例外を投げ何も同期しない() {
        when(projectUserRepository.findByProjectIdAndUserId(1L, 42L)).thenReturn(Optional.empty());

        assertThrows(ProjectUserNotFoundException.class, () -> service().syncUserProfileToProjectSites(1L, 42L));

        verify(publishingServiceClient, never()).provisionAuthor(any(), any());
        verify(auditLogService, never()).logProjectUserSyncAction(anyLong(), anyLong(), anyList());
    }

    @Test
    void 全サイトへの同期に成功すると全件successで監査ログに記録する() {
        ProjectUser projectUser = new ProjectUser(1L, 42L, "author");
        when(projectUserRepository.findByProjectIdAndUserId(1L, 42L)).thenReturn(Optional.of(projectUser));
        when(projectServiceClient.getProject(1L)).thenReturn(buildProject());
        when(userRepository.findById(42L)).thenReturn(Optional.of(buildUser()));

        ProjectServiceClient.SiteBridge localSite =
                new ProjectServiceClient.SiteBridge(10L, "local-key", "ローカル", "https://local.example.com");
        ProjectServiceClient.SiteBridge testSite =
                new ProjectServiceClient.SiteBridge(20L, "test-key", "テスト", "https://test.example.com");
        when(projectServiceClient.getSite(10L)).thenReturn(Optional.of(localSite));
        when(projectServiceClient.getSite(20L)).thenReturn(Optional.of(testSite));

        when(publishingServiceClient.provisionAuthor(eq("local-key"), any()))
                .thenReturn(new PublishingServiceClient.AuthorProvisioningResponse("101"));
        when(publishingServiceClient.provisionAuthor(eq("test-key"), any()))
                .thenReturn(new PublishingServiceClient.AuthorProvisioningResponse("202"));
        when(userSiteAuthorRepository.findByUserIdAndSiteId(anyLong(), anyLong())).thenReturn(Optional.empty());

        List<ProjectUserSyncSiteResult> results = service().syncUserProfileToProjectSites(1L, 42L);

        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(ProjectUserSyncSiteResult::success));

        ArgumentCaptor<PublishingServiceClient.AuthorProvisioningRequest> requestCaptor =
                ArgumentCaptor.forClass(PublishingServiceClient.AuthorProvisioningRequest.class);
        verify(publishingServiceClient).provisionAuthor(eq("local-key"), requestCaptor.capture());
        assertEquals("author", requestCaptor.getValue().wpRole());
        assertEquals("member@example.com", requestCaptor.getValue().email());

        verify(auditLogService).logProjectUserSyncAction(eq(1L), eq(42L), eq(results));
    }

    @Test
    void 一部のサイトが失敗しても他方の結果は保持され両方が監査ログへ渡る() {
        ProjectUser projectUser = new ProjectUser(1L, 42L, "editor");
        when(projectUserRepository.findByProjectIdAndUserId(1L, 42L)).thenReturn(Optional.of(projectUser));
        when(projectServiceClient.getProject(1L)).thenReturn(buildProject());
        when(userRepository.findById(42L)).thenReturn(Optional.of(buildUser()));

        ProjectServiceClient.SiteBridge okSite =
                new ProjectServiceClient.SiteBridge(10L, "ok-key", "OK", "https://ok.example.com");
        ProjectServiceClient.SiteBridge ngSite =
                new ProjectServiceClient.SiteBridge(20L, "ng-key", "NG", "https://ng.example.com");
        when(projectServiceClient.getSite(10L)).thenReturn(Optional.of(okSite));
        when(projectServiceClient.getSite(20L)).thenReturn(Optional.of(ngSite));

        when(publishingServiceClient.provisionAuthor(eq("ok-key"), any()))
                .thenReturn(new PublishingServiceClient.AuthorProvisioningResponse("101"));
        when(publishingServiceClient.provisionAuthor(eq("ng-key"), any()))
                .thenThrow(new PublishingServiceException("接続に失敗しました", null));
        when(userSiteAuthorRepository.findByUserIdAndSiteId(anyLong(), anyLong())).thenReturn(Optional.empty());

        List<ProjectUserSyncSiteResult> results = service().syncUserProfileToProjectSites(1L, 42L);

        assertEquals(2, results.size());
        ProjectUserSyncSiteResult okResult = results.stream().filter(r -> r.siteId().equals(10L)).findFirst().orElseThrow();
        ProjectUserSyncSiteResult ngResult = results.stream().filter(r -> r.siteId().equals(20L)).findFirst().orElseThrow();
        assertTrue(okResult.success());
        assertFalse(ngResult.success());
        assertEquals("接続に失敗しました", ngResult.errorMessage());

        // 失敗したサイト分のuser_site_authors書き込みは行われない(保存できるcmsAuthorIdが無いため)。
        verify(userSiteAuthorRepository, times(1)).save(any());
        verify(auditLogService).logProjectUserSyncAction(eq(1L), eq(42L), eq(results));
    }

    // ------------------------------------------------------------------
    // issue #1069: メンバー追加時のWordPressユーザー作成は、サイトが紐付いている場合にだけ起きる。
    // ------------------------------------------------------------------

    @Test
    void サイト紐付け済みのプロジェクトへメンバーを追加すると各サイトへ著者を作成しuser_site_authorsへ保存する() {
        when(projectServiceClient.getProject(1L)).thenReturn(buildProject());
        when(userRepository.findById(42L)).thenReturn(Optional.of(buildUser()));
        when(projectServiceClient.getSite(10L)).thenReturn(Optional.of(
                new ProjectServiceClient.SiteBridge(10L, "local-key", "ローカル", "https://local.example.com")));
        when(projectServiceClient.getSite(20L)).thenReturn(Optional.of(
                new ProjectServiceClient.SiteBridge(20L, "test-key", "テスト", "https://test.example.com")));
        when(publishingServiceClient.provisionAuthor(eq("local-key"), any()))
                .thenReturn(new PublishingServiceClient.AuthorProvisioningResponse("101"));
        when(publishingServiceClient.provisionAuthor(eq("test-key"), any()))
                .thenReturn(new PublishingServiceClient.AuthorProvisioningResponse("202"));
        when(userSiteAuthorRepository.findByUserIdAndSiteId(anyLong(), anyLong())).thenReturn(Optional.empty());

        service().addUserToProject(1L, 42L, "author");

        ArgumentCaptor<PublishingServiceClient.AuthorProvisioningRequest> requestCaptor =
                ArgumentCaptor.forClass(PublishingServiceClient.AuthorProvisioningRequest.class);
        verify(publishingServiceClient).provisionAuthor(eq("local-key"), requestCaptor.capture());
        verify(publishingServiceClient).provisionAuthor(eq("test-key"), any());
        assertEquals("member@example.com", requestCaptor.getValue().email());
        assertEquals("author", requestCaptor.getValue().wpRole());

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
    }

    @Test
    void サイトが1つも紐付いていないプロジェクトへメンバーを追加してもWordPress著者もuser_site_authorsも作られない() {
        when(projectServiceClient.getProject(1L)).thenReturn(
                new ProjectServiceClient.ProjectBridge(1L, "p", "p-slug", "test", null, null, null, null));
        when(userRepository.findById(42L)).thenReturn(Optional.of(buildUser()));

        service().addUserToProject(1L, 42L, "author");

        // project_users の行だけが残る。後からサイトを紐付けても遡って作られない(補填は #1324)。
        verify(projectUserRepository).save(any(ProjectUser.class));
        verify(projectServiceClient, never()).getSite(anyLong());
        verify(publishingServiceClient, never()).provisionAuthor(any(), any());
        verify(userSiteAuthorRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // issue #1302: 追加/ロール変更の失敗メッセージに、失敗した環境名と元の理由の両方を含める。
    // ------------------------------------------------------------------

    private void stubTwoSitesSecondFails() {
        when(projectServiceClient.getProject(1L)).thenReturn(buildProject());
        when(userRepository.findById(42L)).thenReturn(Optional.of(buildUser()));
        when(projectServiceClient.getSite(10L)).thenReturn(Optional.of(
                new ProjectServiceClient.SiteBridge(10L, "local-key", "ローカル", "https://local.example.com")));
        when(projectServiceClient.getSite(20L)).thenReturn(Optional.of(
                new ProjectServiceClient.SiteBridge(20L, "test-key", "テスト環境", "https://test.example.com")));
        when(publishingServiceClient.provisionAuthor(eq("local-key"), any()))
                .thenReturn(new PublishingServiceClient.AuthorProvisioningResponse("101"));
        when(userSiteAuthorRepository.findByUserIdAndSiteId(anyLong(), anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void メンバー追加で環境の著者登録が失敗すると例外メッセージに環境名と元の理由が含まれ何も保存されない() {
        stubTwoSitesSecondFails();
        PublishingServiceException original =
                new PublishingServiceException("著者プロビジョニング呼び出しに失敗しました: connect timed out", null);
        when(publishingServiceClient.provisionAuthor(eq("test-key"), any())).thenThrow(original);

        PublishingServiceException thrown = assertThrows(PublishingServiceException.class,
                () -> service().addUserToProject(1L, 42L, "author"));

        assertTrue(thrown.getMessage().contains("テスト環境"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("connect timed out"), thrown.getMessage());
        assertSame(original, thrown.getCause());
        verify(projectUserRepository, never()).save(any(ProjectUser.class));
    }

    @Test
    void ロール変更で環境の著者登録が失敗すると例外メッセージに環境名と元の理由が含まれロールは変わらない() {
        ProjectUser existing = new ProjectUser(1L, 42L, "author");
        when(projectUserRepository.findByProjectIdAndUserId(1L, 42L)).thenReturn(Optional.of(existing));
        stubTwoSitesSecondFails();
        when(publishingServiceClient.provisionAuthor(eq("test-key"), any()))
                .thenThrow(new PublishingServiceException("著者プロビジョニング呼び出しに失敗しました: connect timed out", null));

        PublishingServiceException thrown = assertThrows(PublishingServiceException.class,
                () -> service().updateUserProjectRole(1L, 42L, "editor"));

        assertTrue(thrown.getMessage().contains("テスト環境"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("connect timed out"), thrown.getMessage());
        verify(projectUserRepository, never()).save(any(ProjectUser.class));
    }

    @Test
    void 環境同期結果のerrorMessageには環境名を重ねず元の理由だけを返す() {
        when(projectUserRepository.findByProjectIdAndUserId(1L, 42L))
                .thenReturn(Optional.of(new ProjectUser(1L, 42L, "author")));
        when(projectServiceClient.getProject(1L)).thenReturn(buildProject());
        when(userRepository.findById(42L)).thenReturn(Optional.of(buildUser()));
        when(projectServiceClient.getSite(10L)).thenReturn(Optional.of(
                new ProjectServiceClient.SiteBridge(10L, "local-key", "ローカル", "https://local.example.com")));
        when(projectServiceClient.getSite(20L)).thenReturn(Optional.empty());
        when(publishingServiceClient.provisionAuthor(eq("local-key"), any()))
                .thenThrow(new PublishingServiceException("接続に失敗しました", null));

        List<ProjectUserSyncSiteResult> results = service().syncUserProfileToProjectSites(1L, 42L);

        assertEquals(1, results.size());
        assertEquals("接続に失敗しました", results.get(0).errorMessage());
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
        when(projectServiceClient.getSite(30L)).thenReturn(Optional.of(BOUND_SITE));
        when(projectUserRepository.findByProjectId(1L)).thenReturn(List.of(
                new ProjectUser(1L, 42L, "author"), new ProjectUser(1L, 43L, "editor")));
        when(userSiteAuthorRepository.findByUserIdAndSiteId(anyLong(), eq(30L))).thenReturn(Optional.empty());
        when(userRepository.findById(42L)).thenReturn(Optional.of(userWithId(42L, "a@example.com")));
        when(userRepository.findById(43L)).thenReturn(Optional.of(userWithId(43L, "b@example.com")));
        when(publishingServiceClient.provisionAuthor(eq("bound-key"), any()))
                .thenReturn(new PublishingServiceClient.AuthorProvisioningResponse("901"))
                .thenReturn(new PublishingServiceClient.AuthorProvisioningResponse("902"));

        service().backfillMembersToSite(1L, 30L);

        ArgumentCaptor<PublishingServiceClient.AuthorProvisioningRequest> requests =
                ArgumentCaptor.forClass(PublishingServiceClient.AuthorProvisioningRequest.class);
        verify(publishingServiceClient, times(2)).provisionAuthor(eq("bound-key"), requests.capture());
        assertEquals("a@example.com", requests.getAllValues().get(0).email());
        assertEquals("author", requests.getAllValues().get(0).wpRole());
        assertEquals("b@example.com", requests.getAllValues().get(1).email());
        assertEquals("editor", requests.getAllValues().get(1).wpRole());

        ArgumentCaptor<UserSiteAuthor> saved = ArgumentCaptor.forClass(UserSiteAuthor.class);
        verify(userSiteAuthorRepository, times(2)).save(saved.capture());
        assertTrue(saved.getAllValues().stream().allMatch(m -> m.getSiteId().equals(30L)));
        assertEquals("901", saved.getAllValues().get(0).getCmsAuthorId());
        assertEquals("902", saved.getAllValues().get(1).getCmsAuthorId());
    }

    @Test
    void すでにそのサイトの対応表があるメンバーは補填で重複して作られない() {
        when(projectServiceClient.getSite(30L)).thenReturn(Optional.of(BOUND_SITE));
        when(projectUserRepository.findByProjectId(1L)).thenReturn(List.of(
                new ProjectUser(1L, 42L, "author"), new ProjectUser(1L, 43L, "author")));
        when(userSiteAuthorRepository.findByUserIdAndSiteId(42L, 30L))
                .thenReturn(Optional.of(new UserSiteAuthor(42L, 30L, "700")));
        when(userSiteAuthorRepository.findByUserIdAndSiteId(43L, 30L)).thenReturn(Optional.empty());
        when(userRepository.findById(43L)).thenReturn(Optional.of(userWithId(43L, "b@example.com")));
        when(publishingServiceClient.provisionAuthor(eq("bound-key"), any()))
                .thenReturn(new PublishingServiceClient.AuthorProvisioningResponse("902"));

        service().backfillMembersToSite(1L, 30L);

        verify(publishingServiceClient, times(1)).provisionAuthor(eq("bound-key"), any());
        verify(userRepository, never()).findById(42L);
        verify(userSiteAuthorRepository, times(1)).save(any(UserSiteAuthor.class));
    }

    @Test
    void 補填で一部のメンバーが失敗しても例外を投げず残りを処理し失敗したメンバーと理由をログに残す() {
        when(projectServiceClient.getSite(30L)).thenReturn(Optional.of(BOUND_SITE));
        when(projectUserRepository.findByProjectId(1L)).thenReturn(List.of(
                new ProjectUser(1L, 42L, "author"), new ProjectUser(1L, 43L, "author")));
        when(userSiteAuthorRepository.findByUserIdAndSiteId(anyLong(), eq(30L))).thenReturn(Optional.empty());
        when(userRepository.findById(42L)).thenReturn(Optional.of(userWithId(42L, "a@example.com")));
        when(userRepository.findById(43L)).thenReturn(Optional.of(userWithId(43L, "b@example.com")));
        when(publishingServiceClient.provisionAuthor(eq("bound-key"), any()))
                .thenThrow(new PublishingServiceException("connect timed out", null))
                .thenReturn(new PublishingServiceClient.AuthorProvisioningResponse("902"));

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
        when(projectServiceClient.getSite(30L)).thenReturn(Optional.of(BOUND_SITE));
        when(projectUserRepository.findByProjectId(1L)).thenReturn(List.of(
                new ProjectUser(1L, 42L, "author"), new ProjectUser(1L, 43L, "author")));
        when(userSiteAuthorRepository.findByUserIdAndSiteId(anyLong(), eq(30L))).thenReturn(Optional.empty());
        when(userRepository.findById(42L)).thenReturn(Optional.empty());
        when(userRepository.findById(43L)).thenReturn(Optional.of(userWithId(43L, "b@example.com")));
        when(publishingServiceClient.provisionAuthor(eq("bound-key"), any()))
                .thenReturn(new PublishingServiceClient.AuthorProvisioningResponse("902"));

        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            service().backfillMembersToSite(1L, 30L);
        } finally {
            detach(logs);
        }

        verify(publishingServiceClient, times(1)).provisionAuthor(eq("bound-key"), any());
        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel().isGreaterOrEqual(Level.WARN)
                && e.getFormattedMessage().contains("42")));
    }

    @Test
    void 補填の対象サイトが存在しなければ何も作らず例外も投げない() {
        when(projectServiceClient.getSite(30L)).thenReturn(Optional.empty());

        service().backfillMembersToSite(1L, 30L);

        verify(projectUserRepository, never()).findByProjectId(anyLong());
        verify(publishingServiceClient, never()).provisionAuthor(any(), any());
    }

    @Test
    void 補填の対象プロジェクトにメンバーが居なければ何も作らない() {
        when(projectServiceClient.getSite(30L)).thenReturn(Optional.of(BOUND_SITE));
        when(projectUserRepository.findByProjectId(1L)).thenReturn(List.of());

        service().backfillMembersToSite(1L, 30L);

        verify(publishingServiceClient, never()).provisionAuthor(any(), any());
        verify(userSiteAuthorRepository, never()).save(any());
    }
}
