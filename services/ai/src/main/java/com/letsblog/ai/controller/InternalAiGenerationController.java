package com.letsblog.ai.controller;

import com.letsblog.ai.service.AiAssistService;
import java.util.Base64;
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

    /** 画像はbase64文字列で受ける。 */
    public record GenerateWithImageRequest(Long projectId, String prompt, String mimeType, String imageBase64) {
    }

    @PostMapping("/api/internal/ai/generate")
    public GenerateResponse generate(@RequestBody GenerateRequest request) {
        String result = aiAssistService.generateForBridge(
                request.projectId(), request.prompt(), request.providerOverride());
        return new GenerateResponse(result);
    }

    /**
     * 画像1枚を添えた生成(issue #1600)。プロジェクトのLLM設定が画像入力に対応しないときは
     * {@code result}がnullの200を返す(エラーではない。呼び出し側がタグ付けを省略する)。
     * 画像が空・base64として不正なら400(IllegalArgumentException)。
     */
    @PostMapping("/api/internal/ai/generate-with-image")
    public GenerateResponse generateWithImage(@RequestBody GenerateWithImageRequest request) {
        if (request.imageBase64() == null || request.imageBase64().isEmpty()) {
            throw new IllegalArgumentException("画像が指定されていません");
        }
        byte[] data = Base64.getDecoder().decode(request.imageBase64());
        String result = aiAssistService.generateWithImageForBridge(
                request.projectId(), request.prompt(), request.mimeType(), data);
        return new GenerateResponse(result);
    }
}
