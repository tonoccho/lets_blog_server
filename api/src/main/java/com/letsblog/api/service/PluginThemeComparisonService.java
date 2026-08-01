package com.letsblog.api.service;

import com.letsblog.api.domain.BulkOperationLog;
import com.letsblog.api.domain.BulkOperationType;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.ReconcileStateRequest.StateChangeRequest;
import com.letsblog.api.dto.StatusComparisonPage;
import com.letsblog.api.dto.StatusComparisonRow;
import com.letsblog.api.dto.StatusEnvironmentValue;
import com.letsblog.api.provisioning.WordPressBulkManagementClient;
import com.letsblog.api.provisioning.WordPressBulkManagementClient.PluginThemeInfo;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * プラグイン・テーマを3環境(ローカル/テスト/本番)で横断比較し、行(slug)ごとに
 * 環境単位で希望状態(未インストール/無効/有効)へ反映する({@link #reconcilePlugin}/{@link #reconcileTheme})、
 * または全環境から削除する({@link #deletePluginEverywhere}/{@link #deleteThemeEverywhere})。
 * カテゴリ/タグの{@link TermComparisonService}と異なり、slugそのものが環境間で共通のwordpress.org識別子のため
 * 名寄せの曖昧さはない。実際のwp-cli呼び出し・ログ記録は{@link BulkManagementService#applyToEnvironment}に委譲する。
 */
@Service
public class PluginThemeComparisonService {

    private static final List<String> ENVIRONMENT_ORDER = List.of("local", "test", "production");
    private static final String NOT_INSTALLED = "NOT_INSTALLED";
    private static final String INACTIVE = "INACTIVE";
    private static final String ACTIVE = "ACTIVE";

    private final ProjectRepository projectRepository;
    private final SiteRepository siteRepository;
    private final WordPressBulkManagementClient bulkManagementClient;
    private final BulkManagementService bulkManagementService;

    public PluginThemeComparisonService(
            ProjectRepository projectRepository,
            SiteRepository siteRepository,
            WordPressBulkManagementClient bulkManagementClient,
            BulkManagementService bulkManagementService) {
        this.projectRepository = projectRepository;
        this.siteRepository = siteRepository;
        this.bulkManagementClient = bulkManagementClient;
        this.bulkManagementService = bulkManagementService;
    }

    @Transactional(readOnly = true)
    public StatusComparisonPage listPluginComparison(Long projectId, int page, int size) {
        return listComparison(projectId, page, size, bulkManagementClient::listPlugins);
    }

    @Transactional(readOnly = true)
    public StatusComparisonPage listThemeComparison(Long projectId, int page, int size) {
        return listComparison(projectId, page, size, bulkManagementClient::listThemes);
    }

    @Transactional
    public List<BulkOperationLog> reconcilePlugin(
            Long projectId, String slug, List<StateChangeRequest> changes, Long actorId) {
        return reconcile(projectId, slug, changes, actorId, false);
    }

    @Transactional
    public List<BulkOperationLog> reconcileTheme(
            Long projectId, String slug, List<StateChangeRequest> changes, Long actorId) {
        return reconcile(projectId, slug, changes, actorId, true);
    }

    @Transactional
    public List<BulkOperationLog> deletePluginEverywhere(Long projectId, String slug, Long actorId) {
        return deleteEverywhere(projectId, slug, actorId, false);
    }

    @Transactional
    public List<BulkOperationLog> deleteThemeEverywhere(Long projectId, String slug, Long actorId) {
        return deleteEverywhere(projectId, slug, actorId, true);
    }

    private StatusComparisonPage listComparison(
            Long projectId, int page, int size, Function<String, List<PluginThemeInfo>> fetcher) {
        Project project = getProject(projectId);
        String masterEnvironment = project.getMasterEnvironment();

        Map<String, List<PluginThemeInfo>> byEnvironment = new LinkedHashMap<>();
        for (String environment : ENVIRONMENT_ORDER) {
            Site site = resolveOptionalManagedSite(project, environment);
            byEnvironment.put(environment, site != null ? fetcher.apply(site.getWpSlug()) : null);
        }

        Set<String> slugs = new LinkedHashSet<>();
        for (List<PluginThemeInfo> infos : byEnvironment.values()) {
            if (infos == null) {
                continue;
            }
            infos.forEach(info -> slugs.add(info.name()));
        }

        List<StatusComparisonRow> rows = slugs.stream()
                .map(slug -> toRow(slug, byEnvironment))
                .sorted(Comparator.comparing(StatusComparisonRow::slug))
                .toList();

        long totalCount = rows.size();
        List<StatusComparisonRow> pageItems = rows.stream()
                .skip((long) page * size)
                .limit(size)
                .toList();
        return new StatusComparisonPage(pageItems, page, size, totalCount, masterEnvironment);
    }

    private StatusComparisonRow toRow(String slug, Map<String, List<PluginThemeInfo>> byEnvironment) {
        return new StatusComparisonRow(
                slug,
                toValue(slug, byEnvironment.get("local")),
                toValue(slug, byEnvironment.get("test")),
                toValue(slug, byEnvironment.get("production")));
    }

    private StatusEnvironmentValue toValue(String slug, List<PluginThemeInfo> infos) {
        if (infos == null) {
            return StatusEnvironmentValue.unavailable();
        }
        return infos.stream()
                .filter(info -> info.name().equals(slug))
                .findFirst()
                .map(info -> StatusEnvironmentValue.of("active".equalsIgnoreCase(info.status()) ? ACTIVE : INACTIVE))
                .orElse(StatusEnvironmentValue.of(NOT_INSTALLED));
    }

    private List<BulkOperationLog> reconcile(
            Long projectId, String slug, List<StateChangeRequest> changes, Long actorId, boolean isTheme) {
        Project project = getProject(projectId);
        Function<String, List<PluginThemeInfo>> fetcher = isTheme
                ? bulkManagementClient::listThemes
                : bulkManagementClient::listPlugins;

        List<BulkOperationLog> results = new ArrayList<>();
        for (StateChangeRequest change : changes) {
            Site site = resolveManagedSiteOrThrow(project, change.environment());
            String currentStatus = toValue(slug, fetcher.apply(site.getWpSlug())).status();
            for (BulkOperationType step : stepsFor(currentStatus, change.desiredStatus(), isTheme)) {
                results.add(bulkManagementService.applyToEnvironment(
                        projectId, change.environment(), step, slug, null, null, null, null, actorId));
            }
        }
        return results;
    }

    /**
     * 現在状態から希望状態へ到達するために必要なwp-cli操作の列を返す(反映不要なら空リスト)。
     * サポート外の遷移(有効テーマの直接無効化・未インストールへの遷移)は例外にする
     * (テーマの無効化は他テーマの有効化でのみ間接的に行う、未インストール化は削除ボタンで行う)。
     */
    private List<BulkOperationType> stepsFor(String current, String desired, boolean isTheme) {
        if (current.equals(desired)) {
            return List.of();
        }
        if (current.equals(NOT_INSTALLED) && desired.equals(ACTIVE)) {
            return isTheme
                    ? List.of(BulkOperationType.THEME_INSTALL, BulkOperationType.THEME_ACTIVATE)
                    : List.of(BulkOperationType.PLUGIN_INSTALL, BulkOperationType.PLUGIN_ACTIVATE);
        }
        if (current.equals(NOT_INSTALLED) && desired.equals(INACTIVE)) {
            return List.of(isTheme ? BulkOperationType.THEME_INSTALL : BulkOperationType.PLUGIN_INSTALL);
        }
        if (current.equals(INACTIVE) && desired.equals(ACTIVE)) {
            return List.of(isTheme ? BulkOperationType.THEME_ACTIVATE : BulkOperationType.PLUGIN_ACTIVATE);
        }
        if (current.equals(ACTIVE) && desired.equals(INACTIVE) && !isTheme) {
            return List.of(BulkOperationType.PLUGIN_DEACTIVATE);
        }
        throw new IllegalArgumentException("この状態遷移はサポートされていません: " + current + " → " + desired);
    }

    private List<BulkOperationLog> deleteEverywhere(Long projectId, String slug, Long actorId, boolean isTheme) {
        Project project = getProject(projectId);
        Function<String, List<PluginThemeInfo>> fetcher = isTheme
                ? bulkManagementClient::listThemes
                : bulkManagementClient::listPlugins;
        BulkOperationType deleteType = isTheme ? BulkOperationType.THEME_DELETE : BulkOperationType.PLUGIN_DELETE;

        List<BulkOperationLog> results = new ArrayList<>();
        for (String environment : ENVIRONMENT_ORDER) {
            Site site = resolveOptionalManagedSite(project, environment);
            if (site == null) {
                continue;
            }
            String status = toValue(slug, fetcher.apply(site.getWpSlug())).status();
            if (NOT_INSTALLED.equals(status)) {
                continue;
            }
            results.add(bulkManagementService.applyToEnvironment(
                    projectId, environment, deleteType, slug, null, null, null, null, actorId));
        }
        if (results.isEmpty()) {
            throw new IllegalArgumentException("削除対象が見つかりません: " + slug);
        }
        return results;
    }

    private Site resolveManagedSiteOrThrow(Project project, String environment) {
        Site site = resolveOptionalManagedSite(project, environment);
        if (site == null) {
            throw new IllegalArgumentException(environment + "環境には自動構築サイトが紐付けられていません");
        }
        return site;
    }

    private Site resolveOptionalManagedSite(Project project, String environment) {
        Long siteId = switch (environment) {
            case "local" -> project.getLocalSiteId();
            case "test" -> project.getTestSiteId();
            case "production" -> project.getProductionSiteId();
            default -> null;
        };
        if (siteId == null) {
            return null;
        }
        Site site = siteRepository.findById(siteId).orElse(null);
        return (site != null && site.isManagedWordpress()) ? site : null;
    }

    private Project getProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
    }
}
