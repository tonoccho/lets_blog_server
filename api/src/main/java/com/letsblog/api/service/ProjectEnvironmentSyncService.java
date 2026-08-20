package com.letsblog.api.service;

import com.letsblog.api.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.api.cms.ssh.WordPressSshOperations;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.provisioning.WordPressSyncClient;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * プロジェクトに紐づく環境(ローカル/テスト/本番)間で、テーマ・プラグイン・メディア・DBを同期する。
 * 同期先は自動構築(managedWordpress)されたWordPress環境に限る(ファイルシステム・DBへの直接アクセス
 * 手段がないため)。同期元は、managedWordpress環境に加え、SSH/wp-cli管理の外部サイトもDB・メディア・
 * テーマのみ対応する(issue #511。プラグインは、SSH管理サイトがこのコンテナと同一ホストにいないため対象外)。
 * DBを同期対象に含む場合、同期先サイトのWordPressユーザーロールが同期処理によって書き換わりうるため、
 * 同期完了後にproject_usersの内容でロールを強制的に上書きし整合性を回復する(issue #514)。
 */
@Service
public class ProjectEnvironmentSyncService {

    private static final Set<String> VALID_ENVIRONMENTS = Set.of("local", "test", "production");
    private static final Set<String> SSH_SOURCE_SUPPORTED_TARGETS = Set.of("db", "media", "themes");

    private final ProjectRepository projectRepository;
    private final SiteRepository siteRepository;
    private final SiteService siteService;
    private final WordPressSyncClient syncClient;
    private final WordPressSshOperations sshOperations;
    private final ProjectUserSyncService projectUserSyncService;

    public ProjectEnvironmentSyncService(
            ProjectRepository projectRepository, SiteRepository siteRepository, SiteService siteService,
            WordPressSyncClient syncClient, WordPressSshOperations sshOperations,
            ProjectUserSyncService projectUserSyncService) {
        this.projectRepository = projectRepository;
        this.siteRepository = siteRepository;
        this.siteService = siteService;
        this.syncClient = syncClient;
        this.sshOperations = sshOperations;
        this.projectUserSyncService = projectUserSyncService;
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
            projectUserSyncService.reconcileRolesForSite(project.getId(), toSite.getId());
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
        WordPressCredentials creds = dataSource.sshCredentials();

        if (targetSet.contains("db")) {
            WordPressSshOperations.DatabaseExport export = sshOperations.exportDatabase(creds);
            syncClient.importDatabase(toSite.getWpSlug(), toSite.getWpDbName(), fromSite.getBaseUrl(),
                    export.tablePrefix(), export.dump());
        }
        if (targetSet.contains("media")) {
            byte[] mediaArchive = sshOperations.exportMedia(creds);
            if (mediaArchive.length > 0) {
                syncClient.importMedia(toSite.getWpSlug(), mediaArchive);
            }
        }
        if (targetSet.contains("themes")) {
            byte[] themesArchive = sshOperations.exportThemes(creds);
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
