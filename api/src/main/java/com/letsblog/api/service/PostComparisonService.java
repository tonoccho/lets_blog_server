package com.letsblog.api.service;

import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsPostSummary;
import com.letsblog.api.domain.BulkOperationLog;
import com.letsblog.api.domain.BulkOperationType;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.PostComparisonPage;
import com.letsblog.api.dto.PostComparisonRow;
import com.letsblog.api.dto.PostEnvironmentValue;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.SiteRepository;
import com.letsblog.api.util.StackTraceUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 */
@Service
@Slf4j
public class PostComparisonService {

    private static final List<String> ENVIRONMENT_ORDER = List.of("local", "test", "production");

    private final ProjectRepository projectRepository;
    private final SiteRepository siteRepository;
    private final SiteService siteService;
    private final CmsAdapterFactory cmsAdapterFactory;
    private final BulkManagementService bulkManagementService;

    public PostComparisonService(
            ProjectRepository projectRepository,
            SiteRepository siteRepository,
            SiteService siteService,
            CmsAdapterFactory cmsAdapterFactory,
            BulkManagementService bulkManagementService) {
        this.projectRepository = projectRepository;
        this.siteRepository = siteRepository;
        this.siteService = siteService;
        this.cmsAdapterFactory = cmsAdapterFactory;
        this.bulkManagementService = bulkManagementService;
    }

    @Transactional(readOnly = true)
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

    @Transactional
    public List<BulkOperationLog> deleteEverywhere(Long projectId, String postType, String slug, Long actorId) {
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
            results.add(bulkManagementService.deletePostAtEnvironment(
                    projectId, environment, site, match.id(), postType, slug, actorId));
        }
        if (results.isEmpty()) {
            throw new IllegalArgumentException("削除対象が見つかりません: " + slug);
        }
        return results;
    }

    @Transactional
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
        Map<String, EnvironmentPosts> result = new LinkedHashMap<>();
        for (String environment : ENVIRONMENT_ORDER) {
            Site site = resolveSite(project, environment);
            if (site == null) {
                result.put(environment, EnvironmentPosts.unavailable());
                continue;
            }
            try {
                CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
                CmsAdapter adapter = cmsAdapterFactory.resolve(credentials.cmsType());
                List<CmsPostSummary> posts = adapter.listPosts(credentials, postType);
                result.put(environment, EnvironmentPosts.of(posts));
            } catch (RuntimeException e) {
                log.warn("投稿/ページ一覧取得に失敗しました(project={}, environment={}): {}",
                        project.getId(), environment, e.getMessage());
                bulkManagementService.logFetchFailure(
                        project.getId(), BulkOperationType.POST_FETCH, environment, e.getMessage(),
                        StackTraceUtil.toString(e));
                result.put(environment, EnvironmentPosts.error(e.getMessage()));
            }
        }
        return result;
    }

    private Site resolveSite(Project project, String environment) {
        Long siteId = switch (environment) {
            case "local" -> project.getLocalSiteId();
            case "test" -> project.getTestSiteId();
            case "production" -> project.getProductionSiteId();
            default -> null;
        };
        return siteId == null ? null : siteRepository.findById(siteId).orElse(null);
    }

    private Project getProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
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
