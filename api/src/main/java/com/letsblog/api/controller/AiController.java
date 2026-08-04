package com.letsblog.api.controller;

import com.letsblog.api.dto.AiDraftRequest;
import com.letsblog.api.dto.AiDraftResponse;
import com.letsblog.api.dto.AiImageRequest;
import com.letsblog.api.dto.AiImageResponse;
import com.letsblog.api.dto.AiSectionRequest;
import com.letsblog.api.dto.AiSectionResponse;
import com.letsblog.api.dto.AiTagsRequest;
import com.letsblog.api.dto.AiTagsResponse;
import com.letsblog.api.dto.ImageGenerationOptionsResponse;
import com.letsblog.api.service.AiAssistService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiController {

    private final AiAssistService aiAssistService;

    public AiController(AiAssistService aiAssistService) {
        this.aiAssistService = aiAssistService;
    }

    @PostMapping("/api/ai/draft")
    public AiDraftResponse draft(@Valid @RequestBody AiDraftRequest request) {
        return aiAssistService.draft(request);
    }

    @PostMapping("/api/ai/tags")
    public AiTagsResponse tags(@Valid @RequestBody AiTagsRequest request) {
        return aiAssistService.suggestTags(request);
    }

    @PostMapping("/api/ai/image")
    public AiImageResponse image(@Valid @RequestBody AiImageRequest request) {
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
}
