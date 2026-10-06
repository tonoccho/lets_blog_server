package com.letsblog.ai.dto;

import com.letsblog.ai.dto.AiConnectionResponse.Source;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * プロジェクトから見たOllama / ComfyUIの接続先(issue #1503)。
 *
 * <p>{@code overrideBaseUrl}はこのプロジェクトが上書きしている値(無ければnull)、{@code baseUrl}と
 * {@code source}は解決結果(プロジェクト設定 → システム設定(DB)。どちらにも無ければ未設定。環境変数へは落とさない、issue #1567)。
 */
public record ProjectConnectionsResponse(Entry ollama, Entry comfyui) {

    @Schema(name = "ProjectConnectionEntry")
    public record Entry(String overrideBaseUrl, String baseUrl, Source source) {
    }
}
