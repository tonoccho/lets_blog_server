package com.letsblog.api.controller;

import com.letsblog.api.dto.ComfyUiCheckpointListResponse;
import com.letsblog.api.dto.GenerationJobResponse;
import com.letsblog.api.dto.InstallComfyUiCheckpointRequest;
import com.letsblog.api.dto.InstallOllamaModelRequest;
import com.letsblog.api.dto.OllamaModelListResponse;
import com.letsblog.api.dto.SelectComfyUiCheckpointRequest;
import com.letsblog.api.dto.SelectOllamaModelRequest;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.ComfyUiModelService;
import com.letsblog.api.service.OllamaModelService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクト画面から、そのプロジェクトが使うOllama/ComfyUIのモデルを
 * 一覧・切り替え・インストール・削除するためのAPI。
 */
@RestController
@RequestMapping("/api/projects/{id}/ai-models")
public class ProjectAiModelController {

    private final OllamaModelService ollamaModelService;
    private final ComfyUiModelService comfyUiModelService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectAiModelController(
            OllamaModelService ollamaModelService,
            ComfyUiModelService comfyUiModelService,
            AdminAuthorizationService adminAuthorizationService) {
        this.ollamaModelService = ollamaModelService;
        this.comfyUiModelService = comfyUiModelService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping("/ollama/models")
    public OllamaModelListResponse listOllamaModels(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return ollamaModelService.listModelsForProject(id);
    }

    @PutMapping("/ollama/models/selection")
    public OllamaModelListResponse selectOllamaModel(
            @PathVariable Long id, @Valid @RequestBody SelectOllamaModelRequest request) {
        adminAuthorizationService.requireAdmin();
        return ollamaModelService.selectModel(id, request.modelName());
    }

    @PostMapping("/ollama/models/install")
    public GenerationJobResponse installOllamaModel(
            @PathVariable Long id, @Valid @RequestBody InstallOllamaModelRequest request) {
        adminAuthorizationService.requireAdmin();
        return ollamaModelService.startPull(request.modelName());
    }

    @DeleteMapping("/ollama/models")
    public GenerationJobResponse deleteOllamaModel(
            @PathVariable Long id, @RequestParam String modelName) {
        adminAuthorizationService.requireAdmin();
        return ollamaModelService.startDelete(modelName);
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
