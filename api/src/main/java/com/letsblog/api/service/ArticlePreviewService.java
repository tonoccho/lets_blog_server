package com.letsblog.api.service;

import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.ThemeCssResponse;
import com.letsblog.api.markdown.MarkdownRenderer;
import com.letsblog.api.repository.SiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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

    private static final Logger logger = LoggerFactory.getLogger(ArticlePreviewService.class);
    private static final int MAX_STYLESHEETS = 15;
    private static final int MAX_CSS_LENGTH = 3_000_000;

    // live/staging環境はCloudflareのボット対策が有効で、User-Agent等が無いプレーンなHTTPクライアントには
    // JSチャレンジページ(stylesheetリンクを含まないHTML)を返してくる。ブラウザ相当のヘッダーを付与することで
    // 通常のページ取得として扱われるようにする(Issue #245)。
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0.0.0 Safari/537.36";

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
            logger.debug("Fetching site top page: {} (site: {})", fetchUrl, site.getSiteKey());
            html = browserLikeClient().get()
                    .uri(URI.create(fetchUrl))
                    .retrieve()
                    .body(String.class);
            if (html != null) {
                logger.debug("Fetched HTML length: {} bytes for site: {}", html.length(), site.getSiteKey());
            }
        } catch (Exception e) {
            logger.warn("Failed to fetch site top page for site: {}", site.getSiteKey(), e);
            return new ThemeCssResponse("", false, "サイトへの接続に失敗しました: " + e.getMessage());
        }
        if (html == null || html.isBlank()) {
            logger.warn("Empty or null response from site: {}", site.getSiteKey());
            return new ThemeCssResponse("", false, "サイトから空のレスポンスが返されました");
        }

        List<String> stylesheetUrls = extractStylesheetUrls(html, site.getBaseUrl());
        logger.debug("Extracted {} stylesheet URLs for site: {}", stylesheetUrls.size(), site.getSiteKey());
        if (!stylesheetUrls.isEmpty()) {
            stylesheetUrls.forEach(url -> logger.debug("  - {}", url));
        }
        if (stylesheetUrls.isEmpty()) {
            logger.warn("No stylesheet links found in site top page for site: {} (HTML length: {} bytes)",
                    site.getSiteKey(), html.length());
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
     * WordPressコンテナ内の常駐インスタンスへは http://wordpress/sites/{slug}/ でアクセス可能。
     * credentials復号の複雑性を避けるため、wpSlugから直接内部URLを構築する。
     * wpSlugが未設定の場合はnullを返す。
     */
    private String resolveManagedInternalBaseUrl(Site site) {
        if (!site.isManagedWordpress() || !StringUtils.hasText(site.getWpSlug())) {
            logger.debug("Site {} is not a managed WordPress site or wpSlug is missing", site.getSiteKey());
            return null;
        }
        String internalUrl = "http://wordpress/sites/" + site.getWpSlug() + "/";
        logger.debug("Resolved internal URL for site {}: {}", site.getSiteKey(), internalUrl);
        return internalUrl;
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
        int linkTagCount = 0;
        int stylesheetCount = 0;
        int hrefMatchCount = 0;
        while (linkMatcher.find() && urls.size() < MAX_STYLESHEETS) {
            linkTagCount++;
            String tag = linkMatcher.group();
            logger.debug("Found <link> tag #{}: {}", linkTagCount, tag.substring(0, Math.min(100, tag.length())));

            if (!REL_STYLESHEET_PATTERN.matcher(tag).find()) {
                logger.debug("  -> No rel=\"stylesheet\" found, skipping");
                continue;
            }
            stylesheetCount++;

            Matcher hrefMatcher = HREF_PATTERN.matcher(tag);
            if (!hrefMatcher.find()) {
                logger.debug("  -> No href attribute found, skipping");
                continue;
            }
            hrefMatchCount++;

            try {
                String resolvedUrl = base.resolve(hrefMatcher.group(1)).toString();
                urls.add(resolvedUrl);
                logger.debug("  -> Resolved href to: {}", resolvedUrl);
            } catch (IllegalArgumentException e) {
                logger.debug("  -> Failed to resolve href: {}", e.getMessage());
            }
        }
        logger.debug("HTML parsing summary: {} <link> tags found, {} with rel=stylesheet, {} with valid href",
                linkTagCount, stylesheetCount, hrefMatchCount);
        return urls;
    }

    /**
     * ブラウザ相当のUser-Agent/Acceptヘッダーを付与したRestClientを返す(Issue #245)。
     */
    private RestClient browserLikeClient() {
        return restClientBuilder.clone()
                .defaultHeader(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT)
                .defaultHeader(HttpHeaders.ACCEPT,
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "ja,en-US;q=0.9,en;q=0.8")
                .build();
    }

    private String fetchAndConcatStylesheets(List<String> stylesheetUrls) {
        StringBuilder css = new StringBuilder();
        RestClient client = browserLikeClient();
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
