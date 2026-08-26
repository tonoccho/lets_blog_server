package com.letsblog.api.controller;

import com.letsblog.api.client.ProjectServiceClient;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.RoleOptionResponse;
import com.letsblog.api.repository.ProjectUserRepository;
import com.letsblog.api.service.ProjectService;
import com.letsblog.api.service.RoleService;
import com.letsblog.api.service.SiteService;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * content-service向けの内部ブリッジ(issue #576)。CustomTagService/RenderedContentWrapperService
 * (cssSelectorPrefix解決フォールバック)、AdminAuthorizationService(プロジェクトメンバー判定)、
 * MetadataController(ロール一覧)、PostController(site key⇔id解決、サイト名一覧)は、いずれも
 * project_user/roles(publishing-serviceがまだ抽出されていないドメイン)への依存が強いため、
 * content-service側で直接持たず、このブリッジ経由でlegacy-apiへ問い合わせる(media-service(#573)の
 * CmsBridgeController/ai-service(#574)のAiBridgeControllerと同じ方針。認可は呼び出し元
 * (content-service)が既にrequireAdmin/requireProjectMemberOrAdmin等を済ませたリクエストの
 * トークンをそのまま転送してもらう想定で、ここでは追加の認可チェックは行わない)。
 *
 * <p>Project/Site本体の所有権はproject-serviceへ移った(issue #577 stage2)。{@link ProjectService}/
 * {@link SiteService}が内部ブリッジ経由で取得する(issue #577 stage3。ローカルJPAエンティティへの
 * 直接アクセスは廃止した)。組み込みタグ([toc]/[blogcard]/[amazon])のデザイン色/カスタムHTML
 * テンプレートも、tag_design_settingsドメインの所有権がproject-serviceへ抽出された(issue #577
 * stage1)ため、{@link #tagDesign}はproject-serviceへの内部ブリッジ経由で取得する(issue #577
 * stage3。content-service側の呼び出し元(LegacyApiBridgeClient#resolveTagDesign)は#576のスコープの
 * ため変更せず、このブリッジエンドポイント自体は残す)。
 */
@RestController
public class ContentBridgeController {

    private final ProjectUserRepository projectUserRepository;
    private final RoleService roleService;
    private final ProjectService projectService;
    private final SiteService siteService;
    private final ProjectServiceClient projectServiceClient;

    public ContentBridgeController(
            ProjectUserRepository projectUserRepository,
            RoleService roleService,
            ProjectService projectService,
            SiteService siteService,
            ProjectServiceClient projectServiceClient) {
        this.projectUserRepository = projectUserRepository;
        this.roleService = roleService;
        this.projectService = projectService;
        this.siteService = siteService;
        this.projectServiceClient = projectServiceClient;
    }

    /** AdminAuthorizationService(content-service)#requireProjectMemberOrAdminが使う、プロジェクトメンバー判定。 */
    @GetMapping("/api/internal/content/projects/{projectId}/members/{userId}")
    public boolean isProjectMember(@PathVariable Long projectId, @PathVariable Long userId) {
        return projectUserRepository.findByProjectIdAndUserId(projectId, userId).isPresent();
    }

    /** MetadataController(content-service)#rolesが使う、ロールの表示名一覧(特定の権限を要求しない)。 */
    @GetMapping("/api/internal/content/roles")
    public List<RoleOptionResponse> roles() {
        return roleService.getAllRoles().stream().map(RoleOptionResponse::from).toList();
    }

    public record TagDesignResponse(
            String backgroundColor, String textColor, String accentColor, String customCss, String htmlTemplate) {

        static TagDesignResponse from(ProjectServiceClient.TagDesignBridge bridge) {
            return new TagDesignResponse(
                    bridge.backgroundColor(), bridge.textColor(), bridge.accentColor(), bridge.customCss(),
                    bridge.htmlTemplate());
        }
    }

    /**
     * TocStyleRenderService/BlogCardTagRenderService/AmazonTagRenderService(content-service)が使っていた、
     * [toc]/[blogcard]/[amazon]組み込みタグのデザイン(色+カスタムHTMLテンプレート)。
     * tag_design_settingsドメインの所有権はproject-serviceへ移設済み(issue #577 stage1)のため、
     * {@link ProjectServiceClient}経由でproject-serviceへ問い合わせる(issue #577 stage3)。
     */
    @GetMapping("/api/internal/content/tag-design/{tagType}")
    public TagDesignResponse tagDesign(@PathVariable String tagType, @RequestParam Long projectId) {
        return TagDesignResponse.from(projectServiceClient.getTagDesign(projectId, tagType));
    }

    public record SlugResponse(String slug) {
    }

    /**
     * ProjectContentSettingsService(content-service)#resolveCssSelectorPrefixが使う、
     * cssSelectorPrefix未設定時のフォールバック(プロジェクトのslug)。
     */
    @GetMapping("/api/internal/content/projects/{projectId}/slug")
    public SlugResponse projectSlug(@PathVariable Long projectId) {
        Project project = projectService.getProjectEntity(projectId);
        return new SlugResponse(project.getSlug());
    }

    public record SiteIdResponse(Long id) {
    }

    /** PostController(content-service)#lookupBySlugが使う、siteKey→siteId解決。未登録なら404。 */
    @GetMapping("/api/internal/content/sites/by-key/{siteKey}")
    public ResponseEntity<SiteIdResponse> siteIdByKey(@PathVariable String siteKey) {
        return siteService.findBySiteKeyOptional(siteKey)
                .map(site -> ResponseEntity.ok(new SiteIdResponse(site.getId())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record SiteSummary(Long id, String siteKey, String name) {
    }

    /** PostController(content-service)#listが使う、全サイトのid/siteKey/nameの一覧(サイト名表示用)。 */
    @GetMapping("/api/internal/content/sites")
    public List<SiteSummary> sites() {
        return siteService.listAll().stream()
                .map((Site site) -> new SiteSummary(site.getId(), site.getSiteKey(), site.getName()))
                .collect(Collectors.toList());
    }
}
