package com.letsblog.api.service;

import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.ThemeCssResponse;
import com.letsblog.api.markdown.MarkdownRenderer;
import com.letsblog.api.repository.SiteRepository;
import org.springframework.stereotype.Service;
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
    private final MarkdownRenderer markdownRenderer;
    private final ProjectService projectService;
    private final SiteRepository siteRepository;
    private final RestClient.Builder restClientBuilder;

    public ArticlePreviewService(
            CustomTagRenderService customTagRenderService,
            BlogCardTagRenderService blogCardTagRenderService,
            AmazonTagRenderService amazonTagRenderService,
            MarkdownRenderer markdownRenderer,
            ProjectService projectService,
            SiteRepository siteRepository,
            RestClient.Builder restClientBuilder) {
        this.customTagRenderService = customTagRenderService;
        this.blogCardTagRenderService = blogCardTagRenderService;
        this.amazonTagRenderService = amazonTagRenderService;
        this.markdownRenderer = markdownRenderer;
        this.projectService = projectService;
        this.siteRepository = siteRepository;
        this.restClientBuilder = restClientBuilder;
    }

    /**
     * カスタムタグ展開 + 組み込みタグ展開 + Markdown→HTML変換を行う。PostPublishServiceと違い、
     * PlantUML埋め込みや画像アップロードは行わない(プレビュー用の軽量処理。ローカル画像やPlantUML図は
     * VSCode拡張側の責務)。
     */
    public String renderHtml(Long projectId, String markdown) {
        String rendered = customTagRenderService.render(markdown, projectId);
        rendered = blogCardTagRenderService.render(rendered);
        rendered = amazonTagRenderService.render(rendered);
        return markdownRenderer.render(rendered);
    }

    /**
     * プロジェクトのマスター環境(test/production)に紐づくサイトがWordPressであれば、
     * トップページのstylesheetリンクを収集して連結したCSSを返す。
     * 紐付けなし・非WordPress・取得失敗時はavailable=falseで理由を添えて返す。
     */
    public ThemeCssResponse fetchMasterThemeCss(Long projectId) {
        Project project = projectService.getProjectEntity(projectId);
        Site site = resolveMasterSite(project);
        if (site == null) {
            return new ThemeCssResponse("", false,
                    "マスター環境(" + project.getMasterEnvironment() + ")にサイトが紐づいていません");
        }
        if (site.getCmsType() != CmsType.WORDPRESS) {
            return new ThemeCssResponse("", false, "マスター環境サイトがWordPress以外のCMSのためテーマCSSを取得できません");
        }

        String html;
        try {
            html = restClientBuilder.clone().build().get()
                    .uri(URI.create(site.getBaseUrl()))
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

        String css = fetchAndConcatStylesheets(stylesheetUrls);
        return new ThemeCssResponse(css, true, null);
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
