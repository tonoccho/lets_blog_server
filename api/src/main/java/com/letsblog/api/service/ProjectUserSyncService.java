package com.letsblog.api.service;

import com.letsblog.api.aop.AuditLog;
import com.letsblog.api.cms.AuthorProvisioningRequest;
import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.domain.AuditLogAction;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.ProjectUser;
import com.letsblog.api.domain.Site;
import com.letsblog.api.domain.User;
import com.letsblog.api.dto.ProjectUserResponse;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.ProjectUserRepository;
import com.letsblog.api.repository.SiteRepository;
import com.letsblog.api.repository.UserRepository;
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

    private final ProjectRepository projectRepository;
    private final ProjectUserRepository projectUserRepository;
    private final UserRepository userRepository;
    private final SiteRepository siteRepository;
    private final SiteService siteService;
    private final CmsAdapterFactory cmsAdapterFactory;

    public ProjectUserSyncService(
            ProjectRepository projectRepository,
            ProjectUserRepository projectUserRepository,
            UserRepository userRepository,
            SiteRepository siteRepository,
            SiteService siteService,
            CmsAdapterFactory cmsAdapterFactory) {
        this.projectRepository = projectRepository;
        this.projectUserRepository = projectUserRepository;
        this.userRepository = userRepository;
        this.siteRepository = siteRepository;
        this.siteService = siteService;
        this.cmsAdapterFactory = cmsAdapterFactory;
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

    private void syncToProjectSites(Project project, User user, String wpRole) {
        AuthorProvisioningRequest request = new AuthorProvisioningRequest(
                user.getEmail(),
                wpRole,
                user.getFirstName(),
                user.getLastName(),
                user.getDisplayName(),
                user.getWebsiteUrl(),
                user.getBio(),
                user.getLocale());

        for (Site site : getProjectSites(project)) {
            CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
            CmsAdapter adapter = cmsAdapterFactory.resolve(site.getCmsType());
            adapter.provisionAuthor(credentials, request);
        }
    }

    private List<Site> getProjectSites(Project project) {
        List<Long> siteIds = Stream.of(project.getLocalSiteId(), project.getTestSiteId(), project.getProductionSiteId())
                .filter(Objects::nonNull)
                .toList();
        return siteIds.isEmpty() ? List.of() : siteRepository.findAllById(siteIds);
    }

    private Project getProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("id " + userId + " のユーザーは登録されていません"));
    }
}
