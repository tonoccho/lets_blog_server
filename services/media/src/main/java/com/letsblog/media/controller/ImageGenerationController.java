package com.letsblog.media.controller;

import com.letsblog.media.dto.AiImageBatchResponse;
import com.letsblog.media.dto.AiImageRequest;
import com.letsblog.media.dto.ImageGenerationOptionsResponse;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.ImageGenerationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 画像生成API。issue #583でlegacy-apiの{@code AiController}から移設した。
 *
 * <p>テキスト生成(下書き/校正/要約/タグ提案/セクション生成/Ask AI)はissue #574でai-serviceが持つ。
 * 画像生成だけがlegacy-apiに残っていたが、生成画像・ComfyUIチェックポイントの実体・
 * インストールワーカーはいずれもmedia-serviceが所有しているため、#583で生成本体もこちらへ寄せた。
 *
 * <p>パスは{@code /api/ai/image}・{@code /api/ai/image-options}のまま変えていない
 * (既存のWeb/VSCode拡張のURLを変えないため)。gatewayは{@code /api/ai/**}をai-serviceへ
 * 向けているので、この2本だけを先にmedia-serviceへマッチさせるルートを置いている。
 */
@RestController
public class ImageGenerationController {

    private final ImageGenerationService imageGenerationService;
    private final AdminAuthorizationService adminAuthorizationService;

    public ImageGenerationController(
            ImageGenerationService imageGenerationService, AdminAuthorizationService adminAuthorizationService) {
        this.imageGenerationService = imageGenerationService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /**
     * 画像生成。projectId が指定されるとそのプロジェクトの設定(既定サイズ・ネガティブプロンプト等)を
     * 読むため、指定時はメンバー(またはadmin)に限定する(issue #830)。
     */
    @PostMapping("/api/ai/image")
    public AiImageBatchResponse image(@Valid @RequestBody AiImageRequest request) {
        if (request.projectId() != null) {
            adminAuthorizationService.requireProjectMemberOrAdmin(request.projectId());
        }
        return imageGenerationService.generateImage(request);
    }

    /** {@link #image}と同じ理由で、projectId 指定時はメンバー判定を行う(issue #830)。 */
    @GetMapping("/api/ai/image-options")
    public ImageGenerationOptionsResponse imageOptions(@RequestParam(required = false) Long projectId) {
        if (projectId != null) {
            adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        }
        return imageGenerationService.getImageOptions(projectId);
    }
}
