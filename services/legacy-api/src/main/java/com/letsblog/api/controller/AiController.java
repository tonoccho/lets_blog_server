package com.letsblog.api.controller;

import com.letsblog.api.dto.AiImageBatchResponse;
import com.letsblog.api.dto.AiImagePromptRequest;
import com.letsblog.api.dto.AiImagePromptResponse;
import com.letsblog.api.dto.AiImageRequest;
import com.letsblog.api.dto.ImageGenerationOptionsResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.AiAssistService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 画像生成API。テキスト生成(下書き/校正/要約/タグ提案/セクション生成/Ask AI)はissue #574で
 * ai-serviceへ移設したため、このコントローラには残っていない(gatewayの{@code /api/ai/**}ルートは
 * ai-serviceへ向くが、{@code /api/ai/image}・{@code /api/ai/image-options}はlegacy-apiへ向くよう
 * gateway側で個別ルートを追加している。services/gateway/src/main/resources/application.yml参照)。
 */
@RestController
public class AiController {

    private final AiAssistService aiAssistService;
    private final AdminAuthorizationService adminAuthorizationService;

    public AiController(AiAssistService aiAssistService, AdminAuthorizationService adminAuthorizationService) {
        this.aiAssistService = aiAssistService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /**
     * 画像生成。projectId が指定されるとそのプロジェクトの設定(既定サイズ・ネガティブプロンプト等)を
     * 読むため、指定時はメンバー(またはadmin)に限定する(issue #830)。
     * すぐ下の {@link #generateImagePrompt} が既に同じ判定をしているのに揃えた。
     */
    @PostMapping("/api/ai/image")
    public AiImageBatchResponse image(@Valid @RequestBody AiImageRequest request) {
        if (request.projectId() != null) {
            adminAuthorizationService.requireProjectMemberOrAdmin(request.projectId());
        }
        return aiAssistService.generateImage(request);
    }

    /** {@link #image}と同じ理由で、projectId 指定時はメンバー判定を行う(issue #830)。 */
    @GetMapping("/api/ai/image-options")
    public ImageGenerationOptionsResponse imageOptions(@RequestParam(required = false) Long projectId) {
        if (projectId != null) {
            adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        }
        return aiAssistService.getImageOptions(projectId);
    }

    @PostMapping("/api/projects/{projectId}/ai/generate-image-prompt")
    public AiImagePromptResponse generateImagePrompt(
            @PathVariable Long projectId, @Valid @RequestBody AiImagePromptRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return aiAssistService.generateImagePrompt(projectId, request.history(), request.message(), request.provider());
    }
}
