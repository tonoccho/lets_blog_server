package com.letsblog.api.controller;

import com.letsblog.api.dto.ComfyUiCheckpointListResponse;
import com.letsblog.api.dto.GenerationJobResponse;
import com.letsblog.api.dto.InstallComfyUiCheckpointRequest;
import com.letsblog.api.dto.LlmModelListResponse;
import com.letsblog.api.dto.LlmProviderListResponse;
import com.letsblog.api.dto.SelectComfyUiCheckpointRequest;
import com.letsblog.api.dto.SelectLlmModelRequest;
import com.letsblog.api.dto.SelectLlmProviderRequest;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.ComfyUiModelService;
import com.letsblog.api.service.LlmModelService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクト画面から、そのプロジェクトが使うLLM/ComfyUIのモデルを一覧・切り替えするためのAPI。
 * ComfyUIについては加えてインストール・削除も扱う(ローカルダウンロードが必要なため)。
 */
@RestController
@RequestMapping("/api/projects/{id}/ai-models")
public class ProjectAiModelController {

    private final LlmModelService llmModelService;
    private final ComfyUiModelService comfyUiModelService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectAiModelController(
            LlmModelService llmModelService,
            ComfyUiModelService comfyUiModelService,
            AdminAuthorizationService adminAuthorizationService) {
        this.llmModelService = llmModelService;
        this.comfyUiModelService = comfyUiModelService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping("/llm/models")
    public LlmModelListResponse listLlmModels(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return llmModelService.listModelsForProject(id);
    }

    @PutMapping("/llm/models/selection")
    public LlmModelListResponse selectLlmModel(
            @PathVariable Long id, @Valid @RequestBody SelectLlmModelRequest request) {
        adminAuthorizationService.requireAdmin();
        return llmModelService.selectModel(id, request.modelName());
    }

    @GetMapping("/llm/provider")
    public LlmProviderListResponse listLlmProvider(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return llmModelService.listProvidersForProject(id);
    }

    @PutMapping("/llm/provider/selection")
    public LlmProviderListResponse selectLlmProvider(
            @PathVariable Long id, @RequestBody SelectLlmProviderRequest request) {
        adminAuthorizationService.requireAdmin();
        return llmModelService.selectProvider(id, request.provider());
    }

    @GetMapping("/comfyui/checkpoints")
    public ComfyUiCheckpointListResponse listComfyUiCheckpoints(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return comfyUiModelService.listCheckpointsForProject(id);
    }

    @PutMapping("/comfyui/checkpoints/selection")
    public ComfyUiCheckpointListResponse selectComfyUiCheckpoint(
            @PathVariable Long id, @Valid @RequestBody SelectComfyUiCheckpointRequest request) {
        adminAuthorizationService.requireAdmin();
        return comfyUiModelService.selectCheckpoint(id, request.checkpointName());
    }

    @PostMapping("/comfyui/checkpoints/install")
    public GenerationJobResponse installComfyUiCheckpoint(
            @PathVariable Long id, @Valid @RequestBody InstallComfyUiCheckpointRequest request) {
        adminAuthorizationService.requireAdmin();
        return comfyUiModelService.startInstall(request.downloadUrl(), request.fileName());
    }

    @DeleteMapping("/comfyui/checkpoints/{fileName}")
    public GenerationJobResponse deleteComfyUiCheckpoint(
            @PathVariable Long id, @PathVariable String fileName) {
        adminAuthorizationService.requireAdmin();
        return comfyUiModelService.startDelete(fileName);
    }
}
