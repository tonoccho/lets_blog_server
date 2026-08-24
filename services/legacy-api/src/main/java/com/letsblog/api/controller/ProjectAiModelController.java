package com.letsblog.api.controller;

import com.letsblog.api.dto.ComfyUiCheckpointListResponse;
import com.letsblog.api.dto.GenerationJobResponse;
import com.letsblog.api.dto.ImageProviderListResponse;
import com.letsblog.api.dto.InstallComfyUiCheckpointRequest;
import com.letsblog.api.dto.SelectComfyUiCheckpointRequest;
import com.letsblog.api.dto.SelectImageProviderRequest;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.ComfyUiModelService;
import com.letsblog.api.service.ImageModelService;
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
 * プロジェクト画面から、そのプロジェクトが使う画像生成AI/ComfyUIのモデルを一覧・切り替えするためのAPI。
 * ComfyUIについては加えてインストール・削除も扱う(ローカルダウンロードが必要なため)。
 *
 * <p>issue #574で{@code /llm/**}部分(LlmModelService)をai-serviceの{@code ProjectLlmModelController}
 * へ分割した。同じ{@code /api/projects/{id}/ai-models/**}配下だが、gateway側で{@code /llm/**}のみ
 * 先にai-serviceへマッチさせるルートを追加している(services/gateway/src/main/resources/
 * application.yml参照)。
 */
@RestController
@RequestMapping("/api/projects/{id}/ai-models")
public class ProjectAiModelController {

    private final ComfyUiModelService comfyUiModelService;
    private final ImageModelService imageModelService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectAiModelController(
            ComfyUiModelService comfyUiModelService,
            ImageModelService imageModelService,
            AdminAuthorizationService adminAuthorizationService) {
        this.comfyUiModelService = comfyUiModelService;
        this.imageModelService = imageModelService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @GetMapping("/image/provider")
    public ImageProviderListResponse listImageProvider(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return imageModelService.listProvidersForProject(id);
    }

    @PutMapping("/image/provider/selection")
    public ImageProviderListResponse selectImageProvider(
            @PathVariable Long id, @RequestBody SelectImageProviderRequest request) {
        adminAuthorizationService.requireAdmin();
        return imageModelService.selectProvider(id, request.provider());
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
