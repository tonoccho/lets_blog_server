package com.letsblog.ai.controller;

import com.letsblog.ai.dto.PullOllamaModelRequest;
import com.letsblog.ai.dto.PullOllamaModelResponse;
import com.letsblog.ai.service.AdminAuthorizationService;
import com.letsblog.ai.service.OllamaPullService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクトのOllama接続先へ、モデルのpull(インストール)を開始するAPI(issue #1675)。
 * 管理者のみ。pullは時間がかかるので、GenerationJobを作ってすぐ{@code jobId}を返し、実行は
 * {@link com.letsblog.ai.service.OllamaPullJobRunner}が非同期に行う。進捗は
 * {@code GET /api/generation-jobs/{jobId}}をポーリングして読む。
 *
 * <p>パスはai-modelsのまま本サービスが受ける(gatewayで{@code /api/projects/*}{@code /ai-models/**}の
 * media向けルートより先にマッチさせる。{@link ProjectConnectionController}と同じ)。
 */
@RestController
@RequestMapping("/api/projects/{id}/ai-models/ollama")
public class OllamaPullController {

    private final OllamaPullService ollamaPullService;
    private final AdminAuthorizationService adminAuthorizationService;

    public OllamaPullController(
            OllamaPullService ollamaPullService, AdminAuthorizationService adminAuthorizationService) {
        this.ollamaPullService = ollamaPullService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /** 空・不正な形式のモデル名は400、Ollamaの接続先が解決できなければ409。同じモデルが実行中なら新しく始めない。 */
    @PostMapping("/pull")
    @Operation(operationId = "pullOllamaModel")
    public PullOllamaModelResponse pull(@PathVariable Long id, @RequestBody PullOllamaModelRequest request) {
        adminAuthorizationService.requireAdmin();
        return ollamaPullService.start(id, request.model());
    }
}
