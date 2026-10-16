package com.letsblog.ai.testsupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.ai.BraveSearchClient;
import com.letsblog.ai.ai.LlmClient;
import com.letsblog.ai.ai.RedirectedLlmConfigProvider;
import com.letsblog.ai.client.IdentityBridgeClient;
import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.ai.client.ProjectBridgeClient;
import com.letsblog.ai.client.PublishingServiceClient;
import com.letsblog.ai.domain.ArticlePlanSession;
import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.domain.ProjectAiSettings;
import com.letsblog.ai.domain.ProjectReviewStepSetting;
import com.letsblog.ai.github.GithubClient;
import com.letsblog.ai.repository.ArticlePlanSessionRepository;
import com.letsblog.ai.repository.GenerationJobRepository;
import com.letsblog.ai.repository.ProjectAiSettingsRepository;
import com.letsblog.ai.repository.ProjectReviewStepSettingRepository;
import com.letsblog.ai.service.AdminAuthorizationService;
import com.letsblog.ai.service.AiAssistService;
import com.letsblog.ai.service.ArticlePlanService;
import com.letsblog.ai.service.CurrentActorService;
import com.letsblog.ai.service.LlmModelService;
import com.letsblog.ai.service.ProjectAiSettingsService;
import com.letsblog.ai.service.ProjectConnectionService;
import com.letsblog.ai.service.ProviderModelCatalog;
import com.letsblog.ai.service.ReviewStepModelService;
import com.letsblog.ai.service.WebSearchService;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.crypto.CredentialCipher;
import com.letsblog.common.net.ConnectionDestinationGuard;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ai-serviceの{@code service}パッケージのテストが使う「外部との通信の境界だけを差し替えた世界」(issue #1823)。
 *
 * <p>{@code *Service}・{@code *Client}・{@code CurrentActorService}・{@code LlmClient}・
 * {@code RemoteLlmConfigProvider}・{@code CredentialCipher}・{@code ObjectMapper}・DTOはすべて実物で、
 * 外部との通信は次だけを差し替える。
 * <ul>
 *   <li><b>HTTP</b>: {@link FakeHttpServer}(JDK標準のHttpServerをループバックで起動したサーバー側フェイク)。
 *       identity-service・platform-service・project-service・publishing-service・GitHub・Brave Search・
 *       LLM(Ollama / OpenAI互換)のベースURLをすべてこの1台へ向ける。コード内で固定されたクラウドLLMのホストだけは
 *       {@link RedirectedLlmConfigProvider}がこの1台へ向け替える。</li>
 *   <li><b>DB</b>: Spring Dataのリポジトリ(Mockito mock)。保存した行をメモリに持つ({@link InMemoryRepositories})。</li>
 * </ul>
 */
public final class AiWorld implements AutoCloseable {

    public static final String BEARER = "Bearer t";

    public final FakeHttpServer http = new FakeHttpServer();
    public final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    public final MockHttpServletRequest request = new MockHttpServletRequest();

    /** DBの代わりのメモリ上の行。 */
    /**
     * 宛先検査(実物)。名前解決だけは実物のDNSで、ネットワークインタフェースの列挙とコンテナ判定は
     * 「コンテナ外」に固定する(CIのコンテナの中でも、同じ結果になるように)。
     */
    public final ConnectionDestinationGuard destinationGuard =
            new ConnectionDestinationGuard(java.net.InetAddress::getAllByName, List::of, () -> false);
    private FakeHttpServer lan;

    public final InMemoryRepositories.Store<ProjectAiSettings> projectAiSettingsRows = new InMemoryRepositories.Store<>();
    public final InMemoryRepositories.Store<GenerationJob> jobRows = new InMemoryRepositories.Store<>();
    public final InMemoryRepositories.Store<ArticlePlanSession> sessionRows = new InMemoryRepositories.Store<>();
    public final InMemoryRepositories.Store<ProjectReviewStepSetting> reviewStepRows = new InMemoryRepositories.Store<>();

    /** DB(Spring Dataのリポジトリ)。外部との通信なので差し替えてよい。 */
    public final ProjectAiSettingsRepository projectAiSettingsRepository =
            InMemoryRepositories.projectAiSettings(projectAiSettingsRows);
    public final GenerationJobRepository generationJobRepository = InMemoryRepositories.generationJobs(jobRows);
    public final ArticlePlanSessionRepository articlePlanSessionRepository =
            InMemoryRepositories.articlePlanSessions(sessionRows);
    public final ProjectReviewStepSettingRepository reviewStepSettingRepository =
            InMemoryRepositories.reviewStepSettings(reviewStepRows);

    public final CredentialCipher credentialCipher =
            new CredentialCipher(Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
    public final IdentityClient identityClient;
    public final CurrentActorService currentActorService;
    public final IdentityBridgeClient identityBridgeClient;
    public final PlatformServiceClient platformServiceClient;
    public final ProjectBridgeClient projectBridgeClient;
    public final PublishingServiceClient publishingServiceClient;
    public final GithubClient githubClient;
    public final BraveSearchClient braveSearchClient;
    public final ProjectAiSettingsService projectAiSettingsService;
    public final RedirectedLlmConfigProvider llmConfigProvider;
    public final ProviderModelCatalog providerModelCatalog;
    public final LlmClient llmClient;
    public final LlmModelService llmModelService;
    public final WebSearchService webSearchService;
    public final ReviewStepModelService reviewStepModelService;
    public final ArticlePlanService articlePlanService;
    public final AiAssistService aiAssistService;
    public final AdminAuthorizationService adminAuthorizationService;
    public final ProjectConnectionService projectConnectionService;

    /** プラットフォームが返すLLM設定: プロバイダー名 → 設定。 */
    private final Map<String, Map<String, Object>> llmConfigs = new ConcurrentHashMap<>();
    private volatile String defaultProvider = "OLLAMA";

    public AiWorld() {
        SharedCircuitBreakers.reset();
        String base = http.baseUrl();
        identityClient = new IdentityClient(RestClient.builder(), base);
        currentActorService = new CurrentActorService(request, identityClient);
        identityBridgeClient = new IdentityBridgeClient(RestClient.builder(), base);
        platformServiceClient = new PlatformServiceClient(RestClient.builder(), base);
        projectBridgeClient = new ProjectBridgeClient(RestClient.builder(), base);
        publishingServiceClient = new PublishingServiceClient(RestClient.builder(), base);
        githubClient = new GithubClient(RestClient.builder(), base + "/github");
        braveSearchClient = new BraveSearchClient(base + "/brave");
        projectAiSettingsService = new ProjectAiSettingsService(projectAiSettingsRepository);
        llmConfigProvider = new RedirectedLlmConfigProvider(
                platformServiceClient, currentActorService, request, projectAiSettingsService, credentialCipher,
                destinationGuard, base + "/cloud");
        providerModelCatalog = new ProviderModelCatalog(llmConfigProvider, com.letsblog.common.auth.ToolsTokenProvider.disabled());
        llmClient = new LlmClient(llmConfigProvider);
        llmModelService = new LlmModelService(projectAiSettingsService, llmConfigProvider, providerModelCatalog);
        webSearchService = new WebSearchService(
                braveSearchClient, platformServiceClient, projectAiSettingsService, currentActorService, credentialCipher);
        reviewStepModelService = new ReviewStepModelService(
                reviewStepSettingRepository, llmModelService, llmConfigProvider, providerModelCatalog);
        articlePlanService = new ArticlePlanService(
                llmClient, llmModelService, webSearchService, generationJobRepository, objectMapper, githubClient,
                articlePlanSessionRepository, projectBridgeClient, publishingServiceClient, currentActorService);
        aiAssistService = new AiAssistService(
                llmClient, llmModelService, generationJobRepository, webSearchService, objectMapper,
                articlePlanService, reviewStepModelService, currentActorService);
        adminAuthorizationService = new AdminAuthorizationService(currentActorService, identityBridgeClient);
        projectConnectionService = new ProjectConnectionService(
                projectAiSettingsService, platformServiceClient, currentActorService);

        // platform-serviceのllm-config: provider指定があればその設定、無ければ既定プロバイダーの設定。
        http.on("GET", "/api/internal/platform/llm-config", req -> {
            String query = req.query();
            String provider = query != null && query.startsWith("provider=") ? query.substring("provider=".length()) : defaultProvider;
            Map<String, Object> config = llmConfigs.get(provider);
            if (config == null) {
                return FakeHttpServer.Response.json(404, "{\"error\":\"no llm-config for " + provider + "\"}");
            }
            return FakeHttpServer.Response.ok(json(config));
        });
    }

    /** 宛先検査を通る(ループバックでない)アドレスで待ち受けるHTTPフェイク。最初に呼んだときに作る。 */
    public synchronized FakeHttpServer lan() {
        if (lan == null) {
            lan = FakeHttpServer.onAllInterfaces();
        }
        return lan;
    }

    // ---- LLM(OpenAI互換のChat Completions)のフェイク ----

    /** LLMへのリクエスト(Ollamaの{@code /v1}・OpenAI互換の{@code /cloud}どちらも)。 */
    public List<FakeHttpServer.Request> llmRequests() {
        return http.requests("POST", ".*/chat/completions");
    }

    /** LLMへ送られたプロンプト(user メッセージの本文)。 */
    public List<String> llmPrompts() {
        return llmRequests().stream().map(request -> {
            try {
                return objectMapper.readTree(request.bodyText()).path("messages").path(0).path("content").asText();
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    /** LLMへ送られたリクエストの {@code model}。 */
    public String llmModelOf(FakeHttpServer.Request request) {
        try {
            return objectMapper.readTree(request.bodyText()).path("model").asText();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** LLMが、呼ばれた順に与えた本文を返す(最後の本文は以降も返す)。 */
    public AiWorld llmReplies(String... contents) {
        FakeHttpServer.Response[] responses = new FakeHttpServer.Response[contents.length];
        for (int i = 0; i < contents.length; i++) {
            responses[i] = FakeHttpServer.Response.ok(json(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", contents[i]))))));
        }
        http.sequence("POST", ".*/chat/completions", responses);
        return this;
    }

    /** LLMが失敗する。 */
    public AiWorld llmFails(int status, String body) {
        http.status("POST", ".*/chat/completions", status, body);
        return this;
    }

    /** project-serviceが返す、プロジェクトのGitHubアクセス情報。 */
    public AiWorld githubAccess(long projectId, String token, String owner, String repo) {
        http.json("GET", "/api/internal/project/projects/" + projectId + "/github-access",
                json(Map.of("token", token, "owner", owner, "repo", repo)));
        return this;
    }

    /** 本物のAPI(OpenAI / Anthropic)の代わりに、テストのHTTPフェイクが受けるベースURL。 */
    public String cloudBaseUrl() {
        return http.baseUrl() + "/cloud";
    }

    /** 以降のリクエストを、BearerトークンつきでID・ロールの利用者のものにする(identity-serviceが解決する)。 */
    public AiWorld actor(long id, String role) {
        request.addHeader("Authorization", BEARER);
        http.on("GET", "/api/identity/me", FakeHttpServer.Response.ok("{\"id\":" + id + ",\"role\":\"" + role + "\"}"));
        return this;
    }

    public AiWorld admin() {
        return actor(1L, "admin");
    }

    public AiWorld member(long id) {
        return actor(id, "user");
    }

    /** platform-serviceが返すLLM設定を登録する。{@code isDefault}ならプロバイダー指定の無い問い合わせにも使う。 */
    public AiWorld llmConfig(
            AiProvider provider, String baseUrl, String apiKey, String defaultModel, List<String> models, boolean isDefault) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("provider", provider.name());
        config.put("baseUrl", baseUrl);
        config.put("apiKey", apiKey);
        config.put("defaultModel", defaultModel);
        config.put("availableModels", models);
        config.put("requestTimeoutSeconds", 10);
        llmConfigs.put(provider.name(), config);
        if (isDefault) {
            defaultProvider = provider.name();
        }
        return this;
    }

    /** 既定プロバイダー(システム設定)をOllama(このフェイクの {@code /v1})にする。 */
    public AiWorld ollamaDefault(String defaultModel) {
        return llmConfig(AiProvider.OLLAMA, http.baseUrl() + "/v1", "", defaultModel, List.of(defaultModel), true);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        http.close();
        if (lan != null) {
            lan.close();
        }
    }
}
