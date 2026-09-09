package com.letsblog.ai.controller;

import com.letsblog.ai.domain.ReviewStepKey;
import com.letsblog.ai.dto.LlmModelListResponse;
import com.letsblog.ai.dto.LlmProviderListResponse;
import com.letsblog.ai.dto.ReviewStepSettingsResponse;
import com.letsblog.ai.dto.SelectLlmModelRequest;
import com.letsblog.ai.dto.SelectLlmProviderRequest;
import com.letsblog.ai.dto.SelectReviewStepModelRequest;
import com.letsblog.ai.service.AdminAuthorizationService;
import com.letsblog.ai.service.LlmModelService;
import com.letsblog.ai.service.ReviewStepModelService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * プロジェクト画面から、そのプロジェクトが使うLLMのモデル/プロバイダーを一覧・切り替えするためのAPI。
 *
 * <p>legacy-apiの{@code ProjectAiModelController}を分割したもの(issue #574)。同コントローラは
 * {@code /llm/**}(本コントローラ、ai-serviceへ移設)に加え、{@code /image/**}・{@code /comfyui/**}
 * (ImageModelService/ComfyUiModelService、project_image_settings経由でmedia-serviceのドメインに
 * 属する。#573時点でも移設されておらず本Issueの対象外)を同居させていたため、パスの前半部分
 * ({@code /api/projects/{id}/ai-models/llm/**})だけをこちらへ切り出した。gateway側のルーティング
 * ({@code services/gateway/src/main/resources/application.yml})も、この特定パスをlegacy-apiより
 * 先にai-serviceへマッチさせるよう変更している。
 */
@RestController
@RequestMapping("/api/projects/{id}/ai-models/llm")
public class ProjectLlmModelController {

    private final LlmModelService llmModelService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final ReviewStepModelService reviewStepModelService;

    public ProjectLlmModelController(
            LlmModelService llmModelService,
            AdminAuthorizationService adminAuthorizationService,
            ReviewStepModelService reviewStepModelService) {
        this.llmModelService = llmModelService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.reviewStepModelService = reviewStepModelService;
    }

    @GetMapping("/models")
    public LlmModelListResponse listLlmModels(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return llmModelService.listModelsForProject(id);
    }

    @PutMapping("/models/selection")
    public LlmModelListResponse selectLlmModel(
            @PathVariable Long id, @Valid @RequestBody SelectLlmModelRequest request) {
        adminAuthorizationService.requireAdmin();
        return llmModelService.selectModel(id, request.modelName());
    }

    @GetMapping("/provider")
    public LlmProviderListResponse listLlmProvider(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return llmModelService.listProvidersForProject(id);
    }

    @PutMapping("/provider/selection")
    public LlmProviderListResponse selectLlmProvider(
            @PathVariable Long id, @RequestBody SelectLlmProviderRequest request) {
        adminAuthorizationService.requireAdmin();
        return llmModelService.selectProvider(id, request.provider());
    }

    /**
     * 多段レビュー(issue #1210)の5ステップぶんの選択値・選択可能なprovider/model一覧を返す
     * (issue #1211)。
     */
    @GetMapping("/review-steps")
    public ReviewStepSettingsResponse listReviewStepSettings(@PathVariable Long id) {
        adminAuthorizationService.requireAdmin();
        return reviewStepModelService.listSettings(id);
    }

    /** provider/modelが空ならそのステップの上書きを解除する(issue #1211)。 */
    @PutMapping("/review-steps/{stepKey}")
    public ReviewStepSettingsResponse updateReviewStepSetting(
            @PathVariable Long id,
            @PathVariable ReviewStepKey stepKey,
            @RequestBody SelectReviewStepModelRequest request) {
        adminAuthorizationService.requireAdmin();
        return reviewStepModelService.selectSetting(id, stepKey, request.provider(), request.model());
    }
}
