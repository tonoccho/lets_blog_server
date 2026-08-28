package com.letsblog.publishing.controller;

import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.domain.Project;
import com.letsblog.publishing.domain.Site;
import com.letsblog.publishing.service.ProjectService;
import com.letsblog.publishing.service.SiteService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * ai-service向けの内部ブリッジ(issue #574でlegacy-apiに{@code AiBridgeController}として新設、
 * issue #711でこの3エンドポイント(既存カテゴリ/既存カテゴリ(親付き)/既存タグ)のみ
 * publishing-serviceへ移管、Epic #551 C6-5)。GitHubトークン解決・プロジェクトメンバー判定・
 * システム全体既定のBrave Search APIキー・実効LLM接続設定の4エンドポイントはCmsAdapterFactoryに
 * 依存しないため、移管元のlegacy-api版{@code AiBridgeController}に残る。
 *
 * <p>マスター環境サイトの既存カテゴリ/タグ取得はProject/Site/{@link CmsAdapterFactory}への依存が
 * 強く、{@code cms/*}パッケージの所有権が既にpublishing-serviceへ移った(issue #707)ため、
 * ai-serviceからは新規クライアント({@code com.letsblog.ai.client.PublishingServiceClient}）経由で
 * 直接publishing-serviceへ問い合わせる構成に切り替えた(media-service(#573)のCmsMediaBridgeControllerと
 * 同じ方針)。認可は呼び出し元(ai-service)が既にrequireAdmin/requireProjectMemberOrAdmin等を
 * 済ませたリクエストのBearerトークンをそのまま転送してもらう想定で、ここでは追加の認可チェックは
 * 行わない(移管元のlegacy-api版と同じ)。
 */
@RestController
public class AiExistingTaxonomyBridgeController {

    private final ProjectService projectService;
    private final SiteService siteService;
    private final CmsAdapterFactory cmsAdapterFactory;

    public AiExistingTaxonomyBridgeController(
            ProjectService projectService, SiteService siteService, CmsAdapterFactory cmsAdapterFactory) {
        this.projectService = projectService;
        this.siteService = siteService;
        this.cmsAdapterFactory = cmsAdapterFactory;
    }

    /**
     * プロジェクトのマスター環境サイトに既に存在するカテゴリ名一覧。サイト未紐付け・非WordPress・
     * 取得失敗時は空リストを返す(移管元のAiBridgeController#existingCategoriesと同じフェイルオープン方針)。
     */
    @GetMapping("/api/internal/ai/projects/{projectId}/existing-categories")
    public List<String> existingCategories(@PathVariable Long projectId) {
        try {
            CmsAdapterAndCredentials resolved = resolveMasterSiteCmsAdapter(projectId);
            return resolved == null ? List.of() : resolved.adapter().listCategoryNames(resolved.credentials());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** 親カテゴリ名付きの既存カテゴリ一覧(issue #289)。取得失敗時は空リスト。 */
    @GetMapping("/api/internal/ai/projects/{projectId}/existing-categories-with-parents")
    public List<CmsAdapter.CategoryOption> existingCategoriesWithParents(@PathVariable Long projectId) {
        try {
            CmsAdapterAndCredentials resolved = resolveMasterSiteCmsAdapter(projectId);
            return resolved == null ? List.of() : resolved.adapter().listCategoriesWithParents(resolved.credentials());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** プロジェクトのマスター環境サイトに既に存在するタグ名一覧(issue #525)。取得失敗時は空リスト。 */
    @GetMapping("/api/internal/ai/projects/{projectId}/existing-tags")
    public List<String> existingTags(@PathVariable Long projectId) {
        try {
            CmsAdapterAndCredentials resolved = resolveMasterSiteCmsAdapter(projectId);
            return resolved == null ? List.of() : resolved.adapter().listTagNames(resolved.credentials());
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private record CmsAdapterAndCredentials(CmsAdapter adapter, CmsCredentials credentials) {
    }

    private CmsAdapterAndCredentials resolveMasterSiteCmsAdapter(Long projectId) {
        Project project = projectService.getProjectEntity(projectId);
        Site site = resolveMasterSite(project);
        if (site == null) {
            return null;
        }
        CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
        CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());
        return new CmsAdapterAndCredentials(cmsAdapter, credentials);
    }

    /**
     * プロジェクトのマスター環境(test/production)に紐づくサイトを解決する。未紐付けの場合はnullを返す
     * (legacy-apiの{@code ProjectService#resolveMasterSite}と同じロジック)。
     */
    private Site resolveMasterSite(Project project) {
        Long siteId = switch (project.getMasterEnvironment()) {
            case "test" -> project.getTestSiteId();
            case "production" -> project.getProductionSiteId();
            default -> null;
        };
        return siteId == null ? null : siteService.getById(siteId).orElse(null);
    }
}
