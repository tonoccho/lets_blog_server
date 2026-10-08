package com.letsblog.publishing.service;

import com.letsblog.publishing.aop.AuditLog;
import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.CmsPostSummary;
import com.letsblog.publishing.config.EnvironmentFetchExecutorConfig;
import com.letsblog.publishing.domain.AuditLogAction;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.domain.Project;
import com.letsblog.publishing.domain.Site;
import com.letsblog.publishing.dto.PostComparisonPage;
import com.letsblog.publishing.dto.PostComparisonRow;
import com.letsblog.publishing.dto.PostEnvironmentValue;
import com.letsblog.common.util.StackTraceUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * 投稿(post)/固定ページ(page)を3環境(ローカル/テスト/本番)で横断比較し、行(slug)ごとに
 * 全環境から削除する({@link #deleteEverywhere})、またはステータスを変更する({@link #updateStatusEverywhere})。
 * VSCode拡張のfront matter経由で各環境に同一slugで投稿される前提のため、
 * {@link TermComparisonService}と同じくslugで名寄せする。
 * <p>
 * カテゴリ/タグ/プラグイン/テーマと異なり、一覧取得(listPosts)はCmsAdapter側で
 * managed/SSH/REST の3トランスポートをすでに解決しているため、本サービスはサイトごとに
 * CmsAdapterへ委譲するだけでよい(SSHホスト単位のバッチ化のような最適化は行わない)。
 * 削除・ステータス変更の実行とログ記録は{@link BulkManagementService}に委譲する。
 *
 * <p>legacy-apiの{@code com.letsblog.api.service.PostComparisonService}をpublishing-serviceへ
 * 移設したもの(issue #708、Epic #551 C6-2)。
 */
@Service
@Slf4j
public class PostComparisonService {

    private static final List<String> ENVIRONMENT_ORDER = List.of("local", "test", "production");

    private final SiteService siteService;
    private final ProjectService projectService;
    private final CmsAdapterFactory cmsAdapterFactory;
    private final BulkManagementService bulkManagementService;
    private final ExecutorService environmentFetchExecutor;

    /** 単体テスト用: 本番と同じ上限の専用Executorを内部で作る。 */
    public PostComparisonService(
            SiteService siteService,
            ProjectService projectService,
            CmsAdapterFactory cmsAdapterFactory,
            BulkManagementService bulkManagementService) {
        this(siteService, projectService, cmsAdapterFactory, bulkManagementService,
                EnvironmentFetchExecutorConfig.newExecutor());
    }

    @Autowired
    public PostComparisonService(
            SiteService siteService,
            ProjectService projectService,
            CmsAdapterFactory cmsAdapterFactory,
            BulkManagementService bulkManagementService,
            ExecutorService environmentFetchExecutor) {
        this.environmentFetchExecutor = environmentFetchExecutor;
        this.siteService = siteService;
        this.projectService = projectService;
        this.cmsAdapterFactory = cmsAdapterFactory;
        this.bulkManagementService = bulkManagementService;
    }

    public PostComparisonPage listComparison(Long projectId, String postType, int page, int size) {
        Project project = getProject(projectId);
        Map<String, EnvironmentPosts> byEnvironment = resolvePostsByEnvironment(project, postType);

        Set<String> slugs = new LinkedHashSet<>();
        for (EnvironmentPosts envPosts : byEnvironment.values()) {
            if (envPosts.posts() == null) {
                continue;
            }
            envPosts.posts().forEach(p -> slugs.add(p.slug()));
        }

        List<PostComparisonRow> rows = slugs.stream()
                .map(slug -> toRow(slug, byEnvironment))
                .sorted(Comparator.comparing(PostComparisonRow::slug))
                .toList();

        long totalCount = rows.size();
        List<PostComparisonRow> pageItems = rows.stream()
                .skip((long) page * size)
                .limit(size)
                .toList();
        return new PostComparisonPage(pageItems, page, size, totalCount, postType);
    }

    @AuditLog(action = AuditLogAction.POST_BULK_DELETED, resourceType = "POST")
    public List<BulkOperationLog> deleteEverywhere(Long projectId, String postType, String slug, Long actorId) {
        Project project = getProject(projectId);
        Map<String, EnvironmentPosts> byEnvironment = resolvePostsByEnvironment(project, postType);

        // 環境ごとの削除は独立なので並列に走らせる(issue #1687)。結果はENVIRONMENT_ORDER順
        List<Supplier<BulkOperationLog>> tasks = new ArrayList<>();
        for (String environment : ENVIRONMENT_ORDER) {
            EnvironmentPosts envPosts = byEnvironment.get(environment);
            if (envPosts == null || envPosts.posts() == null) {
                continue;
            }
            CmsPostSummary match = findBySlug(envPosts.posts(), slug);
            if (match == null) {
                continue;
            }
            Site site = resolveSite(project, environment);
            tasks.add(() -> bulkManagementService.deletePostAtEnvironment(
                    projectId, environment, site, match.id(), postType, slug, actorId));
        }
        List<BulkOperationLog> results = EnvironmentTasks.runAll(environmentFetchExecutor, tasks);
        if (results.isEmpty()) {
            throw new IllegalArgumentException("削除対象が見つかりません: " + slug);
        }
        return results;
    }

    public List<BulkOperationLog> updateStatusEverywhere(
            Long projectId, String postType, String slug, String newStatus, Long actorId) {
        Project project = getProject(projectId);
        Map<String, EnvironmentPosts> byEnvironment = resolvePostsByEnvironment(project, postType);

        List<BulkOperationLog> results = new ArrayList<>();
        for (String environment : ENVIRONMENT_ORDER) {
            EnvironmentPosts envPosts = byEnvironment.get(environment);
            if (envPosts == null || envPosts.posts() == null) {
                continue;
            }
            CmsPostSummary match = findBySlug(envPosts.posts(), slug);
            if (match == null) {
                continue;
            }
            Site site = resolveSite(project, environment);
            results.add(bulkManagementService.updatePostStatusAtEnvironment(
                    projectId, environment, site, match.id(), postType, slug, newStatus, actorId));
        }
        if (results.isEmpty()) {
            throw new IllegalArgumentException("対象が見つかりません: " + slug);
        }
        return results;
    }

    private CmsPostSummary findBySlug(List<CmsPostSummary> posts, String slug) {
        return posts.stream().filter(p -> p.slug().equals(slug)).findFirst().orElse(null);
    }

    private PostComparisonRow toRow(String slug, Map<String, EnvironmentPosts> byEnvironment) {
        return new PostComparisonRow(
                slug,
                toValue(slug, byEnvironment.get("local")),
                toValue(slug, byEnvironment.get("test")),
                toValue(slug, byEnvironment.get("production")));
    }

    private PostEnvironmentValue toValue(String slug, EnvironmentPosts envPosts) {
        if (envPosts.error()) {
            return PostEnvironmentValue.error(envPosts.errorMessage());
        }
        if (envPosts.posts() == null) {
            return PostEnvironmentValue.unavailable();
        }
        CmsPostSummary match = findBySlug(envPosts.posts(), slug);
        return match != null
                ? PostEnvironmentValue.of(match.id(), match.title(), match.status())
                : PostEnvironmentValue.notFound();
    }

    /**
     * 3環境分の投稿/ページ一覧を取得する。取得に失敗した環境は{@link EnvironmentPosts#error}にし、
     * 作業ログにも記録する(TermComparisonService/PluginThemeComparisonServiceと同じ方針)。
     */
    private Map<String, EnvironmentPosts> resolvePostsByEnvironment(Project project, String postType) {
        // 環境ごとの取得は独立なので並列に走らせる(issue #1687)。環境の並びは先に確定させる
        Map<String, EnvironmentPosts> result = new LinkedHashMap<>();
        Map<String, CompletableFuture<EnvironmentPosts>> fetches = new LinkedHashMap<>();
        // 投入後に後続の環境の解決が例外で中断しても、投入済みの取得を待ち終えてから例外を伝える
        try {
            for (String environment : ENVIRONMENT_ORDER) {
                Site site = resolveSite(project, environment);
                if (site == null) {
                    result.put(environment, EnvironmentPosts.unavailable());
                    continue;
                }
                result.put(environment, null);
                fetches.put(environment, EnvironmentTasks.submit(environmentFetchExecutor,
                        () -> fetchPosts(project, environment, site, postType)));
            }
            List<String> fetched = new ArrayList<>(fetches.keySet());
            List<EnvironmentPosts> fetchResults = EnvironmentTasks.awaitAll(new ArrayList<>(fetches.values()));
            for (int i = 0; i < fetched.size(); i++) {
                result.put(fetched.get(i), fetchResults.get(i));
            }
        } catch (RuntimeException | Error e) {
            EnvironmentTasks.awaitQuietly(fetches.values());
            throw e;
        }
        return result;
    }

    private EnvironmentPosts fetchPosts(Project project, String environment, Site site, String postType) {
        try {
            CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
            CmsAdapter adapter = cmsAdapterFactory.resolve(credentials.cmsType());
            return EnvironmentPosts.of(adapter.listPosts(credentials, postType));
        } catch (RuntimeException e) {
            log.warn("投稿/ページ一覧取得に失敗しました(project={}, environment={}): {}",
                    project.getId(), environment, e.getMessage());
            bulkManagementService.logFetchFailure(
                    project.getId(), BulkOperationType.POST_FETCH, environment, e.getMessage(),
                    StackTraceUtil.toString(e));
            return EnvironmentPosts.error(e.getMessage());
        }
    }

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

    private record EnvironmentPosts(List<CmsPostSummary> posts, boolean error, String errorMessage) {
        static EnvironmentPosts unavailable() {
            return new EnvironmentPosts(null, false, null);
        }

        static EnvironmentPosts error(String errorMessage) {
            return new EnvironmentPosts(null, true, errorMessage);
        }

        static EnvironmentPosts of(List<CmsPostSummary> posts) {
            return new EnvironmentPosts(posts, false, null);
        }
    }
}
