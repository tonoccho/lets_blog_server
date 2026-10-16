package com.letsblog.project.testsupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.project.client.ActorProfile;
import com.letsblog.project.client.AiGenerationClient;
import com.letsblog.project.client.AnalyticsBridgeClient;
import com.letsblog.project.client.CmsProvisioningBridgeClient;
import com.letsblog.project.client.ContentBridgeClient;
import com.letsblog.project.client.IdentityBridgeClient;
import com.letsblog.project.client.IdentityClient;
import com.letsblog.project.client.OutboundAuthHeaders;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.project.cms.CmsType;
import com.letsblog.project.cms.LetsblogPluginStatus;
import com.letsblog.project.cms.LetsblogSnsResult;
import com.letsblog.project.domain.Site;
import com.letsblog.project.messaging.DomainEventPublisher;
import com.letsblog.project.repository.SiteRepository;
import com.letsblog.project.repository.SshKeyPairRepository;
import com.letsblog.project.service.ProvisioningService;
import com.letsblog.project.service.SiteService;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.support.TransactionOperations;
import com.letsblog.project.provisioning.WordPressAgentPluginsClient;
import com.letsblog.project.provisioning.WordPressProvisioningClient;
import com.letsblog.project.provisioning.WordPressSyncClient;
import com.letsblog.project.service.AdminAuthorizationService;
import com.letsblog.project.service.CurrentActorService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

/**
 * project-serviceのテストが使う「外部との通信の境界だけを差し替えた世界」(issue #1817)。
 *
 * <p>自前の{@code *Client}・{@code CurrentActorService}・{@code AdminAuthorizationService}などは
 * すべて実物で、外部との通信は次の2つだけを差し替える。
 * <ul>
 *   <li><b>HTTP</b>: {@link FakeHttpServer}(JDK標準のHttpServerをループバックで起動したサーバー側フェイク)。
 *       identity-service・publishing-service・ai-service・analytics-service・content-service・
 *       provision-agentの各ベースURLをすべてこの1台へ向ける。</li>
 *   <li><b>MQ</b>: {@link RabbitTemplate}のMockito mock(実物の{@link DomainEventPublisher}へ渡す)。</li>
 * </ul>
 * DB(Spring Dataのリポジトリ)は各テストが個別に差し替える。
 */
public final class ProjectWorld implements AutoCloseable {

    /** ProjectWorldが用意するサービストークン。 */
    public static final String SERVICE_TOKEN = "svc-token";

    public final FakeHttpServer http = new FakeHttpServer();
    public final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    public final MockHttpServletRequest request = new MockHttpServletRequest();
    /** DB(Spring Dataのリポジトリ)。外部との通信なので差し替えてよい。 */
    public final SiteRepository siteRepository = Mockito.mock(SiteRepository.class);
    public final SshKeyPairRepository sshKeyPairRepository = Mockito.mock(SshKeyPairRepository.class);
    public final CredentialCipher credentialCipher = new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
    public final RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
    public final DomainEventPublisher domainEventPublisher = new DomainEventPublisher(rabbitTemplate);

    public final ServiceTokenClient serviceTokenClient;
    public final IdentityClient identityClient;
    public final CurrentActorService currentActorService;
    public final IdentityBridgeClient identityBridgeClient;
    public final AdminAuthorizationService adminAuthorizationService;
    public final CmsProvisioningBridgeClient cmsBridgeClient;
    public final ProvisioningService provisioningService;
    public final SiteService siteService;
    public final OutboundAuthHeaders outboundAuthHeaders;
    public final AiGenerationClient aiGenerationClient;
    public final GenerationJobClient generationJobClient;
    public final AnalyticsBridgeClient analyticsBridgeClient;
    public final ContentBridgeClient contentBridgeClient;
    public final WordPressProvisioningClient wordPressProvisioningClient;
    public final WordPressSyncClient wordPressSyncClient;
    public final WordPressAgentPluginsClient wordPressAgentPluginsClient;

    /** ジョブ(ai-serviceのgeneration_jobs)の更新1回分。 */
    public record JobUpdate(String status, JsonNode payload) {
    }

    private final Map<String, ActorProfile> profilesByAuthorization = new HashMap<>();

    public ProjectWorld() {
        String base = http.baseUrl();
        http.on("POST", "/token", FakeHttpServer.Response.ok(
                "{\"access_token\":\"" + SERVICE_TOKEN + "\",\"expires_in\":3600}"));
        http.on("GET", "/api/identity/me", req -> {
            ActorProfile profile = profilesByAuthorization.get(req.header("Authorization"));
            return profile == null
                    ? FakeHttpServer.Response.json(401, "{\"error\":\"unauthorized\"}")
                    : FakeHttpServer.Response.ok(toJson(profile));
        });
        // ai-serviceのジョブ更新(PATCH)は既定で204を返す。
        http.on("PATCH", "/api/internal/ai/generation-jobs/\\d+", FakeHttpServer.Response.empty(204));
        // サービスのトークンエンドポイントも同じフェイクに向ける(Keycloakの代わり)。
        serviceTokenClient = new ServiceTokenClient(RestClient.builder(), base + "/token", "project-service", "secret");
        identityClient = new IdentityClient(RestClient.builder(), base);
        currentActorService = new CurrentActorService(request, identityClient);
        identityBridgeClient = new IdentityBridgeClient(RestClient.builder(), base, serviceTokenClient);
        adminAuthorizationService = new AdminAuthorizationService(currentActorService, identityBridgeClient);
        cmsBridgeClient = new CmsProvisioningBridgeClient(RestClient.builder(), base, request, serviceTokenClient);
        provisioningService = new ProvisioningService(cmsBridgeClient);
        siteService = new SiteService(siteRepository, credentialCipher, objectMapper, cmsBridgeClient,
                provisioningService, sshKeyPairRepository, currentActorService, TransactionOperations.withoutTransaction());
        outboundAuthHeaders = new OutboundAuthHeaders(request, serviceTokenClient);
        aiGenerationClient = new AiGenerationClient(RestClient.builder(), base, outboundAuthHeaders);
        ObjectProvider<ServiceTokenClient> tokenProvider =
                new StaticListableBeanFactory(Map.of("serviceTokenClient", serviceTokenClient))
                        .getBeanProvider(ServiceTokenClient.class);
        generationJobClient = new GenerationJobClient(RestClient.builder(), base, tokenProvider);
        analyticsBridgeClient = new AnalyticsBridgeClient(RestClient.builder(), base);
        contentBridgeClient = new ContentBridgeClient(RestClient.builder(), base);
        wordPressProvisioningClient = new WordPressProvisioningClient(base, "agent-token");
        wordPressSyncClient = new WordPressSyncClient(base, "agent-token");
        wordPressAgentPluginsClient = new WordPressAgentPluginsClient(base, "agent-token");
    }

    /** リクエストの中にいる状態にする({@code RequestContextHolder}へリクエストを束縛する)。 */
    public ProjectWorld inRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return this;
    }

    /**
     * 指定の利用者としてリクエストする。{@code Authorization}ヘッダーを付け、identity-serviceの
     * {@code /api/identity/me}がそのヘッダーに対して返すプロフィールを登録する。
     */
    public ProjectWorld actingAs(Long userId, String role, String email) {
        String authorization = "Bearer token-" + userId;
        request.removeHeader("Authorization");
        request.addHeader("Authorization", authorization);
        profilesByAuthorization.put(authorization, new ActorProfile(userId, role, email));
        return inRequest();
    }

    public ProjectWorld actingAsAdmin(Long userId) {
        return actingAs(userId, "admin", "admin" + userId + "@example.com");
    }

    public ProjectWorld actingAsUser(Long userId) {
        return actingAs(userId, "user", "user" + userId + "@example.com");
    }

    /** JWTのsubを{@code subject}にする(監査用の{@code getCurrentActorKeycloakSub}が返す値)。nullなら未ログイン。 */
    public ProjectWorld signedInAs(String subject) {
        SecurityContextHolder.clearContext();
        if (subject != null) {
            Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(subject).build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        }
        return this;
    }

    /** identity-serviceが{@code /api/identity/me}で障害(5xx)を返す状態にする。 */
    public ProjectWorld identityServiceDown() {
        http.status("GET", "/api/identity/me", 503, "{\"error\":\"down\"}");
        return this;
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public JsonNode bodyOf(FakeHttpServer.Request request) {
        try {
            return objectMapper.readTree(request.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 指定のメソッド・パスへ、値をJSONにして200で返す。 */
    public ProjectWorld respond(String method, String pathRegex, Object value) {
        http.json(method, pathRegex, toJson(value));
        return this;
    }

    /** 指定のメソッド・パスへ、指定ステータスで空のJSONを返す(失敗の再現用)。 */
    public ProjectWorld fail(String method, String pathRegex, int status) {
        http.status(method, pathRegex, status, "{\"error\":\"failed\"}");
        return this;
    }

    // ---- サイト(DB)とpublishing-serviceのCMSブリッジ ----

    private static final String CMS = "/api/internal/project/cms/";

    /** 管理対象外のWordPressサイト(AGENT接続)をDBに登録した状態にする。 */
    public Site addSite(Long id, String name) {
        Site site = new Site();
        site.setId(id);
        site.setName(name);
        site.setSiteKey("site-" + id);
        site.setCmsType(CmsType.WORDPRESS);
        site.setBaseUrl("https://site" + id + ".example.com");
        site.setCredentialsEncrypted(credentialCipher.encrypt(toJson(
                Map.of("baseUrl", site.getBaseUrl(), "transport", "AGENT", "username", "u"))));
        Mockito.when(siteRepository.findById(id)).thenReturn(Optional.of(site));
        return site;
    }

    /** サイトの登録(SiteService#register)が使うCMSブリッジを、プロビジョニング・疎通確認とも成功にする。DBの保存は渡された値をそのまま返す。 */
    public ProjectWorld registrationSucceeds() {
        Mockito.when(siteRepository.save(Mockito.any(Site.class))).thenAnswer(inv -> inv.getArgument(0));
        respond("POST", CMS + "provision", new com.letsblog.project.cms.ProvisioningResult("c", null, "t", null, "a", null));
        respond("POST", CMS + "test-connection", new com.letsblog.project.cms.ConnectionCheckResult(true, null, null, null));
        return this;
    }

    /** 自動構築(managed)のWordPressサイトをDBに登録した状態にする。 */
    public Site addManagedSite(Long id, String slug, String dbName) {
        Site site = addSite(id, "managed-" + slug);
        site.setManagedWordpress(true);
        site.setWpSlug(slug);
        site.setWpDbName(dbName);
        return site;
    }

    /** SSH接続の管理対象外WordPressサイトをDBに登録した状態にする。 */
    public Site addSshSite(Long id, String name) {
        Site site = addSite(id, name);
        site.setCredentialsEncrypted(credentialCipher.encrypt(toJson(Map.of(
                "baseUrl", site.getBaseUrl(), "transport", "SSH", "sshHost", "ssh.example.com", "sshUser", "deploy",
                "wpPath", "/var/www/html", "sshPrivateKeyPem", "PEM"))));
        return site;
    }

    /** letsblogプラグインの導入状態を、publishing-serviceが返す状態にする。 */
    public ProjectWorld pluginState(LetsblogPluginStatus.State state) {
        return respond("POST", CMS + "letsblog-plugin-status", new LetsblogPluginStatus(state, "1.0.0", 1));
    }

    /** publishing-serviceのCMSブリッジが届かない(5xx)状態にする。 */
    public ProjectWorld cmsBridgeDown(String message) {
        http.status("POST", CMS + ".*", 500, "{\"error\":\"" + message + "\"}");
        return this;
    }

    /**
     * {@code wp letsblog sns <command> [<sns>]}が、呼ばれた順にこの標準出力を返すようにする
     * (最後の値は以降も返す)。{@code sns}がnullなら送られた要求にsnsが無いものに一致する。
     */
    public ProjectWorld letsblogSns(String command, String sns, String... stdouts) {
        AtomicInteger calls = new AtomicInteger();
        http.on("POST", CMS + "letsblog-sns", req -> {
            JsonNode body = bodyOf(req);
            boolean snsMatches = sns == null ? !body.has("sns") : sns.equals(body.path("sns").asText(null));
            if (!command.equals(body.path("command").asText()) || !snsMatches) {
                return null;
            }
            String stdout = stdouts[Math.min(calls.getAndIncrement(), stdouts.length - 1)];
            return FakeHttpServer.Response.ok(toJson(new LetsblogSnsResult(stdout)));
        });
        return this;
    }

    /** {@code wp letsblog sns <command> [<sns>]}が失敗する(5xx、本文にmessageを含む)ようにする。 */
    public ProjectWorld letsblogSnsFails(String command, String sns, String message) {
        http.on("POST", CMS + "letsblog-sns", req -> {
            JsonNode body = bodyOf(req);
            boolean snsMatches = sns == null ? !body.has("sns") : sns.equals(body.path("sns").asText(null));
            if (!command.equals(body.path("command").asText()) || !snsMatches) {
                return null;
            }
            return FakeHttpServer.Response.json(500, "{\"error\":\"" + message + "\"}");
        });
        return this;
    }

    /** letsblog-snsへ送られた要求のうち、コマンドが一致するものの本文。 */
    public List<JsonNode> snsRequests(String command) {
        return bodiesOf("POST", CMS + "letsblog-sns").stream()
                .filter(b -> command.equals(b.path("command").asText())).toList();
    }

    /** analytics-serviceがGA4の認証情報としてこの値を返す状態にする。 */
    public ProjectWorld analytics(Object credentials) {
        return respond("GET", ANALYTICS_CREDENTIALS, credentials);
    }

    /** analytics-serviceが障害(503、本文にmessage)の状態にする。 */
    public ProjectWorld analyticsDown(String message) {
        http.status("GET", ANALYTICS_CREDENTIALS, 503, message);
        return this;
    }

    private static final String ANALYTICS_CREDENTIALS = "/api/internal/analytics/projects/\\d+/google-analytics/credentials";

    /** ai-serviceの{@code /api/internal/ai/generate}が、常にこの文字列を結果として返す状態にする。 */
    public ProjectWorld aiReplies(String result) {
        return respond("POST", "/api/internal/ai/generate", Map.of("result", result));
    }

    /** ai-serviceへ送られたジョブ{@code jobId}の更新(状態と結果)を、送られた順に返す。 */
    public List<JobUpdate> jobUpdates(long jobId) {
        return bodiesOf("PATCH", "/api/internal/ai/generation-jobs/" + jobId).stream()
                .map(b -> {
                    try {
                        String payload = b.path("resultPayload").asText();
                        return new JobUpdate(b.path("status").asText(),
                                payload.isEmpty() ? null : objectMapper.readTree(payload));
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
    }

    /** ジョブ{@code jobId}の更新のうち、状態が{@code status}のもの。 */
    public List<JobUpdate> jobUpdates(long jobId, String status) {
        return jobUpdates(jobId).stream().filter(u -> status.equals(u.status())).toList();
    }

    /** ai-serviceのジョブ作成が、この{@code id}・{@code type}のジョブを返す状態にする。 */
    public ProjectWorld jobCreated(long id, String type) {
        return respond("POST", "/api/internal/ai/generation-jobs",
                new com.letsblog.common.client.GenerationJobSummary(id, type, "running",
                        java.time.LocalDateTime.of(2026, 10, 9, 1, 2, 3), java.time.LocalDateTime.of(2026, 10, 9, 1, 2, 3)));
    }

    /** 受けた要求のうち、メソッド・パスに一致するものの本文(JSON)を返す。 */
    public List<JsonNode> bodiesOf(String method, String pathRegex) {
        return http.requests(method, pathRegex).stream().map(this::bodyOf).toList();
    }

    @Override
    public void close() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
        http.close();
    }
}
