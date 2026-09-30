package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.publishing.client.ContentServiceClient;
import com.letsblog.publishing.cms.AuthCookie;
import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.cms.MediaUploadResult;
import com.letsblog.publishing.cms.PostContent;
import com.letsblog.publishing.cms.PostResult;
import com.letsblog.publishing.cms.ReferencePost;
import com.letsblog.publishing.cms.agent.WordPressAgentOperations;
import com.letsblog.publishing.cms.ssh.WordPressSshOperations;
import com.letsblog.publishing.config.LegacyJacksonRestClientConfig;
import com.letsblog.publishing.config.StylesheetFetchExecutorConfig;
import com.letsblog.publishing.domain.Project;
import com.letsblog.publishing.domain.Site;
import com.letsblog.publishing.dto.ThemeCssResponse;
import com.letsblog.publishing.dto.ThemeSkeletonResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VSCode拡張の記事プレビュー機能向けに、プロジェクトのマスター環境サイトのテーマCSS取得・実テーマの
 * DOM構造を保った骨格差し替えを行う。元はlegacy-apiのArticlePreviewServiceで、Markdown→HTML変換
 * パイプライン(renderHtml、CMSへの依存を持たない)だけが先にcontent-serviceへ移設され
 * (issue #576、{@code com.letsblog.content.service.ArticlePreviewService}参照)、
 * CmsAdapter/WordPressAgentOperations/WordPressSshOperationsに依存する残りの
 * fetchThemeCss/renderSkeleton/renderRealPrivatePost/deletePreviewPostが本サービスへ移設された
 * (issue #712、Epic #551 C6-6)。
 *
 * <p>Project/Siteの実体・CMS認証情報の所有権はproject-serviceにあるため、
 * {@link ProjectService}/{@link SiteService}経由(内部ブリッジ)で解決する。また、Playwrightを持つのは
 * content-serviceのため、実際のヘッドレスブラウザ操作(旧PreviewSkeletonFetcher)は
 * {@link ContentServiceClient}経由の内部ブリッジへ委譲する(CMS認証情報自体は転送せず、
 * 解決済みのnavigateUrl・認証Cookieのみ渡す)。
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

    private final ProjectService projectService;
    private final SiteService siteService;
    private final RestClient.Builder restClientBuilder;
    private final ContentServiceClient contentServiceClient;
    private final CmsAdapterFactory cmsAdapterFactory;
    private final WordPressAgentOperations wordPressAgentOperations;
    private final WordPressSshOperations wordPressSshOperations;
    private final ExecutorService stylesheetFetchExecutor;
    private final java.time.Duration stylesheetFetchTimeout;
    /** stylesheet取得専用のHTTPリクエストファクトリ(接続/読み取りタイムアウト付き)。nullなら注入されたBuilderのものを使う。 */
    private final ClientHttpRequestFactory stylesheetRequestFactory;

    /**
     * 1回のstylesheet一括取得(並列)を待つ時間の上限(issue #1473)。httpLoader()のRestClientには接続/読み取り
     * タイムアウトが無く、応答しない1枚が全体を無期限に待たせうる。並列化しても最遅の1枚が全体を決める
     * ため、ここで呼び出し側の待機を打ち切る。ただしFuture#cancelはCompletableFutureの実行スレッドを
     * 中断せず、ブロック中のソケット読み取りは割り込みでも解けないので、共有プール(6本)のワーカーは
     * これだけでは解放されない。ワーカーの解放は{@link #stylesheetRequestFactory}の接続/読み取り
     * タイムアウトが担う(stylesheet取得のRestClientにだけ適用し、WP REST等の他用途や注入された
     * Builderは変えない)。取得開始からの経過で数えるので、枚数が増えても待ち時間の上限は変わらない
     * (SyncCallProfileのRENDER=30秒より短くし、プレビュー全体の上限に収まる値にした)。
     */
    private static final java.time.Duration DEFAULT_STYLESHEET_FETCH_TIMEOUT = java.time.Duration.ofSeconds(20);

    /**
     * 1回のテーマCSS取得におけるstylesheet取得フェーズ(トップページ分と投稿ページ分を合わせたもの)の
     * 締切(issue #1206)。ページHTML自体の取得は対象外(別Issue #1273)。
     * concatStylesheetsの締切は呼び出しごとに数え直されるため、トップと投稿ページで2回呼ばれると
     * 最悪でDEFAULT_STYLESHEET_FETCH_TIMEOUT(20秒)の2倍かかる。VSCode拡張・gatewayの待機に収まる
     * 「十数秒」にするため、stylesheet取得フェーズで1つの締切を共有する。stylesheet取得の締切が短く注入された場合は
     * そちらを上限にする({@link #themeCssFetchBudget()})。
     */
    private static final java.time.Duration THEME_CSS_FETCH_BUDGET = java.time.Duration.ofSeconds(15);

    /** 接続の確立に待つ上限。待機上限(20秒)より十分短く、遅い相手にワーカーを長く預けない。 */
    private static final java.time.Duration STYLESHEET_CONNECT_TIMEOUT = java.time.Duration.ofSeconds(5);

    /** ソケット読み取り1回に待つ上限。待機上限(20秒)より短くし、待機を打ち切った後にワーカーも解放される。 */
    private static final java.time.Duration STYLESHEET_READ_TIMEOUT = java.time.Duration.ofSeconds(10);

    /**
     * stylesheet取得用の、接続/読み取りタイムアウトとリダイレクト追従を持つリクエストファクトリ。
     * リダイレクトはNORMAL(http→httpsは追従、https→httpは追従しない)。HttpURLConnectionベースだと
     * http→httpsを追従せず、httpで書かれたstylesheetのURLがhttpsへ移るサイトのCSSを取り損ねる。
     * JDK HttpClientの読み取りタイムアウトは応答ヘッダーまでを対象とし、ヘッダー後に本文が止まると
     * 効かない。その場合のワーカーは解放されないが、呼び出し側は待機上限(DEFAULT_STYLESHEET_FETCH_TIMEOUT)
     * で打ち切られるのでプレビューは止まらない。
     */
    static ClientHttpRequestFactory stylesheetRequestFactory(
            java.time.Duration connectTimeout, java.time.Duration readTimeout) {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    /** 単体テスト用: 本番と同じ上限の専用Executorを内部で作る。 */
    ArticlePreviewService(
            ProjectService projectService,
            SiteService siteService,
            RestClient.Builder restClientBuilder,
            ContentServiceClient contentServiceClient,
            CmsAdapterFactory cmsAdapterFactory,
            WordPressAgentOperations wordPressAgentOperations,
            WordPressSshOperations wordPressSshOperations) {
        this(projectService, siteService, restClientBuilder, contentServiceClient, cmsAdapterFactory,
                wordPressAgentOperations, wordPressSshOperations, StylesheetFetchExecutorConfig.newExecutor(),
                DEFAULT_STYLESHEET_FETCH_TIMEOUT, null);
    }

    @Autowired
    public ArticlePreviewService(
            ProjectService projectService,
            SiteService siteService,
            RestClient.Builder restClientBuilder,
            ContentServiceClient contentServiceClient,
            CmsAdapterFactory cmsAdapterFactory,
            WordPressAgentOperations wordPressAgentOperations,
            WordPressSshOperations wordPressSshOperations,
            ExecutorService stylesheetFetchExecutor) {
        this(projectService, siteService, restClientBuilder, contentServiceClient, cmsAdapterFactory,
                wordPressAgentOperations, wordPressSshOperations, stylesheetFetchExecutor,
                DEFAULT_STYLESHEET_FETCH_TIMEOUT,
                stylesheetRequestFactory(STYLESHEET_CONNECT_TIMEOUT, STYLESHEET_READ_TIMEOUT));
    }

    /** 単体テスト用: 注入されたBuilderのHTTP層をそのまま使う(タイムアウトの差し替えなし)。 */
    ArticlePreviewService(
            ProjectService projectService,
            SiteService siteService,
            RestClient.Builder restClientBuilder,
            ContentServiceClient contentServiceClient,
            CmsAdapterFactory cmsAdapterFactory,
            WordPressAgentOperations wordPressAgentOperations,
            WordPressSshOperations wordPressSshOperations,
            ExecutorService stylesheetFetchExecutor,
            java.time.Duration stylesheetFetchTimeout) {
        this(projectService, siteService, restClientBuilder, contentServiceClient, cmsAdapterFactory,
                wordPressAgentOperations, wordPressSshOperations, stylesheetFetchExecutor,
                stylesheetFetchTimeout, null);
    }

    ArticlePreviewService(
            ProjectService projectService,
            SiteService siteService,
            RestClient.Builder restClientBuilder,
            ContentServiceClient contentServiceClient,
            CmsAdapterFactory cmsAdapterFactory,
            WordPressAgentOperations wordPressAgentOperations,
            WordPressSshOperations wordPressSshOperations,
            ExecutorService stylesheetFetchExecutor,
            java.time.Duration stylesheetFetchTimeout,
            ClientHttpRequestFactory stylesheetRequestFactory) {
        this.projectService = projectService;
        this.siteService = siteService;
        this.restClientBuilder = restClientBuilder;
        this.contentServiceClient = contentServiceClient;
        this.cmsAdapterFactory = cmsAdapterFactory;
        this.wordPressAgentOperations = wordPressAgentOperations;
        this.wordPressSshOperations = wordPressSshOperations;
        this.stylesheetFetchExecutor = stylesheetFetchExecutor;
        this.stylesheetFetchTimeout = stylesheetFetchTimeout;
        this.stylesheetRequestFactory = stylesheetRequestFactory;
    }

    private java.time.Duration themeCssFetchBudget() {
        return stylesheetFetchTimeout.compareTo(THEME_CSS_FETCH_BUDGET) < 0
                ? stylesheetFetchTimeout : THEME_CSS_FETCH_BUDGET;
    }

    /**
     * 1回のテーマCSS取得のstylesheet取得フェーズで、複数のconcatStylesheets呼び出しが共有する締切と、取得できなかった
     * stylesheetの記録(issue #1206)。
     */
    private static final class StylesheetFetchReport {
        private static final int MAX_REPORTED_URLS = 5;

        private final long deadlineNanos;
        private final List<String> failedUrls = new ArrayList<>();
        private int attempted;

        StylesheetFetchReport(java.time.Duration budget) {
            this.deadlineNanos = System.nanoTime() + budget.toNanos();
        }

        /** 1件も取得を試みていない、または全件成功ならnull(reasonなし)。 */
        String reasonOrNull() {
            if (failedUrls.isEmpty()) {
                return null;
            }
            String shown = String.join(", ", failedUrls.subList(0, Math.min(failedUrls.size(), MAX_REPORTED_URLS)));
            String more = failedUrls.size() > MAX_REPORTED_URLS
                    ? " ほか" + (failedUrls.size() - MAX_REPORTED_URLS) + "件" : "";
            String subject = allFailed() ? "すべてのstylesheet(" + attempted + "件)" : "一部のstylesheet("
                    + failedUrls.size() + "件/" + attempted + "件中)";
            return subject + "を取得できませんでした: " + shown + more;
        }

        boolean allFailed() {
            return attempted > 0 && failedUrls.size() >= attempted;
        }
    }

    /** 取得結果へ失敗の報告を添える。全滅ならavailable=false、一部欠落ならavailable=trueのままreasonで知らせる。 */
    private ThemeCssResponse withFetchReport(String css, StylesheetFetchReport report, String source) {
        // 全滅でも、インラインstyle等で得られたCSSが1文字でもあれば捨てず、available=trueのままreasonで知らせる
        if (report.allFailed() && css.isEmpty()) {
            return new ThemeCssResponse("", false, report.reasonOrNull());
        }
        return new ThemeCssResponse(css, true, report.reasonOrNull(), source);
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

        // SSH管理サイトは、まずリモートホスト自身からHTML/CSSを取得する(issue #1368)。失敗したら
        // 従来のHTTP経路へ自動でフォールバックする。managed(AGENT)/RESTサイトは従来どおり。
        // 認証情報の取得はSSH判定のためだけの先行参照。失敗しても非SSHとして扱い、従来どおり
        // HTTP取得の結果(available=false等)を返せるようにする(要件6: 非SSHサイトの挙動は変えない)。
        CmsCredentials credentials;
        try {
            credentials = siteService.getCredentials(site.getSiteKey());
        } catch (Exception e) {
            logger.debug("Credentials lookup failed, treating as non-SSH site: {}", site.getSiteKey(), e);
            credentials = null;
        }
        boolean sshFallback = false;
        if (credentials instanceof CmsCredentials.WordPressCredentials wpCredentials && wpCredentials.isSsh()) {
            try {
                ThemeCssResponse viaSsh = fetchThemeCssViaSsh(site, wpCredentials);
                if (viaSsh != null) {
                    return viaSsh;
                }
            } catch (Exception e) {
                logger.warn("SSH経由のテーマCSS取得に失敗したためHTTP経路へフォールバックします: {}",
                        site.getSiteKey(), e);
            }
            sshFallback = true;
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

        StylesheetFetchReport report = new StylesheetFetchReport(themeCssFetchBudget());
        StringBuilder css = new StringBuilder(concatStylesheets(stylesheetUrls, httpLoader(), report));
        for (String inlineStyle : inlineStyles) {
            if (css.length() >= MAX_CSS_LENGTH) {
                break;
            }
            css.append("/* inline <style> */\n").append(inlineStyle).append("\n");
        }
        if (credentials == null) {
            // 先行参照が失敗(またはnull)だった場合は、従来どおりここで改めて取得する
            credentials = siteService.getCredentials(site.getSiteKey());
        }
        appendPostPageCss(css, fetchUrl, site, internalOrigin, credentials, sshFallback, report);
        String result = css.length() > MAX_CSS_LENGTH ? css.substring(0, MAX_CSS_LENGTH) : css.toString();
        return withFetchReport(result, report, ThemeCssResponse.SOURCE_HTTP);
    }

    /**
     * SSH管理サイトのテーマCSSを、リモートホスト経由で取得する(issue #1368)。トップページ/参照投稿ページの
     * HTMLは{@link WordPressSshOperations#fetchPageHtml}(リモートでのwp_remote_get)、サイト内stylesheetは
     * SFTP({@link WordPressSshOperations#readStylesheetFile})で読み、外部ホストのstylesheetと、SFTPで
     * 読めなかったサイト内stylesheetはHTTPでベストエフォート取得する。
     *
     * トップページのHTML取得に失敗、またはstylesheet/インラインstyleが1つも無い(ボット対策の
     * チャレンジページ等)場合は、呼び出し側がHTTP経路へフォールバックできるよう例外またはnullを返す。
     */
    private ThemeCssResponse fetchThemeCssViaSsh(Site site, CmsCredentials.WordPressCredentials creds) {
        WordPressSshOperations.SiteFileLayout layout = wordPressSshOperations.fetchSiteFileLayout(creds);
        SshPageSource source = new SshPageSource(creds, layout);
        String home = layout.home() + "/";
        String html = wordPressSshOperations.fetchPageHtml(creds, layout, home);
        if (html == null || html.isBlank()) {
            logger.warn("Empty HTML fetched via SSH for site: {}", site.getSiteKey());
            return null;
        }
        List<String> stylesheetUrls = extractStylesheetUrls(html, home);
        List<String> inlineStyles = extractInlineStyles(html);
        if (stylesheetUrls.isEmpty() && inlineStyles.isEmpty()) {
            logger.warn("No stylesheet links or inline <style> blocks found via SSH for site: {}", site.getSiteKey());
            return null;
        }
        StylesheetFetchReport report = new StylesheetFetchReport(themeCssFetchBudget());
        StringBuilder css = new StringBuilder(concatStylesheets(stylesheetUrls, source::load, report));
        for (String inlineStyle : inlineStyles) {
            if (css.length() >= MAX_CSS_LENGTH) {
                break;
            }
            css.append("/* inline <style> */\n").append(inlineStyle).append("\n");
        }
        // 投稿ページ分はベストエフォート: 失敗してもトップページ分のCSSは活かす
        try {
            appendPostPageCss(css, home, site, null, creds, false, source, report);
        } catch (Exception e) {
            logger.warn("SSH経由の投稿ページCSS取得に失敗しました(トップページ分のみ返します): {}",
                    site.getSiteKey(), e);
        }
        String result = css.length() > MAX_CSS_LENGTH ? css.substring(0, MAX_CSS_LENGTH) : css.toString();
        return withFetchReport(result, report, ThemeCssResponse.SOURCE_SSH);
    }

    /**
     * SSH経路でのHTML/stylesheet取得。サイト内stylesheetはSFTP、それ以外(外部ホスト、SFTPで
     * 読めなかったもの)はHTTPで取得する({@link #httpStylesheetLoader})。
     */
    private class SshPageSource {
        private final CmsCredentials.WordPressCredentials creds;
        private final WordPressSshOperations.SiteFileLayout layout;

        SshPageSource(CmsCredentials.WordPressCredentials creds, WordPressSshOperations.SiteFileLayout layout) {
            this.creds = creds;
            this.layout = layout;
        }

        String fetchHtml(String url) {
            return wordPressSshOperations.fetchPageHtml(creds, layout, url);
        }

        String load(String url) {
            if (layout.owns(url)) {
                try {
                    java.util.Optional<String> file = wordPressSshOperations.readStylesheetFile(creds, layout, url);
                    if (file.isPresent()) {
                        return file.get();
                    }
                } catch (Exception e) {
                    logger.debug("Failed to read stylesheet via SFTP, falling back to HTTP: {}", url, e);
                }
            }
            return httpStylesheetLoader(url);
        }
    }

    /**
     * is_single()等でトップページには読み込まれず投稿ページ限定で読み込まれるCSS(Issue #337)を補うため、
     * サイト内の最新投稿ページ(renderSkeletonの参照記事解決と同じ参照記事)を対象にも
     * stylesheet/インラインstyleを収集し、トップページ分のCSSへ追記する。
     *
     * renderSkeletonのようなPlaywrightナビゲーションを伴わない軽量な代替経路のため、参照記事の取得や
     * ページ取得に失敗しても、既に得られているトップページのCSSは活かせるようベストエフォートで扱い、
     * 例外はログのみで握りつぶす。
     */
    private void appendPostPageCss(
            StringBuilder css, String fetchOrigin, Site site, String internalOrigin, CmsCredentials credentials,
            boolean sshFallback, StylesheetFetchReport report) {
        appendPostPageCss(css, fetchOrigin, site, internalOrigin, credentials, sshFallback, null, report);
    }

    /**
     * sshSourceがnullでなければSSH経路(HTML/stylesheetをリモート経由で取得)、nullならHTTP経路。
     * sshFallbackは、SSH経路が失敗してHTTP経路へ落ちてきたことを示す。SSHが落ちているので
     * wp-cliでの参照記事取得も失敗しうるため、その場合は例外にせずREST取得へ落とす。
     */
    private void appendPostPageCss(
            StringBuilder css, String fetchOrigin, Site site, String internalOrigin, CmsCredentials credentials,
            boolean sshFallback, SshPageSource sshSource, StylesheetFetchReport report) {
        if (css.length() >= MAX_CSS_LENGTH) {
            return;
        }
        String referenceLink;
        WpCliReferencePostLookup lookup;
        try {
            lookup = lookupReferencePostViaWpCli(credentials, site);
        } catch (Exception e) {
            if (!sshFallback) {
                throw e;
            }
            logger.debug("Reference post lookup via SSH failed after SSH fallback: {}", site.getSiteKey(), e);
            lookup = new WpCliReferencePostLookup(false, null);
        }
        if (lookup.supported()) {
            if (lookup.referencePost() == null) {
                return;
            }
            referenceLink = lookup.referencePost().link();
        } else {
            String base = fetchOrigin.endsWith("/") ? fetchOrigin : fetchOrigin + "/";
            JsonNode posts;
            try {
                posts = browserLikeClient().get()
                        .uri(base + "wp-json/wp/v2/posts?per_page=1&orderby=date&order=desc&_fields=id,link")
                        .retrieve()
                        .body(JsonNode.class);
            } catch (Exception e) {
                logger.debug("Failed to fetch reference post for post-page CSS fallback: {}", site.getSiteKey(), e);
                return;
            }
            if (posts == null || posts.size() == 0) {
                return;
            }
            referenceLink = posts.get(0).path("link").asText(null);
            if (referenceLink == null) {
                return;
            }
        }
        String publicOrigin = originOf(site.getBaseUrl());
        String postPageUrl = internalOrigin != null
                ? rewriteToInternalOrigin(referenceLink, publicOrigin, internalOrigin)
                : referenceLink;

        String postHtml;
        try {
            postHtml = sshSource != null
                    ? sshSource.fetchHtml(postPageUrl)
                    : browserLikeClient().get().uri(URI.create(postPageUrl)).retrieve().body(String.class);
        } catch (Exception e) {
            logger.debug("Failed to fetch post page for CSS fallback: {}", postPageUrl, e);
            return;
        }
        if (postHtml == null || postHtml.isBlank()) {
            return;
        }

        List<String> postStylesheetUrls = extractStylesheetUrls(postHtml, site.getBaseUrl());
        List<String> postInlineStyles = extractInlineStyles(postHtml);
        if (postStylesheetUrls.isEmpty() && postInlineStyles.isEmpty()) {
            return;
        }
        logger.debug("Collected {} additional stylesheet URLs and {} inline <style> blocks from post page "
                + "for site: {}", postStylesheetUrls.size(), postInlineStyles.size(), site.getSiteKey());
        if (internalOrigin != null && !postStylesheetUrls.isEmpty()) {
            postStylesheetUrls = rewriteToInternalOrigin(postStylesheetUrls, publicOrigin, internalOrigin);
        }

        String postCss = sshSource != null
                ? concatStylesheets(postStylesheetUrls, sshSource::load, report)
                : concatStylesheets(postStylesheetUrls, httpLoader(), report);
        if (!postCss.isEmpty() && css.length() + postCss.length() <= MAX_CSS_LENGTH) {
            css.append(postCss);
        }
        for (String inlineStyle : postInlineStyles) {
            if (css.length() >= MAX_CSS_LENGTH) {
                break;
            }
            css.append("/* inline <style> (post page) */\n").append(inlineStyle).append("\n");
        }
    }

    /**
     * サイト内の既存記事ページを骨格として流用し、実テーマのDOM構造(タイトルの見出し要素、
     * アイキャッチ、本文コンテナ等)を保ったまま、プレビュー対象記事のタイトル/本文/アイキャッチへ
     * 差し替えたHTML断片を返す。
     *
     * 差し替え位置は、サイト内の最新記事をWP REST APIで取得し、そのtitle.rendered/content.renderedを
     * 実際に描画されたDOM内から検索することで特定する(Playwrightを持つcontent-serviceへの
     * 内部ブリッジ、{@link ContentServiceClient#fetchAndSplice}参照)。参照記事が存在しない、
     * 差し替え位置を特定できない等の場合はavailable=falseを返し、呼び出し側で従来の表示
     * (テーマDOM構造を再現しないプレーンな表示)へフォールバックする。
     *
     * ただし対象サイトが本番以外、かつ認証情報がサーバー側コード実行手段を持つ経路(managed
     * WordPressのagent transport、またはSSH transport)の場合は、この差し替え探索を行わず、
     * プレビュー対象記事そのものを非公開(private)投稿として実際にWordPressへ作成し、その実ページを
     * 直接閲覧する経路({@link #renderRealPrivatePost}参照)を使う。こちらは差し替え位置の特定が
     * 原理的に不要なため、上記のような特定失敗が発生しない。REST(Application Password)のみの
     * 経路はサーバー側で認証Cookieを発行できないため、従来のスクレイプ&amp;スプライス経路を使う。
     *
     * 本番サイトは一旦この経路の対象から除外している(issue #483フォローアップ)。本番はCloudflare等の
     * ボット対策(Managed Challenge)を経由することが多く、Playwrightのヘッドレスブラウザは自動化として
     * 検知され、待っても自動突破できない対話式チャレンジへ誘導されてプレビューが常に空白になる上、
     * 非公開投稿だけが実際のサイトに作成されてしまう(閲覧できないのに投稿だけは残る)。ボット対策側の
     * 例外設定なしにコード側だけで確実に解消する手段が無いため、本番は安全側の従来経路(スクレイプ&splice、
     * 参照記事が無ければavailable=false)へ戻す。
     */
    public ThemeSkeletonResponse renderSkeleton(
            Long projectId, Long siteId, String title, String contentHtml, String featuredImageDataUri,
            String existingPreviewPostId, String slug, List<String> categories, List<String> tags) {
        Project project = projectService.getProjectEntity(projectId);
        SiteResolution resolution = resolveSiteForPreview(project, siteId);
        if (resolution.site() == null) {
            return new ThemeSkeletonResponse(null, false, resolution.errorReason(), false, "");
        }
        Site site = resolution.site();
        boolean isProductionSite = project.getProductionSiteId() != null
                && project.getProductionSiteId().equals(site.getId());

        CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
        // usernameは閲覧用Cookie発行(generateAuthCookie)がなりすます対象のWordPressユーザーを
        // 特定するのに必須。無ければCookieを発行できず非公開投稿を閲覧できないため、その場合は
        // 投稿の作成自体を行わず(孤立した非公開投稿を残さないため)従来のスクレイプ&スプライス経路へ
        // フォールバックする(SSH transportではusernameがそもそも登録されていないサイトがありうる)。
        if (!isProductionSite && credentials instanceof CmsCredentials.WordPressCredentials wpCredentials
                && (wpCredentials.isAgent() || wpCredentials.isSsh())
                && StringUtils.hasText(wpCredentials.username())) {
            return renderRealPrivatePost(site, credentials, title, contentHtml, featuredImageDataUri,
                    existingPreviewPostId, slug, categories, tags);
        }

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

        String titleRendered;
        String contentRendered;
        String referenceLink;
        WpCliReferencePostLookup lookup = lookupReferencePostViaWpCli(credentials, site);
        if (lookup.supported()) {
            if (lookup.referencePost() == null) {
                return new ThemeSkeletonResponse(null, false, "参照記事が見つかりませんでした", false, "");
            }
            titleRendered = lookup.referencePost().title();
            contentRendered = lookup.referencePost().content();
            referenceLink = lookup.referencePost().link();
        } else {
            JsonNode posts;
            try {
                posts = browserLikeClient().get()
                        .uri(base + "wp-json/wp/v2/posts?per_page=1&orderby=date&order=desc"
                                + "&_fields=id,link,title,content")
                        .retrieve()
                        .body(JsonNode.class);
            } catch (Exception e) {
                logger.warn("Failed to fetch reference post for skeleton preview: {}", site.getSiteKey(), e);
                return new ThemeSkeletonResponse(null, false, "参照記事の取得に失敗しました: " + e.getMessage(), false, "");
            }
            if (posts == null || posts.size() == 0) {
                return new ThemeSkeletonResponse(null, false, "参照記事が見つかりませんでした", false, "");
            }
            JsonNode reference = posts.get(0);
            titleRendered = reference.path("title").path("rendered").asText("");
            contentRendered = reference.path("content").path("rendered").asText("");
            referenceLink = reference.path("link").asText(null);
            if (referenceLink == null) {
                return new ThemeSkeletonResponse(null, false, "参照記事のURLを取得できませんでした", false, "");
            }
        }

        // 参照記事のlinkはWordPressのsiteurl設定(公開URL)を基準に絶対URLで出力されるため、
        // fetchThemeCssと同様、managedサイトでは公開オリジンを内部オリジンへ差し替えてから
        // Playwrightのナビゲーション先として使う(APIコンテナ自身からは公開URLに到達できないため)。
        String publicOrigin = originOf(site.getBaseUrl());
        String navigateUrl = internalOrigin != null
                ? rewriteToInternalOrigin(referenceLink, publicOrigin, internalOrigin)
                : referenceLink;

        ThemeSkeletonResponse spliced;
        List<String> unreadable;
        try {
            ContentServiceClient.ThemeSkeletonBridgeResponse bridged = contentServiceClient.fetchAndSplice(
                    navigateUrl, titleRendered, contentRendered, title, contentHtml, featuredImageDataUri);
            spliced = new ThemeSkeletonResponse(
                    bridged.html(), bridged.available(), bridged.reason(), bridged.eyecatchSpliced(), bridged.css());
            unreadable = bridged.unreadableStylesheets();
        } catch (Exception e) {
            logger.warn("Failed to render skeleton preview for site: {}", site.getSiteKey(), e);
            return new ThemeSkeletonResponse(null, false, "記事ページの取得に失敗しました: " + e.getMessage(), false, "");
        }
        // Playwright側で解決された絶対URL(img src/a href、CSS内のurl()等)は内部オリジン基準のため、
        // Webviewから実際に読み込める公開オリジンへ戻してから返す。本文の差し替え位置を特定できず
        // htmlがavailable=falseの場合でも、ナビゲーション自体には成功していればcssは収集できているため、
        // 呼び出し側(拡張機能)がトップページのCSSとマージできるよう合わせて返す。
        String mergedCss = supplementUnreadableStylesheets(spliced.css(), unreadable, site, credentials);
        String css = internalOrigin != null ? mergedCss.replace(internalOrigin, publicOrigin) : mergedCss;
        if (!spliced.available() || spliced.html() == null) {
            return new ThemeSkeletonResponse(spliced.html(), false, spliced.reason(), spliced.eyecatchSpliced(), css);
        }
        String html = internalOrigin != null ? spliced.html().replace(internalOrigin, publicOrigin) : spliced.html();
        return new ThemeSkeletonResponse(html, true, null, spliced.eyecatchSpliced(), css);
    }

    /**
     * プレビュー対象記事そのものを非公開(private)投稿としてWordPressへ作成/更新し、その実ページを
     * 認証Cookie付きで直接閲覧する。差し替え位置の探索を行わないため、{@link #renderSkeleton}の
     * スクレイピング&amp;スプライス経路と異なり、本文の位置を特定できず失敗するケースが発生しない。
     *
     * サーバー側でwp-cliを実行できる経路(managed WordPressのagent transport、またはSSH transport)
     * 限定({@link #renderSkeleton}のガード参照)。そうした経路でのみ、閲覧用の認証Cookieをリモート
     * 発行できるため。本番環境であっても、対象サイトの認証情報がこれらの経路であれば同じ処理を使う
     * (実データベースへ非公開投稿として書き込みが発生する点に注意)。
     */
    private ThemeSkeletonResponse renderRealPrivatePost(
            Site site, CmsCredentials credentials, String title, String contentHtml,
            String featuredImageDataUri, String existingPreviewPostId, String slug,
            List<String> categories, List<String> tags) {
        CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());
        // 通常の投稿(PostPublishService)と同様、front matterのcategories/tags(名前)をCMS側の
        // IDへ解決してから渡す。ここを素通りさせるとプレビュー用の非公開投稿にカテゴリ/タグが
        // 一切反映されない(issue #483 フィードバック)。
        List<String> categoryIds = cmsAdapter.resolveCategories(credentials, categories != null ? categories : List.of());
        List<String> tagIds = cmsAdapter.resolveTags(credentials, tags != null ? tags : List.of());

        String featuredMediaId = null;
        // アップロード失敗はここでは中断せず、投稿自体はアイキャッチ無しで継続する
        // (プレビューの本文確認自体は妨げないため)。ただし利用者がアイキャッチの欠落に
        // 気付けるよう、成功レスポンスのwarningとして呼び出し側(拡張機能)へ伝える。
        String eyecatchWarning = null;
        if (StringUtils.hasText(featuredImageDataUri)) {
            try {
                DecodedDataUri decoded = decodeDataUri(featuredImageDataUri);
                String extension = previewImageExtension(decoded.contentType());
                if (extension == null) {
                    throw new IllegalArgumentException("非対応の画像形式です: " + decoded.contentType());
                }
                MediaUploadResult media = cmsAdapter.uploadMedia(
                        credentials, "preview-featured-image" + extension, decoded.contentType(), decoded.data());
                featuredMediaId = media.id();
            } catch (Exception e) {
                logger.warn("プレビュー用アイキャッチのアップロードに失敗しました: {}", site.getSiteKey(), e);
                eyecatchWarning = "アイキャッチ画像のアップロードに失敗しました: " + e.getMessage();
            }
        }

        PostContent content = new PostContent(
                title, slug, contentHtml, "private", categoryIds, tagIds, featuredMediaId, null);

        PostResult result;
        try {
            result = cmsAdapter.createOrUpdatePost(credentials, content, existingPreviewPostId);
        } catch (Exception e) {
            logger.warn("プレビュー用非公開投稿の作成に失敗しました: {}", site.getSiteKey(), e);
            return new ThemeSkeletonResponse(
                    null, false, "非公開投稿の作成に失敗しました: " + e.getMessage(), false, "", existingPreviewPostId);
        }

        // ここから先で失敗しても投稿自体は作成/更新済みのため、result.id()を返して呼び出し側
        // (拡張機能)が追跡・削除できるようにする(existingPreviewPostIdのままだと、投稿がAPI側では
        // 孤立し、パネルを閉じても削除できなくなる)。
        AuthCookie cookie;
        try {
            cookie = cmsAdapter.generateAuthCookie(credentials);
        } catch (Exception e) {
            logger.warn("プレビュー用投稿の認証Cookie発行に失敗しました: {}", site.getSiteKey(), e);
            return new ThemeSkeletonResponse(
                    null, false, "認証Cookieの発行に失敗しました: " + e.getMessage(), false, "", result.id());
        }

        String publicOrigin = originOf(site.getBaseUrl());
        String internalBaseUrl = resolveManagedInternalBaseUrl(site);
        String internalOrigin = internalBaseUrl != null ? originOf(internalBaseUrl) : null;
        String navigateUrl = internalOrigin != null
                ? rewriteToInternalOrigin(result.link(), publicOrigin, internalOrigin)
                : result.link();

        ThemeSkeletonResponse fetched;
        List<String> unreadable;
        try {
            ContentServiceClient.ThemeSkeletonBridgeResponse bridged =
                    contentServiceClient.fetchRealPost(navigateUrl, cookie.name(), cookie.value());
            fetched = new ThemeSkeletonResponse(
                    bridged.html(), bridged.available(), bridged.reason(), bridged.eyecatchSpliced(), bridged.css());
            unreadable = bridged.unreadableStylesheets();
        } catch (Exception e) {
            logger.warn("プレビュー用投稿ページの取得に失敗しました: {}", site.getSiteKey(), e);
            // issue #1207 Requirement 4: 401/403(認証エラー)は、タイムアウト・5xx・通信断といった
            // 他の失敗原因と混ぜず区別できるよう理由に明記する。ContentServiceClient#fetchRealPostは
            // RestClientResponseExceptionをIllegalStateExceptionのcauseとして包んで再送出するため、
            // causeチェーンを辿って判定する(ContentServiceClient自体は変更しない)。
            String prefix = isAuthFailure(e) ? "投稿ページの取得に失敗しました(認証エラー): "
                    : "投稿ページの取得に失敗しました: ";
            return new ThemeSkeletonResponse(
                    null, false, prefix + e.getMessage(), false, "", result.id());
        }

        String mergedCss = supplementUnreadableStylesheets(fetched.css(), unreadable, site, credentials);
        String css = internalOrigin != null ? mergedCss.replace(internalOrigin, publicOrigin) : mergedCss;
        if (!fetched.available() || fetched.html() == null) {
            return new ThemeSkeletonResponse(fetched.html(), false, fetched.reason(), false, css, result.id());
        }
        String html = internalOrigin != null ? fetched.html().replace(internalOrigin, publicOrigin) : fetched.html();
        return new ThemeSkeletonResponse(html, true, null, false, css, result.id(), eyecatchWarning);
    }

    /**
     * 骨格取得(Playwright)で{@code cssRules}を読めなかったstylesheet(issue #1370)を、テーマCSS取得と
     * 同じ経路(SSH管理サイトは{@link SshPageSource}=SFTP→HTTP、それ以外はHTTP)で取得して
     * 骨格のCSSへ追記する。
     *
     * <p>hrefで重複排除する(既にCSSに{@code /* href *}/の見出しで含まれるものは取得しない)。
     * ベストエフォートで、取得失敗はhref付きでログに残して読み飛ばす。追記後のCSSが
     * {@link #MAX_CSS_LENGTH}を超えるstylesheetは(構文を壊さないよう)丸ごとスキップする。
     * hrefはPlaywrightがナビゲートしたオリジン基準(managedサイトでは内部オリジン)のため、
     * そのまま取得でき、呼び出し側が公開オリジンへ戻す前に合流させる。
     */
    private String supplementUnreadableStylesheets(
            String css, List<String> hrefs, Site site, CmsCredentials credentials) {
        if (hrefs == null || hrefs.isEmpty()) {
            return css;
        }
        java.util.function.Function<String, String> loader = stylesheetLoaderFor(site, credentials);
        StringBuilder merged = new StringBuilder(css);
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String> toFetch = new ArrayList<>();
        for (String href : hrefs) {
            if (seen.add(href) && !css.contains("/* " + href + " */")) {
                toFetch.add(href);
            }
        }
        // 取得だけを並列に走らせ、追記とMAX_CSS_LENGTH判定は従来どおり入力順に行う(issue #1473)。
        long deadline = System.nanoTime() + stylesheetFetchTimeout.toNanos();
        List<CompletableFuture<String>> fetches = startFetches(toFetch, loader);
        for (int i = 0; i < toFetch.size(); i++) {
            String href = toFetch.get(i);
            try {
                String body = awaitFetch(fetches.get(i), deadline);
                if (body == null || body.isBlank()) {
                    continue;
                }
                String entry = "\n/* " + href + " */\n" + rewriteRelativeCssUrls(body, href) + "\n";
                if (merged.length() + entry.length() > MAX_CSS_LENGTH) {
                    logger.warn("Skipping unreadable-stylesheet supplement because CSS would exceed "
                            + "MAX_CSS_LENGTH ({}): {}", MAX_CSS_LENGTH, href);
                    continue;
                }
                merged.append(entry);
            } catch (InterruptedException e) {
                logger.warn("骨格プレビューの補完取得が割り込まれたため残りを取り消します: {} (site: {})",
                        href, site.getSiteKey(), failureOf(e));
                fetches.forEach(f -> f.cancel(true));
                break;
            } catch (Exception e) {
                logger.warn("骨格プレビューの読めなかったstylesheetの補完取得に失敗しました: {} (site: {})",
                        href, site.getSiteKey(), failureOf(e));
            }
        }
        return merged.toString();
    }

    /** SSH管理サイトはSFTP→HTTP、それ以外(またはSSHのレイアウト取得失敗時)はHTTPで取得する。 */
    private java.util.function.Function<String, String> stylesheetLoaderFor(
            Site site, CmsCredentials credentials) {
        if (credentials instanceof CmsCredentials.WordPressCredentials wp && wp.isSsh()) {
            try {
                return new SshPageSource(wp, wordPressSshOperations.fetchSiteFileLayout(wp))::load;
            } catch (Exception e) {
                logger.warn("SSHのレイアウト取得に失敗したためHTTPで補完します: {}", site.getSiteKey(), e);
            }
        }
        return httpLoader();
    }

    /**
     * issue #1207 Requirement 4: 例外(のcauseチェーン)に401/403の{@link RestClientResponseException}、
     * または本サービス自身のClient Credentialsトークン取得失敗
     * ({@link com.letsblog.common.auth.ServiceTokenUnavailableException}、issue #567)が
     * 含まれるかどうかで、認証エラーとそれ以外(タイムアウト・5xx・通信断)を区別する。
     */
    private boolean isAuthFailure(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof RestClientResponseException responseException) {
                HttpStatusCode status = responseException.getStatusCode();
                if (status.value() == 401 || status.value() == 403) {
                    return true;
                }
            }
            if (cause instanceof com.letsblog.common.auth.ServiceTokenUnavailableException) {
                return true;
            }
        }
        return false;
    }

    /**
     * プレビュー用アイキャッチのcontentTypeから、uploadMediaへ渡すファイル名の拡張子を解決する
     * (issue #1240)。拡張子なしファイル名(旧実装の固定文字列"preview-featured-image")では
     * WordPressの{@code wp_check_filetype_and_ext()}がMIMEを判定できずアップロードを拒否するため。
     * 未知の形式({@code decodeDataUri}の{@code application/octet-stream}フォールバックを含む)は
     * nullを返し、呼び出し側でアップロード自体を試みず警告とする。
     *
     * <p>公開経路の{@link PostPublishService#extensionForMimeType}はjpeg/png限定かつ
     * 元ファイル名によるフォールバックを持つため、そちらは変更せずこちらへ個別に定義する
     * (プレビューはgif/webp/svg+xmlも扱い、元ファイル名を持たない)。呼び出し元は
     * {@link #decodeDataUri}が返した非null文字列(未知形式は"application/octet-stream")
     * しか渡さないため、null受け取りは想定していない。
     */
    private String previewImageExtension(String contentType) {
        return switch (contentType.toLowerCase(java.util.Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            default -> null;
        };
    }

    /** data URI(data:&lt;contentType&gt;;base64,&lt;data&gt;)をデコードした結果。 */
    private record DecodedDataUri(String contentType, byte[] data) {
    }

    private DecodedDataUri decodeDataUri(String dataUri) {
        int commaIndex = dataUri.indexOf(',');
        if (!dataUri.startsWith("data:") || commaIndex < 0) {
            throw new IllegalArgumentException("data URI形式が不正です");
        }
        String meta = dataUri.substring("data:".length(), commaIndex);
        String contentType = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
        byte[] data = Base64.getDecoder().decode(dataUri.substring(commaIndex + 1));
        return new DecodedDataUri(contentType.isBlank() ? "application/octet-stream" : contentType, data);
    }

    /**
     * {@link #renderRealPrivatePost}で作成したプレビュー用の非公開投稿を削除する
     * (VSCode拡張側でプレビューパネルを閉じた際に呼ばれる。ゴミ箱への移動)。
     */
    public void deletePreviewPost(Long projectId, Long siteId, String postId) {
        Project project = projectService.getProjectEntity(projectId);
        SiteResolution resolution = resolveSiteForPreview(project, siteId);
        if (resolution.site() == null) {
            return;
        }
        Site site = resolution.site();
        CmsCredentials credentials = siteService.getCredentials(site.getSiteKey());
        CmsAdapter cmsAdapter = cmsAdapterFactory.resolve(credentials.cmsType());
        try {
            cmsAdapter.deletePost(credentials, postId);
        } catch (Exception e) {
            logger.warn("プレビュー用投稿の削除に失敗しました: site={}, postId={}", site.getSiteKey(), postId, e);
        }
    }

    /**
     * wp-cliが実行できる経路(managed WordPressのagent transport、またはSSH transport)であれば、
     * その経路(WordPressAgentOperations/WordPressSshOperations)経由で参照記事を取得する。
     * 従来はfetchThemeCss(投稿ページ限定CSSの補完)・renderSkeleton(スクレイプ&amp;スプライスの
     * 差し替え位置探索)の両方が、認証なしのWordPress REST API(wp-json/wp/v2/posts)を
     * CmsAdapterを経由しない独立した経路として直接叩いていたが、managed/SSH管理サイトは
     * 他の全操作と同じくwp-cli経由に揃える(issue #519)。
     *
     * supported=falseは、RESTトランスポート(Application Password)等、wp-cliに対応しない
     * 認証情報だったことを示す。呼び出し元はこの場合に限り、従来のREST直接呼び出しへ
     * フォールバックする(REST専用サイトにはwp-cliで代替する手段がないため)。
     */
    private WpCliReferencePostLookup lookupReferencePostViaWpCli(CmsCredentials credentials, Site site) {
        if (!(credentials instanceof CmsCredentials.WordPressCredentials wpCredentials)) {
            return new WpCliReferencePostLookup(false, null);
        }
        if (wpCredentials.isAgent()) {
            return new WpCliReferencePostLookup(true, wordPressAgentOperations.getLatestPost(wpCredentials)
                    .orElse(null));
        }
        if (wpCredentials.isSsh()) {
            return new WpCliReferencePostLookup(true, wordPressSshOperations.getLatestPost(wpCredentials)
                    .orElse(null));
        }
        return new WpCliReferencePostLookup(false, null);
    }

    /**
     * {@link #lookupReferencePostViaWpCli}の結果。supported=falseの場合、referencePostは常にnull
     * (呼び出し元はREST直接呼び出しへフォールバックする)。supported=trueでreferencePost==nullは、
     * wp-cli経由で参照記事が0件だったこと(サイトに公開済み投稿が無い)を示す。
     */
    private record WpCliReferencePostLookup(boolean supported, ReferencePost referencePost) {
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
        return browserLikeClient(null);
    }

    private RestClient browserLikeClient(ClientHttpRequestFactory requestFactory) {
        RestClient.Builder builder = restClientBuilder.clone();
        if (requestFactory != null) {
            builder.requestFactory(requestFactory);
        }
        builder
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
        return concatStylesheets(stylesheetUrls, httpLoader(), new StylesheetFetchReport(stylesheetFetchTimeout));
    }

    private java.util.function.Function<String, String> httpLoader() {
        RestClient client = browserLikeClient(stylesheetRequestFactory);
        return url -> client.get().uri(URI.create(url)).retrieve().body(String.class);
    }

    private String httpStylesheetLoader(String url) {
        return httpLoader().apply(url);
    }

    /** 取得開始時に決めた締め切りまでの残り時間だけ待つ。超えたらFutureを取り消してTimeoutExceptionを投げる。
     * 取り消しは未着手の取得を走らせないだけで、実行中のワーカーは中断しない(解放は読み取りタイムアウトが担う)。 */
    private String awaitFetch(CompletableFuture<String> fetch, long deadlineNanos) throws Exception {
        try {
            return fetch.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            fetch.cancel(true);
            throw e;
        }
    }

    /** ログには並列実行の包み(ExecutionException。CompletableFuture由来では必ずcauseを持つ)ではなく、従来と同じ元の例外を載せる。割り込みは保つ。 */
    private Throwable failureOf(Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return e instanceof ExecutionException ? e.getCause() : e;
    }

    /** 各URLの取得を共有Executorへ投入し、入力と同じ順序のFutureを返す(issue #1473)。 */
    private List<CompletableFuture<String>> startFetches(
            List<String> urls, java.util.function.Function<String, String> loader) {
        return urls.stream()
                .map(url -> CompletableFuture.supplyAsync(() -> loader.apply(url), stylesheetFetchExecutor))
                .toList();
    }

    /**
     * loaderが投げた例外・空の本文はベストエフォートとして読み飛ばす(取得元がHTTPかSFTPかは問わない)。
     *
     * 取得(loader)だけを共有Executorで並列に走らせ(issue #1473。並列度の上限と根拠は
     * {@link StylesheetFetchExecutorConfig})、連結とMAX_CSS_LENGTHの判定は従来どおり入力順に行う。
     * 完了順で連結すると、上限に達したときにどのstylesheetが落ちるかが実行ごとに変わってしまうため。
     * browserLikeClient()が返すRestClientは不変でスレッドセーフ、SFTP経路はホスト単位のロックで
     * 直列化される(SshjCommandExecutor)ため、並列化しても安全(SFTPは速くならずHTTPだけ速くなる)。
     */
    private String concatStylesheets(
            List<String> stylesheetUrls, java.util.function.Function<String, String> loader,
            StylesheetFetchReport report) {
        long deadline = report.deadlineNanos;
        report.attempted += stylesheetUrls.size();
        List<CompletableFuture<String>> fetches = startFetches(stylesheetUrls, loader);
        StringBuilder css = new StringBuilder();
        for (int i = 0; i < stylesheetUrls.size(); i++) {
            String url = stylesheetUrls.get(i);
            try {
                String body = awaitFetch(fetches.get(i), deadline);
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
            } catch (InterruptedException e) {
                logger.warn("Interrupted while fetching stylesheet: {}", url, failureOf(e));
                fetches.forEach(f -> f.cancel(true));
                // 取り消した残りも取得できなかったものとして報告する
                report.failedUrls.addAll(stylesheetUrls.subList(i, stylesheetUrls.size()));
                break;
            } catch (Exception e) {
                logger.warn("Failed to fetch stylesheet: {}", url, failureOf(e));
                report.failedUrls.add(url);
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
