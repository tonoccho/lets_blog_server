package com.letsblog.ai.service;

import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import com.letsblog.common.scheduling.MdcPropagation;
import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.ai.client.PlatformServiceClient.AiConnectionsConfig;
import com.letsblog.ai.client.PlatformServiceClient.ProviderConnectionConfig;
import com.letsblog.ai.dto.AiConnectionResponse;
import com.letsblog.ai.dto.AiConnectionResponse.Provider;
import com.letsblog.ai.dto.AiConnectionResponse.Source;
import com.letsblog.ai.dto.AiConnectionResponse.Status;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import java.util.function.Function;

/**
 * プロジェクトから見たAIプロバイダー4種(Ollama/ComfyUI/ChatGPT/Claude)の接続先と利用可否を集約する
 * (issue #1499)。
 *
 * <p>Ollama/ComfyUIは自ホスト上のコンテナで無料のため実際に疎通確認する。判定規約は
 * platform-serviceの{@code ConnectedServiceStatusService#checkHttpService}と同じ(何らかのHTTP応答が
 * あれば到達可能とみなし4xxもNORMAL、5xxはWARNING、接続できなければERROR)。ChatGPT/Claudeは
 * 第三者の有料APIのため実リクエストを送らず、APIキーの設定有無を利用可否とする
 * ({@code checkLlmApiKeyConfigured}と同じ方針)。
 *
 * <p>接続先・設定の出所・APIキーの設定有無はplatform-serviceの内部ブリッジから受け取る。
 * ブリッジはAPIキーの値を返さないため、この応答にキー値が入り込む経路は無い。
 * 判定はプロバイダーごとに並列で行い、各々にタイムアウトを置く(1つが遅くても他は返る)。
 */
@Service
public class AiConnectionService {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
    private static final String NOT_CONFIGURED_URL = "接続先URLが設定されていません";
    private static final String NOT_CONFIGURED_KEY = "APIキーが設定されていません";

    private final PlatformServiceClient platformServiceClient;
    private final CurrentActorService currentActorService;
    private final ProjectAiSettingsService projectAiSettingsService;
    private final Function<String, RestClient.Builder> clientBuilderFactory;
    private final Duration timeout;

    @Autowired
    public AiConnectionService(
            PlatformServiceClient platformServiceClient, CurrentActorService currentActorService,
            ProjectAiSettingsService projectAiSettingsService) {
        this(platformServiceClient, currentActorService, projectAiSettingsService,
                AiConnectionService::builderWithTimeout, DEFAULT_TIMEOUT);
    }

    /** テスト専用: MockRestServiceServerを介せるようRestClient.Builderの生成とタイムアウトを差し替える。 */
    AiConnectionService(
            PlatformServiceClient platformServiceClient,
            CurrentActorService currentActorService,
            ProjectAiSettingsService projectAiSettingsService,
            Function<String, RestClient.Builder> clientBuilderFactory,
            Duration timeout) {
        this.platformServiceClient = platformServiceClient;
        this.currentActorService = currentActorService;
        this.projectAiSettingsService = projectAiSettingsService;
        this.clientBuilderFactory = clientBuilderFactory;
        this.timeout = timeout;
    }

    private static RestClient.Builder builderWithTimeout(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(DEFAULT_TIMEOUT);
        return RestClient.builder().requestInterceptor(new ExternalCallLoggingInterceptor("llm-connection-test")).baseUrl(baseUrl).requestFactory(requestFactory);
    }

    /**
     * @param projectId プロジェクト単位の接続先上書き(#1503)があれば、Ollama / ComfyUIはその接続先を疎通確認し
     *                  {@code source}に{@code PROJECT}を返す
     */
    public List<AiConnectionResponse> listConnections(Long projectId) {
        AiConnectionsConfig config = ProjectConnectionService.applyOverrides(
                platformServiceClient.resolveAiConnectionsConfig(currentActorService.getAuthorizationHeader()),
                projectAiSettingsService.getOllamaBaseUrl(projectId),
                projectAiSettingsService.getComfyuiBaseUrl(projectId),
                projectAiSettingsService.hasOpenAiApiKey(projectId),
                projectAiSettingsService.hasClaudeApiKey(projectId));

        CompletableFuture<AiConnectionResponse> ollama = async(
                Provider.OLLAMA, "Ollama", () -> checkHttp(Provider.OLLAMA, "Ollama", config.ollama(), "/models"));
        CompletableFuture<AiConnectionResponse> comfyui = async(
                Provider.COMFYUI, "ComfyUI",
                () -> checkHttp(Provider.COMFYUI, "ComfyUI", config.comfyui(), "/system_stats"));
        CompletableFuture<AiConnectionResponse> openai = async(
                Provider.OPENAI, "ChatGPT", () -> checkApiKey(Provider.OPENAI, "ChatGPT", config.openai()));
        CompletableFuture<AiConnectionResponse> claude = async(
                Provider.CLAUDE, "Claude", () -> checkApiKey(Provider.CLAUDE, "Claude", config.claude()));
        return List.of(ollama.join(), comfyui.join(), openai.join(), claude.join());
    }

    private CompletableFuture<AiConnectionResponse> async(
            Provider provider, String displayName, Supplier<AiConnectionResponse> check) {
        AiConnectionResponse timedOut = new AiConnectionResponse(
                provider, displayName, null, Source.NONE, Status.ERROR,
                "タイムアウトしました(" + timeout.toMillis() + "ms)", false);
        return CompletableFuture.supplyAsync(MdcPropagation.supplier(check)).completeOnTimeout(timedOut, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private AiConnectionResponse checkHttp(
            Provider provider, String displayName, ProviderConnectionConfig config, String path) {
        Source source = sourceOf(config);
        String baseUrl = config == null ? null : config.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return new AiConnectionResponse(
                    provider, displayName, null, source, Status.WARNING, NOT_CONFIGURED_URL, false);
        }
        String targetUrl = baseUrl + path;
        try {
            ResponseEntity<Void> response =
                    clientBuilderFactory.apply(baseUrl).build().get().uri(path).retrieve().toBodilessEntity();
            return new AiConnectionResponse(provider, displayName, targetUrl, source, Status.NORMAL, null, true);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                return new AiConnectionResponse(
                        provider, displayName, targetUrl, source, Status.WARNING, e.getMessage(), true);
            }
            return new AiConnectionResponse(provider, displayName, targetUrl, source, Status.NORMAL, null, true);
        } catch (RestClientException e) {
            return new AiConnectionResponse(
                    provider, displayName, targetUrl, source, Status.ERROR, e.getMessage(), true);
        }
    }

    private AiConnectionResponse checkApiKey(Provider provider, String displayName, ProviderConnectionConfig config) {
        Source source = sourceOf(config);
        if (config != null && config.configured()) {
            return new AiConnectionResponse(provider, displayName, null, source, Status.NORMAL, null, true);
        }
        return new AiConnectionResponse(provider, displayName, null, source, Status.WARNING, NOT_CONFIGURED_KEY, false);
    }

    static Source sourceOf(ProviderConnectionConfig config) {
        if (config == null || config.source() == null) {
            return Source.NONE;
        }
        try {
            return Source.valueOf(config.source());
        } catch (IllegalArgumentException e) {
            return Source.NONE;
        }
    }
}
