package com.letsblog.api.controller;

import com.letsblog.api.dto.AiAskRequest;
import com.letsblog.api.dto.AiAskResponse;
import com.letsblog.api.dto.AiDraftRequest;
import com.letsblog.api.dto.AiDraftResponse;
import com.letsblog.api.dto.AiImageBatchResponse;
import com.letsblog.api.dto.AiImagePromptRequest;
import com.letsblog.api.dto.AiImagePromptResponse;
import com.letsblog.api.dto.AiImageRequest;
import com.letsblog.api.dto.AiProofreadRequest;
import com.letsblog.api.dto.AiProofreadResponse;
import com.letsblog.api.dto.AiSectionRequest;
import com.letsblog.api.dto.AiSectionResponse;
import com.letsblog.api.dto.AiTagsRequest;
import com.letsblog.api.dto.AiTagsResponse;
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

@RestController
public class AiController {

    private final AiAssistService aiAssistService;
    private final AdminAuthorizationService adminAuthorizationService;

    public AiController(AiAssistService aiAssistService, AdminAuthorizationService adminAuthorizationService) {
        this.aiAssistService = aiAssistService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    @PostMapping("/api/ai/draft")
    public AiDraftResponse draft(@Valid @RequestBody AiDraftRequest request) {
        return aiAssistService.draft(request);
    }

    /** issue #526: エディタ右クリックメニュー「Ask AI」からの質問に、Web検索結果を踏まえて回答する。 */
    @PostMapping("/api/ai/ask")
    public AiAskResponse ask(@Valid @RequestBody AiAskRequest request) {
        return aiAssistService.ask(request);
    }

    @PostMapping("/api/ai/tags")
    public AiTagsResponse tags(@Valid @RequestBody AiTagsRequest request) {
        return aiAssistService.suggestTags(request);
    }

    /** issue #523: エディタでのリアルタイム校正チェック。 */
    @PostMapping("/api/ai/proofread")
    public AiProofreadResponse proofread(@Valid @RequestBody AiProofreadRequest request) {
        return aiAssistService.proofreadContent(request);
    }

    @PostMapping("/api/ai/image")
    public AiImageBatchResponse image(@Valid @RequestBody AiImageRequest request) {
        return aiAssistService.generateImage(request);
    }

    @GetMapping("/api/ai/image-options")
    public ImageGenerationOptionsResponse imageOptions(@RequestParam(required = false) Long projectId) {
        return aiAssistService.getImageOptions(projectId);
    }

    @PostMapping("/api/ai/section")
    public AiSectionResponse section(@Valid @RequestBody AiSectionRequest request) {
        return aiAssistService.generateSection(request);
    }

    @PostMapping("/api/projects/{projectId}/ai/generate-image-prompt")
    public AiImagePromptResponse generateImagePrompt(
            @PathVariable Long projectId, @Valid @RequestBody AiImagePromptRequest request) {
        adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
        return aiAssistService.generateImagePrompt(projectId, request.history(), request.message(), request.provider());
    }
}
