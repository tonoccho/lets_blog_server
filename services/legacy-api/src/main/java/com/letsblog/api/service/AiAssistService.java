package com.letsblog.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.ChatGptImageClient;
import com.letsblog.api.ai.ComfyUiClient;
import com.letsblog.api.ai.ComfyUiGenerationParams;
import com.letsblog.api.ai.ComfyUiImage;
import com.letsblog.api.ai.ImageGenerationProvider;
import com.letsblog.api.ai.ImageProvider;
import com.letsblog.api.ai.MediaGeneratedImageClient;
import com.letsblog.api.client.AiGenerationClient;
import com.letsblog.api.client.GenerationJobClient;
import com.letsblog.api.dto.AiImageBatchResponse;
import com.letsblog.api.dto.AiImagePromptResponse;
import com.letsblog.api.dto.AiImageRequest;
import com.letsblog.api.dto.AiImageResponse;
import com.letsblog.api.dto.ImageGenerationOptionsResponse;
import com.letsblog.api.dto.PlanChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * ComfyUI/ChatGPT画像生成AIを利用した画像生成支援。呼び出しごとにgeneration_jobs(ai-serviceが
 * 所有、issue #574)テーブルへ履歴を記録する。
 *
 * <p>issue #574でLLMテキスト生成部分(下書き/校正/要約、タグ提案、セクション生成、Ask)は
 * ai-serviceへ移設した(新しいai-service側のAiAssistServiceを参照)。画像生成は#573で
 * 「生成画像の保存責務のみをmedia-serviceへ委譲し、生成AI呼び出し自体はlegacy-apiに残す」と
 * 判断された経緯があり(MediaGeneratedImageClientのJavadoc参照)、#574でも同じ判断を踏襲して
 * legacy-apiに残す。generation_jobsへの直接書き込みができなくなった(ADR-0004)ため、
 * ジョブの作成・進捗更新は{@link GenerationJobClient}経由でai-serviceへ委譲し、LLM呼び出し
 * (画像生成プロンプトの組み立て・生成画像のタグ提案に使う)も{@link AiGenerationClient}経由で
 * ai-serviceへ委譲する。
 */
@Service
public class AiAssistService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistService.class);

    private static final String IMAGE_PROMPT_SYSTEM_PROMPT =
            "あなたは画像生成AI(Stable Diffusion)向けのプロンプトエンジニアです。"
            + "ユーザーとの会話から生成したい画像の内容を理解し、Stable Diffusion用の英語のプロンプトを作成してください。"
            + "被写体、構図、スタイル、雰囲気、画質に関する具体的なキーワードをカンマ区切りで含めてください。"
            + "出力はプロンプト文字列のみとし、説明文や前置き、日本語は含めないでください。";

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
    private final MediaGeneratedImageClient mediaGeneratedImageClient;
    private final GenerationJobClient generationJobClient;
    private final ObjectMapper objectMapper;
    private final ProjectService projectService;
    private final ProhibitedContentFilterService prohibitedContentFilterService;

    public AiAssistService(AiGenerationClient aiGenerationClient,
                           ComfyUiClient comfyUiClient,
                           ChatGptImageClient chatGptImageClient,
                           ImageModelService imageModelService,
                           ComfyUiModelService comfyUiModelService,
                           MediaGeneratedImageClient mediaGeneratedImageClient,
                           GenerationJobClient generationJobClient,
                           ObjectMapper objectMapper,
                           ProjectService projectService,
                           ProhibitedContentFilterService prohibitedContentFilterService) {
        this.aiGenerationClient = aiGenerationClient;
        this.comfyUiClient = comfyUiClient;
        this.chatGptImageClient = chatGptImageClient;
        this.imageModelService = imageModelService;
        this.comfyUiModelService = comfyUiModelService;
        this.mediaGeneratedImageClient = mediaGeneratedImageClient;
        this.generationJobClient = generationJobClient;
        this.objectMapper = objectMapper;
        this.projectService = projectService;
        this.prohibitedContentFilterService = prohibitedContentFilterService;
    }

    public AiImageBatchResponse generateImage(AiImageRequest request) {
        ImageProvider provider = imageModelService.getSelectedProvider(request.projectId());
        ImageGenerationProvider generator = provider == ImageProvider.CHATGPT ? chatGptImageClient : comfyUiClient;
        Long jobId = startJob(
                provider == ImageProvider.CHATGPT ? "chatgpt_image" : "comfyui_image",
                Map.of("prompt", request.prompt()));
        try {
            ComfyUiGenerationParams params = resolveParams(request);
            prohibitedContentFilterService.check(
                    params.prompt(),
                    projectService.resolveBlockSexualContent(request.projectId()),
                    projectService.resolveBlockViolentContent(request.projectId()),
                    projectService.resolveBlockDiscriminatoryContent(request.projectId()));
            List<ComfyUiImage> images = generator.generateImage(params);
            // バッチ内の全画像は同じprompt/negativePromptから生成されるため、タグ提案は1回で済ませて使い回す。
            String tagsJson = suggestImageTagsJson(params.prompt());
            List<AiImageResponse> responses = new ArrayList<>();
            for (ComfyUiImage image : images) {
                String base64 = Base64.getEncoder().encodeToString(image.data());
                Long savedId = mediaGeneratedImageClient.create(
                        request.projectId(), params.prompt(), params.negativePrompt(), params.steps(),
                        params.cfgScale(), params.samplerName(), params.scheduler(), params.seed(),
                        params.width(), params.height(), params.batchSize(), params.checkpoint(),
                        params.loraName(), params.loraWeight(), image.mimeType(), provider.name(), tagsJson,
                        image.data());
                responses.add(new AiImageResponse(savedId, image.fileName(), base64, image.mimeType()));
            }
            completeJob(jobId, Map.of("count", String.valueOf(responses.size())));
            return new AiImageBatchResponse(responses);
        } catch (RuntimeException e) {
            failJob(jobId, e);
            throw e;
        }
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

    /**
     * チャットメッセージ(と任意の履歴)から、ComfyUIへ渡す画像生成プロンプト(英語)をLLMで生成する。
     * ArticlePlanService.buildChatPrompt(ai-service側)と同様に「System+履歴+User」形式で
     * プロンプトを組み立てる。
     */
    public AiImagePromptResponse generateImagePrompt(
            Long projectId, List<PlanChatMessage> history, String message, String providerOverride) {
        Long jobId = startJob("llm_image_prompt", Map.of(
                "projectId", String.valueOf(projectId),
                "message", message
        ));
        try {
            String prompt = buildImagePromptChat(history, message);
            String result = aiGenerationClient.generate(projectId, prompt, providerOverride);
            completeJob(jobId, Map.of("result", result));
            return new AiImagePromptResponse(result);
        } catch (RuntimeException e) {
            failJob(jobId, e);
            throw e;
        }
    }

    private String buildImagePromptChat(List<PlanChatMessage> history, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("System: ").append(IMAGE_PROMPT_SYSTEM_PROMPT).append("\n\n");
        if (history != null) {
            for (PlanChatMessage msg : history) {
                sb.append("user".equals(msg.role()) ? "User" : "Assistant")
                        .append(": ")
                        .append(msg.content())
                        .append("\n");
            }
        }
        sb.append("User: ").append(message).append("\n");
        sb.append("Assistant: ");
        return sb.toString();
    }

    public ImageGenerationOptionsResponse getImageOptions(Long projectId) {
        return new ImageGenerationOptionsResponse(
                comfyUiClient.listCheckpoints(),
                comfyUiModelService.getSelectedCheckpointOrGlobalDefault(projectId),
                comfyUiClient.listSamplers(),
                comfyUiClient.listSchedulers(),
                comfyUiClient.listLoras(),
                projectService.resolveDefaultGeneratedImageWidth(projectId),
                projectService.resolveDefaultGeneratedImageHeight(projectId),
                projectService.resolveDefaultNegativePrompt(projectId),
                projectService.resolveDefaultQualityPrompt(projectId));
    }

    private ComfyUiGenerationParams resolveParams(AiImageRequest request) {
        String checkpoint = request.checkpoint() != null && !request.checkpoint().isBlank()
                ? request.checkpoint()
                : comfyUiModelService.getSelectedCheckpointOrGlobalDefault(request.projectId());
        String negativePrompt = request.negativePrompt() != null && !request.negativePrompt().isBlank()
                ? request.negativePrompt()
                : projectService.resolveDefaultNegativePrompt(request.projectId());
        String qualityPrompt = projectService.resolveDefaultQualityPrompt(request.projectId());
        String prompt = qualityPrompt == null || qualityPrompt.isBlank()
                ? request.prompt()
                : request.prompt() + ", " + qualityPrompt;
        return new ComfyUiGenerationParams(
                prompt,
                negativePrompt,
                request.steps() != null ? request.steps() : 20,
                request.cfgScale() != null ? request.cfgScale() : 7.0,
                request.samplerName() != null ? request.samplerName() : "euler",
                request.scheduler() != null ? request.scheduler() : "normal",
                request.seed(),
                request.width() != null ? request.width() : projectService.resolveDefaultGeneratedImageWidth(request.projectId()),
                request.height() != null ? request.height() : projectService.resolveDefaultGeneratedImageHeight(request.projectId()),
                request.batchSize() != null ? request.batchSize() : 1,
                checkpoint,
                request.loraName(),
                request.loraWeight()
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

    private Long startJob(String type, Map<String, String> requestPayload) {
        return generationJobClient.create(type, toJson(requestPayload)).id();
    }

    private void completeJob(Long jobId, Map<String, String> resultPayload) {
        generationJobClient.updateStatus(jobId, "done", toJson(resultPayload));
    }

    private void failJob(Long jobId, Exception e) {
        generationJobClient.updateStatus(jobId, "failed", toJson(Map.of("error", String.valueOf(e.getMessage()))));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
