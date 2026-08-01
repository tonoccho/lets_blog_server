package com.letsblog.api.service;

import com.letsblog.api.domain.BulkOperationLog;
import com.letsblog.api.domain.BulkOperationType;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.TermComparisonPage;
import com.letsblog.api.dto.TermComparisonRow;
import com.letsblog.api.dto.TermEnvironmentValue;
import com.letsblog.api.provisioning.WordPressBulkManagementClient;
import com.letsblog.api.provisioning.WordPressBulkManagementClient.CategoryInfo;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * カテゴリ・タグを3環境(ローカル/テスト/本番)で横断比較し、マスター環境(Project#masterEnvironment)の
 * 値を基準に、非マスター環境への同期・全環境からの削除をオーケストレーションする。
 * 実際のwp-cli呼び出し・ログ記録は{@link BulkManagementService#applyToEnvironment}に委譲する
 * (1環境=1操作=1ログという既存の粒度をそのまま使う)。
 * 項目の同一性は名前(大文字小文字を無視した完全一致)で判定する(Phase11-01初版の判定方針を踏襲)。
 */
@Service
public class TermComparisonService {

    private static final List<String> ENVIRONMENT_ORDER = List.of("local", "test", "production");

    private final ProjectRepository projectRepository;
    private final SiteRepository siteRepository;
    private final WordPressBulkManagementClient bulkManagementClient;
    private final BulkManagementService bulkManagementService;

    public TermComparisonService(
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
    public TermComparisonPage listCategoryComparison(Long projectId, int page, int size) {
        return listComparison(projectId, page, size, bulkManagementClient::listCategories);
    }

    @Transactional(readOnly = true)
    public TermComparisonPage listTagComparison(Long projectId, int page, int size) {
        return listComparison(projectId, page, size, bulkManagementClient::listTags);
    }

    @Transactional
    public List<BulkOperationLog> syncCategory(Long projectId, String name, Long actorId) {
        return sync(projectId, name, actorId, true);
    }

    @Transactional
    public List<BulkOperationLog> syncTag(Long projectId, String name, Long actorId) {
        return sync(projectId, name, actorId, false);
    }

    @Transactional
    public List<BulkOperationLog> deleteCategoryEverywhere(Long projectId, String name, Long actorId) {
        return deleteEverywhere(projectId, name, actorId, true);
    }

    @Transactional
    public List<BulkOperationLog> deleteTagEverywhere(Long projectId, String name, Long actorId) {
        return deleteEverywhere(projectId, name, actorId, false);
    }

    private TermComparisonPage listComparison(
            Long projectId, int page, int size, Function<String, List<CategoryInfo>> fetcher) {
        Project project = getProject(projectId);
        String masterEnvironment = project.getMasterEnvironment();

        Map<String, List<CategoryInfo>> termsByEnvironment = new LinkedHashMap<>();
        for (String environment : ENVIRONMENT_ORDER) {
            Site site = resolveOptionalManagedSite(project, environment);
            termsByEnvironment.put(environment, site != null ? fetcher.apply(site.getWpSlug()) : null);
        }

        Map<String, String> displayNameByKey = new LinkedHashMap<>();
        Map<String, Map<String, CategoryInfo>> termByKeyAndEnvironment = new LinkedHashMap<>();
        for (String environment : ENVIRONMENT_ORDER) {
            List<CategoryInfo> terms = termsByEnvironment.get(environment);
            if (terms == null) {
                continue;
            }
            for (CategoryInfo term : terms) {
                String key = term.name().toLowerCase(Locale.ROOT);
                termByKeyAndEnvironment.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(environment, term);
                if (!displayNameByKey.containsKey(key) || environment.equals(masterEnvironment)) {
                    displayNameByKey.put(key, term.name());
                }
            }
        }

        List<TermComparisonRow> rows = termByKeyAndEnvironment.entrySet().stream()
                .map(entry -> toRow(displayNameByKey.get(entry.getKey()), entry.getValue(), termsByEnvironment))
                .sorted(Comparator.comparing(TermComparisonRow::name, Comparator.naturalOrder()))
                .toList();

        long totalCount = rows.size();
        List<TermComparisonRow> pageItems = rows.stream()
                .skip((long) page * size)
                .limit(size)
                .toList();
        return new TermComparisonPage(pageItems, page, size, totalCount, masterEnvironment);
    }

    private TermComparisonRow toRow(
            String name, Map<String, CategoryInfo> byEnvironment, Map<String, List<CategoryInfo>> availability) {
        return new TermComparisonRow(
                name,
                toValue(byEnvironment.get("local"), availability.get("local") != null),
                toValue(byEnvironment.get("test"), availability.get("test") != null),
                toValue(byEnvironment.get("production"), availability.get("production") != null));
    }

    private TermEnvironmentValue toValue(CategoryInfo term, boolean environmentAvailable) {
        if (!environmentAvailable) {
            return TermEnvironmentValue.unavailable();
        }
        if (term == null) {
            return TermEnvironmentValue.missing();
        }
        return TermEnvironmentValue.of(term.slug(), term.parentSlug(), term.description());
    }

    private List<BulkOperationLog> sync(Long projectId, String name, Long actorId, boolean isCategory) {
        Project project = getProject(projectId);
        String masterEnvironment = project.getMasterEnvironment();
        Function<String, List<CategoryInfo>> fetcher = isCategory
                ? bulkManagementClient::listCategories
                : bulkManagementClient::listTags;

        Map<String, CategoryInfo> byEnvironment = findByName(project, name, fetcher);
        CategoryInfo master = byEnvironment.get(masterEnvironment);
        if (master == null) {
            throw new IllegalArgumentException("マスター環境に存在しない項目は同期できません: " + name);
        }

        BulkOperationType createType = isCategory ? BulkOperationType.CATEGORY_CREATE : BulkOperationType.TAG_CREATE;
        BulkOperationType editType = isCategory ? BulkOperationType.CATEGORY_EDIT : BulkOperationType.TAG_EDIT;

        List<BulkOperationLog> results = new ArrayList<>();
        for (String environment : ENVIRONMENT_ORDER) {
            if (environment.equals(masterEnvironment)) {
                continue;
            }
            if (resolveOptionalManagedSite(project, environment) == null) {
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

    private List<BulkOperationLog> deleteEverywhere(Long projectId, String name, Long actorId, boolean isCategory) {
        Project project = getProject(projectId);
        Function<String, List<CategoryInfo>> fetcher = isCategory
                ? bulkManagementClient::listCategories
                : bulkManagementClient::listTags;
        Map<String, CategoryInfo> byEnvironment = findByName(project, name, fetcher);
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
            throw new IllegalArgumentException("削除対象が見つかりません: " + name);
        }
        return results;
    }

    private Map<String, CategoryInfo> findByName(
            Project project, String name, Function<String, List<CategoryInfo>> fetcher) {
        Map<String, CategoryInfo> result = new LinkedHashMap<>();
        for (String environment : ENVIRONMENT_ORDER) {
            Site site = resolveOptionalManagedSite(project, environment);
            if (site == null) {
                continue;
            }
            fetcher.apply(site.getWpSlug()).stream()
                    .filter(term -> term.name().equalsIgnoreCase(name))
                    .findFirst()
                    .ifPresent(term -> result.put(environment, term));
        }
        return result;
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
