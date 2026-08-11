package com.letsblog.api.service;

import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.ThemeCssResponse;
import com.letsblog.api.markdown.MarkdownRenderer;
import com.letsblog.api.repository.SiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VSCode拡張の記事プレビュー機能向けに、Markdown→HTML変換とプロジェクトのマスター環境サイトの
 * テーマCSS取得を行う。PostPublishServiceと異なり、実際のCMSへの投稿やPlantUML図の生成・画像アップロードは
 * 行わない(プレビューなので副作用のある外部呼び出しは避ける)。
 */
@Service
public class ArticlePreviewService {

    private static final int MAX_STYLESHEETS = 15;
    private static final int MAX_CSS_LENGTH = 3_000_000;

    private static final Pattern LINK_TAG_PATTERN =
            Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern REL_STYLESHEET_PATTERN =
            Pattern.compile("rel\\s*=\\s*[\"']stylesheet[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_PATTERN =
            Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private final CustomTagRenderService customTagRenderService;
    private final BlogCardTagRenderService blogCardTagRenderService;
    private final AmazonTagRenderService amazonTagRenderService;
    private final TocStyleRenderService tocStyleRenderService;
    private final MarkdownRenderer markdownRenderer;
    private final ProjectService projectService;
    private final SiteRepository siteRepository;
    private final SiteService siteService;
    private final RestClient.Builder restClientBuilder;

    public ArticlePreviewService(
            CustomTagRenderService customTagRenderService,
            BlogCardTagRenderService blogCardTagRenderService,
            AmazonTagRenderService amazonTagRenderService,
            TocStyleRenderService tocStyleRenderService,
            MarkdownRenderer markdownRenderer,
            ProjectService projectService,
            SiteRepository siteRepository,
            SiteService siteService,
            RestClient.Builder restClientBuilder) {
        this.customTagRenderService = customTagRenderService;
        this.blogCardTagRenderService = blogCardTagRenderService;
        this.amazonTagRenderService = amazonTagRenderService;
        this.tocStyleRenderService = tocStyleRenderService;
        this.markdownRenderer = markdownRenderer;
        this.projectService = projectService;
        this.siteRepository = siteRepository;
        this.siteService = siteService;
        this.restClientBuilder = restClientBuilder;
    }

    /**
     * カスタムタグ展開 + 組み込みタグ展開 + Markdown→HTML変換を行う。PostPublishServiceと違い、
     * PlantUML埋め込みや画像アップロードは行わない(プレビュー用の軽量処理。ローカル画像やPlantUML図は
     * VSCode拡張側の責務)。
     */
    public String renderHtml(Long projectId, String markdown) {
        String rendered = customTagRenderService.render(markdown, projectId);
        rendered = blogCardTagRenderService.render(rendered, projectId);
        rendered = amazonTagRenderService.render(rendered, projectId);
        rendered = tocStyleRenderService.render(rendered, projectId);
        String html = markdownRenderer.render(rendered);
        return tocStyleRenderService.applyHtmlTemplate(html, projectId);
    }

    /**
     * プロジェクトのマスター環境(test/production)に紐づくサイトのテーマCSSを返す。
     */
    public ThemeCssResponse fetchMasterThemeCss(Long projectId) {
        return fetchThemeCss(projectId, null);
    }

    /**
     * 指定サイト(siteId)のテーマCSSを返す。siteIdがnullの場合はマスター環境のサイトを対象とする。
     *
     * VSCode拡張のプレビューで、ローカル/テスト/本番のどのサイトの見た目で確認するかを
     * 選べるようにするためにsiteIdを受け取る。指定されたサイトがこのプロジェクトに
     * 紐づいていない場合は、他プロジェクトのサイトを覗けてしまわないよう取得を拒否する。
     *
     * 対象がWordPressであれば、トップページのstylesheetリンクを収集して連結したCSSを返す。
     * 紐付けなし・非WordPress・取得失敗時はavailable=falseで理由を添えて返す。
     */
    public ThemeCssResponse fetchThemeCss(Long projectId, Long siteId) {
        Project project = projectService.getProjectEntity(projectId);
        Site site;
        if (siteId == null) {
            site = resolveMasterSite(project);
            if (site == null) {
                return new ThemeCssResponse("", false,
                        "マスター環境(" + project.getMasterEnvironment() + ")にサイトが紐づいていません");
            }
        } else {
            if (!isProjectSite(project, siteId)) {
                return new ThemeCssResponse("", false, "指定されたサイトはこのプロジェクトに紐づいていません");
            }
            site = siteRepository.findById(siteId).orElse(null);
            if (site == null) {
                return new ThemeCssResponse("", false, "指定されたサイトが見つかりません");
            }
        }
        if (site.getCmsType() != CmsType.WORDPRESS) {
            return new ThemeCssResponse("", false, "対象サイトがWordPress以外のCMSのためテーマCSSを取得できません");
        }

        // managed(local)サイトのbase_urlはreverse-proxy経由のブラウザ向け公開URL(https://localhost/...)であり、
        // APIコンテナ自身からは(コンテナ内の「localhost」は自分自身を指すため)到達できない。
        // 常駐wordpressコンテナへ直結できる内部URル(credentials.baseUrl、例: http://wordpress/sites/{slug})が
        // 取得できる場合は、そちらをfetch起点として使う(Issue #246)。取得できない場合は従来通りbase_urlを使う。
        String fetchUrl = site.getBaseUrl();
        String internalOrigin = null;
        if (site.isManagedWordpress()) {
            String internalBaseUrl = resolveManagedInternalBaseUrl(site);
            if (internalBaseUrl != null) {
                fetchUrl = internalBaseUrl.endsWith("/") ? internalBaseUrl : internalBaseUrl + "/";
                internalOrigin = originOf(internalBaseUrl);
            }
        }

        String html;
        try {
            html = restClientBuilder.clone().build().get()
                    .uri(URI.create(fetchUrl))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            return new ThemeCssResponse("", false, "サイトへの接続に失敗しました: " + e.getMessage());
        }
        if (html == null || html.isBlank()) {
            return new ThemeCssResponse("", false, "サイトから空のレスポンスが返されました");
        }

        List<String> stylesheetUrls = extractStylesheetUrls(html, site.getBaseUrl());
        if (stylesheetUrls.isEmpty()) {
            return new ThemeCssResponse("", false, "サイトのトップページにstylesheetリンクが見つかりませんでした");
        }
        if (internalOrigin != null) {
            // stylesheetのhrefはWordPressのsiteurl設定(公開URL)を基準に絶対URLで出力されるため、
            // トップページと同様に公開オリジンを内部オリジンへ差し替えてから取得する。
            stylesheetUrls = rewriteToInternalOrigin(stylesheetUrls, originOf(site.getBaseUrl()), internalOrigin);
        }

        String css = fetchAndConcatStylesheets(stylesheetUrls);
        return new ThemeCssResponse(css, true, null);
    }

    /**
     * managed(local)WordPressサイトの、APIコンテナから直接到達できる内部URLを返す。
     * credentials(baseUrl)が復号できない・保持されていない場合はnullを返し、呼び出し元は
     * 従来通りsite.getBaseUrl()にフォールバックする。
     */
    private String resolveManagedInternalBaseUrl(Site site) {
        try {
            CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
            if (credentials instanceof CmsCredentials.WordPressCredentials wp && StringUtils.hasText(wp.baseUrl())) {
                return wp.baseUrl();
            }
        } catch (RuntimeException ignored) {
            // 認証情報の復号に失敗した場合は公開URL(site.getBaseUrl())へのフォールバックに任せる
        }
        return null;
    }

    private String originOf(String url) {
        URI uri = URI.create(url);
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private List<String> rewriteToInternalOrigin(List<String> urls, String publicOrigin, String internalOrigin) {
        List<String> rewritten = new ArrayList<>(urls.size());
        for (String url : urls) {
            rewritten.add(url.startsWith(publicOrigin) ? internalOrigin + url.substring(publicOrigin.length()) : url);
        }
        return rewritten;
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
        return siteId == null ? null : siteRepository.findById(siteId).orElse(null);
    }

    private List<String> extractStylesheetUrls(String html, String baseUrl) {
        URI base = URI.create(baseUrl);
        List<String> urls = new ArrayList<>();
        Matcher linkMatcher = LINK_TAG_PATTERN.matcher(html);
        while (linkMatcher.find() && urls.size() < MAX_STYLESHEETS) {
            String tag = linkMatcher.group();
            if (!REL_STYLESHEET_PATTERN.matcher(tag).find()) {
                continue;
            }
            Matcher hrefMatcher = HREF_PATTERN.matcher(tag);
            if (!hrefMatcher.find()) {
                continue;
            }
            try {
                urls.add(base.resolve(hrefMatcher.group(1)).toString());
            } catch (IllegalArgumentException ignored) {
                // 不正なURLは無視する
            }
        }
        return urls;
    }

    private String fetchAndConcatStylesheets(List<String> stylesheetUrls) {
        StringBuilder css = new StringBuilder();
        RestClient client = restClientBuilder.clone().build();
        for (String url : stylesheetUrls) {
            if (css.length() >= MAX_CSS_LENGTH) {
                break;
            }
            try {
                String body = client.get().uri(URI.create(url)).retrieve().body(String.class);
                if (body != null && !body.isBlank()) {
                    css.append("/* ").append(url).append(" */\n").append(body).append("\n");
                }
            } catch (Exception ignored) {
                // 個別のCSS取得失敗はスキップする(プレビューが完全に崩れることを防ぐ)
            }
        }
        return css.length() > MAX_CSS_LENGTH ? css.substring(0, MAX_CSS_LENGTH) : css.toString();
    }
}
