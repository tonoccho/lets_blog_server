package com.letsblog.api.controller;

import com.letsblog.api.dto.AiDraftRequest;
import com.letsblog.api.dto.AiDraftResponse;
import com.letsblog.api.dto.AiTagsRequest;
import com.letsblog.api.dto.AiTagsResponse;
import com.letsblog.api.service.AiAssistService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
}
