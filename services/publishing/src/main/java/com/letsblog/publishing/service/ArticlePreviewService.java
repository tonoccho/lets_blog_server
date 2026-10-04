package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.cms.LetsblogPluginUnavailableException;
import com.letsblog.publishing.cms.SignedPreview;
import com.letsblog.publishing.domain.Project;
import com.letsblog.publishing.domain.Site;
import com.letsblog.publishing.dto.SignedPreviewUrlRequest;
import com.letsblog.publishing.dto.SignedPreviewUrlResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * VSCode拡張の記事プレビュー向けに、実サイトで表示する署名付きプレビューURLを発行する(issue #1561)。
 *
 * <p>プラグイン必須化(Epic #1555)に伴い、非公開投稿を作る旧方式・テーマCSSの取得・DOM骨格の差し替えは
 * issue #1564で削除した。記事本文のMarkdown→HTML変換(renderHtml)はCMSへの依存を持たないため
 * content-serviceが持つ(issue #576)。
 *
 * <p>Project/Siteの実体・CMS認証情報の所有権はproject-serviceにあるため、
 * {@link ProjectService}/{@link SiteService}経由(内部ブリッジ)で解決する。
 */
@Service
public class ArticlePreviewService {

    private static final ObjectMapper SIGNED_PREVIEW_MAPPER = new ObjectMapper();

    private final ProjectService projectService;
    private final SiteService siteService;
    private final CmsAdapterFactory cmsAdapterFactory;

    public ArticlePreviewService(
            ProjectService projectService, SiteService siteService, CmsAdapterFactory cmsAdapterFactory) {
        this.projectService = projectService;
        this.siteService = siteService;
        this.cmsAdapterFactory = cmsAdapterFactory;
    }

    /**
     * 投稿を作らずに実テーマで表示する、期限付きの署名付きプレビューURLを発行する(issue #1561)。
     * タイトル・本文HTML・カテゴリ・タグ・アイキャッチを、wp-cliでletsblogプラグインへ渡す(REST APIは使わない)。
     * 非公開投稿もメディアも作らないので、本番サイトでも使える。letsblogプラグインが使えないサイトは、
     * 何も渡さず理由と対処を示して拒否する({@link LetsblogPluginUnavailableException}、409)。
     *
     * @throws IllegalStateException 対象サイトを解決できないとき(409)
     */
    public SignedPreviewUrlResponse createSignedPreviewUrl(Long projectId, SignedPreviewUrlRequest request) {
        Project project = projectService.getProjectEntity(projectId);
        SiteResolution resolution = resolveSiteForPreview(project, request.siteId());
        if (resolution.site() == null) {
            throw new IllegalStateException(resolution.errorReason());
        }
        CmsCredentials credentials = siteService.getCredentials(resolution.site().getSiteKey());
        CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());
        cmsAdapter.requireLetsblogPlugin(credentials);

        ObjectNode payload = SIGNED_PREVIEW_MAPPER.createObjectNode();
        payload.put("title", request.title());
        payload.put("content", request.contentHtml());
        ArrayNode categories = payload.putArray("categories");
        (request.categories() != null ? request.categories() : List.<String>of()).forEach(categories::add);
        ArrayNode tags = payload.putArray("tags");
        (request.tags() != null ? request.tags() : List.<String>of()).forEach(tags::add);
        if (StringUtils.hasText(request.featuredImageDataUri())) {
            payload.put("featured_image", request.featuredImageDataUri());
        }

        SignedPreview preview = cmsAdapter.createSignedPreview(credentials, payload.toString(), request.ttlSeconds());
        return new SignedPreviewUrlResponse(preview.url(), preview.expiresAt());
    }

    /** site==nullの場合はerrorReasonに理由が入る({@link #resolveSiteForPreview}参照)。 */
    private record SiteResolution(Site site, String errorReason) {
    }

    /**
     * projectId/siteIdからプレビュー対象サイトを解決する(サイトの紐付け確認 + WordPressサイトであることの確認)。
     * siteIdがnullの場合はマスター環境のサイトを対象とする。
     */
    private SiteResolution resolveSiteForPreview(Project project, Long siteId) {
        Site site;
        if (siteId == null) {
            site = resolveMasterSite(project);
            if (site == null) {
                return new SiteResolution(null,
                        "マスター環境(" + project.getMasterEnvironment() + ")にサイトが紐づいていません");
            }
        } else {
            if (!isProjectSite(project, siteId)) {
                return new SiteResolution(null, "指定されたサイトはこのプロジェクトに紐づいていません");
            }
            site = siteService.getById(siteId).orElse(null);
            if (site == null) {
                return new SiteResolution(null, "指定されたサイトが見つかりません");
            }
        }
        if (site.getCmsType() != CmsType.WORDPRESS) {
            return new SiteResolution(null, "対象サイトがWordPress以外のCMSのため取得できません");
        }
        return new SiteResolution(site, null);
    }

    /** siteIdがこのプロジェクトのいずれかの環境に紐づいているか。 */
    private boolean isProjectSite(Project project, Long siteId) {
        return siteId.equals(project.getLocalSiteId())
                || siteId.equals(project.getTestSiteId())
                || siteId.equals(project.getProductionSiteId());
    }

    private Site resolveMasterSite(Project project) {
        Long siteId = switch (project.getMasterEnvironment()) {
            case "test" -> project.getTestSiteId();
            case "production" -> project.getProductionSiteId();
            default -> null;
        };
        return siteId == null ? null : siteService.getById(siteId).orElse(null);
    }
}
