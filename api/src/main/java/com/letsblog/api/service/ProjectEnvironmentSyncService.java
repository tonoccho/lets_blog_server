package com.letsblog.api.service;

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
 * プロジェクトに紐づく環境(ローカル/テスト/本番)間で、自動構築(managedWordpress)された
 * WordPress環境同士に限り、テーマ・プラグイン・DBを同期する。
 * 外部登録サイトが紐付いている環境スロットは、ファイルシステム・DBへの直接アクセス手段がないため対象外。
 */
@Service
public class ProjectEnvironmentSyncService {

    private static final Set<String> VALID_ENVIRONMENTS = Set.of("local", "test", "production");

    private final ProjectRepository projectRepository;
    private final SiteRepository siteRepository;
    private final WordPressSyncClient syncClient;

    public ProjectEnvironmentSyncService(
            ProjectRepository projectRepository, SiteRepository siteRepository, WordPressSyncClient syncClient) {
        this.projectRepository = projectRepository;
        this.siteRepository = siteRepository;
        this.syncClient = syncClient;
    }

    @Transactional(readOnly = true)
    public void sync(Long projectId, String fromEnvironment, String toEnvironment, List<String> targets) {
        requireValidEnvironment(fromEnvironment);
        requireValidEnvironment(toEnvironment);
        if (fromEnvironment.equals(toEnvironment)) {
            throw new IllegalArgumentException("同期元と同期先には異なる環境を指定してください");
        }

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));

        Site fromSite = resolveManagedSite(project, fromEnvironment);
        Site toSite = resolveManagedSite(project, toEnvironment);

        syncClient.sync(new WordPressSyncClient.SyncCommand(
                fromSite.getWpSlug(), fromSite.getWpDbName(),
                toSite.getWpSlug(), toSite.getWpDbName(), targets));
    }

    private Site resolveManagedSite(Project project, String environment) {
        Long siteId = switch (environment) {
            case "local" -> project.getLocalSiteId();
            case "test" -> project.getTestSiteId();
            case "production" -> project.getProductionSiteId();
            default -> throw new IllegalArgumentException("environment は local/test/production のいずれかを指定してください");
        };
        if (siteId == null) {
            throw new IllegalArgumentException(environment + "環境にはサイトが紐付けられていません");
        }
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> new SiteNotFoundException("id " + siteId + " のサイトは登録されていません"));
        if (!site.isManagedWordpress()) {
            throw new IllegalArgumentException(
                    environment + "環境(" + site.getSiteKey() + ")は自動構築サイトではないため同期できません");
        }
        return site;
    }

    private void requireValidEnvironment(String environment) {
        if (!VALID_ENVIRONMENTS.contains(environment)) {
            throw new IllegalArgumentException("environment は local/test/production のいずれかを指定してください");
        }
    }
}
