package com.letsblog.project.service;

import com.letsblog.project.cms.DatabaseExport;
import com.letsblog.project.client.CmsProvisioningBridgeClient;
import com.letsblog.project.client.IdentityBridgeClient;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.Site;
import com.letsblog.project.provisioning.WordPressSyncClient;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.repository.SiteRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * プロジェクトに紐づく環境(ローカル/テスト/本番)間で、テーマ・プラグイン・メディア・DBを同期する
 * (issue #577 stage2、legacy-apiから移設)。同期先は自動構築(managedWordpress)されたWordPress環境に
 * 限る。同期元は、managedWordpress環境に加え、SSH/wp-cli管理の外部サイトもDB・メディア・テーマのみ
 * 対応する(issue #511)。
 *
 * <p>{@code project_users}テーブルはidentity-serviceが所有する(issue #583)ため、DB同期完了後の
 * WordPressユーザーロール再整合(issue #514)は{@link IdentityBridgeClient#reconcileRolesForSite}
 * 経由でidentity-serviceへ依頼する。
 */
@Service
public class ProjectEnvironmentSyncService {

    private static final Set<String> VALID_ENVIRONMENTS = Set.of("local", "test", "production");
    private static final Set<String> SSH_SOURCE_SUPPORTED_TARGETS = Set.of("db", "media", "themes");

    private final ProjectRepository projectRepository;
    private final SiteRepository siteRepository;
    private final SiteService siteService;
    private final WordPressSyncClient syncClient;
    private final CmsProvisioningBridgeClient bridgeClient;
    private final IdentityBridgeClient identityBridgeClient;
    private final CurrentActorService currentActorService;

    public ProjectEnvironmentSyncService(
            ProjectRepository projectRepository, SiteRepository siteRepository, SiteService siteService,
            WordPressSyncClient syncClient, CmsProvisioningBridgeClient bridgeClient,
            IdentityBridgeClient identityBridgeClient, CurrentActorService currentActorService) {
        this.projectRepository = projectRepository;
        this.siteRepository = siteRepository;
        this.siteService = siteService;
        this.syncClient = syncClient;
        this.bridgeClient = bridgeClient;
        this.identityBridgeClient = identityBridgeClient;
        this.currentActorService = currentActorService;
    }

    @Transactional
    public void sync(Long projectId, String fromEnvironment, String toEnvironment, List<String> targets) {
        requireValidEnvironment(fromEnvironment);
        requireValidEnvironment(toEnvironment);
        if (fromEnvironment.equals(toEnvironment)) {
            throw new IllegalArgumentException("同期元と同期先には異なる環境を指定してください");
        }
        if ("local".equals(fromEnvironment)) {
            throw new IllegalArgumentException("ローカル環境は同期元に指定できません");
        }
        if ("production".equals(toEnvironment)) {
            throw new IllegalArgumentException("本番環境は同期先に指定できません");
        }

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));

        Site fromSite = resolveSite(project, fromEnvironment);
        Site toSite = resolveManagedSite(project, toEnvironment);

        if (fromSite.isManagedWordpress()) {
            syncClient.sync(new WordPressSyncClient.SyncCommand(
                    fromSite.getWpSlug(), fromSite.getWpDbName(),
                    toSite.getWpSlug(), toSite.getWpDbName(), targets));
        } else {
            syncFromSshManagedSite(fromEnvironment, fromSite, toSite, targets);
        }

        if (targets.contains("db")) {
            identityBridgeClient.reconcileRolesForSite(
                    project.getId(), toSite.getId(), currentActorService.getAuthorizationHeader());
        }
    }

    private void syncFromSshManagedSite(String fromEnvironment, Site fromSite, Site toSite, List<String> targets) {
        Set<String> targetSet = Set.copyOf(targets);
        if (!SSH_SOURCE_SUPPORTED_TARGETS.containsAll(targetSet)) {
            throw new IllegalArgumentException(
                    fromEnvironment + "環境(" + fromSite.getSiteKey() + ")はSSH管理サイトのため、DB・メディア・テーマのみ同期できます");
        }
        SiteService.SiteDataSource dataSource = siteService.resolveDataSource(fromSite);
        if (!dataSource.hasSsh()) {
            throw new IllegalArgumentException(
                    fromEnvironment + "環境(" + fromSite.getSiteKey() + ")はSSH接続が設定されていないため同期できません");
        }
        Map<String, String> creds = dataSource.sshCredentials();

        if (targetSet.contains("db")) {
            DatabaseExport export = bridgeClient.exportDatabase(creds);
            syncClient.importDatabase(toSite.getWpSlug(), toSite.getWpDbName(), fromSite.getBaseUrl(),
                    export.tablePrefix(), export.dump());
        }
        if (targetSet.contains("media")) {
            byte[] mediaArchive = bridgeClient.exportMedia(creds);
            if (mediaArchive.length > 0) {
                syncClient.importMedia(toSite.getWpSlug(), mediaArchive);
            }
        }
        if (targetSet.contains("themes")) {
            byte[] themesArchive = bridgeClient.exportThemes(creds);
            if (themesArchive.length > 0) {
                syncClient.importThemes(toSite.getWpSlug(), themesArchive);
            }
        }
    }

    private Site resolveSite(Project project, String environment) {
        Long siteId = switch (environment) {
            case "local" -> project.getLocalSiteId();
            case "test" -> project.getTestSiteId();
            case "production" -> project.getProductionSiteId();
            default -> throw new IllegalArgumentException("environment は local/test/production のいずれかを指定してください");
        };
        if (siteId == null) {
            throw new IllegalArgumentException(environment + "環境にはサイトが紐付けられていません");
        }
        return siteRepository.findById(siteId)
                .orElseThrow(() -> new SiteNotFoundException("id " + siteId + " のサイトは登録されていません"));
    }

    private Site resolveManagedSite(Project project, String environment) {
        Site site = resolveSite(project, environment);
        if (!site.isManagedWordpress()) {
            throw new IllegalArgumentException(
                    environment + "環境(" + site.getSiteKey() + ")は自動構築サイトではないため同期先にできません");
        }
        return site;
    }

    private void requireValidEnvironment(String environment) {
        if (!VALID_ENVIRONMENTS.contains(environment)) {
            throw new IllegalArgumentException("environment は local/test/production のいずれかを指定してください");
        }
    }
}
