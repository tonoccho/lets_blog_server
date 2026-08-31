package com.letsblog.media.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.ChatGptImageClient;
import com.letsblog.media.ai.ComfyUiClient;
import com.letsblog.media.ai.ComfyUiGenerationParams;
import com.letsblog.media.ai.ComfyUiImage;
import com.letsblog.media.ai.ImageGenerationProvider;
import com.letsblog.media.ai.ImageProvider;
import com.letsblog.media.client.AiGenerationClient;
import com.letsblog.media.client.GenerationJobClient;
import com.letsblog.media.dto.AiImageBatchResponse;
import com.letsblog.media.dto.AiImageRequest;
import com.letsblog.media.dto.AiImageResponse;
import com.letsblog.media.dto.CreateGeneratedImageRequest;
import com.letsblog.media.dto.ImageGenerationOptionsResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * ComfyUI/ChatGPTによる画像生成。呼び出しごとに{@code generation_jobs}
 * (ai-serviceが所有、issue #574)へ履歴を記録する。
 *
 * <p>issue #583でlegacy-apiの{@code AiAssistService}から移設した。#573・#574の時点では
 * 「生成画像の保存責務だけをmedia-serviceへ委譲し、生成AI呼び出し自体はlegacy-apiに残す」という
 * 判断だったが、#583のlegacy-api解体にあたり<b>画像生成一式をmedia-serviceへ寄せる</b>と決めた
 * (media-serviceは既に{@code generated_images}・ComfyUIチェックポイントの実体・
 * インストールワーカーを所有しており、生成だけが別サービスに残っている状態のほうが不自然なため)。
 *
 * <p>移設により、生成した画像の保存がHTTP({@code POST /api/generated-images})から
 * 同一サービス内の直接呼び出し({@link GeneratedImageCreationService})になった。
 *
 * <p>LLM呼び出し(生成画像のタグ提案)だけは所有権がai-service(#574)にあるため、
 * {@link AiGenerationClient}経由で委譲する。画像生成プロンプトの組み立て
 * ({@code POST /api/projects/{id}/ai/generate-image-prompt})は純粋なLLM機能なので
 * #583でai-serviceへ移した(こちらには無い)。
 */
@Service
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);

    /** issue #281: 生成画像の検索・分類用タグを、画像生成に使ったプロンプトから提案させる。 */
    private static final String IMAGE_TAGS_PROMPT_TEMPLATE = """
            以下は画像生成AIに渡したプロンプトです。この画像を検索・分類しやすくするための
            短い日本語タグを3〜5個程度提案してください。
            出力は必ず次のJSON形式のみとし、他の文章は一切含めないでください。

            {"tags": ["タグ1", "タグ2", "タグ3"]}

            画像生成プロンプト:
            %s
            """;

    private final AiGenerationClient aiGenerationClient;
    private final ComfyUiClient comfyUiClient;
    private final ChatGptImageClient chatGptImageClient;
    private final ImageModelService imageModelService;
    private final ComfyUiModelService comfyUiModelService;
    private final GeneratedImageCreationService generatedImageCreationService;
    private final GenerationJobClient generationJobClient;
    private final ObjectMapper objectMapper;
    private final ProjectImageDefaultsResolver defaultsResolver;
    private final ProhibitedContentFilterService prohibitedContentFilterService;
    private final HttpServletRequest request;

    public ImageGenerationService(
            AiGenerationClient aiGenerationClient,
            ComfyUiClient comfyUiClient,
            ChatGptImageClient chatGptImageClient,
            ImageModelService imageModelService,
            ComfyUiModelService comfyUiModelService,
            GeneratedImageCreationService generatedImageCreationService,
            GenerationJobClient generationJobClient,
            ObjectMapper objectMapper,
            ProjectImageDefaultsResolver defaultsResolver,
            ProhibitedContentFilterService prohibitedContentFilterService,
            HttpServletRequest request) {
        this.aiGenerationClient = aiGenerationClient;
        this.comfyUiClient = comfyUiClient;
        this.chatGptImageClient = chatGptImageClient;
        this.imageModelService = imageModelService;
        this.comfyUiModelService = comfyUiModelService;
        this.generatedImageCreationService = generatedImageCreationService;
        this.generationJobClient = generationJobClient;
        this.objectMapper = objectMapper;
        this.defaultsResolver = defaultsResolver;
        this.prohibitedContentFilterService = prohibitedContentFilterService;
        this.request = request;
    }

    public AiImageBatchResponse generateImage(AiImageRequest imageRequest) {
        ImageProvider provider = imageModelService.getSelectedProvider(imageRequest.projectId());
        ImageGenerationProvider generator = provider == ImageProvider.CHATGPT ? chatGptImageClient : comfyUiClient;
        Long jobId = startJob(
                provider == ImageProvider.CHATGPT ? "chatgpt_image" : "comfyui_image",
                Map.of("prompt", imageRequest.prompt()));
        try {
            ComfyUiGenerationParams params = resolveParams(imageRequest);
            prohibitedContentFilterService.check(
                    params.prompt(),
                    defaultsResolver.resolveBlockSexualContent(imageRequest.projectId()),
                    defaultsResolver.resolveBlockViolentContent(imageRequest.projectId()),
                    defaultsResolver.resolveBlockDiscriminatoryContent(imageRequest.projectId()));
            List<ComfyUiImage> images = generator.generateImage(params);
            // バッチ内の全画像は同じprompt/negativePromptから生成されるため、タグ提案は1回で済ませて使い回す。
            String tagsJson = suggestImageTagsJson(params.prompt());
            List<AiImageResponse> responses = new ArrayList<>();
            for (ComfyUiImage image : images) {
                Long savedId = generatedImageCreationService.create(new CreateGeneratedImageRequest(
                        imageRequest.projectId(), params.prompt(), params.negativePrompt(), params.steps(),
                        params.cfgScale(), params.samplerName(), params.scheduler(), params.seed(),
                        params.width(), params.height(), params.batchSize(), params.checkpoint(),
                        params.loraName(), params.loraWeight(), image.mimeType(), provider.name(), tagsJson,
                        image.data())).getId();
                String base64 = Base64.getEncoder().encodeToString(image.data());
                responses.add(new AiImageResponse(savedId, image.fileName(), base64, image.mimeType()));
            }
            completeJob(jobId, Map.of("count", String.valueOf(responses.size())));
            return new AiImageBatchResponse(responses);
        } catch (RuntimeException e) {
            failJob(jobId, e);
            throw e;
        }
    }

    public ImageGenerationOptionsResponse getImageOptions(Long projectId) {
        return new ImageGenerationOptionsResponse(
                comfyUiClient.listCheckpoints(),
                comfyUiModelService.getSelectedCheckpointOrGlobalDefault(projectId),
                comfyUiClient.listSamplers(),
                comfyUiClient.listSchedulers(),
                comfyUiClient.listLoras(),
                defaultsResolver.resolveDefaultGeneratedImageWidth(projectId),
                defaultsResolver.resolveDefaultGeneratedImageHeight(projectId),
                defaultsResolver.resolveDefaultNegativePrompt(projectId),
                defaultsResolver.resolveDefaultQualityPrompt(projectId));
    }

    /**
     * 画像生成プロンプトから検索・分類用のタグを提案し、JSON配列文字列として返す(issue #281)。
     * タグ提案はあくまで補助機能のため、LLM呼び出しの失敗で画像生成自体を失敗させない
     * (取得できない場合はタグなし=nullを返す)。
     */
    private String suggestImageTagsJson(String prompt) {
        try {
            String raw = aiGenerationClient.generate(null, IMAGE_TAGS_PROMPT_TEMPLATE.formatted(prompt), null);
            JsonNode node = objectMapper.readTree(extractJsonObject(raw));
            List<String> tags = toStringList(node.get("tags"));
            if (tags.isEmpty()) {
                return null;
            }
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            log.warn("生成画像のタグ提案に失敗しました(タグなしで保存を続行します): {}", e.getMessage());
            return null;
        }
    }

    private ComfyUiGenerationParams resolveParams(AiImageRequest imageRequest) {
        String checkpoint = imageRequest.checkpoint() != null && !imageRequest.checkpoint().isBlank()
                ? imageRequest.checkpoint()
                : comfyUiModelService.getSelectedCheckpointOrGlobalDefault(imageRequest.projectId());
        String negativePrompt = imageRequest.negativePrompt() != null && !imageRequest.negativePrompt().isBlank()
                ? imageRequest.negativePrompt()
                : defaultsResolver.resolveDefaultNegativePrompt(imageRequest.projectId());
        String qualityPrompt = defaultsResolver.resolveDefaultQualityPrompt(imageRequest.projectId());
        String prompt = qualityPrompt == null || qualityPrompt.isBlank()
                ? imageRequest.prompt()
                : imageRequest.prompt() + ", " + qualityPrompt;
        return new ComfyUiGenerationParams(
                prompt,
                negativePrompt,
                imageRequest.steps() != null ? imageRequest.steps() : 20,
                imageRequest.cfgScale() != null ? imageRequest.cfgScale() : 7.0,
                imageRequest.samplerName() != null ? imageRequest.samplerName() : "euler",
                imageRequest.scheduler() != null ? imageRequest.scheduler() : "normal",
                imageRequest.seed(),
                imageRequest.width() != null
                        ? imageRequest.width()
                        : defaultsResolver.resolveDefaultGeneratedImageWidth(imageRequest.projectId()),
                imageRequest.height() != null
                        ? imageRequest.height()
                        : defaultsResolver.resolveDefaultGeneratedImageHeight(imageRequest.projectId()),
                imageRequest.batchSize() != null ? imageRequest.batchSize() : 1,
                checkpoint,
                imageRequest.loraName(),
                imageRequest.loraWeight()
        );
    }

    private String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < 0 || end < start) {
            return raw;
        }
        return raw.substring(start, end + 1);
    }

    private List<String> toStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> result.add(n.asText()));
        }
        return result;
    }

    private String bearerToken() {
        return request.getHeader(HttpHeaders.AUTHORIZATION);
    }

    private Long startJob(String type, Map<String, String> requestPayload) {
        return generationJobClient.create(type, toJson(requestPayload), bearerToken()).id();
    }

    private void completeJob(Long jobId, Map<String, String> resultPayload) {
        generationJobClient.updateStatus(jobId, "done", toJson(resultPayload), bearerToken());
    }

    private void failJob(Long jobId, Exception e) {
        generationJobClient.updateStatus(
                jobId, "failed", toJson(Map.of("error", String.valueOf(e.getMessage()))), bearerToken());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
