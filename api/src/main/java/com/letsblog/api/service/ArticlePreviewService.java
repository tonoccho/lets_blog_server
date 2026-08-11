package com.letsblog.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.config.LegacyJacksonRestClientConfig;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.ThemeCssResponse;
import com.letsblog.api.dto.ThemeSkeletonResponse;
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
    private static final Pattern REL_PRELOAD_PATTERN =
            Pattern.compile("rel\\s*=\\s*[\"']preload[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern AS_STYLE_PATTERN =
            Pattern.compile("as\\s*=\\s*[\"']style[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_PATTERN =
            Pattern.compile("href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_BLOCK_PATTERN =
            Pattern.compile("<style\\b[^>]*>(.*?)</style>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CSS_URL_PATTERN =
            Pattern.compile("url\\(\\s*(['\"]?)([^'\")]+)\\1\\s*\\)", Pattern.CASE_INSENSITIVE);

    private final CustomTagRenderService customTagRenderService;
    private final BlogCardTagRenderService blogCardTagRenderService;
    private final AmazonTagRenderService amazonTagRenderService;
    private final TocStyleRenderService tocStyleRenderService;
    private final MarkdownRenderer markdownRenderer;
    private final ProjectService projectService;
    private final SiteRepository siteRepository;
    private final SiteService siteService;
    private final RestClient.Builder restClientBuilder;
    private final PreviewSkeletonFetcher previewSkeletonFetcher;

    public ArticlePreviewService(
            CustomTagRenderService customTagRenderService,
            BlogCardTagRenderService blogCardTagRenderService,
            AmazonTagRenderService amazonTagRenderService,
            TocStyleRenderService tocStyleRenderService,
            MarkdownRenderer markdownRenderer,
            ProjectService projectService,
            SiteRepository siteRepository,
            SiteService siteService,
            RestClient.Builder restClientBuilder,
            PreviewSkeletonFetcher previewSkeletonFetcher) {
        this.customTagRenderService = customTagRenderService;
        this.blogCardTagRenderService = blogCardTagRenderService;
        this.amazonTagRenderService = amazonTagRenderService;
        this.tocStyleRenderService = tocStyleRenderService;
        this.markdownRenderer = markdownRenderer;
        this.projectService = projectService;
        this.siteRepository = siteRepository;
        this.siteService = siteService;
        this.restClientBuilder = restClientBuilder;
        this.previewSkeletonFetcher = previewSkeletonFetcher;
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
        SiteResolution resolution = resolveSiteForPreview(project, siteId);
        if (resolution.site() == null) {
            return new ThemeCssResponse("", false, resolution.errorReason());
        }
        Site site = resolution.site();

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
        List<String> inlineStyles = extractInlineStyles(html);
        logger.debug("Extracted {} stylesheet URLs and {} inline <style> blocks for site: {}",
                stylesheetUrls.size(), inlineStyles.size(), site.getSiteKey());
        if (!stylesheetUrls.isEmpty()) {
            stylesheetUrls.forEach(url -> logger.debug("  - {}", url));
        }
        if (stylesheetUrls.isEmpty() && inlineStyles.isEmpty()) {
            logger.warn("No stylesheet links or inline <style> blocks found in site top page for site: {} "
                    + "(HTML length: {} bytes)", site.getSiteKey(), html.length());
            return new ThemeCssResponse("", false, "サイトのトップページにstylesheetリンクが見つかりませんでした");
        }
        if (internalOrigin != null && !stylesheetUrls.isEmpty()) {
            // stylesheetのhrefはWordPressのsiteurl設定(公開URL)を基準に絶対URLで出力されるため、
            // トップページと同様に公開オリジンを内部オリジンへ差し替えてから取得する。
            stylesheetUrls = rewriteToInternalOrigin(stylesheetUrls, originOf(site.getBaseUrl()), internalOrigin);
        }

        StringBuilder css = new StringBuilder(fetchAndConcatStylesheets(stylesheetUrls));
        for (String inlineStyle : inlineStyles) {
            if (css.length() >= MAX_CSS_LENGTH) {
                break;
            }
            css.append("/* inline <style> */\n").append(inlineStyle).append("\n");
        }
        String result = css.length() > MAX_CSS_LENGTH ? css.substring(0, MAX_CSS_LENGTH) : css.toString();
        return new ThemeCssResponse(result, true, null);
    }

    /**
     * サイト内の既存記事ページを骨格として流用し、実テーマのDOM構造(タイトルの見出し要素、
     * アイキャッチ、本文コンテナ等)を保ったまま、プレビュー対象記事のタイトル/本文/アイキャッチへ
     * 差し替えたHTML断片を返す。
     *
     * 差し替え位置は、サイト内の最新記事をWP REST APIで取得し、そのtitle.rendered/content.renderedを
     * 実際に描画されたDOM内から検索することで特定する({@link PreviewSkeletonFetcher}参照)。
     * 参照記事が存在しない、差し替え位置を特定できない等の場合はavailable=falseを返し、
     * 呼び出し側で従来の表示(テーマDOM構造を再現しないプレーンな表示)へフォールバックする。
     */
    public ThemeSkeletonResponse renderSkeleton(
            Long projectId, Long siteId, String title, String contentHtml, String featuredImageDataUri) {
        Project project = projectService.getProjectEntity(projectId);
        SiteResolution resolution = resolveSiteForPreview(project, siteId);
        if (resolution.site() == null) {
            return new ThemeSkeletonResponse(null, false, resolution.errorReason(), false);
        }
        Site site = resolution.site();

        String fetchOrigin = site.getBaseUrl();
        String internalOrigin = null;
        if (site.isManagedWordpress()) {
            String internalBaseUrl = resolveManagedInternalBaseUrl(site);
            if (internalBaseUrl != null) {
                fetchOrigin = internalBaseUrl;
                internalOrigin = originOf(internalBaseUrl);
            }
        }
        String base = fetchOrigin.endsWith("/") ? fetchOrigin : fetchOrigin + "/";

        JsonNode posts;
        try {
            posts = browserLikeClient().get()
                    .uri(base + "wp-json/wp/v2/posts?per_page=1&orderby=date&order=desc"
                            + "&_fields=id,link,title,content")
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            logger.warn("Failed to fetch reference post for skeleton preview: {}", site.getSiteKey(), e);
            return new ThemeSkeletonResponse(null, false, "参照記事の取得に失敗しました: " + e.getMessage(), false);
        }
        if (posts == null || posts.size() == 0) {
            return new ThemeSkeletonResponse(null, false, "参照記事が見つかりませんでした", false);
        }
        JsonNode reference = posts.get(0);
        String titleRendered = reference.path("title").path("rendered").asText("");
        String contentRendered = reference.path("content").path("rendered").asText("");
        String referenceLink = reference.path("link").asText(null);
        if (referenceLink == null) {
            return new ThemeSkeletonResponse(null, false, "参照記事のURLを取得できませんでした", false);
        }

        // 参照記事のlinkはWordPressのsiteurl設定(公開URL)を基準に絶対URLで出力されるため、
        // fetchThemeCssと同様、managedサイトでは公開オリジンを内部オリジンへ差し替えてから
        // Playwrightのナビゲーション先として使う(APIコンテナ自身からは公開URLに到達できないため)。
        String publicOrigin = originOf(site.getBaseUrl());
        String navigateUrl = internalOrigin != null
                ? rewriteToInternalOrigin(referenceLink, publicOrigin, internalOrigin)
                : referenceLink;

        ThemeSkeletonResponse spliced;
        try {
            spliced = previewSkeletonFetcher.fetchAndSplice(
                    navigateUrl, titleRendered, contentRendered, title, contentHtml, featuredImageDataUri);
        } catch (Exception e) {
            logger.warn("Failed to render skeleton preview for site: {}", site.getSiteKey(), e);
            return new ThemeSkeletonResponse(null, false, "記事ページの取得に失敗しました: " + e.getMessage(), false);
        }
        if (!spliced.available() || spliced.html() == null) {
            return spliced;
        }
        // Playwright側で解決された絶対URL(img src/a href等)は内部オリジン基準のため、
        // Webviewから実際に読み込める公開オリジンへ戻してから返す。
        String html = internalOrigin != null ? spliced.html().replace(internalOrigin, publicOrigin) : spliced.html();
        return new ThemeSkeletonResponse(html, true, null, spliced.eyecatchSpliced());
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
            rewritten.add(rewriteToInternalOrigin(url, publicOrigin, internalOrigin));
        }
        return rewritten;
    }

    private String rewriteToInternalOrigin(String url, String publicOrigin, String internalOrigin) {
        return url.startsWith(publicOrigin) ? internalOrigin + url.substring(publicOrigin.length()) : url;
    }

    /** site==nullの場合はerrorReasonに理由が入る({@link #resolveSiteForPreview}参照)。 */
    private record SiteResolution(Site site, String errorReason) {
    }

    /**
     * projectId/siteIdからプレビュー対象サイトを解決する。fetchThemeCssとrenderSkeletonの両方で
     * 必要な「サイトの紐付け確認 + WordPressサイトであることの確認」が共通のため、ここへ集約する。
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
            site = siteRepository.findById(siteId).orElse(null);
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

            boolean isStylesheet = REL_STYLESHEET_PATTERN.matcher(tag).find();
            // 最適化プラグイン(Autoptimize/WP Rocket等)は rel="preload" as="style" で読み込み、
            // JS実行後にonloadでrel="stylesheet"へ書き換える。プレビューはJSを実行しないため、
            // このパターンも通常のstylesheetと同様に扱う。
            boolean isPreloadStyle = !isStylesheet
                    && REL_PRELOAD_PATTERN.matcher(tag).find()
                    && AS_STYLE_PATTERN.matcher(tag).find();
            if (!isStylesheet && !isPreloadStyle) {
                logger.debug("  -> No rel=\"stylesheet\" or rel=\"preload\" as=\"style\" found, skipping");
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
        if (urls.size() >= MAX_STYLESHEETS) {
            logger.warn("Reached MAX_STYLESHEETS ({}) limit; remaining <link rel=\"stylesheet\"> tags "
                    + "on the page were not collected", MAX_STYLESHEETS);
        }
        return urls;
    }

    /**
     * Critical CSSや wp_add_inline_style() 等でページに直接埋め込まれるインライン<style>ブロックの
     * 中身を収集する。<link rel="stylesheet">では収集できないCSSを補うため。
     */
    private List<String> extractInlineStyles(String html) {
        List<String> styles = new ArrayList<>();
        Matcher matcher = STYLE_BLOCK_PATTERN.matcher(html);
        while (matcher.find()) {
            String content = matcher.group(1).trim();
            if (!content.isEmpty()) {
                styles.add(content);
            }
        }
        return styles;
    }

    /**
     * ブラウザ相当のUser-Agent/Acceptヘッダーを付与したRestClientを返す(Issue #245)。
     */
    private RestClient browserLikeClient() {
        RestClient.Builder builder = restClientBuilder.clone()
                .defaultHeader(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT)
                .defaultHeader(HttpHeaders.ACCEPT,
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "ja,en-US;q=0.9,en;q=0.8");
        // WP REST API(/wp-json/wp/v2/posts)の応答をJsonNode(Jackson2)へデシリアライズするため、
        // Boot4既定のJackson3コンバータをJackson2へ差し替える(LegacyJacksonRestClientConfig参照)。
        LegacyJacksonRestClientConfig.preferJackson2(builder);
        return builder.build();
    }

    private String fetchAndConcatStylesheets(List<String> stylesheetUrls) {
        StringBuilder css = new StringBuilder();
        RestClient client = browserLikeClient();
        for (String url : stylesheetUrls) {
            try {
                String body = client.get().uri(URI.create(url)).retrieve().body(String.class);
                if (body == null || body.isBlank()) {
                    continue;
                }
                String entry = "/* " + url + " */\n" + rewriteRelativeCssUrls(body, url) + "\n";
                // 上限超過分を単純に切り詰めるとCSSが構文の途中で壊れるため、
                // 上限を超えるstylesheetは丸ごとスキップし、既に連結済みの内容は壊さない。
                if (css.length() + entry.length() > MAX_CSS_LENGTH) {
                    logger.warn("Skipping stylesheet because concatenated CSS would exceed "
                            + "MAX_CSS_LENGTH ({}): {}", MAX_CSS_LENGTH, url);
                    continue;
                }
                css.append(entry);
            } catch (Exception e) {
                logger.debug("Failed to fetch stylesheet: {}", url, e);
            }
        }
        return css.toString();
    }

    /**
     * CSS内の url(...) の相対参照を、そのstylesheet自身の絶対URLを基準に絶対URLへ書き換える。
     * 取得したCSSはWebview内の<style>として埋め込まれ、埋め込み先のbase URIはstylesheetの
     * オリジンと異なるため、相対参照のままだとフォントや背景画像が解決できなくなる。
     */
    private String rewriteRelativeCssUrls(String css, String stylesheetUrl) {
        URI base;
        try {
            base = URI.create(stylesheetUrl);
        } catch (IllegalArgumentException e) {
            return css;
        }
        Matcher matcher = CSS_URL_PATTERN.matcher(css);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String quote = matcher.group(1);
            String value = matcher.group(2).trim();
            String replacement = matcher.group(0);
            if (!value.startsWith("data:") && !value.startsWith("http://")
                    && !value.startsWith("https://") && !value.startsWith("//")) {
                try {
                    String resolved = base.resolve(value).toString();
                    replacement = "url(" + quote + resolved + quote + ")";
                } catch (IllegalArgumentException ignored) {
                    // 解決できない場合は元の値をそのまま残す
                }
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
