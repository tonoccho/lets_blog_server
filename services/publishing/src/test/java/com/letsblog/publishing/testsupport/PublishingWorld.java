package com.letsblog.publishing.testsupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.publishing.client.IdentityBridgeClient;
import com.letsblog.publishing.client.IdentityClient;
import com.letsblog.publishing.client.ContentServiceClient;
import com.letsblog.publishing.client.MediaGeneratedImageClient;
import com.letsblog.publishing.client.MediaSettingsBridgeClient;
import com.letsblog.publishing.client.OutboundAuthHeaders;
import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.FakeSshCommandExecutor;
import com.letsblog.publishing.cms.WordPressAdapter;
import com.letsblog.publishing.cms.agent.WordPressAgentOperations;
import com.letsblog.publishing.cms.ssh.WordPressSshOperations;
import com.letsblog.publishing.github.GithubPullRequestClient;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient;
import com.letsblog.publishing.render.MediaRenderClient;
import com.letsblog.publishing.messaging.DomainEventPublisher;
import com.letsblog.publishing.service.AdminAuthorizationService;
import com.letsblog.publishing.service.BulkManagementService;
import com.letsblog.publishing.service.BulkUploadStorageService;
import com.letsblog.publishing.service.CurrentActorService;
import com.letsblog.publishing.service.ImageResizeService;
import com.letsblog.publishing.service.PlantUmlEmbedService;
import com.letsblog.publishing.service.PlantUmlTagRenderService;
import com.letsblog.publishing.service.PostPublishService;
import com.letsblog.publishing.service.ProjectService;
import com.letsblog.publishing.service.SiteService;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * service パッケージのテストが使う「実物の配線」(issue #1815)。
 *
 * <p>自前のクラス({@code *Service}・{@code *Client}・{@code CmsAdapterFactory}・{@code WordPressAdapter}等)は
 * すべて実物で、外部との通信だけを差し替える。HTTPは{@link FakeHttpServer}(サーバー側のフェイク)、
 * SSHは{@link FakeSshCommandExecutor}(手書きフェイク)。HTTPリクエストは Spring Test の
 * {@link MockHttpServletRequest}(Mockitoのモックではない)。
 */
public final class PublishingWorld implements AutoCloseable {

    public static final String PROVISION_TOKEN = "agent-token";
    public static final String SERVICE_TOKEN = "svc-token";

    public final ObjectMapper mapper = new ObjectMapper();

    // 外部との通信(サーバー側のフェイク)
    public final FakeHttpServer project = new FakeHttpServer();
    public final FakeHttpServer identity = new FakeHttpServer();
    public final FakeHttpServer content = new FakeHttpServer();
    public final FakeHttpServer media = new FakeHttpServer();
    public final FakeHttpServer github = new FakeHttpServer();
    public final FakeHttpServer agent = new FakeHttpServer();
    public final FakeHttpServer token = new FakeHttpServer();
    /** ai-service(ジョブの作成・状態更新)。 */
    public final FakeHttpServer ai = new FakeHttpServer();
    public final FakeWordPress ssh = new FakeWordPress();

    /** Authorizationヘッダーの値 → identity-serviceの{@code /api/identity/me}が返すプロフィール。 */
    private final Map<String, String> actors = new LinkedHashMap<>();

    /** 現在のHTTPリクエスト(Authorizationヘッダーの転送元)。 */
    public final MockHttpServletRequest request = new MockHttpServletRequest();

    // 実物
    public final ServiceTokenClient serviceTokenClient;
    public final OutboundAuthHeaders outboundAuthHeaders;
    public final ProjectServiceClient projectServiceClient;
    public final ProjectService projectService;
    public final SiteService siteService;
    public final IdentityClient identityClient;
    public final IdentityBridgeClient identityBridgeClient;
    public final CurrentActorService currentActorService;
    public final ContentServiceClient contentServiceClient;
    public final MediaSettingsBridgeClient mediaSettingsBridgeClient;
    public final MediaGeneratedImageClient mediaGeneratedImageClient;
    public final MediaRenderClient mediaRenderClient;
    public final GithubPullRequestClient githubPullRequestClient;
    public final WordPressSshOperations sshOperations;
    public final WordPressAgentOperations agentOperations;
    public final WordPressBulkManagementClient bulkManagementClient;
    public final WordPressAdapter wordPressAdapter;
    public final CmsAdapterFactory cmsAdapterFactory;
    public final AdminAuthorizationService adminAuthorizationService;
    public final ImageResizeService imageResizeService = new ImageResizeService();
    public final PlantUmlEmbedService plantUmlEmbedService;
    public final PlantUmlTagRenderService plantUmlTagRenderService;
    /** MQ(RabbitMQ)との通信そのもの。外部通信なのでMockitoで差し替える。 */
    public final RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
    public final DomainEventPublisher domainEventPublisher = new DomainEventPublisher(rabbitTemplate);
    public final PostPublishService postPublishService;
    /** provision-agentの一覧取得(カテゴリ・タグ・プラグイン・テーマ)のフェイクの状態。 */
    public final AgentListings agentListings;
    public final GenerationJobClient generationJobClient;
    public final BulkUploadStorageService bulkUploadStorageService;
    public final BulkManagementService bulkManagementService;
    private final java.nio.file.Path storageDir;

    /** content-serviceの投稿行。キーは「siteId:wpPostId」、値は{@code PostBridgeResponse}のJSON。 */
    public final Map<String, String> contentPosts = new ConcurrentHashMap<>();
    /** content-serviceのスラッグ照会。キーは「siteKey/slug」、値は{@code PostSlugLookup}のJSON。 */
    public final Map<String, String> contentSlugs = new ConcurrentHashMap<>();

    public PublishingWorld() {
        identity.on("GET", "/api/identity/me", req -> {
            String profile = actors.get(req.header("Authorization"));
            return profile == null ? FakeHttpServer.Response.json(401, "{\"error\":\"unauthorized\"}")
                    : FakeHttpServer.Response.ok(profile);
        });
        token.json("POST", ".*", "{\"access_token\":\"" + SERVICE_TOKEN + "\",\"expires_in\":300}");
        serviceTokenClient = new ServiceTokenClient(RestClient.builder(), token.baseUrl(), "publishing", "secret");
        outboundAuthHeaders = new OutboundAuthHeaders(request, serviceTokenClient);
        projectServiceClient = new ProjectServiceClient(RestClient.builder(), project.baseUrl(), outboundAuthHeaders);
        projectService = new ProjectService(projectServiceClient);
        siteService = new SiteService(projectServiceClient);
        identityClient = new IdentityClient(RestClient.builder(), identity.baseUrl());
        identityBridgeClient = new IdentityBridgeClient(RestClient.builder(), identity.baseUrl(), request);
        currentActorService = new CurrentActorService(request, identityClient);
        contentServiceClient = new ContentServiceClient(RestClient.builder(), content.baseUrl(), request);
        mediaSettingsBridgeClient =
                new MediaSettingsBridgeClient(RestClient.builder(), media.baseUrl(), 1300, request);
        mediaGeneratedImageClient = new MediaGeneratedImageClient(RestClient.builder(), media.baseUrl(), request);
        mediaRenderClient = new MediaRenderClient(RestClient.builder(), media.baseUrl(), request);
        githubPullRequestClient = new GithubPullRequestClient(RestClient.builder(), github.baseUrl());
        sshOperations = new WordPressSshOperations(ssh, mapper);
        agentOperations = new WordPressAgentOperations(RestClient.builder(), agent.baseUrl(), PROVISION_TOKEN, 5, 5);
        bulkManagementClient = new WordPressBulkManagementClient(agent.baseUrl(), PROVISION_TOKEN, 5, 5);
        wordPressAdapter = new WordPressAdapter(sshOperations, agentOperations, bulkManagementClient);
        cmsAdapterFactory = new CmsAdapterFactory(List.of(wordPressAdapter));
        adminAuthorizationService = new AdminAuthorizationService(currentActorService, identityBridgeClient);
        plantUmlEmbedService = new PlantUmlEmbedService(mediaRenderClient, cmsAdapterFactory);
        plantUmlTagRenderService = new PlantUmlTagRenderService(mediaRenderClient, cmsAdapterFactory);
        postPublishService = new PostPublishService(projectServiceClient, cmsAdapterFactory, contentServiceClient,
                plantUmlEmbedService, plantUmlTagRenderService, currentActorService, identityBridgeClient,
                mediaSettingsBridgeClient, mapper, imageResizeService, domainEventPublisher,
                adminAuthorizationService);
        try {
            storageDir = java.nio.file.Files.createTempDirectory("lbs-publishing-bulk-uploads");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        bulkUploadStorageService = new BulkUploadStorageService(storageDir.toString());
        bulkManagementService = new BulkManagementService(bulkManagementClient, bulkUploadStorageService,
                siteService, sshOperations, cmsAdapterFactory, projectService, imageResizeService,
                mediaSettingsBridgeClient);
        agentListings = new AgentListings(agent);
        org.springframework.beans.factory.support.DefaultListableBeanFactory beans =
                new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("serviceTokenClient", serviceTokenClient);
        generationJobClient = new GenerationJobClient(RestClient.builder(), ai.baseUrl(),
                beans.getBeanProvider(ServiceTokenClient.class));
        java.util.concurrent.atomic.AtomicLong jobIds = new java.util.concurrent.atomic.AtomicLong(20);
        ai.on("POST", "/api/internal/ai/generation-jobs", req -> {
            try {
                String type = mapper.readTree(req.body()).path("type").asText();
                Map<String, Object> job = new LinkedHashMap<>();
                job.put("id", jobIds.incrementAndGet());
                job.put("type", type);
                job.put("status", "running");
                job.put("createdAt", "2026-10-13T01:02:03");
                job.put("updatedAt", "2026-10-13T01:02:03");
                return FakeHttpServer.Response.ok(json(job));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        ai.on("PATCH", "/api/internal/ai/generation-jobs/\\d+", FakeHttpServer.Response.empty(200));
        installContentDefaults();
        identity.json("POST", "/api/internal/identity/user-site-authors", "{}");
        media.json("GET", "/api/internal/media/projects/\\d+/article-image-long-edge-px", "{\"value\":1300}");
    }

    /**
     * content-serviceの既定の応答: pre-imageは本文をそのまま返し、finalize-htmlは{@code <p>本文</p>}にする。
     * 投稿行の読み書きは{@link #contentPosts}、スラッグ照会は{@link #contentSlugs}を見る。
     */
    private void installContentDefaults() {
        content.on("POST", "/api/internal/content/render/pre-image", req -> {
            try {
                return FakeHttpServer.Response.ok(json(Map.of("markdown",
                        mapper.readTree(req.body()).path("markdown").asText())));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        content.on("POST", "/api/internal/content/render/finalize-html", req -> {
            try {
                return FakeHttpServer.Response.ok(json(Map.of("html",
                        "<p>" + mapper.readTree(req.body()).path("markdown").asText() + "</p>")));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        });
        content.on("GET", "/api/internal/content/posts", req -> {
            String json = contentPosts.get(queryParam(req, "siteId") + ":" + queryParam(req, "wpPostId"));
            return json == null ? FakeHttpServer.Response.json(404, "{}") : FakeHttpServer.Response.ok(json);
        });
        content.on("PUT", "/api/internal/content/posts", req -> {
            try {
                var node = mapper.readTree(req.body());
                contentPosts.put(node.path("siteId").asText() + ":" + node.path("wpPostId").asText(),
                        req.bodyText());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            return FakeHttpServer.Response.empty(200);
        });
        content.on("POST", "/api/internal/content/posts/mark-trashed", FakeHttpServer.Response.empty(200));
        content.on("GET", "/api/posts/([^/]+)/by-slug/([^/]+)", req -> {
            String[] parts = req.path().split("/");
            String json = contentSlugs.get(parts[3] + "/" + parts[5]);
            return json == null ? FakeHttpServer.Response.json(404, "{}") : FakeHttpServer.Response.ok(json);
        });
    }

    private static String queryParam(FakeHttpServer.Request req, String name) {
        for (String pair : req.query().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) {
                return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** content-serviceに、前回投稿時の投稿行(アップロード済み画像のJSON付き)があることにする。 */
    public PublishingWorld stubContentPost(long siteId, String wpPostId, String slug, String uploadedImagesJson) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("siteId", siteId);
        row.put("wpPostId", wpPostId);
        row.put("slug", slug);
        row.put("status", "draft");
        row.put("uploadedImagesJson", uploadedImagesJson);
        contentPosts.put(siteId + ":" + wpPostId, json(row));
        return this;
    }

    /** content-serviceのスラッグ照会に、既存投稿があることにする。 */
    public PublishingWorld stubContentSlug(String siteKey, String slug, String wpPostId, String status) {
        contentSlugs.put(siteKey + "/" + slug, json(Map.of("wpPostId", wpPostId, "status", status)));
        return this;
    }

    /**
     * 環境(local/test/production)に紐付けるサイトの定義。{@code managed}ならprovision-agent(HTTP)経由、
     * そうでなければ{@code transport}がSSHのときSSH(wp-cli)経由で扱われる。
     */
    public record SiteSpec(long id, String key, boolean managed, String transport, String sshHost, String wpPath) {

        /** 自動構築(managed)サイト。wpSlugはサイトキーと同じ。 */
        public static SiteSpec managed(long id, String key) {
            return new SiteSpec(id, key, true, "AGENT", null, null);
        }

        /** SSH接続情報つきの非managedサイト。 */
        public static SiteSpec ssh(long id, String key, String host, String wpPath) {
            return new SiteSpec(id, key, false, "SSH", host, wpPath);
        }

        /** SSH接続情報の無い非managedサイト(一括管理の対象外)。 */
        public static SiteSpec noSsh(long id, String key) {
            return new SiteSpec(id, key, false, "AGENT", null, null);
        }
    }

    /**
     * プロジェクトの3環境にサイトを紐付ける(nullの環境は未紐付け)。サイト・認証情報・プロジェクトの環境と、
     * サイトからプロジェクトへの逆引きをproject-serviceに登録する。
     */
    public PublishingWorld bindEnvironments(long projectId, String masterEnvironment, SiteSpec local,
            SiteSpec test, SiteSpec production) {
        for (SiteSpec spec : new SiteSpec[] {local, test, production}) {
            if (spec == null) {
                continue;
            }
            String body = json(siteMap(spec));
            project.json("GET", "/api/internal/project/sites/by-key/" + spec.key(), body);
            project.json("GET", "/api/internal/project/sites/" + spec.id(), body);
            Map<String, String> credentials = new LinkedHashMap<>(sshCredentialsMap());
            credentials.put("transport", spec.transport());
            if (spec.sshHost() != null) {
                credentials.put("sshHost", spec.sshHost());
                credentials.put("wpPath", spec.wpPath());
            } else {
                credentials.remove("sshHost");
                credentials.remove("wpPath");
            }
            stubCredentials(spec.key(), credentials);
            bindProject(spec.id(), projectId, local == null ? null : local.id(),
                    test == null ? null : test.id(), production == null ? null : production.id(),
                    masterEnvironment);
        }
        return this;
    }

    /** project-serviceが返すサイトのJSON(404などの後続の応答を{@code sequence}で差し替えるときに使う)。 */
    public String siteJson(SiteSpec spec) {
        return json(siteMap(spec));
    }

    private static Map<String, Object> siteMap(SiteSpec spec) {
        Map<String, Object> site = new LinkedHashMap<>();
        site.put("id", spec.id());
        site.put("siteKey", spec.key());
        site.put("name", spec.key() + "-name");
        site.put("baseUrl", "https://" + spec.key() + ".example.com");
        site.put("cmsType", "WORDPRESS");
        site.put("managedWordpress", spec.managed());
        site.put("wpSlug", spec.managed() ? spec.key() : null);
        return site;
    }

    /**
     * 既定のサイト「main」(id=1、SSHトランスポート)をproject-serviceに置く。どのプロジェクトにも
     * 紐付いていない状態(project-idがnull)。
     */
    public PublishingWorld withMainSite() {
        stubSite(1L, "main", false);
        stubCredentials("main", sshCredentialsMap());
        project.json("GET", "/api/internal/project/sites/1/project-id", "{\"projectId\":null}");
        return this;
    }

    /** サイトid={@code siteId}をプロジェクトに紐付け、プロジェクトの環境(local/test/production)を登録する。 */
    public PublishingWorld bindProject(long siteId, long projectId, Long localSiteId, Long testSiteId,
            Long productionSiteId) {
        return bindProject(siteId, projectId, localSiteId, testSiteId, productionSiteId, "test");
    }

    /** {@link #bindProject(long, long, Long, Long, Long)}のマスター環境を指定できる版。 */
    public PublishingWorld bindProject(long siteId, long projectId, Long localSiteId, Long testSiteId,
            Long productionSiteId, String masterEnvironment) {
        project.json("GET", "/api/internal/project/sites/" + siteId + "/project-id",
                json(Map.of("projectId", projectId)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", projectId);
        body.put("localSiteId", localSiteId);
        body.put("testSiteId", testSiteId);
        body.put("productionSiteId", productionSiteId);
        body.put("masterEnvironment", masterEnvironment);
        project.json("GET", "/api/internal/project/projects/" + projectId, json(body));
        return this;
    }

    /** SSHトランスポートのWordPress認証情報(project-serviceが返すcredentialsマップの形)。 */
    public static Map<String, String> sshCredentialsMap() {
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("baseUrl", "https://example.com");
        credentials.put("username", "admin");
        credentials.put("transport", "SSH");
        credentials.put("sshHost", "ssh.example.com");
        credentials.put("sshPort", "22");
        credentials.put("sshUser", "deploy");
        credentials.put("wpPath", "/var/www/html");
        credentials.put("sshPrivateKeyPem", "PRIVATE-KEY-PEM");
        return credentials;
    }

    /** SSHトランスポートのWordPress認証情報。 */
    public static CmsCredentials.WordPressCredentials sshCreds() {
        return new CmsCredentials.WordPressCredentials("https://example.com", "admin", "SSH", "ssh.example.com", 22,
                "deploy", "/var/www/html", "PRIVATE-KEY-PEM", null, null);
    }

    /** project-serviceの{@code GET /sites/{siteKey}/credentials}が返す応答を登録する。 */
    public PublishingWorld stubCredentials(String siteKey, Map<String, String> credentials) {
        project.json("GET", "/api/internal/project/sites/" + siteKey + "/credentials",
                json(Map.of("siteId", 1, "cmsType", "WORDPRESS", "credentials", credentials)));
        return this;
    }

    /** project-serviceの{@code GET /sites/by-key/{siteKey}}と{@code GET /sites/{id}}が返す応答を登録する。 */
    public PublishingWorld stubSite(long id, String siteKey, boolean managedWordpress) {
        String body = json(Map.of("id", id, "siteKey", siteKey, "name", siteKey + "-name",
                "baseUrl", "https://" + siteKey + ".example.com", "cmsType", "WORDPRESS",
                "managedWordpress", managedWordpress));
        project.json("GET", "/api/internal/project/sites/by-key/" + siteKey, body);
        project.json("GET", "/api/internal/project/sites/" + id, body);
        return this;
    }

    /**
     * identity-serviceの{@code GET /api/identity/me}が、指定のAuthorizationヘッダーに返すプロフィールを登録する。
     * 登録の無いヘッダーには401を返す。
     */
    public PublishingWorld stubActor(String authorization, Long id, String role, String email) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", id);
        profile.put("role", role);
        profile.put("email", email);
        actors.put(authorization, json(profile));
        return this;
    }

    /** identity-serviceの{@code GET /projects/{projectId}/members/{userId}}が返す真偽を登録する。 */
    public PublishingWorld stubProjectMember(long projectId, long userId, boolean member) {
        identity.json("GET", "/api/internal/identity/projects/" + projectId + "/members/" + userId,
                Boolean.toString(member));
        return this;
    }

    /**
     * プロジェクトに、テスト環境(id={@code testSiteId})と本番環境(id={@code prodSiteId})のサイトを紐付ける。
     * どちらのサイトもSSHトランスポートで、サイトのプロジェクトは{@code projectId}になる。
     */
    public PublishingWorld withTestAndProductionSites(long projectId, long testSiteId, String testSiteKey,
            long prodSiteId, String prodSiteKey) {
        stubSite(testSiteId, testSiteKey, false);
        stubSite(prodSiteId, prodSiteKey, false);
        stubCredentials(testSiteKey, sshCredentialsMap());
        stubCredentials(prodSiteKey, sshCredentialsMap());
        bindProject(testSiteId, projectId, 1L, testSiteId, prodSiteId);
        bindProject(prodSiteId, projectId, 1L, testSiteId, prodSiteId);
        return this;
    }

    /** project-serviceの{@code GET /projects/{projectId}/github-access}が返すGitHubのアクセス情報を登録する。 */
    public PublishingWorld stubGithubAccess(long projectId, String token, String owner, String repo) {
        project.json("GET", "/api/internal/project/projects/" + projectId + "/github-access",
                json(Map.of("token", token, "owner", owner, "repo", repo)));
        return this;
    }

    /** ai-serviceが受けたジョブの状態更新。 */
    public record JobUpdate(long id, String status, JsonNode result) {
    }

    /** ai-serviceへ送られたジョブの状態更新を、受けた順に返す(resultPayloadはJSONとして読む)。 */
    public List<JobUpdate> jobUpdates() {
        List<JobUpdate> updates = new java.util.ArrayList<>();
        for (FakeHttpServer.Request request : ai.requests("PATCH", "/api/internal/ai/generation-jobs/\\d+")) {
            try {
                JsonNode body = mapper.readTree(request.body());
                String payload = body.path("resultPayload").asText();
                updates.add(new JobUpdate(Long.parseLong(request.path().substring(request.path().lastIndexOf('/') + 1)),
                        body.path("status").asText(), payload.isEmpty() ? null : mapper.readTree(payload)));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return updates;
    }

    /** オブジェクトをJSON文字列にする。 */
    public String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 呼び出し元ユーザーのAuthorizationヘッダーを持つHTTPリクエストの処理中として、
     * このスレッドにリクエストを束縛する({@link #close()}で解除する)。
     */
    public PublishingWorld asUser(String authorization) {
        request.removeHeader("Authorization");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return this;
    }

    /** リクエストの無いスレッド(@Asyncのジョブ)として振る舞わせる。 */
    public PublishingWorld withoutRequest() {
        RequestContextHolder.resetRequestAttributes();
        return this;
    }

    @Override
    public void close() {
        RequestContextHolder.resetRequestAttributes();
        project.close();
        identity.close();
        content.close();
        media.close();
        github.close();
        agent.close();
        token.close();
        ai.close();
        org.springframework.util.FileSystemUtils.deleteRecursively(storageDir.toFile());
    }
}
