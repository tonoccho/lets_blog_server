package com.letsblog.identity.service;

import com.letsblog.identity.client.ProjectServiceClient;
import com.letsblog.identity.client.PublishingServiceClient;
import com.letsblog.identity.client.PublishingServiceException;
import com.letsblog.identity.domain.ProjectUser;
import com.letsblog.identity.domain.User;
import com.letsblog.identity.domain.UserSiteAuthor;
import com.letsblog.identity.dto.ProjectUserResponse;
import com.letsblog.identity.dto.ProjectUserSummaryResponse;
import com.letsblog.identity.dto.ProjectUserSyncSiteResult;
import com.letsblog.identity.repository.ProjectUserRepository;
import com.letsblog.identity.repository.UserRepository;
import com.letsblog.identity.repository.UserSiteAuthorRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * プロジェクトに参加したユーザーを、そのプロジェクトに紐付く各WordPress環境
 * (ローカル/テスト/本番、設定されているもののみ)へ即時同期する。
 * いずれかの環境への同期に失敗した場合は例外をそのまま伝播させ、
 * {@code project_users}への反映も含めてトランザクション全体をロールバックする。
 *
 * <p>issue #583でlegacy-apiから移設した。{@code project_users}/{@code user_site_authors}の
 * 所有権がidentity-serviceへ移ったため({@code lbs_identity}、ADR-0004)、
 * プロジェクト/サイトの照会は{@link ProjectServiceClient}、CMS側のユーザー作成・更新は
 * {@link PublishingServiceClient}という内部ブリッジ経由になる。
 */
@Service
@Slf4j
public class ProjectUserSyncService {

    private final ProjectServiceClient projectServiceClient;
    private final ProjectUserRepository projectUserRepository;
    private final UserRepository userRepository;
    private final PublishingServiceClient publishingServiceClient;
    private final UserSiteAuthorRepository userSiteAuthorRepository;
    private final AuditLogService auditLogService;

    public ProjectUserSyncService(
            ProjectServiceClient projectServiceClient,
            ProjectUserRepository projectUserRepository,
            UserRepository userRepository,
            PublishingServiceClient publishingServiceClient,
            UserSiteAuthorRepository userSiteAuthorRepository,
            AuditLogService auditLogService) {
        this.projectServiceClient = projectServiceClient;
        this.projectUserRepository = projectUserRepository;
        this.userRepository = userRepository;
        this.publishingServiceClient = publishingServiceClient;
        this.userSiteAuthorRepository = userSiteAuthorRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void addUserToProject(Long projectId, Long userId, String wpRole) {
        ProjectServiceClient.ProjectBridge project = projectServiceClient.getProject(projectId);
        User user = getUser(userId);
        syncToProjectSites(project, user, wpRole);
        projectUserRepository.save(new ProjectUser(projectId, userId, wpRole));
        auditLogService.logProjectUserAction(AuditLogService.ACTION_PROJECT_USER_ADDED, projectId);
    }

    @Transactional
    public void updateUserProjectRole(Long projectId, Long userId, String newWpRole) {
        ProjectUser projectUser = projectUserRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ProjectUserNotFoundException(
                        "プロジェクト " + projectId + " にユーザー " + userId + " は参加していません"));
        ProjectServiceClient.ProjectBridge project = projectServiceClient.getProject(projectId);
        User user = getUser(userId);
        syncToProjectSites(project, user, newWpRole);
        projectUser.setWpRole(newWpRole);
        projectUserRepository.save(projectUser);
        auditLogService.logProjectUserAction(AuditLogService.ACTION_PROJECT_USER_ROLE_UPDATED, projectId);
    }

    @Transactional
    public void removeUserFromProject(Long projectId, Long userId) {
        // WordPress側のユーザーは削除しない(残す運用)。project_usersからの解除のみ行う。
        projectUserRepository.deleteByProjectIdAndUserId(projectId, userId);
        auditLogService.logProjectUserAction(AuditLogService.ACTION_PROJECT_USER_REMOVED, projectId);
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

    /** ある利用者が参加しているプロジェクトIDの一覧(内部ブリッジ、一覧系の絞り込みに使う)。 */
    @Transactional(readOnly = true)
    public List<Long> projectIdsForUser(Long userId) {
        return projectUserRepository.findByUserId(userId).stream().map(ProjectUser::getProjectId).toList();
    }

    /** プロジェクトメンバー判定(内部ブリッジ、全サービス共通)。 */
    @Transactional(readOnly = true)
    public boolean isProjectMember(Long projectId, Long userId) {
        return projectUserRepository.findByProjectIdAndUserId(projectId, userId).isPresent();
    }

    /** {@code user_site_authors}の対応表照会(内部ブリッジ、publishing-serviceの著者ID解決に使う)。 */
    @Transactional(readOnly = true)
    public Optional<String> findCmsAuthorId(Long userId, Long siteId) {
        return userSiteAuthorRepository.findByUserIdAndSiteId(userId, siteId).map(UserSiteAuthor::getCmsAuthorId);
    }

    /** {@code user_site_authors}へのキャッシュ書き込み(内部ブリッジ)。 */
    @Transactional
    public void cacheAuthorMapping(Long userId, Long siteId, String cmsAuthorId) {
        saveAuthorMapping(userId, siteId, cmsAuthorId);
    }

    /**
     * 環境同期(ProjectEnvironmentSyncService)がDBを同期対象に含む場合、同期先サイトのWordPress
     * ユーザーロールが同期処理によって書き換わりうる(#514)。{@code project_users}に記録された
     * 意図したロールで、同期先サイトに参加中の全ユーザーのロールを強制的に上書きし、整合性を回復する。
     */
    @Transactional
    public void reconcileRolesForSite(Long projectId, Long siteId) {
        ProjectServiceClient.SiteBridge site = projectServiceClient.getSite(siteId)
                .orElseThrow(() -> new SiteNotFoundException("id " + siteId + " のサイトは登録されていません"));
        for (ProjectUser projectUser : projectUserRepository.findByProjectId(projectId)) {
            User user = userRepository.findById(projectUser.getUserId()).orElse(null);
            if (user == null) {
                continue;
            }
            provisionUserOnSite(site, user, projectUser.getWpRole());
        }
    }

    /**
     * issue #1242: メンバー1人分のプロフィール(email/wpRole/firstName/lastName/displayName/
     * websiteUrl/bio/locale)を、そのプロジェクトに紐づく全WordPress環境へ再送信する
     * (追加/ロール変更を伴わない)。
     *
     * <p>{@code addUserToProject}/{@code updateUserProjectRole}と異なり{@code @Transactional}を
     * 持たない。要件3により、一部の環境への反映が失敗しても成功した環境の結果はロールバックせず
     * 保持し、失敗した環境とその理由を呼び出し元へ返す。サイトごとの{@code provisionUserOnSite}
     * (内部で{@code user_site_authors}へ保存する)は独立して実行され、失敗した環境の分だけ
     * その保存がスキップされる。
     */
    public List<ProjectUserSyncSiteResult> syncUserProfileToProjectSites(Long projectId, Long userId) {
        ProjectUser projectUser = projectUserRepository.findByProjectIdAndUserId(projectId, userId)
                .orElseThrow(() -> new ProjectUserNotFoundException(
                        "プロジェクト " + projectId + " にユーザー " + userId + " は参加していません"));
        ProjectServiceClient.ProjectBridge project = projectServiceClient.getProject(projectId);
        User user = getUser(userId);

        List<ProjectUserSyncSiteResult> results = new ArrayList<>();
        for (ProjectServiceClient.SiteBridge site : getProjectSites(project)) {
            try {
                provisionUserOnSite(site, user, projectUser.getWpRole());
                results.add(new ProjectUserSyncSiteResult(site.id(), site.siteKey(), site.name(), true, null));
            } catch (RuntimeException e) {
                results.add(new ProjectUserSyncSiteResult(site.id(), site.siteKey(), site.name(), false, e.getMessage()));
            }
        }
        auditLogService.logProjectUserSyncAction(projectId, userId, results);
        return results;
    }

    /**
     * issue #1324: サイト(環境)を後から紐付けたとき、その時点のメンバー全員のWordPressユーザーを
     * 紐付けたサイトに作り、{@code user_site_authors}へ保存する。project-serviceが発行する
     * {@code project.environment-bound}イベントの購読側から呼ばれる。
     *
     * <p>すでにそのサイトの対応表があるメンバーは作らない(重複防止、イベントの再配信にも冪等)。
     * {@code @Transactional}を持たず、メンバーごとに独立して実行する(#1242と同じ方針): 一部の
     * メンバーで失敗しても環境の紐付け(project-service側で確定済み)は取り消さず、他のメンバーの
     * 補填も続ける。失敗したメンバーと理由は警告ログに残す(例外は呼び出し元へ伝播させない)。
     */
    public void backfillMembersToSite(Long projectId, Long siteId) {
        Optional<ProjectServiceClient.SiteBridge> found = projectServiceClient.getSite(siteId);
        if (found.isEmpty()) {
            log.warn("環境紐付け後のメンバー補填をスキップしました(サイトが見つかりません): projectId={}, siteId={}",
                    projectId, siteId);
            return;
        }
        ProjectServiceClient.SiteBridge site = found.get();
        for (ProjectUser projectUser : projectUserRepository.findByProjectId(projectId)) {
            Long userId = projectUser.getUserId();
            if (userSiteAuthorRepository.findByUserIdAndSiteId(userId, siteId).isPresent()) {
                continue;
            }
            try {
                provisionUserOnSite(site, getUser(userId), projectUser.getWpRole());
            } catch (RuntimeException e) {
                log.warn("環境紐付け後のメンバー補填に失敗しました: projectId={}, userId={}, siteId={}, site={}, reason={}",
                        projectId, userId, siteId, site.name(), e.getMessage(), e);
            }
        }
    }

    private void syncToProjectSites(ProjectServiceClient.ProjectBridge project, User user, String wpRole) {
        for (ProjectServiceClient.SiteBridge site : getProjectSites(project)) {
            try {
                provisionUserOnSite(site, user, wpRole);
            } catch (PublishingServiceException e) {
                // issue #1302: 複数環境が紐づくとき、どの環境で失敗したか利用者に分かるよう環境名を添える。
                // 環境名はここ(all-or-nothingの経路)でだけ付ける。syncUserProfileToProjectSites は
                // 環境名を別項目(siteName)で返すため、provisionUserOnSite 側では付けない。
                throw new PublishingServiceException(site.name() + ": " + e.getMessage(), e);
            }
        }
    }

    private void provisionUserOnSite(ProjectServiceClient.SiteBridge site, User user, String wpRole) {
        // 著者(WordPressユーザー)作成/更新の実処理はpublishing-serviceが持つ(issue #707、
        // #575設計判断4の書き込み側)。返ってきたcmsAuthorIdのuser_site_authorsへの永続化が
        // こちらの責務(user_site_authors自体の所有権はidentity-service、issue #583)。
        PublishingServiceClient.AuthorProvisioningRequest request =
                new PublishingServiceClient.AuthorProvisioningRequest(
                        user.getEmail(),
                        wpRole,
                        user.getFirstName(),
                        user.getLastName(),
                        user.getDisplayName(),
                        user.getWebsiteUrl(),
                        user.getBio(),
                        user.getLocale());

        String cmsAuthorId = publishingServiceClient.provisionAuthor(site.siteKey(), request).cmsAuthorId();
        saveAuthorMapping(user.getId(), site.id(), cmsAuthorId);
    }

    /**
     * provisionAuthorが返したWordPress側ユーザーIDを{@code user_site_authors}へ永続化する。
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

    private List<ProjectServiceClient.SiteBridge> getProjectSites(ProjectServiceClient.ProjectBridge project) {
        return Stream.of(project.localSiteId(), project.testSiteId(), project.productionSiteId())
                .filter(Objects::nonNull)
                .map(projectServiceClient::getSite)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("id " + userId + " のユーザーは登録されていません"));
    }
}
