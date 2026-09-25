package com.letsblog.ai.controller;

import com.letsblog.ai.service.AiAssistService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * legacy-apiに残った画像生成コード(AiAssistService#generateImage/#generateImagePrompt、
 * CustomTagGenerationService/TagDesignGenerationService/StaticContentGenerationService)からの
 * LLMテキスト生成呼び出しを受ける内部ブリッジ(issue #574)。media-service(#573)の
 * CmsMediaBridgeControllerと同じ方針で、追加の認可チェックはここでは行わない
 * (呼び出し元(legacy-api)が既に自身の認可チェックを済ませたリクエストのBearerトークンを
 * 転送してもらう想定)。generation_jobsへの記録は行わない(元々これらの呼び出し元も
 * 記録していなかったため、挙動を変えない)。
 */
@RestController
public class InternalAiGenerationController {

    private final AiAssistService aiAssistService;

    public InternalAiGenerationController(AiAssistService aiAssistService) {
        this.aiAssistService = aiAssistService;
    }

    public record GenerateRequest(Long projectId, String prompt, String providerOverride) {
    }

    public record GenerateResponse(String result) {
    }

    @PostMapping("/api/internal/ai/generate")
    public GenerateResponse generate(@RequestBody GenerateRequest request) {
        String result = aiAssistService.generateForBridge(
                request.projectId(), request.prompt(), request.providerOverride());
        return new GenerateResponse(result);
    }
}
