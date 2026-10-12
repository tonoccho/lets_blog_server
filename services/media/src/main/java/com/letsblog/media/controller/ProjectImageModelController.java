package com.letsblog.media.controller;

import com.letsblog.media.dto.ComfyUiCheckpointListResponse;
import com.letsblog.media.dto.ImageProviderListResponse;
import com.letsblog.media.dto.SelectComfyUiCheckpointRequest;
import com.letsblog.media.dto.SelectImageProviderRequest;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.ComfyUiModelService;
import com.letsblog.media.service.ImageModelService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクト画面から、そのプロジェクトが使う画像生成AI/ComfyUIのモデルを一覧・切り替えするAPI。
 * ComfyUIのチェックポイントは一覧と選択だけを扱う。導入・削除はComfyUIを動かしているPC側で行い、
 * ここには無い(issue #1746)。
 *
 * <p>issue #583でlegacy-apiの{@code ProjectAiModelController}から移設した。同じ
 * {@code /api/projects/{id}/ai-models/**}配下の{@code /llm/**}はissue #574でai-serviceが持つ。
 * gateway側で{@code /llm/**}をai-serviceへ、{@code /image/**}・{@code /comfyui/**}を
 * media-serviceへ、それぞれ先にマッチさせるルートを置いている。
 */
@RestController
@RequestMapping("/api/projects/{id}/ai-models")
public class ProjectImageModelController {

    private final ComfyUiModelService comfyUiModelService;
    private final ImageModelService imageModelService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ProjectImageModelController(
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
            @PathVariable Long id, @Valid @RequestBody SelectImageProviderRequest request) {
        adminAuthorizationService.requireAdmin();
        return imageModelService.selectProvider(id, request.provider());
    }

    @GetMapping("/comfyui/checkpoints")
    public ComfyUiCheckpointListResponse listCheckpoints(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return comfyUiModelService.listCheckpointsForProject(id);
    }

    @PutMapping("/comfyui/checkpoints/selection")
    public ComfyUiCheckpointListResponse selectCheckpoint(
            @PathVariable Long id, @Valid @RequestBody SelectComfyUiCheckpointRequest request) {
        adminAuthorizationService.requireAdmin();
        return comfyUiModelService.selectCheckpoint(id, request.checkpointName());
    }
}
