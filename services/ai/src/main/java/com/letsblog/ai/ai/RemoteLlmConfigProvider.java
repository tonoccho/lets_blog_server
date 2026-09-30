package com.letsblog.ai.ai;

import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.ai.service.CurrentActorService;
import com.letsblog.ai.service.ProjectAiSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link LlmConfigProvider}のai-service向け実装。実際の値(LLM APIキー・ベースURL・モデル名等)は
 * Web管理画面のシステム設定(issue #403)で決まり、legacy-apiのAppSettingServiceがsystem_settings
 * (platform-serviceがまだ未抽出のためlegacy-apiに残る、ADR-0004)を正として保持し続けるため、
 * ai-serviceは{@link PlatformServiceClient#resolveLlmConfig}経由で都度解決する(issue #574。#583でlegacy-apiの中継を外し、システム設定を所有するplatform-serviceを直接呼ぶよう切り替えた)。
 *
 * <p>1リクエスト内でLlmClientが複数回設定値を参照しても(provider()→apiKeyFor(provider)のように)
 * legacy-apiへの往復が増えないよう、解決済みの{@link PlatformServiceClient.LlmConfig}を
 * プロバイダー名(未指定時は"__default__")ごとにリクエストスコープでキャッシュする。
 */
@Component
public class RemoteLlmConfigProvider implements LlmConfigProvider {

    private static final String CACHE_ATTR = RemoteLlmConfigProvider.class.getName() + ".cache";
    private static final String PROJECT_ATTR = RemoteLlmConfigProvider.class.getName() + ".project";
    private static final String DEFAULT_KEY = "__default__";

    private final PlatformServiceClient platformServiceClient;
    private final CurrentActorService currentActorService;
    private final HttpServletRequest request;
    private final ProjectAiSettingsService projectAiSettingsService;

    public RemoteLlmConfigProvider(
            PlatformServiceClient platformServiceClient, CurrentActorService currentActorService,
            HttpServletRequest request, ProjectAiSettingsService projectAiSettingsService) {
        this.platformServiceClient = platformServiceClient;
        this.currentActorService = currentActorService;
        this.request = request;
        this.projectAiSettingsService = projectAiSettingsService;
    }

    /** リクエスト単位で対象プロジェクトを覚える(キャッシュと同じくリクエスト属性に持つ)。 */
    @Override
    public void useProject(Long projectId) {
        request.setAttribute(PROJECT_ATTR, projectId);
    }

    @Override
    public String baseUrl() {
        return resolveDefault().baseUrl();
    }

    @Override
    public String apiKey() {
        return resolveDefault().apiKey();
    }

    @Override
    public String defaultModel() {
        return resolveDefault().defaultModel();
    }

    @Override
    public long requestTimeoutSeconds() {
        return resolveDefault().requestTimeoutSeconds();
    }

    @Override
    public AiProvider provider() {
        return AiProvider.fromString(resolveDefault().provider());
    }

    @Override
    public String apiKeyFor(AiProvider provider) {
        return resolveFor(provider).apiKey();
    }

    @Override
    public String defaultModelFor(AiProvider provider) {
        return resolveFor(provider).defaultModel();
    }

    @Override
    public String baseUrlFor(AiProvider provider) {
        if (provider == AiProvider.CLAUDE) {
            return LlmClient.ANTHROPIC_BASE_URL;
        }
        String projectOverride = provider == AiProvider.OLLAMA ? ollamaOverrideOfCurrentProject() : null;
        return projectOverride != null ? projectOverride : resolveFor(provider).baseUrl();
    }

    /** {@link #useProject}で宣言されたプロジェクトのOllama接続先の上書き。無い(null/空)ならnull(issue #1503)。 */
    private String ollamaOverrideOfCurrentProject() {
        Long projectId = (Long) request.getAttribute(PROJECT_ATTR);
        if (projectId == null) {
            return null;
        }
        String override = projectAiSettingsService.getOllamaBaseUrl(projectId);
        return override == null || override.isBlank() ? null : override;
    }

    @Override
    public List<String> availableModels() {
        return resolveDefault().availableModels();
    }

    @Override
    public List<String> availableModelsFor(AiProvider provider) {
        return resolveFor(provider).availableModels();
    }

    private PlatformServiceClient.LlmConfig resolveFor(AiProvider provider) {
        return resolve(provider == null ? null : provider.name());
    }

    private PlatformServiceClient.LlmConfig resolveDefault() {
        return resolve(null);
    }

    @SuppressWarnings("unchecked")
    private PlatformServiceClient.LlmConfig resolve(String providerName) {
        Map<String, PlatformServiceClient.LlmConfig> cache =
                (Map<String, PlatformServiceClient.LlmConfig>) request.getAttribute(CACHE_ATTR);
        if (cache == null) {
            cache = new HashMap<>();
            request.setAttribute(CACHE_ATTR, cache);
        }
        String cacheKey = providerName == null ? DEFAULT_KEY : providerName;
        PlatformServiceClient.LlmConfig cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        PlatformServiceClient.LlmConfig resolved =
                platformServiceClient.resolveLlmConfig(providerName, currentActorService.getAuthorizationHeader());
        cache.put(cacheKey, resolved);
        // 解決結果自体が名乗るプロバイダー名でもキャッシュしておく(未指定呼び出し→provider()解決後、
        // 同じリクエスト内でapiKeyFor(解決済みprovider)を呼んでも往復が増えないようにするため)。
        if (resolved.provider() != null) {
            cache.putIfAbsent(resolved.provider(), resolved);
        }
        return resolved;
    }
}
