package com.letsblog.publishing.service;

import com.letsblog.common.util.StackTraceUtil;
import com.letsblog.publishing.aop.AuditLog;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.config.EnvironmentFetchExecutorConfig;
import com.letsblog.publishing.cms.ssh.WordPressSshOperations;
import com.letsblog.publishing.domain.AuditLogAction;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.domain.Project;
import com.letsblog.publishing.domain.Site;
import com.letsblog.publishing.dto.ReconcileStateRequest.StateChangeRequest;
import com.letsblog.publishing.dto.StatusComparisonPage;
import com.letsblog.publishing.dto.StatusComparisonRow;
import com.letsblog.publishing.dto.StatusEnvironmentValue;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * プラグイン・テーマを3環境(ローカル/テスト/本番)で横断比較し、行(slug)ごとに
 * 環境単位で希望状態(未インストール/無効/有効)へ反映する({@link #reconcilePlugin}/{@link #reconcileTheme})、
 * または全環境から削除する({@link #deletePluginEverywhere}/{@link #deleteThemeEverywhere})。
 * カテゴリ/タグの{@link TermComparisonService}と異なり、slugそのものが環境間で共通のwordpress.org識別子のため
 * 名寄せの曖昧さはない。実際のwp-cli呼び出し・ログ記録は{@link BulkManagementService#applyToEnvironment}に
 * 委譲する。
 * <p>
 * 読み取り(一覧取得)は{@link #resolveInfosByEnvironment}に集約する。managedサイトは内部エージェント、
 * 非managedサイトはSSH経由で取得する。SSHのみで解決する環境が同一ホストを共有していれば
 * {@link WordPressSshOperations#fetchPluginsOrThemesForEnvironments}で1回の接続にまとめる。
 * 取得に失敗した環境は「対象外」ではなく「エラー」として扱い、作業ログにも記録する。
 *
 * <p>legacy-apiの{@code com.letsblog.api.service.PluginThemeComparisonService}をpublishing-serviceへ
 * 移設したもの(issue #708、Epic #551 C6-2)。
 */
@Service
@Slf4j
public class PluginThemeComparisonService {

    private static final List<String> ENVIRONMENT_ORDER = List.of("local", "test", "production");
    private static final String NOT_INSTALLED = "NOT_INSTALLED";
    private static final String INACTIVE = "INACTIVE";
    private static final String ACTIVE = "ACTIVE";

    private final WordPressBulkManagementClient bulkManagementClient;
    private final BulkManagementService bulkManagementService;
    private final SiteService siteService;
    private final ProjectService projectService;
    private final WordPressSshOperations sshOperations;
    private final ExecutorService environmentFetchExecutor;

    /** 単体テスト用: 本番と同じ上限の専用Executorを内部で作る。 */
    public PluginThemeComparisonService(
            WordPressBulkManagementClient bulkManagementClient,
            BulkManagementService bulkManagementService,
            SiteService siteService,
            ProjectService projectService,
            WordPressSshOperations sshOperations) {
        this(bulkManagementClient, bulkManagementService, siteService, projectService, sshOperations,
                EnvironmentFetchExecutorConfig.newExecutor());
    }

    @Autowired
    public PluginThemeComparisonService(
            WordPressBulkManagementClient bulkManagementClient,
            BulkManagementService bulkManagementService,
            SiteService siteService,
            ProjectService projectService,
            WordPressSshOperations sshOperations,
            ExecutorService environmentFetchExecutor) {
        this.environmentFetchExecutor = environmentFetchExecutor;
        this.bulkManagementClient = bulkManagementClient;
        this.bulkManagementService = bulkManagementService;
        this.siteService = siteService;
        this.projectService = projectService;
        this.sshOperations = sshOperations;
    }

    public StatusComparisonPage listPluginComparison(Long projectId, int page, int size) {
        return listComparison(projectId, page, size, false);
    }

    public StatusComparisonPage listThemeComparison(Long projectId, int page, int size) {
        return listComparison(projectId, page, size, true);
    }

    public List<BulkOperationLog> reconcilePlugin(
            Long projectId, String slug, List<StateChangeRequest> changes, Long actorId) {
        return reconcile(projectId, slug, changes, actorId, false);
    }

    public List<BulkOperationLog> reconcileTheme(
            Long projectId, String slug, List<StateChangeRequest> changes, Long actorId) {
        return reconcile(projectId, slug, changes, actorId, true);
    }

    @AuditLog(action = AuditLogAction.PLUGIN_BULK_DELETED, resourceType = "PLUGIN")
    public List<BulkOperationLog> deletePluginEverywhere(Long projectId, String slug, Long actorId) {
        return deleteEverywhere(projectId, slug, actorId, false);
    }

    @AuditLog(action = AuditLogAction.THEME_BULK_DELETED, resourceType = "THEME")
    public List<BulkOperationLog> deleteThemeEverywhere(Long projectId, String slug, Long actorId) {
        return deleteEverywhere(projectId, slug, actorId, true);
    }

    private StatusComparisonPage listComparison(Long projectId, int page, int size, boolean isTheme) {
        Project project = getProject(projectId);
        String masterEnvironment = project.getMasterEnvironment();
        Map<String, EnvironmentInfos> byEnvironment = resolveInfosByEnvironment(project, isTheme);

        Set<String> slugs = new LinkedHashSet<>();
        for (EnvironmentInfos envInfos : byEnvironment.values()) {
            if (envInfos.infos() == null) {
                continue;
            }
            envInfos.infos().forEach(info -> slugs.add(info.name()));
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

    private StatusComparisonRow toRow(String slug, Map<String, EnvironmentInfos> byEnvironment) {
        return new StatusComparisonRow(
                slug,
                toValue(slug, byEnvironment.get("local")),
                toValue(slug, byEnvironment.get("test")),
                toValue(slug, byEnvironment.get("production")));
    }

    private StatusEnvironmentValue toValue(String slug, EnvironmentInfos envInfos) {
        if (envInfos.error()) {
            return StatusEnvironmentValue.error(envInfos.errorMessage());
        }
        if (envInfos.infos() == null) {
            return StatusEnvironmentValue.unavailable();
        }
        return envInfos.infos().stream()
                .filter(info -> info.name().equals(slug))
                .findFirst()
                .map(info -> StatusEnvironmentValue.of("active".equalsIgnoreCase(info.status()) ? ACTIVE : INACTIVE))
                .orElse(StatusEnvironmentValue.of(NOT_INSTALLED));
    }

    private List<BulkOperationLog> reconcile(
            Long projectId, String slug, List<StateChangeRequest> changes, Long actorId, boolean isTheme) {
        Project project = getProject(projectId);
        Map<String, EnvironmentInfos> byEnvironment = resolveInfosByEnvironment(project, isTheme);

        // 先に全変更を検証する。対象外の環境・サポート外の遷移は、どの環境にも書き込む前に
        // IllegalArgumentExceptionで返す(並列実行では「途中まで反映済み」を作らないため)
        Map<String, List<ReconcilePlan>> plansByEnvironment = new LinkedHashMap<>();
        int index = 0;
        for (StateChangeRequest change : changes) {
            EnvironmentInfos envInfos = byEnvironment.get(change.environment());
            if (envInfos == null || envInfos.infos() == null) {
                throw new IllegalArgumentException(
                        change.environment() + "環境は対象外です(自動構築サイト・SSHのいずれも利用できません)");
            }
            String currentStatus = toValue(slug, envInfos).status();
            plansByEnvironment.computeIfAbsent(change.environment(), k -> new ArrayList<>())
                    .add(new ReconcilePlan(index++, change.environment(),
                            stepsFor(currentStatus, change.desiredStatus(), isTheme)));
        }

        // 環境間は並列、環境内(段階の順、同じ環境を複数指定された場合の変更の順)は直列(issue #1687)
        List<Supplier<Map<Integer, List<BulkOperationLog>>>> tasks = new ArrayList<>();
        for (List<ReconcilePlan> plans : plansByEnvironment.values()) {
            tasks.add(() -> {
                Map<Integer, List<BulkOperationLog>> logsByChange = new TreeMap<>();
                for (ReconcilePlan plan : plans) {
                    List<BulkOperationLog> logs = new ArrayList<>();
                    for (BulkOperationType step : plan.steps()) {
                        logs.add(bulkManagementService.applyToEnvironment(
                                projectId, plan.environment(), step, slug, null, null, null, null, actorId));
                    }
                    logsByChange.put(plan.index(), logs);
                }
                return logsByChange;
            });
        }
        // 結果ログは要求のchangesの順に並べる
        Map<Integer, List<BulkOperationLog>> ordered = new TreeMap<>();
        EnvironmentTasks.runAll(environmentFetchExecutor, tasks).forEach(ordered::putAll);
        List<BulkOperationLog> results = new ArrayList<>();
        ordered.values().forEach(results::addAll);
        return results;
    }

    private record ReconcilePlan(int index, String environment, List<BulkOperationType> steps) {
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
        Map<String, EnvironmentInfos> byEnvironment = resolveInfosByEnvironment(project, isTheme);
        BulkOperationType deleteType = isTheme ? BulkOperationType.THEME_DELETE : BulkOperationType.PLUGIN_DELETE;

        // 環境ごとの削除は独立なので並列に走らせる(issue #1687)。結果はENVIRONMENT_ORDER順
        List<Supplier<BulkOperationLog>> tasks = new ArrayList<>();
        for (String environment : ENVIRONMENT_ORDER) {
            EnvironmentInfos envInfos = byEnvironment.get(environment);
            if (envInfos.infos() == null) {
                continue;
            }
            String status = toValue(slug, envInfos).status();
            if (NOT_INSTALLED.equals(status)) {
                continue;
            }
            tasks.add(() -> bulkManagementService.applyToEnvironment(
                    projectId, environment, deleteType, slug, null, null, null, null, actorId));
        }
        List<BulkOperationLog> results = EnvironmentTasks.runAll(environmentFetchExecutor, tasks);
        if (results.isEmpty()) {
            throw new IllegalArgumentException("削除対象が見つかりません: " + slug);
        }
        return results;
    }

    /**
     * 3環境分のプラグイン/テーマ一覧を、環境ごとの経路(managed=内部エージェント、非managedはSSH)で
     * 解決する。SSHのみで解決する環境が複数あり同一ホスト(sshHost:sshPort)を共有している場合は、
     * ホストごとにまとめて{@link WordPressSshOperations#fetchPluginsOrThemesForEnvironments}で
     * 1回の接続にする。取得に失敗した環境は{@link EnvironmentInfos#error}にし、作業ログにも記録する。
     */
    private Map<String, EnvironmentInfos> resolveInfosByEnvironment(Project project, boolean isTheme) {
        Map<String, EnvironmentInfos> result = new LinkedHashMap<>();
        Map<String, Map<String, CmsCredentials.WordPressCredentials>> sshGroupsByHost = new LinkedHashMap<>();
        Map<String, CompletableFuture<EnvironmentInfos>> agentFetches = new LinkedHashMap<>();

        // 投入後に後続の処理が例外で中断しても、投入済みのagent取得を待ち終えてから例外を伝える
        try {
            for (String environment : ENVIRONMENT_ORDER) {
                Site site = resolveSite(project, environment);
                if (site == null) {
                    result.put(environment, EnvironmentInfos.unavailable());
                    continue;
                }
                if (site.isManagedWordpress()) {
                    // 取得は並列に走らせ、環境の並び(LinkedHashMapの挿入順)はここで確定させる(issue #1687。#1474と同じ方針)
                    result.put(environment, null);
                    agentFetches.put(environment,
                            EnvironmentTasks.submit(environmentFetchExecutor, () -> fetchViaAgent(project, environment, site, isTheme)));
                    continue;
                }
                SiteService.SiteDataSource dataSource = siteService.resolveDataSource(site);
                if (dataSource.hasSsh()) {
                    String hostKey = hostKeyOf(dataSource.sshCredentials());
                    sshGroupsByHost.computeIfAbsent(hostKey, k -> new LinkedHashMap<>())
                            .put(environment, dataSource.sshCredentials());
                    continue;
                }
                result.put(environment, EnvironmentInfos.unavailable());
            }

            for (Map<String, CmsCredentials.WordPressCredentials> group : sshGroupsByHost.values()) {
                WordPressSshOperations.EnvironmentFetchResult<WordPressSshOperations.PluginThemeInfo> fetchResult =
                        sshOperations.fetchPluginsOrThemesForEnvironments(isTheme ? "theme" : "plugin", group);
                for (String environment : group.keySet()) {
                    String error = fetchResult.errorByEnvironment().get(environment);
                    if (error != null) {
                        logFetchError(project, environment, isTheme, error, fetchResult.stackTraceByEnvironment().get(environment));
                        result.put(environment, EnvironmentInfos.error(error));
                    } else {
                        List<PluginThemeInfo> infos = fetchResult.byEnvironment()
                                .getOrDefault(environment, List.of()).stream()
                                .map(info -> new PluginThemeInfo(info.name(), info.status()))
                                .toList();
                        result.put(environment, EnvironmentInfos.of(infos));
                    }
                }
            }
            // SSHの取得中にagent側も並行して進んでいる。既存キーへのputなので挿入順は変わらない
            List<String> agentEnvironments = new ArrayList<>(agentFetches.keySet());
            List<EnvironmentInfos> agentResults = EnvironmentTasks.awaitAll(new ArrayList<>(agentFetches.values()));
            for (int i = 0; i < agentEnvironments.size(); i++) {
                result.put(agentEnvironments.get(i), agentResults.get(i));
            }
        } catch (RuntimeException | Error e) {
            EnvironmentTasks.awaitQuietly(agentFetches.values());
            throw e;
        }
        return result;
    }

    /** agent経由の取得失敗は空一覧にせず、SSH経路と同じくerror値にして作業ログへ記録する(#1682)。 */
    private EnvironmentInfos fetchViaAgent(Project project, String environment, Site site, boolean isTheme) {
        try {
            List<WordPressBulkManagementClient.PluginThemeInfo> infos = isTheme
                    ? bulkManagementClient.listThemes(site.getWpSlug())
                    : bulkManagementClient.listPlugins(site.getWpSlug());
            return EnvironmentInfos.of(infos.stream()
                    .map(info -> new PluginThemeInfo(info.name(), info.status()))
                    .toList());
        } catch (RestClientException e) {
            logFetchError(project, environment, isTheme, e.getMessage(), StackTraceUtil.toString(e));
            return EnvironmentInfos.error(e.getMessage());
        }
    }

    private String hostKeyOf(CmsCredentials.WordPressCredentials creds) {
        return creds.sshHost() + ":" + (creds.sshPort() != null ? creds.sshPort() : 22);
    }

    private void logFetchError(
            Project project, String environment, boolean isTheme, String message, String stackTrace) {
        log.warn("{}一覧取得に失敗しました(project={}, environment={}): {}",
                isTheme ? "テーマ" : "プラグイン", project.getId(), environment, message);
        bulkManagementService.logFetchFailure(project.getId(),
                isTheme ? BulkOperationType.THEME_FETCH : BulkOperationType.PLUGIN_FETCH, environment, message,
                stackTrace);
    }

    /**
     * 環境に紐付いたサイトを、managed/非managedを問わず返す(紐付けなしはnull)。
     */
    private Site resolveSite(Project project, String environment) {
        Long siteId = switch (environment) {
            case "local" -> project.getLocalSiteId();
            case "test" -> project.getTestSiteId();
            case "production" -> project.getProductionSiteId();
            default -> null;
        };
        return siteId == null ? null : siteService.getById(siteId).orElse(null);
    }

    private Project getProject(Long projectId) {
        return projectService.getProjectEntity(projectId);
    }

    private record PluginThemeInfo(String name, String status) {
    }

    /**
     * 1環境分の取得結果。infos=null かつ error=false は「対象外」、infos=null かつ error=true は
     * 「取得を試みて失敗」を表す(StatusEnvironmentValueへの変換時にこの2つを区別する)。
     */
    private record EnvironmentInfos(List<PluginThemeInfo> infos, boolean error, String errorMessage) {
        static EnvironmentInfos unavailable() {
            return new EnvironmentInfos(null, false, null);
        }

        static EnvironmentInfos error(String errorMessage) {
            return new EnvironmentInfos(null, true, errorMessage);
        }

        static EnvironmentInfos of(List<PluginThemeInfo> infos) {
            return new EnvironmentInfos(infos, false, null);
        }
    }
}
