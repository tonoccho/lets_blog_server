package com.letsblog.platform.controller;

import com.letsblog.platform.ai.AiProvider;
import com.letsblog.platform.service.AppSettingService;
import com.letsblog.platform.service.SystemSettingService;
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
 * <p>issue #705で本サービスのSecurityConfigは「公開パスを除きJWT必須」へ変更したが、この
 * {@code /api/internal/platform/**}だけは当初PUBLIC_PATHSに残していた。gatewayのルート表に
 * 載っておらず外部から到達できないことに加え、呼び出し元のPlatformServiceClientが
 * Bearerトークンを一切付与しない実装で、authenticatedにすると実行時に壊れたためである。
 *
 * <p>issue #742でその呼び出し元をClient Credentials Grantでトークンを付与するよう修正し、
 * ここもJWT必須へ移した。これでproject-service/publishing-serviceの内部ブリッジと同じ方式に揃う。
 * 本コントローラが返すのはBrave Search APIキー・LLM APIキー・ChatGPTキーという実際の
 * シークレットであり、外部到達性が無いとはいえ内部ネットワークから無防備なまま残す理由が無い。
 *
 * <p>認証は要求するが、認可(ロール判定)は行わない。認証済みであれば誰でも到達できる点は
 * project-service/publishing-serviceの内部ブリッジと同じモデルで、
 * サービストークン限定に絞るかどうかは別途の判断({@code docs/AUTHORIZATION_MATRIX.md}参照)。
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

    /**
     * ChatGPT / ClaudeのAPIキーはプロジェクト単位だけで、システム全体のキーは無い(issue #1568)。
     * ai-serviceはプロジェクトのキーだけを使うので、この項目は常に空(契約の互換のために項目は残す)。
     */
    private static final String NO_SYSTEM_API_KEY = "";

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
                NO_SYSTEM_API_KEY,
                appSettingService.defaultModelFor(resolved),
                appSettingService.availableModelsFor(resolved),
                appSettingService.requestTimeoutSeconds());
    }

    public record ImageGenerationConfigResponse(String comfyUiBaseUrl, String chatGptBaseUrl) {
    }

    /**
     * legacy-apiに残るChatGptImageClient/ComfyUiClientが、PlatformServiceClient
     * (ImageGenerationConfigProvider実装)経由で呼ぶ(issue #531、#693)。
     */
    @GetMapping("/api/internal/platform/image-generation-config")
    public ImageGenerationConfigResponse imageGenerationConfig() {
        return new ImageGenerationConfigResponse(
                appSettingService.comfyUiBaseUrl(),
                appSettingService.chatGptBaseUrl());
    }

    /** 接続先URL(APIキー方式のプロバイダーはnull)・設定の出所・設定有無。APIキーの値は含まない。 */
    public record ProviderConnectionConfig(String baseUrl, String source, boolean configured) {
    }

    public record AiConnectionsConfigResponse(
            ProviderConnectionConfig ollama, ProviderConnectionConfig comfyui,
            ProviderConnectionConfig openai, ProviderConnectionConfig claude) {
    }

    /**
     * ai-serviceのai-connections API(issue #1499)向け。4プロバイダーそれぞれの接続先・設定の出所・
     * 設定有無を返す。OllamaとComfyUIのURLを含め、実際のキー値はレスポンスに
     * 含めない(ai-serviceは有無しか必要としない)。
     */
    @GetMapping("/api/internal/platform/ai-connections-config")
    public AiConnectionsConfigResponse aiConnectionsConfig() {
        return new AiConnectionsConfigResponse(
                urlConfig(appSettingService.getLlmOllamaBaseUrl(), appSettingService.ollamaBaseUrlSource()),
                urlConfig(appSettingService.getComfyUiBaseUrl(), appSettingService.comfyUiBaseUrlSource()),
                NO_SYSTEM_KEY,
                NO_SYSTEM_KEY);
    }

    private static ProviderConnectionConfig urlConfig(String baseUrl, AppSettingService.SettingSource source) {
        boolean configured = baseUrl != null && !baseUrl.isBlank();
        return new ProviderConnectionConfig(configured ? baseUrl : null, source.name(), configured);
    }

    /** ChatGPT / Claudeはシステム側に出所が無い(プロジェクト単位のキーだけ。ai-serviceがプロジェクトの有無で上書きする)。 */
    private static final ProviderConnectionConfig NO_SYSTEM_KEY =
            new ProviderConnectionConfig(null, AppSettingService.SettingSource.NONE.name(), false);
}
