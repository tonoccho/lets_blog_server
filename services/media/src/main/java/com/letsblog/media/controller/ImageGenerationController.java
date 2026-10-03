package com.letsblog.media.controller;

import com.letsblog.media.dto.AiImageBatchResponse;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.media.dto.AiImageRequest;
import com.letsblog.media.dto.GenerationJobResponse;
import com.letsblog.media.dto.ImageGenerationOptionsResponse;
import com.letsblog.media.service.AdminAuthorizationService;
import com.letsblog.media.service.ImageGenerationJobStarter;
import com.letsblog.media.service.ImageGenerationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    private final ImageGenerationJobStarter imageGenerationJobStarter;

    public ImageGenerationController(
            ImageGenerationService imageGenerationService,
            AdminAuthorizationService adminAuthorizationService,
            ImageGenerationJobStarter imageGenerationJobStarter) {
        this.imageGenerationService = imageGenerationService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.imageGenerationJobStarter = imageGenerationJobStarter;
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

    /**
     * 画像生成を非同期ジョブとして受理する(issue #1405)。生成の完了を待たずにジョブIDを返し、
     * 状態と結果(生成画像のID)は{@code GET /api/generation-jobs/{id}}で引く。
     * {@link #image}(同期)は変えず、認可は同じ({@code projectId}指定時はメンバー判定)。
     *
     * <p>パスを分けたのは、既存パスに真偽値を足すと同期と同じレート制限バケットに
     * 束ねられるため。この口はgatewayでapi-globalに置く(gatewayのRateLimitWebFilter参照)。
     */
    @PostMapping("/api/ai/image/jobs")
    public ResponseEntity<GenerationJobResponse> imageJob(
            @Valid @RequestBody AiImageRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (request.projectId() != null) {
            adminAuthorizationService.requireProjectMemberOrAdmin(request.projectId());
        }
        GenerationJobSummary job = imageGenerationJobStarter.start(request, authorization);
        return ResponseEntity.accepted()
                .body(GenerationJobResponse.from(job));
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
