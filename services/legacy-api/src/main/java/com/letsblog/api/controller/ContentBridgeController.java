package com.letsblog.api.controller;

import com.letsblog.api.domain.EmbedTagType;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.RoleOptionResponse;
import com.letsblog.api.dto.TagDesignColors;
import com.letsblog.api.repository.ProjectUserRepository;
import com.letsblog.api.repository.SiteRepository;
import com.letsblog.api.service.ProjectService;
import com.letsblog.api.service.RoleService;
import com.letsblog.api.service.TagDesignSettingService;
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
 * MetadataController(ロール一覧)、PostController(site key⇔id解決、サイト名一覧)、
 * TocStyleRenderService/BlogCardTagRenderService/AmazonTagRenderService(組み込みタグのデザイン色/
 * カスタムHTMLテンプレート)は、いずれもProject/Site/project_user/roles/tag_design_settings
 * (project-service/publishing-serviceがまだ抽出されていないドメイン)への依存が強いため、
 * content-service側で直接持たず、このブリッジ経由でlegacy-apiへ問い合わせる(media-service(#573)の
 * CmsBridgeController/ai-service(#574)のAiBridgeControllerと同じ方針。認可は呼び出し元
 * (content-service)が既にrequireAdmin/requireProjectMemberOrAdmin等を済ませたリクエストの
 * トークンをそのまま転送してもらう想定で、ここでは追加の認可チェックは行わない)。
 */
@RestController
public class ContentBridgeController {

    private final ProjectUserRepository projectUserRepository;
    private final RoleService roleService;
    private final TagDesignSettingService tagDesignSettingService;
    private final ProjectService projectService;
    private final SiteRepository siteRepository;

    public ContentBridgeController(
            ProjectUserRepository projectUserRepository,
            RoleService roleService,
            TagDesignSettingService tagDesignSettingService,
            ProjectService projectService,
            SiteRepository siteRepository) {
        this.projectUserRepository = projectUserRepository;
        this.roleService = roleService;
        this.tagDesignSettingService = tagDesignSettingService;
        this.projectService = projectService;
        this.siteRepository = siteRepository;
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
    }

    /**
     * TocStyleRenderService/BlogCardTagRenderService/AmazonTagRenderService(content-service)が使う、
     * [toc]/[blogcard]/[amazon]組み込みタグのデザイン(色+カスタムHTMLテンプレート)。
     */
    @GetMapping("/api/internal/content/tag-design/{tagType}")
    public TagDesignResponse tagDesign(@PathVariable String tagType, @RequestParam Long projectId) {
        EmbedTagType type = EmbedTagType.valueOf(tagType);
        TagDesignColors colors = tagDesignSettingService.resolveColors(projectId, type);
        String htmlTemplate = tagDesignSettingService.resolveHtmlTemplate(projectId, type);
        return new TagDesignResponse(
                colors.backgroundColor(), colors.textColor(), colors.accentColor(), colors.customCss(),
                htmlTemplate);
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
        return siteRepository.findBySiteKey(siteKey)
                .map(site -> ResponseEntity.ok(new SiteIdResponse(site.getId())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record SiteSummary(Long id, String siteKey, String name) {
    }

    /** PostController(content-service)#listが使う、全サイトのid/siteKey/nameの一覧(サイト名表示用)。 */
    @GetMapping("/api/internal/content/sites")
    public List<SiteSummary> sites() {
        return siteRepository.findAll().stream()
                .map((Site site) -> new SiteSummary(site.getId(), site.getSiteKey(), site.getName()))
                .collect(Collectors.toList());
    }
}
