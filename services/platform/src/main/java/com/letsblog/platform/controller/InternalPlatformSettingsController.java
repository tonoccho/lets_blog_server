package com.letsblog.platform.controller;

import com.letsblog.platform.ai.AiProvider;
import com.letsblog.platform.service.AppSettingService;
import com.letsblog.platform.service.SystemSettingService;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * legacy-apiへ、システム設定の「実際の値」を提供する内部ブリッジ(issue #693)。
 * legacy-apiに残るAiBridgeController(ai-service向けブリッジ、system-settings/brave-search-api-key・
 * llm-config)、およびChatGptImageClient/ComfyUiClient(画像生成、#574で移設されずlegacy-apiに残置)は
 * PlatformServiceClient経由でここを呼び出す(content-serviceの{@code /api/internal/content/**}と
 * 同じ「ホスト側が自分のサービス名を名乗る」命名規約)。
 *
 * <p>いずれも移設前のAiBridgeController#systemBraveSearchApiKey/#llmConfigと同じくadmin権限チェックは
 * 行わない(システム全体で1つの値を解決するだけで、特定ユーザーのデータではないため)。
 * SecurityConfigが全経路permitAllのため、Bearerトークンの転送も不要。
 */
@RestController
public class InternalPlatformSettingsController {

    private final SystemSettingService systemSettingService;
    private final AppSettingService appSettingService;

    public InternalPlatformSettingsController(
            SystemSettingService systemSettingService, AppSettingService appSettingService) {
        this.systemSettingService = systemSettingService;
        this.appSettingService = appSettingService;
    }

    public record SystemBraveSearchApiKeyResponse(String apiKey) {
    }

    /** WebSearchService(ai-service)のプロジェクト非依存フォールバック向け。未設定ならnull。 */
    @GetMapping("/api/internal/platform/system-settings/brave-search-api-key")
    public SystemBraveSearchApiKeyResponse systemBraveSearchApiKey() {
        String apiKey = systemSettingService.getBraveSearchApiKey();
        return new SystemBraveSearchApiKeyResponse(apiKey == null || apiKey.isBlank() ? null : apiKey);
    }

    public record BraveSearchApiKeyStatusResponse(boolean configured, String source) {
    }

    /**
     * ConnectedServiceStatusService(legacy-api)がPlatformServiceClient#isBraveSearchApiKeyConfigured
     * 経由で呼ぶ(issue #693のレビュー指摘)。公開エンドポイント({@code GET /api/system-settings/
     * brave-search-api-key}、SystemSettingController参照)はログイン済みユーザーであることを要求する
     * ようになったため、認証コンテキストを持たないこのサービス間呼び出しは代わりにこちらを使う。
     */
    @GetMapping("/api/internal/platform/system-settings/brave-search-api-key-status")
    public BraveSearchApiKeyStatusResponse systemBraveSearchApiKeyStatus() {
        SystemSettingService.BraveSearchApiKeyStatus status = systemSettingService.getBraveSearchApiKeyStatusInternal();
        return new BraveSearchApiKeyStatusResponse(status.configured(), status.source().name());
    }

    public record LlmConfigResponse(
            String provider, String baseUrl, String apiKey, String defaultModel,
            List<String> availableModels, long requestTimeoutSeconds) {
    }

    /**
     * RemoteLlmConfigProvider(ai-service)が、legacy-apiのAiBridgeController#llmConfig経由で呼ぶ。
     * providerを指定しなければシステム設定の既定プロバイダーを使う。
     */
    @GetMapping("/api/internal/platform/llm-config")
    public LlmConfigResponse llmConfig(@RequestParam(required = false) String provider) {
        AiProvider resolved = provider != null && !provider.isBlank()
                ? AiProvider.fromString(provider) : appSettingService.provider();
        return new LlmConfigResponse(
                resolved.name(),
                appSettingService.baseUrlFor(resolved),
                appSettingService.apiKeyFor(resolved),
                appSettingService.defaultModelFor(resolved),
                parseAvailableModels(appSettingService.getLlmAvailableModels()),
                appSettingService.requestTimeoutSeconds());
    }

    public record ImageGenerationConfigResponse(String comfyUiBaseUrl, String chatGptApiKey, String chatGptBaseUrl) {
    }

    /**
     * legacy-apiに残るChatGptImageClient/ComfyUiClientが、PlatformServiceClient
     * (ImageGenerationConfigProvider実装)経由で呼ぶ(issue #531、#693)。
     */
    @GetMapping("/api/internal/platform/image-generation-config")
    public ImageGenerationConfigResponse imageGenerationConfig() {
        return new ImageGenerationConfigResponse(
                appSettingService.comfyUiBaseUrl(),
                appSettingService.chatGptApiKey(),
                appSettingService.chatGptBaseUrl());
    }

    private List<String> parseAvailableModels(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }
}
