package com.letsblog.ai.ai;

import com.letsblog.ai.client.LegacyApiBridgeClient;
import com.letsblog.ai.service.CurrentActorService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@link LlmConfigProvider}のai-service向け実装。実際の値(LLM APIキー・ベースURL・モデル名等)は
 * Web管理画面のシステム設定(issue #403)で決まり、legacy-apiのAppSettingServiceがsystem_settings
 * (platform-serviceがまだ未抽出のためlegacy-apiに残る、ADR-0004)を正として保持し続けるため、
 * ai-serviceは{@link LegacyApiBridgeClient#resolveLlmConfig}経由で都度解決する(issue #574)。
 *
 * <p>1リクエスト内でLlmClientが複数回設定値を参照しても(provider()→apiKeyFor(provider)のように)
 * legacy-apiへの往復が増えないよう、解決済みの{@link LegacyApiBridgeClient.LlmConfig}を
 * プロバイダー名(未指定時は"__default__")ごとにリクエストスコープでキャッシュする。
 */
@Component
public class RemoteLlmConfigProvider implements LlmConfigProvider {

    private static final String CACHE_ATTR = RemoteLlmConfigProvider.class.getName() + ".cache";
    private static final String DEFAULT_KEY = "__default__";

    private final LegacyApiBridgeClient bridgeClient;
    private final CurrentActorService currentActorService;
    private final HttpServletRequest request;

    public RemoteLlmConfigProvider(
            LegacyApiBridgeClient bridgeClient, CurrentActorService currentActorService, HttpServletRequest request) {
        this.bridgeClient = bridgeClient;
        this.currentActorService = currentActorService;
        this.request = request;
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
        return provider == AiProvider.CLAUDE ? LlmClient.ANTHROPIC_BASE_URL : resolveFor(provider).baseUrl();
    }

    @Override
    public List<String> availableModels() {
        return resolveDefault().availableModels();
    }

    private LegacyApiBridgeClient.LlmConfig resolveFor(AiProvider provider) {
        return resolve(provider == null ? null : provider.name());
    }

    private LegacyApiBridgeClient.LlmConfig resolveDefault() {
        return resolve(null);
    }

    @SuppressWarnings("unchecked")
    private LegacyApiBridgeClient.LlmConfig resolve(String providerName) {
        Map<String, LegacyApiBridgeClient.LlmConfig> cache =
                (Map<String, LegacyApiBridgeClient.LlmConfig>) request.getAttribute(CACHE_ATTR);
        if (cache == null) {
            cache = new HashMap<>();
            request.setAttribute(CACHE_ATTR, cache);
        }
        String cacheKey = providerName == null ? DEFAULT_KEY : providerName;
        LegacyApiBridgeClient.LlmConfig cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        LegacyApiBridgeClient.LlmConfig resolved =
                bridgeClient.resolveLlmConfig(providerName, currentActorService.getAuthorizationHeader());
        cache.put(cacheKey, resolved);
        // 解決結果自体が名乗るプロバイダー名でもキャッシュしておく(未指定呼び出し→provider()解決後、
        // 同じリクエスト内でapiKeyFor(解決済みprovider)を呼んでも往復が増えないようにするため)。
        if (resolved.provider() != null) {
            cache.putIfAbsent(resolved.provider(), resolved);
        }
        return resolved;
    }
}
