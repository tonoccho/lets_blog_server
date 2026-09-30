package com.letsblog.ai.service;

import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.ai.client.PlatformServiceClient.AiConnectionsConfig;
import com.letsblog.ai.client.PlatformServiceClient.ProviderConnectionConfig;
import com.letsblog.ai.dto.ProjectConnectionsResponse;
import com.letsblog.ai.dto.ProjectConnectionsResponse.Entry;
import com.letsblog.ai.dto.UpdateProjectConnectionsRequest;
import org.springframework.stereotype.Service;

/**
 * プロジェクト単位のOllama / ComfyUI接続先の参照・更新(issue #1503)。
 * 解決順は プロジェクト設定 → システム設定(DB) → 環境変数既定。システム側の解決結果は
 * platform-serviceが返し(#1499)、ここではプロジェクトの上書きがあればそれで置き換える。
 */
@Service
public class ProjectConnectionService {

    static final String PROJECT_SOURCE = "PROJECT";

    private final ProjectAiSettingsService projectAiSettingsService;
    private final PlatformServiceClient platformServiceClient;
    private final CurrentActorService currentActorService;

    public ProjectConnectionService(
            ProjectAiSettingsService projectAiSettingsService, PlatformServiceClient platformServiceClient,
            CurrentActorService currentActorService) {
        this.projectAiSettingsService = projectAiSettingsService;
        this.platformServiceClient = platformServiceClient;
        this.currentActorService = currentActorService;
    }

    public ProjectConnectionsResponse get(Long projectId) {
        String ollamaOverride = blankToNull(projectAiSettingsService.getOllamaBaseUrl(projectId));
        String comfyuiOverride = blankToNull(projectAiSettingsService.getComfyuiBaseUrl(projectId));
        AiConnectionsConfig merged = applyOverrides(
                platformServiceClient.resolveAiConnectionsConfig(currentActorService.getAuthorizationHeader()),
                ollamaOverride, comfyuiOverride);
        return new ProjectConnectionsResponse(
                entry(ollamaOverride, merged.ollama()), entry(comfyuiOverride, merged.comfyui()));
    }

    /** 不正なURLなら例外で終わり、何も保存されない。 */
    public ProjectConnectionsResponse update(Long projectId, UpdateProjectConnectionsRequest request) {
        projectAiSettingsService.setConnectionUrls(projectId, request.ollamaBaseUrl(), request.comfyuiBaseUrl());
        return get(projectId);
    }

    /** Ollama / ComfyUIにプロジェクトの上書き(null/空は無し)を重ねる。ChatGPT / Claudeは素通し。 */
    static AiConnectionsConfig applyOverrides(AiConnectionsConfig base, String ollamaOverride, String comfyuiOverride) {
        return new AiConnectionsConfig(
                override(base.ollama(), ollamaOverride), override(base.comfyui(), comfyuiOverride),
                base.openai(), base.claude());
    }

    private static ProviderConnectionConfig override(ProviderConnectionConfig system, String override) {
        return blankToNull(override) == null
                ? system : new ProviderConnectionConfig(override, PROJECT_SOURCE, true);
    }

    private static Entry entry(String override, ProviderConnectionConfig resolved) {
        return new Entry(override, resolved == null ? null : resolved.baseUrl(), AiConnectionService.sourceOf(resolved));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
