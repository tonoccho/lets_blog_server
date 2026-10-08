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
import com.letsblog.publishing.dto.TermComparisonPage;
import com.letsblog.publishing.dto.TermComparisonRow;
import com.letsblog.publishing.dto.TermEnvironmentValue;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/**
 * カテゴリ・タグを3環境(ローカル/テスト/本番)で横断比較し、マスター環境(Project#masterEnvironment)の
 * 値を基準に、非マスター環境への同期・全環境からの削除をオーケストレーションする。
 * 実際のwp-cli呼び出し・ログ記録は{@link BulkManagementService#applyToEnvironment}に委譲する
 * (1環境=1操作=1ログという既存の粒度をそのまま使う)。
 * 項目の同一性はスラッグ(大文字小文字を無視した完全一致)で判定する。名前は環境間で表記が
 * 揺れうる(空白・全角半角・リネーム等)一方、WordPressの内部識別としてはスラッグが安定しているため
 * (Phase12フィードバックで、名前基準の名寄せだと同一スラッグでも行が分裂する不具合を修正した)。
 * <p>
 * 環境の値取得は{@link #resolveTermsByEnvironment}に集約する: managed(自動構築)サイトは内部エージェント
 * ({@link WordPressBulkManagementClient})経由、非managedサイトはSSH接続情報(transport=SSH)があれば
 * {@link WordPressSshOperations}経由で取得する。SSHのみで解決する環境が複数あり同一ホストを共有している
 * 場合は、{@link WordPressSshOperations#fetchTermsForEnvironments}で1回の接続にまとめる。
 * 取得に失敗した環境は「対象外」ではなく「エラー」として扱い、作業ログにも記録する。
 *
 * <p><b>トランザクション(issue #1124)。</b>このクラス(および{@link BulkManagementService}・
 * {@code PluginThemeComparisonService}・{@code PostComparisonService})には{@code @Transactional}を
 * 付けない。publishing-serviceはDBテーブルを所有せず(プロジェクト/サイト情報はproject-service、
 * BulkOperationLogは非永続の値オブジェクト)、これらのメソッドがDBへ触れることは無い。
 * にもかかわらず{@code @Transactional}を付けると、開始時にHikariCPのコネクションを取得し、
 * HTTP/SSHのリモートI/Oの所要時間だけそれを保持してしまう(2026-09-06/07の障害、issue #1122/#1123)。
 * 将来DB永続化を足す場合は、リモートI/Oの後の短いトランザクション(TransactionTemplate等)に限ること。
 *
 * <p>legacy-apiの{@code com.letsblog.api.service.TermComparisonService}をpublishing-serviceへ
 * 移設したもの(issue #708、Epic #551 C6-2)。
 */
@Service
@Slf4j
public class TermComparisonService {

    private static final List<String> ENVIRONMENT_ORDER = List.of("local", "test", "production");

    private final WordPressBulkManagementClient bulkManagementClient;
    private final BulkManagementService bulkManagementService;
    private final SiteService siteService;
    private final ProjectService projectService;
    private final WordPressSshOperations sshOperations;
    private final ExecutorService environmentFetchExecutor;

    /** 単体テスト用: 本番と同じ上限の専用Executorを内部で作る。 */
    public TermComparisonService(
            WordPressBulkManagementClient bulkManagementClient,
            BulkManagementService bulkManagementService,
            SiteService siteService,
            ProjectService projectService,
            WordPressSshOperations sshOperations) {
        this(bulkManagementClient, bulkManagementService, siteService, projectService, sshOperations,
                EnvironmentFetchExecutorConfig.newExecutor());
    }

    @Autowired
    public TermComparisonService(
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

    public TermComparisonPage listCategoryComparison(Long projectId, int page, int size) {
        return listComparison(projectId, page, size, true);
    }

    public TermComparisonPage listTagComparison(Long projectId, int page, int size) {
        return listComparison(projectId, page, size, false);
    }

    public List<BulkOperationLog> syncCategory(Long projectId, String slug, Long actorId) {
        return sync(projectId, slug, actorId, true);
    }

    public List<BulkOperationLog> syncTag(Long projectId, String slug, Long actorId) {
        return sync(projectId, slug, actorId, false);
    }

    @AuditLog(action = AuditLogAction.CATEGORY_BULK_DELETED, resourceType = "CATEGORY")
    public List<BulkOperationLog> deleteCategoryEverywhere(Long projectId, String slug, Long actorId) {
        return deleteEverywhere(projectId, slug, actorId, true);
    }

    @AuditLog(action = AuditLogAction.TAG_BULK_DELETED, resourceType = "TAG")
    public List<BulkOperationLog> deleteTagEverywhere(Long projectId, String slug, Long actorId) {
        return deleteEverywhere(projectId, slug, actorId, false);
    }

    public List<BulkOperationLog> editCategoryAndSync(
            Long projectId, String oldSlug, String value, String slug, String parentSlug, String description,
            Long actorId) {
        return editAndSync(projectId, oldSlug, value, slug, parentSlug, description, actorId, true);
    }

    public List<BulkOperationLog> editTagAndSync(
            Long projectId, String oldSlug, String value, String slug, String parentSlug, String description,
            Long actorId) {
        return editAndSync(projectId, oldSlug, value, slug, parentSlug, description, actorId, false);
    }

    public List<BulkOperationLog> syncAllCategoriesToMaster(Long projectId, Long actorId) {
        return syncAllToMaster(projectId, actorId, true);
    }

    public List<BulkOperationLog> syncAllTagsToMaster(Long projectId, Long actorId) {
        return syncAllToMaster(projectId, actorId, false);
    }

    private TermComparisonPage listComparison(Long projectId, int page, int size, boolean isCategory) {
        Project project = getProject(projectId);
        List<TermComparisonRow> rows = buildRows(project, isCategory);

        long totalCount = rows.size();
        List<TermComparisonRow> pageItems = rows.stream()
                .skip((long) page * size)
                .limit(size)
                .toList();
        return new TermComparisonPage(pageItems, page, size, totalCount, project.getMasterEnvironment());
    }

    /**
     * ページングする前の全件比較行を組み立てる({@link #listComparison}・{@link #syncAllToMaster}で共用)。
     */
    private List<TermComparisonRow> buildRows(Project project, boolean isCategory) {
        String masterEnvironment = project.getMasterEnvironment();
        Map<String, EnvironmentTerms> termsByEnvironment = resolveTermsByEnvironment(project, isCategory);

        Map<String, String> displayNameByKey = new LinkedHashMap<>();
        Map<String, Map<String, CategoryInfo>> termByKeyAndEnvironment = new LinkedHashMap<>();
        for (String environment : ENVIRONMENT_ORDER) {
            List<CategoryInfo> terms = termsByEnvironment.get(environment).terms();
            if (terms == null) {
                continue;
            }
            for (CategoryInfo term : terms) {
                String key = term.slug().toLowerCase(Locale.ROOT);
                termByKeyAndEnvironment.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(environment, term);
                if (!displayNameByKey.containsKey(key) || environment.equals(masterEnvironment)) {
                    displayNameByKey.put(key, term.name());
                }
            }
        }

        return termByKeyAndEnvironment.entrySet().stream()
                .map(entry -> toRow(displayNameByKey.get(entry.getKey()), entry.getKey(), entry.getValue(), termsByEnvironment))
                .sorted(Comparator.comparing(TermComparisonRow::name, Comparator.naturalOrder()))
                .toList();
    }

    private TermComparisonRow toRow(
            String name, String slug, Map<String, CategoryInfo> byEnvironment,
            Map<String, EnvironmentTerms> termsByEnvironment) {
        return new TermComparisonRow(
                name,
                slug,
                toValue(byEnvironment.get("local"), termsByEnvironment.get("local")),
                toValue(byEnvironment.get("test"), termsByEnvironment.get("test")),
                toValue(byEnvironment.get("production"), termsByEnvironment.get("production")));
    }

    private TermEnvironmentValue toValue(CategoryInfo term, EnvironmentTerms environmentTerms) {
        if (environmentTerms.error()) {
            return TermEnvironmentValue.error(environmentTerms.errorMessage());
        }
        if (environmentTerms.terms() == null) {
            return TermEnvironmentValue.unavailable();
        }
        if (term == null) {
            return TermEnvironmentValue.missing();
        }
        return TermEnvironmentValue.of(term.slug(), term.parentSlug(), term.description());
    }

    private List<BulkOperationLog> sync(Long projectId, String slug, Long actorId, boolean isCategory) {
        Project project = getProject(projectId);
        String masterEnvironment = project.getMasterEnvironment();
        Map<String, EnvironmentTerms> termsByEnvironment = resolveTermsByEnvironment(project, isCategory);

        Map<String, CategoryInfo> byEnvironment = findBySlugFrom(termsByEnvironment, slug);
        CategoryInfo master = byEnvironment.get(masterEnvironment);
        if (master == null) {
            throw new IllegalArgumentException("マスター環境に存在しない項目は同期できません: " + slug);
        }

        BulkOperationType createType = isCategory ? BulkOperationType.CATEGORY_CREATE : BulkOperationType.TAG_CREATE;
        BulkOperationType editType = isCategory ? BulkOperationType.CATEGORY_EDIT : BulkOperationType.TAG_EDIT;

        List<BulkOperationLog> results = new ArrayList<>();
        for (String environment : ENVIRONMENT_ORDER) {
            if (environment.equals(masterEnvironment)) {
                continue;
            }
            if (termsByEnvironment.get(environment).terms() == null) {
                continue;
            }
            CategoryInfo existing = byEnvironment.get(environment);
            if (existing != null) {
                results.add(bulkManagementService.applyToEnvironment(projectId, environment, editType,
                        master.name(), master.slug(), master.parentSlug(), master.description(),
                        existing.slug(), actorId));
            } else {
                results.add(bulkManagementService.applyToEnvironment(projectId, environment, createType,
                        master.name(), master.slug(), master.parentSlug(), master.description(),
                        null, actorId));
            }
        }
        return results;
    }

    /**
     * マスター環境の項目を新しい値へ更新し、続けて他の非マスター環境にも同じ新しい値を反映する
     * (「編集」と「マスターへの同期」を1回の操作にまとめたもの)。マスター環境にまだ項目が
     * 存在しない場合(他の環境にのみ存在する項目)は、マスター環境には新規作成する。
     */
    private List<BulkOperationLog> editAndSync(
            Long projectId, String oldSlug, String value, String slug, String parentSlug, String description,
            Long actorId, boolean isCategory) {
        Project project = getProject(projectId);
        String masterEnvironment = project.getMasterEnvironment();
        Map<String, EnvironmentTerms> termsByEnvironment = resolveTermsByEnvironment(project, isCategory);

        Map<String, CategoryInfo> byEnvironment = findBySlugFrom(termsByEnvironment, oldSlug);
        CategoryInfo master = byEnvironment.get(masterEnvironment);

        BulkOperationType createType = isCategory ? BulkOperationType.CATEGORY_CREATE : BulkOperationType.TAG_CREATE;
        BulkOperationType editType = isCategory ? BulkOperationType.CATEGORY_EDIT : BulkOperationType.TAG_EDIT;

        List<BulkOperationLog> results = new ArrayList<>();
        if (master != null) {
            results.add(bulkManagementService.applyToEnvironment(projectId, masterEnvironment, editType,
                    value, slug, parentSlug, description, master.slug(), actorId));
        } else {
            results.add(bulkManagementService.applyToEnvironment(projectId, masterEnvironment, createType,
                    value, slug, parentSlug, description, null, actorId));
        }

        for (String environment : ENVIRONMENT_ORDER) {
            if (environment.equals(masterEnvironment)) {
                continue;
            }
            if (termsByEnvironment.get(environment).terms() == null) {
                continue;
            }
            CategoryInfo existing = byEnvironment.get(environment);
            if (existing != null) {
                results.add(bulkManagementService.applyToEnvironment(projectId, environment, editType,
                        value, slug, parentSlug, description, existing.slug(), actorId));
            } else {
                results.add(bulkManagementService.applyToEnvironment(projectId, environment, createType,
                        value, slug, parentSlug, description, null, actorId));
            }
        }
        return results;
    }

    /**
     * マスター環境と異なる(または非マスター環境に存在しない)項目をすべて洗い出し、既存の{@link #sync}を
     * 項目ごとに呼び出してマスターへ一括で揃える。マスター環境に存在しない項目(非マスターのみに存在)は対象外。
     */
    private List<BulkOperationLog> syncAllToMaster(Long projectId, Long actorId, boolean isCategory) {
        Project project = getProject(projectId);
        String masterEnvironment = project.getMasterEnvironment();

        List<TermComparisonRow> rows = buildRows(project, isCategory);
        List<BulkOperationLog> results = new ArrayList<>();
        for (TermComparisonRow row : rows) {
            TermEnvironmentValue masterValue = valueOf(row, masterEnvironment);
            if (!masterValue.available() || masterValue.slug() == null) {
                continue;
            }
            if (needsSync(row, masterEnvironment)) {
                results.addAll(sync(projectId, row.slug(), actorId, isCategory));
            }
        }
        return results;
    }

    private boolean needsSync(TermComparisonRow row, String masterEnvironment) {
        TermEnvironmentValue masterValue = valueOf(row, masterEnvironment);
        for (String environment : ENVIRONMENT_ORDER) {
            if (environment.equals(masterEnvironment)) {
                continue;
            }
            TermEnvironmentValue value = valueOf(row, environment);
            if (!value.available()) {
                continue;
            }
            if (!Objects.equals(value.slug(), masterValue.slug())
                    || !Objects.equals(value.parentSlug(), masterValue.parentSlug())
                    || !Objects.equals(value.description(), masterValue.description())) {
                return true;
            }
        }
        return false;
    }

    private List<BulkOperationLog> deleteEverywhere(Long projectId, String slug, Long actorId, boolean isCategory) {
        Project project = getProject(projectId);
        Map<String, CategoryInfo> byEnvironment = findBySlugFrom(resolveTermsByEnvironment(project, isCategory), slug);
        BulkOperationType deleteType = isCategory ? BulkOperationType.CATEGORY_DELETE : BulkOperationType.TAG_DELETE;

        List<BulkOperationLog> results = new ArrayList<>();
        for (String environment : ENVIRONMENT_ORDER) {
            CategoryInfo existing = byEnvironment.get(environment);
            if (existing == null) {
                continue;
            }
            results.add(bulkManagementService.applyToEnvironment(projectId, environment, deleteType,
                    null, null, null, null, existing.slug(), actorId));
        }
        if (results.isEmpty()) {
            throw new IllegalArgumentException("削除対象が見つかりません: " + slug);
        }
        return results;
    }

    private TermEnvironmentValue valueOf(TermComparisonRow row, String environment) {
        return switch (environment) {
            case "local" -> row.local();
            case "test" -> row.test();
            case "production" -> row.production();
            default -> throw new IllegalArgumentException("unknown environment: " + environment);
        };
    }

    private Map<String, CategoryInfo> findBySlugFrom(Map<String, EnvironmentTerms> termsByEnvironment, String slug) {
        Map<String, CategoryInfo> result = new LinkedHashMap<>();
        for (String environment : ENVIRONMENT_ORDER) {
            List<CategoryInfo> terms = termsByEnvironment.get(environment).terms();
            if (terms == null) {
                continue;
            }
            terms.stream()
                    .filter(term -> term.slug().equalsIgnoreCase(slug))
                    .findFirst()
                    .ifPresent(term -> result.put(environment, term));
        }
        return result;
    }

    /**
     * 3環境分のカテゴリ/タグ一覧を、環境ごとの経路(managed=内部エージェント、非managedはSSH)で
     * 解決する。SSHのみで解決する環境が複数あり同一ホスト(sshHost:sshPort)を共有している場合は、
     * ホストごとにまとめて{@link WordPressSshOperations#fetchTermsForEnvironments}で1回の接続にする。
     * 取得に失敗した環境は{@link EnvironmentTerms#error}にし、作業ログにも記録する。
     */
    private Map<String, EnvironmentTerms> resolveTermsByEnvironment(Project project, boolean isCategory) {
        Map<String, EnvironmentTerms> result = new LinkedHashMap<>();
        Map<String, Map<String, CmsCredentials.WordPressCredentials>> sshGroupsByHost = new LinkedHashMap<>();
        Map<String, CompletableFuture<EnvironmentTerms>> agentFetches = new LinkedHashMap<>();

        for (String environment : ENVIRONMENT_ORDER) {
            Site site = resolveSite(project, environment);
            if (site == null) {
                result.put(environment, EnvironmentTerms.unavailable());
                continue;
            }
            if (site.isManagedWordpress()) {
                // 取得は並列に走らせ、環境の並び(LinkedHashMapの挿入順)は従来どおりここで確定させる(issue #1474)
                result.put(environment, null);
                agentFetches.put(environment,
                        CompletableFuture.supplyAsync(() -> fetchViaAgent(project, environment, site, isCategory), environmentFetchExecutor));
                continue;
            }
            SiteService.SiteDataSource dataSource = siteService.resolveDataSource(site);
            if (dataSource.hasSsh()) {
                String hostKey = hostKeyOf(dataSource.sshCredentials());
                sshGroupsByHost.computeIfAbsent(hostKey, k -> new LinkedHashMap<>())
                        .put(environment, dataSource.sshCredentials());
                continue;
            }
            result.put(environment, EnvironmentTerms.unavailable());
        }

        for (Map<String, CmsCredentials.WordPressCredentials> group : sshGroupsByHost.values()) {
            WordPressSshOperations.EnvironmentFetchResult<WordPressSshOperations.CategoryInfo> fetchResult =
                    sshOperations.fetchTermsForEnvironments(isCategory ? "category" : "post_tag", group);
            for (String environment : group.keySet()) {
                String error = fetchResult.errorByEnvironment().get(environment);
                if (error != null) {
                    logFetchError(project, environment, isCategory, error, fetchResult.stackTraceByEnvironment().get(environment));
                    result.put(environment, EnvironmentTerms.error(error));
                } else {
                    List<CategoryInfo> infos = fetchResult.byEnvironment()
                            .getOrDefault(environment, List.of()).stream()
                            .map(info -> new CategoryInfo(info.name(), info.slug(), info.parentSlug(), info.description()))
                            .toList();
                    result.put(environment, EnvironmentTerms.of(infos));
                }
            }
        }
        // SSHの取得中にagent側も並行して進んでいる。既存キーへのputなので挿入順は変わらない
        agentFetches.forEach((environment, future) -> result.put(environment, awaitFetch(future)));
        return result;
    }

    /** 並列化前と同じく、取得中の実行時例外はラップせずそのまま呼び出し元へ伝える。 */
    private EnvironmentTerms awaitFetch(CompletableFuture<EnvironmentTerms> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    /** agent経由の取得失敗は空一覧にせず、SSH経路と同じくerror値にして作業ログへ記録する(#1682)。 */
    private EnvironmentTerms fetchViaAgent(Project project, String environment, Site site, boolean isCategory) {
        try {
            List<WordPressBulkManagementClient.CategoryInfo> infos = isCategory
                    ? bulkManagementClient.listCategories(site.getWpSlug())
                    : bulkManagementClient.listTags(site.getWpSlug());
            return EnvironmentTerms.of(infos.stream()
                    .map(info -> new CategoryInfo(info.name(), info.slug(), info.parentSlug(), info.description()))
                    .toList());
        } catch (RestClientException e) {
            logFetchError(project, environment, isCategory, e.getMessage(), StackTraceUtil.toString(e));
            return EnvironmentTerms.error(e.getMessage());
        }
    }

    private String hostKeyOf(CmsCredentials.WordPressCredentials creds) {
        return creds.sshHost() + ":" + (creds.sshPort() != null ? creds.sshPort() : 22);
    }

    private void logFetchError(
            Project project, String environment, boolean isCategory, String message, String stackTrace) {
        log.warn("{}一覧取得に失敗しました(project={}, environment={}): {}",
                isCategory ? "カテゴリ" : "タグ", project.getId(), environment, message);
        bulkManagementService.logFetchFailure(project.getId(),
                isCategory ? BulkOperationType.CATEGORY_FETCH : BulkOperationType.TAG_FETCH, environment, message,
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

    private record CategoryInfo(String name, String slug, String parentSlug, String description) {
    }

    /**
     * 1環境分の取得結果。terms=null かつ error=false は「対象外」、terms=null かつ error=true は
     * 「取得を試みて失敗」を表す(TermEnvironmentValueへの変換時にこの2つを区別する)。
     */
    private record EnvironmentTerms(List<CategoryInfo> terms, boolean error, String errorMessage) {
        static EnvironmentTerms unavailable() {
            return new EnvironmentTerms(null, false, null);
        }

        static EnvironmentTerms error(String errorMessage) {
            return new EnvironmentTerms(null, true, errorMessage);
        }

        static EnvironmentTerms of(List<CategoryInfo> terms) {
            return new EnvironmentTerms(terms, false, null);
        }
    }
}
