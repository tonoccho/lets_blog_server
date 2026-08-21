package com.letsblog.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.AiProvider;
import com.letsblog.api.ai.ChatGptImageClient;
import com.letsblog.api.ai.ComfyUiClient;
import com.letsblog.api.ai.ComfyUiGenerationParams;
import com.letsblog.api.ai.ComfyUiImage;
import com.letsblog.api.ai.GeneratedImageStorageService;
import com.letsblog.api.ai.ImageGenerationProvider;
import com.letsblog.api.ai.ImageProvider;
import com.letsblog.api.ai.LlmClient;
import com.letsblog.api.domain.GeneratedImage;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.dto.AiDraftRequest;
import com.letsblog.api.dto.AiDraftResponse;
import com.letsblog.api.dto.AiImageBatchResponse;
import com.letsblog.api.dto.AiImagePromptResponse;
import com.letsblog.api.dto.AiImageRequest;
import com.letsblog.api.dto.AiImageResponse;
import com.letsblog.api.dto.AiSectionRequest;
import com.letsblog.api.dto.AiSectionResponse;
import com.letsblog.api.dto.AiTagsRequest;
import com.letsblog.api.dto.PlanChatMessage;
import com.letsblog.api.dto.AiTagsResponse;
import com.letsblog.api.dto.ImageGenerationOptionsResponse;
import com.letsblog.api.repository.GeneratedImageRepository;
import com.letsblog.api.repository.GenerationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * LLM/ComfyUIを利用した記事執筆支援(下書き/校正/要約、タグ・カテゴリ提案、画像生成)。
 * 呼び出しごとに generation_jobs テーブルへ履歴を記録する。
 */
@Service
public class AiAssistService {

    private static final Logger log = LoggerFactory.getLogger(AiAssistService.class);

    private static final Map<String, String> DRAFT_PROMPT_TEMPLATES = Map.of(
            "draft", """
                    あなたはブログ執筆アシスタントです。以下のお題から、日本語のブログ記事の下書きをMarkdown形式で作成してください。
                    見出し(#, ##)を使い、簡潔で読みやすい文章にしてください。前置きや説明は書かず、記事本文だけを出力してください。

                    お題:
                    %s
                    """,
            "proofread", """
                    あなたは日本語のプロの校正者です。以下の文章の誤字脱字・文法・表現を校正し、Markdown形式のまま校正後の全文だけを出力してください。
                    説明や前置きは不要です。

                    本文:
                    %s
                    """,
            "summarize", """
                    あなたはブログ編集者です。以下の記事本文を、日本語で3〜5行程度に要約してください。要約文だけを出力してください。

                    本文:
                    %s
                    """
    );

    private static final Map<String, String> SECTION_PROMPT_TEMPLATES = Map.of(
            "body", """
                    あなたはブログ執筆アシスタントです。以下の見出しについて、日本語のブログ記事の本文をMarkdown形式で作成してください。
                    見出し自体は出力せず、本文の段落のみを出力してください。前置きや説明は不要です。

                    記事タイトル: %s
                    直前までの文脈:
                    %s

                    見出し:
                    %s
                    """,
            "lead", """
                    あなたはブログ執筆アシスタントです。以下の記事全体の構成を踏まえて、記事のリード文(導入文)を日本語で作成してください。
                    読者の関心を引く2〜3文程度の簡潔な文章のみを出力してください。前置きや説明は不要です。

                    記事タイトル: %s

                    記事の構成(見出し一覧):
                    %s
                    """,
            "lead-subsections", """
                    あなたはブログ執筆アシスタントです。以下のセクションにはこのあと複数のサブセクションが続きます。
                    読者にこのセクション全体の見通しを示すリード文(導入文)を日本語で2〜3文程度で作成してください。
                    見出し自体は出力せず、リード文の文章のみを出力してください。前置きや説明は不要です。

                    記事タイトル: %s

                    セクション見出し: %s
                    このセクションに含まれるサブセクション:
                    %s
                    """
    );

    private static final String IMAGE_PROMPT_SYSTEM_PROMPT =
            "あなたは画像生成AI(Stable Diffusion)向けのプロンプトエンジニアです。"
            + "ユーザーとの会話から生成したい画像の内容を理解し、Stable Diffusion用の英語のプロンプトを作成してください。"
            + "被写体、構図、スタイル、雰囲気、画質に関する具体的なキーワードをカンマ区切りで含めてください。"
            + "出力はプロンプト文字列のみとし、説明文や前置き、日本語は含めないでください。";

    private static final String TAGS_PROMPT_TEMPLATE = """
            以下のブログ記事本文を読み、適切なカテゴリ候補とタグ候補を提案してください。
            出力は必ず次のJSON形式のみとし、他の文章は一切含めないでください。

            {"categories": ["カテゴリ1", "カテゴリ2"], "tags": ["タグ1", "タグ2", "タグ3"]}

            本文:
            %s
            """;

    /** issue #281: 生成画像の検索・分類用タグを、画像生成に使ったプロンプトから提案させる。 */
    private static final String IMAGE_TAGS_PROMPT_TEMPLATE = """
            以下は画像生成AIに渡したプロンプトです。この画像を検索・分類しやすくするための
            短い日本語タグを3〜5個程度提案してください。
            出力は必ず次のJSON形式のみとし、他の文章は一切含めないでください。

            {"tags": ["タグ1", "タグ2", "タグ3"]}

            画像生成プロンプト:
            %s
            """;

    private final LlmClient llmClient;
    private final LlmModelService llmModelService;
    private final ComfyUiClient comfyUiClient;
    private final ChatGptImageClient chatGptImageClient;
    private final ImageModelService imageModelService;
    private final ComfyUiModelService comfyUiModelService;
    private final GeneratedImageStorageService generatedImageStorageService;
    private final GeneratedImageRepository generatedImageRepository;
    private final GenerationJobRepository generationJobRepository;
    private final WebSearchService webSearchService;
    private final ObjectMapper objectMapper;
    private final ProjectService projectService;
    private final ProhibitedContentFilterService prohibitedContentFilterService;

    public AiAssistService(LlmClient llmClient, LlmModelService llmModelService,
                           ComfyUiClient comfyUiClient,
                           ChatGptImageClient chatGptImageClient,
                           ImageModelService imageModelService,
                           ComfyUiModelService comfyUiModelService,
                           GeneratedImageStorageService generatedImageStorageService,
                           GeneratedImageRepository generatedImageRepository,
                           GenerationJobRepository generationJobRepository,
                           WebSearchService webSearchService, ObjectMapper objectMapper,
                           ProjectService projectService,
                           ProhibitedContentFilterService prohibitedContentFilterService) {
        this.llmClient = llmClient;
        this.llmModelService = llmModelService;
        this.comfyUiClient = comfyUiClient;
        this.chatGptImageClient = chatGptImageClient;
        this.imageModelService = imageModelService;
        this.comfyUiModelService = comfyUiModelService;
        this.generatedImageStorageService = generatedImageStorageService;
        this.generatedImageRepository = generatedImageRepository;
        this.generationJobRepository = generationJobRepository;
        this.webSearchService = webSearchService;
        this.objectMapper = objectMapper;
        this.projectService = projectService;
        this.prohibitedContentFilterService = prohibitedContentFilterService;
    }

    public AiImageBatchResponse generateImage(AiImageRequest request) {
        ImageProvider provider = imageModelService.getSelectedProvider(request.projectId());
        ImageGenerationProvider generator = provider == ImageProvider.CHATGPT ? chatGptImageClient : comfyUiClient;
        GenerationJob job = startJob(
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
                String filePath = generatedImageStorageService.store(request.projectId(), image.data());
                GeneratedImage saved = generatedImageRepository.save(
                        toEntity(request.projectId(), params, filePath, image.mimeType(), tagsJson, provider));
                responses.add(new AiImageResponse(saved.getId(), image.fileName(), base64, image.mimeType()));
            }
            completeJob(job, Map.of("count", String.valueOf(responses.size())));
            return new AiImageBatchResponse(responses);
        } catch (RuntimeException e) {
            failJob(job, e);
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
            String raw = llmClient.generate(IMAGE_TAGS_PROMPT_TEMPLATE.formatted(prompt));
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
     * ArticlePlanService.buildChatPromptと同様に「System+履歴+User」形式でプロンプトを組み立てる。
     */
    public AiImagePromptResponse generateImagePrompt(
            Long projectId, List<PlanChatMessage> history, String message, String providerOverride) {
        GenerationJob job = startJob("llm_image_prompt", Map.of(
                "projectId", String.valueOf(projectId),
                "message", message
        ));
        try {
            String model = llmModelService.getSelectedModel(projectId);
            // リクエストでプロバイダーが明示された場合はそれを優先し、なければプロジェクト単位の既定へ
            // フォールバックする(issue #530)。
            AiProvider provider = AiProvider.fromString(providerOverride);
            if (provider == null) {
                provider = llmModelService.getSelectedProvider(projectId);
            }
            String prompt = buildImagePromptChat(history, message);
            String result = llmClient.generate(prompt, model, provider);
            completeJob(job, Map.of("result", result));
            return new AiImagePromptResponse(result);
        } catch (RuntimeException e) {
            failJob(job, e);
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

    private GeneratedImage toEntity(
            Long projectId, ComfyUiGenerationParams params, String filePath, String mimeType, String tagsJson,
            ImageProvider provider) {
        GeneratedImage entity = new GeneratedImage();
        entity.setProjectId(projectId);
        entity.setProvider(provider.name());
        entity.setPrompt(params.prompt());
        entity.setNegativePrompt(params.negativePrompt());
        entity.setSteps(params.steps());
        entity.setCfgScale(params.cfgScale() != null ? BigDecimal.valueOf(params.cfgScale()) : null);
        entity.setSamplerName(params.samplerName());
        entity.setScheduler(params.scheduler());
        entity.setSeed(params.seed());
        entity.setWidth(params.width());
        entity.setHeight(params.height());
        entity.setBatchSize(params.batchSize());
        entity.setCheckpoint(params.checkpoint());
        entity.setLoraName(params.loraName());
        entity.setLoraWeight(params.loraWeight() != null ? BigDecimal.valueOf(params.loraWeight()) : null);
        entity.setFilePath(filePath);
        entity.setMimeType(mimeType);
        entity.setTagsJson(tagsJson);
        return entity;
    }

    public AiDraftResponse draft(AiDraftRequest request) {
        String template = DRAFT_PROMPT_TEMPLATES.get(request.mode());
        if (template == null) {
            throw new IllegalArgumentException(
                    "mode は draft/proofread/summarize のいずれかを指定してください: " + request.mode());
        }

        GenerationJob job = startJob("llm_" + request.mode(), Map.of("mode", request.mode(), "text", request.text()));
        try {
            WebSearchOutcome searchOutcome = webSearchService.searchSafely(buildSearchQuery(request.text()));
            String prompt = WebSearchService.formatForPrompt(searchOutcome) + template.formatted(request.text());
            String result = llmClient.generate(prompt, null, AiProvider.fromString(request.provider()));
            completeJob(job, Map.of("result", result));
            return new AiDraftResponse(result, WebSearchService.toSources(searchOutcome),
                    WebSearchService.buildSearchNote(searchOutcome));
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
    }

    /**
     * セクション単位(本文/リード文)のAI生成。draft()と同様にBrave検索結果を出典として付与する。
     * request.message()が指定されている場合は壁打ち(追加指示による再生成)として扱い、
     * 初回生成時の指示をSystemプロンプトとしたまま、履歴+追加指示を踏まえて再生成する。
     */
    public AiSectionResponse generateSection(AiSectionRequest request) {
        String template = SECTION_PROMPT_TEMPLATES.get(request.mode());
        if (template == null) {
            throw new IllegalArgumentException(
                    "mode は body/lead/lead-subsections のいずれかを指定してください: " + request.mode());
        }

        String articleTitle = request.articleTitle() != null && !request.articleTitle().isBlank()
                ? request.articleTitle() : "(未設定)";
        String precedingContext = request.precedingContext() != null && !request.precedingContext().isBlank()
                ? request.precedingContext() : "(なし)";
        String heading = request.heading() != null && !request.heading().isBlank()
                ? request.heading() : "(未設定)";
        String outline = formatOutline(request.subsectionHeadings());
        String searchQuery = buildSearchQuery(
                (request.articleTitle() != null ? request.articleTitle() + " " : "") + heading);

        GenerationJob job = startJob("llm_section_" + request.mode(),
                Map.of("mode", request.mode(), "heading", heading));
        try {
            WebSearchOutcome searchOutcome = webSearchService.searchSafely(searchQuery);
            String basePrompt = switch (request.mode()) {
                case "body" -> template.formatted(articleTitle, precedingContext, heading);
                case "lead" -> template.formatted(articleTitle, outline);
                case "lead-subsections" -> template.formatted(articleTitle, heading, outline);
                default -> throw new IllegalArgumentException("未対応のmodeです: " + request.mode());
            };

            String prompt = request.message() != null && !request.message().isBlank()
                    ? buildSectionChatPrompt(basePrompt, request.history(), request.message(), searchOutcome)
                    : WebSearchService.formatForPrompt(searchOutcome) + basePrompt;

            String result = llmClient.generate(prompt, null, AiProvider.fromString(request.provider()));
            completeJob(job, Map.of("result", result));
            return new AiSectionResponse(result, WebSearchService.toSources(searchOutcome),
                    WebSearchService.buildSearchNote(searchOutcome));
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
    }

    /** 見出し一覧を箇条書きテキストに整形する。空なら"(なし)"を返す。 */
    private String formatOutline(List<String> headings) {
        if (headings == null || headings.isEmpty()) {
            return "(なし)";
        }
        StringBuilder sb = new StringBuilder();
        for (String heading : headings) {
            sb.append("・").append(heading).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 壁打ち(追加指示による再生成)用のプロンプトを組み立てる。初回生成の指示文をSystemとして固定し、
     * これまでの往復(history)と最新の追加指示(message)を続けることで、同じ方針のまま再生成させる。
     * ArticlePlanService.buildChatPromptと同じ「System+履歴+User」形式を踏襲する。
     */
    private String buildSectionChatPrompt(
            String basePrompt, List<PlanChatMessage> history, String message, WebSearchOutcome searchOutcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("System: ").append(basePrompt.strip()).append("\n\n");
        sb.append(WebSearchService.formatForPrompt(searchOutcome));
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

    /**
     * Brave検索クエリはURLに載る都合上長すぎる入力をそのまま渡さないよう先頭200文字に丸める。
     */
    private String buildSearchQuery(String text) {
        String trimmed = text == null ? "" : text.strip();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }

    public AiTagsResponse suggestTags(AiTagsRequest request) {
        GenerationJob job = startJob("llm_tags", Map.of("text", request.text()));
        try {
            String raw = llmClient.generate(
                    TAGS_PROMPT_TEMPLATE.formatted(request.text()), null, AiProvider.fromString(request.provider()));
            AiTagsResponse parsed = parseTagsResponse(raw);
            completeJob(job, Map.of("result", raw));
            return parsed;
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
    }

    private AiTagsResponse parseTagsResponse(String raw) {
        String jsonPart = extractJsonObject(raw);
        try {
            JsonNode node = objectMapper.readTree(jsonPart);
            List<String> categories = toStringList(node.get("categories"));
            List<String> tags = toStringList(node.get("tags"));
            return new AiTagsResponse(categories, tags);
        } catch (Exception e) {
            return new AiTagsResponse(List.of(), List.of());
        }
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

    private GenerationJob startJob(String type, Map<String, String> requestPayload) {
        GenerationJob job = new GenerationJob();
        job.setType(type);
        job.setStatus("running");
        job.setRequestPayload(toJson(requestPayload));
        return generationJobRepository.save(job);
    }

    private void completeJob(GenerationJob job, Map<String, String> resultPayload) {
        job.setStatus("done");
        job.setResultPayload(toJson(resultPayload));
        generationJobRepository.save(job);
    }

    private void failJob(GenerationJob job, Exception e) {
        job.setStatus("failed");
        job.setResultPayload(toJson(Map.of("error", String.valueOf(e.getMessage()))));
        generationJobRepository.save(job);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
