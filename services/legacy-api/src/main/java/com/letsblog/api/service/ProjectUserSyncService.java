package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.client.PublishingServiceClient;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.ProjectUser;
import com.letsblog.api.domain.Site;
import com.letsblog.api.domain.User;
import com.letsblog.api.domain.UserSiteAuthor;
import com.letsblog.api.dto.ProjectUserResponse;
import com.letsblog.api.dto.ProjectUserSummaryResponse;
import com.letsblog.api.repository.ProjectUserRepository;
import com.letsblog.api.repository.UserRepository;
import com.letsblog.api.repository.UserSiteAuthorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * プロジェクトに参加したユーザーを、そのプロジェクトに紐付く各WordPress環境
 * (ローカル/テスト/本番、設定されているもののみ)へ即時同期する。
 * いずれかの環境への同期に失敗した場合は例外をそのまま伝播させ、
 * project_usersへの反映も含めてトランザクション全体をロールバックする。
 */
@Service
public class ProjectUserSyncService {

    private final ProjectService projectService;
    private final ProjectUserRepository projectUserRepository;
    private final UserRepository userRepository;
    private final SiteService siteService;
    private final PublishingServiceClient publishingServiceClient;
    private final UserSiteAuthorRepository userSiteAuthorRepository;

    public ProjectUserSyncService(
            ProjectService projectService,
            ProjectUserRepository projectUserRepository,
            UserRepository userRepository,
            SiteService siteService,
            PublishingServiceClient publishingServiceClient,
            UserSiteAuthorRepository userSiteAuthorRepository) {
        this.projectService = projectService;
        this.projectUserRepository = projectUserRepository;
        this.userRepository = userRepository;
        this.siteService = siteService;
        this.publishingServiceClient = publishingServiceClient;
        this.userSiteAuthorRepository = userSiteAuthorRepository;
    }

    @AuditLog(action = AuditLogAction.PROJECT_USER_ADDED, resourceType = "PROJECT_USER")
    @Transactional
    public void addUserToProject(Long projectId, Long userId, String wpRole) {
        Project project = getProject(projectId);
        User user = getUser(userId);
        syncToProjectSites(project, user, wpRole);
        projectUserRepository.save(new ProjectUser(projectId, userId, wpRole));
    }

    @AuditLog(action = AuditLogAction.PROJECT_USER_ROLE_UPDATED, resourceType = "PROJECT_USER")
    @Transactional
    public void updateUserProjectRole(Long projectId, Long userId, String newWpRole) {
        ProjectUser projectUser = projectUserRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ProjectUserNotFoundException(
                        "プロジェクト " + projectId + " にユーザー " + userId + " は参加していません"));
        Project project = getProject(projectId);
        User user = getUser(userId);
        syncToProjectSites(project, user, newWpRole);
        projectUser.setWpRole(newWpRole);
        projectUserRepository.save(projectUser);
    }

    @AuditLog(action = AuditLogAction.PROJECT_USER_REMOVED, resourceType = "PROJECT_USER")
    @Transactional
    public void removeUserFromProject(Long projectId, Long userId) {
        // WordPress側のユーザーは削除しない(残す運用)。project_usersからの解除のみ行う。
        projectUserRepository.deleteByProjectIdAndUserId(projectId, userId);
    }

    @Transactional(readOnly = true)
    public List<ProjectUserResponse> getProjectUsers(Long projectId) {
        return projectUserRepository.findByProjectId(projectId).stream()
                .map(pu -> {
                    User user = userRepository.findById(pu.getUserId()).orElse(null);
                    return new ProjectUserResponse(
                            pu.getUserId(),
                            user != null ? user.getEmail() : null,
                            user != null ? user.getDisplayName() : null,
                            pu.getWpRole());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ProjectUserSummaryResponse> listAllProjectUsers() {
        return projectUserRepository.findAll().stream()
                .map(pu -> new ProjectUserSummaryResponse(pu.getProjectId(), pu.getUserId(), pu.getWpRole()))
                .toList();
    }

    /**
     * 環境同期(ProjectEnvironmentSyncService)がDBを同期対象に含む場合、同期先サイトのWordPress
     * ユーザーロールが同期処理によって書き換わりうる(#514)。project_usersに記録された意図した
     * ロールで、同期先サイトに参加中の全ユーザーのロールを強制的に上書きし、整合性を回復する。
     */
    @Transactional
    public void reconcileRolesForSite(Long projectId, Long siteId) {
        Site site = siteService.getById(siteId)
                .orElseThrow(() -> new SiteNotFoundException("id " + siteId + " のサイトは登録されていません"));
        for (ProjectUser projectUser : projectUserRepository.findByProjectId(projectId)) {
            User user = userRepository.findById(projectUser.getUserId()).orElse(null);
            if (user == null) {
                continue;
            }
            provisionUserOnSite(site, user, projectUser.getWpRole());
        }
    }

    private void syncToProjectSites(Project project, User user, String wpRole) {
        for (Site site : getProjectSites(project)) {
            provisionUserOnSite(site, user, wpRole);
        }
    }

    private void provisionUserOnSite(Site site, User user, String wpRole) {
        // 著者(WordPressユーザー)作成/更新の実処理はpublishing-serviceへ移管した(issue #707、
        // #575設計判断4の書き込み側)。実際のCMS側ユーザー作成/更新はpublishing-serviceの
        // 内部ブリッジ経由で依頼する。返ってきたcmsAuthorIdのuser_site_authorsへの永続化は
        // 引き続きこちらの責務(user_site_authors自体の所有権はlegacy-apiに残る)。
        PublishingServiceClient.AuthorProvisioningRequest request = new PublishingServiceClient.AuthorProvisioningRequest(
                user.getEmail(),
                wpRole,
                user.getFirstName(),
                user.getLastName(),
                user.getDisplayName(),
                user.getWebsiteUrl(),
                user.getBio(),
                user.getLocale());

        String cmsAuthorId = publishingServiceClient.provisionAuthor(site.getSiteKey(), request).cmsAuthorId();
        saveAuthorMapping(user.getId(), site.getId(), cmsAuthorId);
    }

    /**
     * provisionAuthorが返したWordPress側ユーザーIDをuser_site_authorsへ永続化する。
     * 投稿時(PostPublishService.resolveAuthorId)は、この対応表を優先して参照することで
     * 投稿の都度メールアドレス検索を行わずに著者IDを解決できる。
     */
    private void saveAuthorMapping(Long userId, Long siteId, String cmsAuthorId) {
        if (cmsAuthorId == null) {
            return;
        }
        UserSiteAuthor mapping = userSiteAuthorRepository.findByUserIdAndSiteId(userId, siteId)
                .orElseGet(() -> new UserSiteAuthor(userId, siteId, cmsAuthorId));
        mapping.setCmsAuthorId(cmsAuthorId);
        userSiteAuthorRepository.save(mapping);
    }

    private List<Site> getProjectSites(Project project) {
        List<Long> siteIds = Stream.of(project.getLocalSiteId(), project.getTestSiteId(), project.getProductionSiteId())
                .filter(Objects::nonNull)
                .toList();
        return siteIds.isEmpty() ? List.of() : siteService.getAllById(siteIds);
    }

    private Project getProject(Long projectId) {
        return projectService.getProjectEntity(projectId);
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("id " + userId + " のユーザーは登録されていません"));
    }
}
