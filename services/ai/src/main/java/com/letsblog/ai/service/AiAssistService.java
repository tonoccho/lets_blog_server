package com.letsblog.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.ai.LlmClient;
import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.dto.AiAskRequest;
import com.letsblog.ai.dto.AiAskResponse;
import com.letsblog.ai.dto.AiDraftRequest;
import com.letsblog.ai.dto.AiDraftResponse;
import com.letsblog.ai.dto.AiProofreadRequest;
import com.letsblog.ai.dto.AiProofreadResponse;
import com.letsblog.ai.dto.AiSectionRequest;
import com.letsblog.ai.dto.AiSectionResponse;
import com.letsblog.ai.dto.AiTagsRequest;
import com.letsblog.ai.dto.PlanChatMessage;
import com.letsblog.ai.dto.AiTagsResponse;
import com.letsblog.ai.dto.ProofreadIssue;
import com.letsblog.ai.repository.GenerationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * LLMを利用した記事執筆支援(下書き/校正/要約、タグ・カテゴリ提案)。
 * 呼び出しごとに generation_jobs テーブルへ履歴を記録する。
 *
 * <p>issue #574でai-serviceへ移設。画像生成(generateImage/generateImagePrompt/getImageOptions)は
 * ComfyUiClient/ChatGptImageClient/ImageModelService/ComfyUiModelService/MediaGeneratedImageClientという
 * media-service連携(issue #573)に強く結び付いているため、このIssueの移設対象外としてlegacy-apiに
 * 残す(PR説明参照。#573が生成画像の保存責務のみをmedia-serviceへ委譲し、生成AI呼び出し自体は
 * legacy-apiに残した判断を踏襲する)。legacy-api側に残った画像生成コードがテキスト生成を必要とする
 * 箇所(画像プロンプト生成・生成画像のタグ提案)は、ai-serviceが公開する内部ブリッジ
 * ({@code POST /api/ai/internal/generate})経由でLLM呼び出しを行う。
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

    private static final String TAGS_PROMPT_TEMPLATE = """
            以下のブログ記事本文を読み、適切なカテゴリ候補とタグ候補を提案してください。
            出力は必ず次のJSON形式のみとし、他の文章は一切含めないでください。

            {"categories": ["カテゴリ1", "カテゴリ2"], "tags": ["タグ1", "タグ2", "タグ3"]}

            本文:
            %s
            """;

    /**
     * issue #523: エディタでのリアルタイム校正チェック用。DRAFT_PROMPT_TEMPLATESの"proofread"
     * (全文を校正済みの本文に書き換えて返す)とは異なり、指摘一覧をJSON配列で返させ、
     * エディタ側で該当箇所に波線(赤色)の指摘として表示する。
     */
    private static final String PROOFREAD_CHECK_PROMPT_TEMPLATE = """
            あなたは日本語のプロの校正者です。以下のブログ記事本文を読み、次の観点で問題があれば指摘してください。
            - typo: 誤字脱字・変換ミス
            - readability: 読みにくい・分かりにくい表現
            - unnecessary: 冗長で削ってよい表現

            出力は必ず次のJSON配列の形式のみとし、他の文章は一切含めないでください。問題が無ければ空配列 [] を返してください。
            originalTextには本文中の該当箇所を、一字一句変えずにそのまま引用してください(位置の特定に使うため)。
            suggestionには置き換え案を入れてください。直接の置き換え案が無い指摘(readabilityなど)ではnullにしてください。

            [{"type": "typo", "originalText": "本文中の該当箇所", "message": "指摘内容", "suggestion": "置き換え案またはnull"}]

            本文:
            %s
            """;

    /** issue #526: エディタ右クリックメニュー「Ask AI」からの質問に、Web検索結果を踏まえて回答する。 */
    private static final String ASK_PROMPT_TEMPLATE = """
            あなたはブログ執筆アシスタントです。以下の質問についてWeb検索結果を参考にしながら調査し、
            日本語で簡潔に要約してください。説明や前置きは不要で、要約文のみをMarkdown形式で出力してください。
            出典URLは要約文に含めないでください(別途一覧として表示します)。

            質問:
            %s
            """;

    private final LlmClient llmClient;
    private final LlmModelService llmModelService;
    private final GenerationJobRepository generationJobRepository;
    private final WebSearchService webSearchService;
    private final ObjectMapper objectMapper;
    private final ArticlePlanService articlePlanService;

    public AiAssistService(LlmClient llmClient,
                           LlmModelService llmModelService,
                           GenerationJobRepository generationJobRepository,
                           WebSearchService webSearchService, ObjectMapper objectMapper,
                           ArticlePlanService articlePlanService) {
        this.llmClient = llmClient;
        this.llmModelService = llmModelService;
        this.generationJobRepository = generationJobRepository;
        this.webSearchService = webSearchService;
        this.objectMapper = objectMapper;
        this.articlePlanService = articlePlanService;
    }

    /**
     * issue #574: legacy-apiに残った画像生成(AiAssistService#generateImage/generateImagePrompt)からの
     * テキスト生成呼び出しを受ける内部ブリッジ({@code POST /api/ai/internal/generate}が呼ぶ)。
     * projectIdが指定されればそのプロジェクトの選択中モデルを使い、未指定ならシステム既定モデルを使う
     * (元のAiAssistService#suggestImageTagsJson/generateImagePromptと同じ解決順)。
     */
    public String generateForBridge(Long projectId, String prompt, String providerOverride) {
        String model = projectId != null ? llmModelService.getSelectedModel(projectId) : null;
        return llmClient.generate(prompt, model, AiProvider.fromString(providerOverride));
    }

    /**
     * エディタ右クリックメニュー「Ask AI」からの質問に、Web検索結果を踏まえて回答する(issue #526)。
     */
    public AiAskResponse ask(AiAskRequest request) {
        GenerationJob job = startJob("llm_ask", Map.of("question", request.question()));
        try {
            WebSearchOutcome searchOutcome = webSearchService.searchSafely(buildSearchQuery(request.question()));
            String prompt = WebSearchService.formatForPrompt(searchOutcome)
                    + ASK_PROMPT_TEMPLATE.formatted(request.question());
            String result = llmClient.generate(prompt, null, AiProvider.fromString(request.provider()));
            completeJob(job, Map.of("result", result));
            return new AiAskResponse(result, WebSearchService.toSources(searchOutcome),
                    WebSearchService.buildSearchNote(searchOutcome));
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
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
            List<String> existingTags = request.projectId() != null
                    ? articlePlanService.listExistingTags(request.projectId())
                    : List.of();
            String prompt = buildTagsPrompt(request.text(), existingTags);
            String raw = llmClient.generate(prompt, null, AiProvider.fromString(request.provider()));
            AiTagsResponse parsed = parseTagsResponse(raw);
            AiTagsResponse prioritized = prioritizeExistingTags(parsed, existingTags);
            completeJob(job, Map.of("result", raw));
            return prioritized;
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
    }

    /**
     * 既存タグ一覧がある場合、新しいタグを作る前にまずそちらから選ぶようAIへ指示を追加する(issue #525)。
     */
    private String buildTagsPrompt(String text, List<String> existingTags) {
        String prompt = TAGS_PROMPT_TEMPLATE.formatted(text);
        if (existingTags.isEmpty()) {
            return prompt;
        }
        return prompt
                + "\ntagsは新しいタグを作る前に、必ず次の既存タグ一覧の中に記事に合うものがないか確認し、"
                + "あればそちらを優先して選んでください(一覧にない新しいタグも、本文の内容から必要であれば追加してかまいません): "
                + String.join(", ", existingTags) + "\n";
    }

    /**
     * 既存タグに一致する提案を先頭へ並べ替える(issue #525)。プロンプトでの指示に加えて、
     * 表示順でも既存タグが優先されることをプログラム側で保証する。
     */
    private AiTagsResponse prioritizeExistingTags(AiTagsResponse response, List<String> existingTags) {
        if (existingTags.isEmpty()) {
            return response;
        }
        List<String> sortedTags = response.tags().stream()
                .sorted(Comparator.comparing(
                        tag -> existingTags.stream().noneMatch(existing -> existing.equalsIgnoreCase(tag))))
                .toList();
        return new AiTagsResponse(response.categories(), sortedTags);
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

    /**
     * issue #523: リアルタイム校正チェック。本文中の問題点をtypo/readability/unnecessaryの
     * 3種類で検出し、エディタ側で該当箇所へ波線表示するための一覧を返す。
     */
    public AiProofreadResponse proofreadContent(AiProofreadRequest request) {
        GenerationJob job = startJob("llm_proofread_check", Map.of("text", request.text()));
        try {
            String prompt = PROOFREAD_CHECK_PROMPT_TEMPLATE.formatted(request.text());
            String raw = llmClient.generate(prompt, null, AiProvider.fromString(request.provider()));
            List<ProofreadIssue> issues = parseProofreadResponse(raw, request.text());
            completeJob(job, Map.of("result", raw));
            return new AiProofreadResponse(issues);
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
    }

    private List<ProofreadIssue> parseProofreadResponse(String raw, String sourceText) {
        String jsonPart = extractJsonArray(raw);
        try {
            JsonNode node = objectMapper.readTree(jsonPart);
            if (!node.isArray()) {
                return List.of();
            }
            List<ProofreadIssue> issues = new ArrayList<>();
            for (JsonNode item : node) {
                String originalText = item.path("originalText").asText(null);
                // originalTextが本文中に実在しない指摘は、エディタ側で位置特定ができず表示できないため除外する
                // (LLMの引用ミス・幻覚に対する防御)。
                if (originalText == null || originalText.isEmpty() || !sourceText.contains(originalText)) {
                    continue;
                }
                String type = item.path("type").asText(null);
                String message = item.path("message").asText(null);
                JsonNode suggestionNode = item.get("suggestion");
                String suggestion = suggestionNode == null || suggestionNode.isNull()
                        ? null : suggestionNode.asText();
                issues.add(new ProofreadIssue(type, originalText, message, suggestion));
            }
            return issues;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String extractJsonArray(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
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
