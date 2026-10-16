package com.letsblog.content.testsupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.content.client.AiGenerationClient;
import com.letsblog.content.client.IdentityBridgeClient;
import com.letsblog.content.client.OutboundAuthHeaders;
import com.letsblog.content.client.ProjectBridgeClient;
import com.letsblog.content.contentcache.AmazonProductParser;
import com.letsblog.content.contentcache.ContentCacheService;
import com.letsblog.content.contentcache.OgpMetadataParser;
import com.letsblog.content.contentcache.PlaywrightPageFetcher;
import com.letsblog.content.contentcache.TestOutboundUrlGuards;
import com.letsblog.content.domain.ContentCache;
import com.letsblog.content.domain.CustomTag;
import com.letsblog.content.domain.CustomTagTemplate;
import com.letsblog.content.domain.ProjectContentSettings;
import com.letsblog.content.markdown.MarkdownRenderer;
import com.letsblog.content.render.MediaRenderClient;
import com.letsblog.content.repository.ContentCacheRepository;
import com.letsblog.content.repository.CustomTagRepository;
import com.letsblog.content.repository.CustomTagTemplateRepository;
import com.letsblog.content.repository.ProjectContentSettingsRepository;
import com.letsblog.content.service.AdminAuthorizationService;
import com.letsblog.content.service.AmazonTagRenderService;
import com.letsblog.content.service.ArticlePreviewService;
import com.letsblog.content.service.AuditLogService;
import com.letsblog.content.service.BlogCardTagRenderService;
import com.letsblog.content.service.CurrentActorService;
import com.letsblog.content.service.CustomTagChangeListener;
import com.letsblog.content.service.CustomTagGenerationJobRunner;
import com.letsblog.content.service.CustomTagGenerationJobStarter;
import com.letsblog.content.service.CustomTagGenerationService;
import com.letsblog.content.service.CustomTagRenderService;
import com.letsblog.content.service.CustomTagService;
import com.letsblog.content.service.CustomTagTemplateService;
import com.letsblog.content.service.CustomTagValidationService;
import com.letsblog.content.service.LetsblogSyncNotifier;
import com.letsblog.content.service.LetsblogSyncPayloadService;
import com.letsblog.content.service.PlantUmlEmbedService;
import com.letsblog.content.service.PlantUmlTagRenderService;
import com.letsblog.content.service.ProjectContentSettingsService;
import com.letsblog.content.service.RechartsTagRenderService;
import com.letsblog.content.service.RenderedContentWrapperService;
import com.letsblog.content.service.TocStyleRenderService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * content-serviceの{@code service}パッケージのテストが使う「外部との通信の境界だけを差し替えた世界」(issue #1821)。
 *
 * <p>{@code *Service}・{@code *Client}・{@code CurrentActorService}・{@code MarkdownRenderer}・
 * {@code ContentCacheService}・{@code ObjectMapper}・DTOはすべて実物で、外部との通信は次だけを差し替える。
 * <ul>
 *   <li><b>HTTP</b>: {@link FakeHttpServer}(JDK標準のHttpServerをループバックで起動したサーバー側フェイク)。
 *       identity-service・project-service・media-service・ai-service・トークンエンドポイント(Keycloakの代わり)の
 *       ベースURLをすべてこの1台へ向ける。</li>
 *   <li><b>MQ</b>: {@link RabbitTemplate}のMockito mock(実物の{@link AuditLogService}へ渡す)。</li>
 *   <li><b>DB</b>: Spring Dataのリポジトリ({@link CustomTagRepository}・{@link CustomTagTemplateRepository}・
 *       {@link ProjectContentSettingsRepository}・{@link ContentCacheRepository})のMockito mock。保存した行を
 *       メモリに持ち、{@code findById}などがそれを返す(本物のテーブルの代わり)。</li>
 *   <li><b>外部プロセス</b>: Playwrightの{@link Browser}・{@link Page}のMockito mock(ヘッドレスChromiumの代わり)。
 *       {@link #page(String, String)}で登録したURLのHTMLを返す。</li>
 *   <li><b>DNS</b>: {@code OutboundUrlGuard}の名前解決だけを{@link TestOutboundUrlGuards}で差し替える。</li>
 * </ul>
 */
public final class ContentWorld implements AutoCloseable {

    public static final String SERVICE_TOKEN = "svc-token";

    public final FakeHttpServer http = new FakeHttpServer();
    public final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    public final MockHttpServletRequest request = new MockHttpServletRequest();

    /** DB(Spring Dataのリポジトリ)。外部との通信なので差し替えてよい。 */
    public final CustomTagRepository customTagRepository = Mockito.mock(CustomTagRepository.class);
    public final CustomTagTemplateRepository customTagTemplateRepository =
            Mockito.mock(CustomTagTemplateRepository.class);
    public final ProjectContentSettingsRepository projectContentSettingsRepository =
            Mockito.mock(ProjectContentSettingsRepository.class);
    public final ContentCacheRepository contentCacheRepository = Mockito.mock(ContentCacheRepository.class);
    /** MQ。 */
    public final RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
    /** 外部プロセス(ヘッドレスChromium)。 */
    public final Browser browser = Mockito.mock(Browser.class);

    /** DBの代わりのメモリ上の行。 */
    public final Map<Long, CustomTag> tags = new ConcurrentHashMap<>();
    public final Map<Long, CustomTagTemplate> templates = new ConcurrentHashMap<>();
    public final Map<Long, ProjectContentSettings> settings = new ConcurrentHashMap<>();
    public final Map<String, ContentCache> contentCache = new ConcurrentHashMap<>();
    /** {@code save}に渡されたカスタムタグを、保存した順に持つ。 */
    public final List<CustomTag> savedTags = new CopyOnWriteArrayList<>();
    public final List<CustomTagTemplate> savedTemplates = new CopyOnWriteArrayList<>();
    private final AtomicLong tagIds = new AtomicLong(0);
    private final AtomicLong templateIds = new AtomicLong(0);
    private final AtomicLong settingsIds = new AtomicLong(0);
    private final AtomicLong cacheIds = new AtomicLong(0);

    /** ブラウザが返すページ: URL → HTML。 */
    private final Map<String, String> pages = new ConcurrentHashMap<>();
    /** ブラウザが開こうとしたURLの記録。 */
    public final List<String> navigatedUrls = new CopyOnWriteArrayList<>();

    private final Map<String, ActorProfile> profilesByAuthorization = new HashMap<>();
    private final Map<String, String> tagDesigns = new ConcurrentHashMap<>();
    private final Map<Long, String> projectSlugs = new ConcurrentHashMap<>();
    private final AtomicLong jobIds = new AtomicLong(9);

    public final ServiceTokenClient serviceTokenClient;
    public final IdentityClient identityClient;
    public final CurrentActorService currentActorService;
    public final IdentityBridgeClient identityBridgeClient;
    public final ProjectBridgeClient projectBridgeClient;
    public final AdminAuthorizationService adminAuthorizationService;
    public final OutboundAuthHeaders outboundAuthHeaders;
    public final AiGenerationClient aiGenerationClient;
    public final GenerationJobClient generationJobClient;
    public final MediaRenderClient mediaRenderClient;
    public final MarkdownRenderer markdownRenderer = new MarkdownRenderer();
    public final AuditLogService auditLogService = new AuditLogService(rabbitTemplate);
    public final LetsblogSyncNotifier letsblogSyncNotifier;
    public final CustomTagChangeListener customTagChangeListener;
    public final ContentCacheService contentCacheService;
    public final ProjectContentSettingsService projectContentSettingsService;
    public final RenderedContentWrapperService renderedContentWrapperService;
    public final TocStyleRenderService tocStyleRenderService;
    public final BlogCardTagRenderService blogCardTagRenderService;
    public final AmazonTagRenderService amazonTagRenderService;
    public final CustomTagRenderService customTagRenderService;
    public final CustomTagValidationService customTagValidationService = new CustomTagValidationService();
    public final CustomTagService customTagService;
    public final CustomTagTemplateService customTagTemplateService;
    public final CustomTagGenerationService customTagGenerationService;
    public final CustomTagGenerationJobRunner customTagGenerationJobRunner;
    public final CustomTagGenerationJobStarter customTagGenerationJobStarter;
    public final RechartsTagRenderService rechartsTagRenderService;
    public final PlantUmlEmbedService plantUmlEmbedService;
    public final PlantUmlTagRenderService plantUmlTagRenderService;
    public final ArticlePreviewService articlePreviewService;
    public final LetsblogSyncPayloadService letsblogSyncPayloadService;

    public ContentWorld() {
        this(24);
    }

    public ContentWorld(long contentCacheTtlHours) {
        SharedCircuitBreakers.reset();
        String base = http.baseUrl();
        installDefaultRoutes();
        installRepositoryBehavior();
        installBrowserBehavior();

        serviceTokenClient = new ServiceTokenClient(RestClient.builder(), base + "/token", "content-service", "secret");
        identityClient = new IdentityClient(RestClient.builder(), base);
        currentActorService = new CurrentActorService(request, identityClient);
        identityBridgeClient = new IdentityBridgeClient(RestClient.builder(), base);
        projectBridgeClient = new ProjectBridgeClient(RestClient.builder(), base);
        adminAuthorizationService =
                new AdminAuthorizationService(currentActorService, identityBridgeClient, projectBridgeClient);
        outboundAuthHeaders = new OutboundAuthHeaders(request, serviceTokenClient);
        aiGenerationClient = new AiGenerationClient(RestClient.builder(), base, outboundAuthHeaders);
        ObjectProvider<ServiceTokenClient> tokenProvider =
                new StaticListableBeanFactory(Map.of("serviceTokenClient", serviceTokenClient))
                        .getBeanProvider(ServiceTokenClient.class);
        generationJobClient = new GenerationJobClient(RestClient.builder(), base, tokenProvider);
        mediaRenderClient = new MediaRenderClient(RestClient.builder(), base, request);

        letsblogSyncNotifier = new LetsblogSyncNotifier(projectBridgeClient, currentActorService);
        customTagChangeListener = new CustomTagChangeListener(letsblogSyncNotifier);
        projectContentSettingsService = new ProjectContentSettingsService(
                projectContentSettingsRepository, projectBridgeClient, currentActorService, letsblogSyncNotifier);
        renderedContentWrapperService = new RenderedContentWrapperService(projectContentSettingsService);

        PlaywrightPageFetcher pageFetcher = new PlaywrightPageFetcher(
                browser, publicHosts(), new ReentrantLock());
        contentCacheService = new ContentCacheService(
                contentCacheRepository, pageFetcher, new OgpMetadataParser(), new AmazonProductParser(),
                objectMapper, publicHosts(), contentCacheTtlHours);

        tocStyleRenderService = new TocStyleRenderService(projectBridgeClient, currentActorService);
        blogCardTagRenderService =
                new BlogCardTagRenderService(contentCacheService, projectBridgeClient, currentActorService);
        amazonTagRenderService =
                new AmazonTagRenderService(contentCacheService, projectBridgeClient, currentActorService);
        customTagRenderService = new CustomTagRenderService(customTagRepository, markdownRenderer);
        customTagService = new CustomTagService(
                customTagRepository, adminAuthorizationService, projectBridgeClient, currentActorService,
                tocStyleRenderService, blogCardTagRenderService, amazonTagRenderService,
                projectContentSettingsService);
        customTagTemplateService = new CustomTagTemplateService(
                customTagTemplateRepository, customTagRepository, customTagValidationService, currentActorService,
                adminAuthorizationService);
        customTagGenerationService = new CustomTagGenerationService(
                aiGenerationClient, customTagRepository, adminAuthorizationService, customTagValidationService);
        customTagGenerationJobRunner =
                new CustomTagGenerationJobRunner(customTagGenerationService, generationJobClient, objectMapper);
        customTagGenerationJobStarter = new CustomTagGenerationJobStarter(
                generationJobClient, customTagGenerationJobRunner, adminAuthorizationService, objectMapper);
        rechartsTagRenderService = new RechartsTagRenderService(mediaRenderClient);
        plantUmlEmbedService = new PlantUmlEmbedService(mediaRenderClient);
        plantUmlTagRenderService = new PlantUmlTagRenderService(mediaRenderClient);
        articlePreviewService = new ArticlePreviewService(
                customTagRenderService, blogCardTagRenderService, amazonTagRenderService,
                rechartsTagRenderService, plantUmlEmbedService, plantUmlTagRenderService,
                tocStyleRenderService, renderedContentWrapperService, markdownRenderer);
        letsblogSyncPayloadService = new LetsblogSyncPayloadService(
                customTagService, projectContentSettingsService, projectBridgeClient, currentActorService,
                objectMapper);
    }

    private static com.letsblog.content.contentcache.OutboundUrlGuard publicHosts() {
        // 名前解決は外部との通信なので差し替える。公開アドレス(RFC 5737のTEST-NET-3)に解決するホストと、
        // 内部アドレスに解決するホストを用意する。分類(isBlocked)は実物のまま動く。
        Map<String, String> hosts = new LinkedHashMap<>();
        hosts.put("example.com", "203.0.113.10");
        hosts.put("blog.example.com", "203.0.113.11");
        hosts.put("amazon.co.jp", "203.0.113.19");
        hosts.put("www.amazon.co.jp", "203.0.113.20");
        hosts.put("www.amazon.com", "203.0.113.21");
        hosts.put("amzn.to", "203.0.113.22");
        hosts.put("amzn.asia", "203.0.113.23");
        hosts.put("internal.example.com", "10.0.0.5");
        return TestOutboundUrlGuards.resolving(hosts);
    }

    // ------------------------------------------------------------------
    // 既定の応答
    // ------------------------------------------------------------------

    private void installDefaultRoutes() {
        http.on("POST", "/token", FakeHttpServer.Response.ok(
                "{\"access_token\":\"" + SERVICE_TOKEN + "\",\"expires_in\":3600}"));
        http.on("GET", "/api/identity/me", req -> {
            ActorProfile profile = profilesByAuthorization.get(req.header("Authorization"));
            return profile == null
                    ? FakeHttpServer.Response.json(401, "{\"error\":\"unauthorized\"}")
                    : FakeHttpServer.Response.ok(toJson(profile));
        });
        // identity-service: 既定ではプロジェクトのメンバーではない。
        http.on("GET", "/api/internal/identity/projects/\\d+/members/\\d+", FakeHttpServer.Response.ok("false"));
        http.on("GET", "/api/internal/identity/roles", FakeHttpServer.Response.ok("[]"));
        // project-service。
        http.on("GET", "/api/internal/project/tag-design/(TOC|BLOGCARD|AMAZON)", req -> {
            String type = req.path().substring(req.path().lastIndexOf('/') + 1);
            return FakeHttpServer.Response.ok(tagDesigns.getOrDefault(type, tagDesignJson(null, null)));
        });
        http.on("GET", "/api/internal/project/projects/(\\d+)", req -> {
            long id = Long.parseLong(req.path().substring(req.path().lastIndexOf('/') + 1));
            String slug = projectSlugs.get(id);
            return FakeHttpServer.Response.ok(toJson(slug == null
                    ? Collections.singletonMap("id", id)
                    : Map.of("id", id, "name", "p" + id, "slug", slug)));
        });
        http.on("POST", "/api/internal/project/letsblog-sync", FakeHttpServer.Response.empty(202));
        // ai-service。
        http.on("POST", "/api/internal/ai/generate", FakeHttpServer.Response.ok("{\"result\":\"\"}"));
        http.on("POST", "/api/internal/ai/generation-jobs", req -> FakeHttpServer.Response.ok(
                "{\"id\":" + jobIds.getAndIncrement() + ",\"type\":\"t\",\"status\":\"running\"}"));
        http.on("PATCH", "/api/internal/ai/generation-jobs/\\d+", FakeHttpServer.Response.empty(204));
        // media-service。
        http.on("POST", "/api/render/plantuml",
                FakeHttpServer.Response.bytes(200, "image/png", new byte[] {1, 2, 3}));
        http.on("POST", "/api/render/recharts", FakeHttpServer.Response.ok("{\"html\":\"<svg></svg>\"}"));
    }

    @SuppressWarnings("unchecked")
    private void installRepositoryBehavior() {
        when(customTagRepository.save(any(CustomTag.class))).thenAnswer(inv -> {
            CustomTag tag = inv.getArgument(0);
            if (tag.getId() == null) {
                tag.setId(tagIds.incrementAndGet());
            }
            tags.put(tag.getId(), tag);
            savedTags.add(tag);
            return tag;
        });
        when(customTagRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(tags.get(inv.<Long>getArgument(0))));
        when(customTagRepository.existsById(anyLong()))
                .thenAnswer(inv -> tags.containsKey(inv.<Long>getArgument(0)));
        doAnswer(inv -> tags.remove(inv.<Long>getArgument(0))).when(customTagRepository).deleteById(anyLong());
        when(customTagRepository.findByTagName(anyString())).thenAnswer(inv -> tags.values().stream()
                .filter(t -> t.getTagName().equals(inv.getArgument(0))).findFirst());
        when(customTagRepository.existsByTagName(anyString())).thenAnswer(inv -> tags.values().stream()
                .anyMatch(t -> t.getTagName().equals(inv.getArgument(0))));
        when(customTagRepository.findByTagNameAndProjectId(anyString(), anyLong())).thenAnswer(inv ->
                tags.values().stream().filter(t -> t.getTagName().equals(inv.getArgument(0))
                        && inv.<Long>getArgument(1).equals(t.getProjectId())).findFirst());
        when(customTagRepository.findByTagNameAndProjectIdIsNull(anyString())).thenAnswer(inv ->
                tags.values().stream().filter(t -> t.getTagName().equals(inv.getArgument(0))
                        && t.getProjectId() == null).findFirst());
        when(customTagRepository.findByProjectIdIsNull()).thenAnswer(inv -> sortedTags(t -> t.getProjectId() == null));
        when(customTagRepository.findByProjectIdOrProjectIdIsNull(anyLong())).thenAnswer(inv ->
                sortedTags(t -> t.getProjectId() == null || t.getProjectId().equals(inv.<Long>getArgument(0))));
        when(customTagRepository.findByProjectId(anyLong())).thenAnswer(inv ->
                sortedTags(t -> inv.<Long>getArgument(0).equals(t.getProjectId())));

        when(customTagTemplateRepository.save(any(CustomTagTemplate.class))).thenAnswer(inv -> {
            CustomTagTemplate template = inv.getArgument(0);
            if (template.getId() == null) {
                template.setId(templateIds.incrementAndGet());
            }
            templates.put(template.getId(), template);
            savedTemplates.add(template);
            return template;
        });
        when(customTagTemplateRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(templates.get(inv.<Long>getArgument(0))));
        when(customTagTemplateRepository.existsById(anyLong()))
                .thenAnswer(inv -> templates.containsKey(inv.<Long>getArgument(0)));
        doAnswer(inv -> templates.remove(inv.<Long>getArgument(0))).when(customTagTemplateRepository)
                .deleteById(anyLong());

        when(projectContentSettingsRepository.findByProjectId(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(settings.get(inv.<Long>getArgument(0))));
        when(projectContentSettingsRepository.save(any(ProjectContentSettings.class))).thenAnswer(inv -> {
            ProjectContentSettings row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(settingsIds.incrementAndGet());
            }
            settings.put(row.getProjectId(), row);
            return row;
        });

        when(contentCacheRepository.findByUrlHash(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(contentCache.get(inv.<String>getArgument(0))));
        when(contentCacheRepository.save(any(ContentCache.class))).thenAnswer(inv -> {
            ContentCache row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(cacheIds.incrementAndGet());
            }
            contentCache.put(row.getUrlHash(), row);
            return row;
        });
    }

    private List<CustomTag> sortedTags(java.util.function.Predicate<CustomTag> filter) {
        List<CustomTag> result = new ArrayList<>();
        tags.values().stream().filter(filter)
                .sorted(java.util.Comparator.comparing(CustomTag::getId)).forEach(result::add);
        return result;
    }

    private void installBrowserBehavior() {
        when(browser.newPage()).thenAnswer(inv -> {
            Page page = Mockito.mock(Page.class);
            String[] current = new String[1];
            when(page.navigate(anyString(), any(Page.NavigateOptions.class))).thenAnswer(nav -> {
                current[0] = nav.getArgument(0);
                navigatedUrls.add(current[0]);
                return null;
            });
            when(page.content()).thenAnswer(c -> {
                String html = pages.get(current[0]);
                if (html == null) {
                    throw new PlaywrightException("net::ERR_NAME_NOT_RESOLVED at " + current[0]);
                }
                return html;
            });
            return page;
        });
    }

    // ------------------------------------------------------------------
    // 利用者・リクエスト
    // ------------------------------------------------------------------

    /** リクエストの中にいる状態にする({@code RequestContextHolder}へリクエストを束縛する)。 */
    public ContentWorld inRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return this;
    }

    /** 指定の利用者としてリクエストする。{@code Authorization}ヘッダーを付け、identity-serviceがそのプロフィールを返す。 */
    public ContentWorld actingAs(Long userId, String role) {
        String authorization = "Bearer token-" + userId;
        request.removeHeader("Authorization");
        request.addHeader("Authorization", authorization);
        profilesByAuthorization.put(authorization, new ActorProfile(userId, role));
        return inRequest();
    }

    public ContentWorld actingAsAdmin(Long userId) {
        return actingAs(userId, "admin");
    }

    public ContentWorld actingAsUser(Long userId) {
        return actingAs(userId, "user");
    }

    /** JWTのsubを{@code subject}にする(監査用の{@code getCurrentActorKeycloakSub}が返す値)。nullなら未ログイン。 */
    public ContentWorld signedInAs(String subject) {
        SecurityContextHolder.clearContext();
        if (subject != null) {
            Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject).build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        }
        return this;
    }

    /** identity-serviceが{@code /api/identity/me}で障害(5xx)を返す状態にする。 */
    public ContentWorld identityServiceDown() {
        http.status("GET", "/api/identity/me", 503, "{\"error\":\"down\"}");
        return this;
    }

    /** identity-serviceが、指定プロジェクトのメンバーかどうかを{@code member}と答える状態にする。 */
    public ContentWorld projectMember(long projectId, long userId, boolean member) {
        http.on("GET", "/api/internal/identity/projects/" + projectId + "/members/" + userId,
                FakeHttpServer.Response.ok(Boolean.toString(member)));
        return this;
    }

    /** 指定の利用者がアクセスできるサイトのIDを、project-serviceが返す状態にする。 */
    public ContentWorld accessibleSites(long userId, Long... siteIds) {
        http.on("GET", "/api/internal/project/users/" + userId + "/site-ids",
                FakeHttpServer.Response.ok(toJson(List.of(siteIds))));
        return this;
    }

    // ------------------------------------------------------------------
    // 状態の設定
    // ------------------------------------------------------------------

    /** 組み込みタグ(TOC/BLOGCARD/AMAZON)のデザインを、project-serviceが返す状態にする。 */
    public ContentWorld tagDesign(String tagType, String customCss, String htmlTemplate) {
        tagDesigns.put(tagType, tagDesignJson(customCss, htmlTemplate));
        return this;
    }

    private String tagDesignJson(String customCss, String htmlTemplate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("backgroundColor", "#fff");
        body.put("textColor", "#000");
        body.put("accentColor", "#f00");
        body.put("customCss", customCss);
        body.put("htmlTemplate", htmlTemplate);
        return toJson(body);
    }

    /** project-serviceが、プロジェクトのslugを返す状態にする。 */
    public ContentWorld projectSlug(long projectId, String slug) {
        projectSlugs.put(projectId, slug);
        return this;
    }

    /** ブラウザが、指定URLを開くとそのHTMLを返す状態にする。登録の無いURLは取得に失敗する。 */
    public ContentWorld page(String url, String html) {
        pages.put(url, html);
        return this;
    }

    /** DBに、指定のカスタムタグの行を用意する。 */
    public CustomTag tag(String name, String htmlTemplate, String css,
                         com.letsblog.content.domain.CustomTagFormat format, Long projectId) {
        CustomTag tag = new CustomTag();
        tag.setTagName(name);
        tag.setHtmlTemplate(htmlTemplate);
        tag.setCssContent(css);
        tag.setTagFormat(format);
        tag.setProjectId(projectId);
        return customTagRepository.save(tag);
    }

    /** DBに、カスタムタグのテンプレートの行を用意する。 */
    public CustomTagTemplate template(String name, String html, String css, boolean published, Long projectId) {
        CustomTagTemplate template = new CustomTagTemplate();
        template.setTemplateName(name);
        template.setHtmlTemplate(html);
        template.setCssContent(css);
        template.setVersion(1);
        template.setIsPublished(published);
        template.setProjectId(projectId);
        return customTagTemplateRepository.save(template);
    }

    /** DBに、指定プロジェクトのコンテンツ設定の行を用意する。 */
    public ProjectContentSettings settingsFor(long projectId, String cssSelectorPrefix) {
        ProjectContentSettings row = new ProjectContentSettings(projectId);
        row.setCssSelectorPrefix(cssSelectorPrefix);
        return projectContentSettingsRepository.save(row);
    }

    /**
     * 取得済みのキャッシュ行(DBの{@code content_cache})を用意する。{@code url}は{@code ContentCacheService}が
     * 正規化したURL({@code URI#toString})と同じ文字列で渡す。最後の確認が{@code lastCheckedAt}。
     */
    public ContentCache cached(String url, com.letsblog.content.domain.ContentType type,
                               Map<String, String> data, LocalDateTime lastCheckedAt) {
        ContentCache cache = new ContentCache();
        cache.setUrl(url);
        cache.setUrlHash(sha256(url));
        cache.setContentType(type);
        cache.setDataJson(toJson(data));
        cache.setContentHash(sha256(cache.getDataJson()));
        cache.setLastCheckedAt(lastCheckedAt);
        cache.setLastUpdatedAt(lastCheckedAt);
        return contentCacheRepository.save(cache);
    }

    /** OGPメタタグを持つページのHTML。nullの項目は出さない。 */
    public static String ogHtml(String title, String description, String siteName, String url, String image) {
        StringBuilder html = new StringBuilder("<html><head>");
        appendMeta(html, "og:title", title);
        appendMeta(html, "og:description", description);
        appendMeta(html, "og:site_name", siteName);
        appendMeta(html, "og:url", url);
        appendMeta(html, "og:image", image);
        return html.append("</head><body></body></html>").toString();
    }

    private static void appendMeta(StringBuilder html, String property, String value) {
        if (value != null) {
            html.append("<meta property=\"").append(property).append("\" content=\"")
                    .append(value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
                            .replace(">", "&gt;"))
                    .append("\">");
        }
    }

    /** 期限(TTL)を過ぎた状態のキャッシュ行を用意する(最後の確認が{@code hoursAgo}時間前)。 */
    public ContentCache ageCache(String normalizedUrl, long hoursAgo) {
        ContentCache cache = contentCache.get(sha256(normalizedUrl));
        cache.setLastCheckedAt(LocalDateTime.now().minusHours(hoursAgo));
        return cache;
    }

    public static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // 観測
    // ------------------------------------------------------------------

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public JsonNode bodyOf(FakeHttpServer.Request req) {
        return parse(req.bodyText());
    }

    /** 実行を終えたあと、スレッドに束縛したリクエストと認証を外す。 */
    @Override
    public void close() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
        http.close();
    }
}
