package com.letsblog.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.ai.BraveSearchResult;
import com.letsblog.ai.ai.LlmClient;
import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.domain.ReviewStepKey;
import com.letsblog.ai.dto.AiAskRequest;
import com.letsblog.ai.dto.AiAskResponse;
import com.letsblog.ai.dto.AiDraftRequest;
import com.letsblog.ai.dto.AiImagePromptResponse;
import com.letsblog.ai.dto.AiDraftResponse;
import com.letsblog.ai.dto.AiProofreadRequest;
import com.letsblog.ai.dto.AiProofreadResponse;
import com.letsblog.ai.dto.AiReviewStepSuggestionsResponse;
import com.letsblog.ai.dto.AiSectionRequest;
import com.letsblog.ai.dto.AiSectionResponse;
import com.letsblog.ai.dto.AiTagsRequest;
import com.letsblog.ai.dto.PlanChatMessage;
import com.letsblog.ai.dto.AiTagsResponse;
import com.letsblog.ai.dto.ProofreadIssue;
import com.letsblog.ai.dto.ReviewStepSuggestion;
import com.letsblog.ai.dto.SourceReference;
import com.letsblog.ai.repository.GenerationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
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
 * ({@code POST /api/internal/ai/generate})経由でLLM呼び出しを行う。
 */
@Service
public class AiAssistService {

    /** issue #583でlegacy-apiから移設。画像生成プロンプト作成のシステムプロンプト。 */
    private static final String IMAGE_PROMPT_SYSTEM_PROMPT =
            "あなたは画像生成AI(Stable Diffusion)向けのプロンプトエンジニアです。"
            + "ユーザーとの会話から生成したい画像の内容を理解し、Stable Diffusion用の英語のプロンプトを作成してください。"
            + "被写体、構図、スタイル、雰囲気、画質に関する具体的なキーワードをカンマ区切りで含めてください。"
            + "出力はプロンプト文字列のみとし、説明文や前置き、日本語は含めないでください。";

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

    /**
     * issue #1213: 多段レビュー(issue #1210)のステップ別指摘生成用プロンプト。PROOFREAD_CHECK_
     * PROMPT_TEMPLATE(3観点まとめて1回で返す、エディタのリアルタイム校正用)とは異なり、
     * ステップごとに1観点だけを問い、応答も{originalText, message}のみ(typeやsuggestionは持たない)。
     * 識別子(id)は本文中の出現位置を含めないためLLMには出させず、サーバ側で
     * {@link #computeSuggestionId}が算出する。JAPANESE/PROOFREADING(issue #1213)に加え、
     * READER_PERSPECTIVE/STYLE(issue #1221)も同じ形・同じ規則で持つ。FACT_CHECKだけは検索を伴い
     * 形が違うため{@link #generateFactCheckSuggestions}が別に扱う(issue #1214)。
     */
    private static final Map<ReviewStepKey, String> REVIEW_STEP_PROMPT_TEMPLATES = buildReviewStepPromptTemplates();

    private static Map<ReviewStepKey, String> buildReviewStepPromptTemplates() {
        Map<ReviewStepKey, String> templates = new EnumMap<>(ReviewStepKey.class);
        templates.put(ReviewStepKey.JAPANESE, """
                あなたは日本語のプロの校正者です。以下のブログ記事本文を読み、日本語としての正しさ
                (文法誤り、ら抜き言葉、二重否定、修飾関係の曖昧さ、助詞の誤用など)の観点でのみ問題を指摘してください。
                誤字脱字や表記ゆれなど表記の正しさは対象外です(別の観点で扱います)。

                出力は必ず次のJSON配列の形式のみとし、他の文章は一切含めないでください。問題が無ければ空配列 [] を返してください。
                originalTextには本文中の該当箇所を、一字一句変えずにそのまま引用してください(位置の特定に使うため)。

                [{"originalText": "本文中の該当箇所", "message": "指摘内容"}]

                本文:
                %s
                """);
        templates.put(ReviewStepKey.PROOFREADING, """
                あなたは日本語のプロの校正者です。以下のブログ記事本文を読み、表記の正しさ
                (誤字脱字、表記ゆれ(例: サーバ/サーバー)、送り仮名、半角/全角の不統一、衍字など)の観点でのみ問題を指摘してください。
                日本語の文法的な正しさは対象外です(別の観点で扱います)。

                出力は必ず次のJSON配列の形式のみとし、他の文章は一切含めないでください。問題が無ければ空配列 [] を返してください。
                originalTextには本文中の該当箇所を、一字一句変えずにそのまま引用してください(位置の特定に使うため)。

                [{"originalText": "本文中の該当箇所", "message": "指摘内容"}]

                本文:
                %s
                """);
        templates.put(ReviewStepKey.READER_PERSPECTIVE, """
                あなたはこのブログ記事の想定読者(記事のテーマについて詳しくない一般の読者)の立場に立つ編集者です。
                以下のブログ記事本文を読み、読者にとっての前提知識の飛躍・説明不足
                (説明なしに使われている専門用語・略語、定義されていない概念、論理や手順の飛び、
                読者が知っている前提で省かれた背景説明など)の観点でのみ問題を指摘してください。
                日本語の正しさ、表記、事実の正誤、文体は対象外です(別の観点で扱います)。

                出力は必ず次のJSON配列の形式のみとし、他の文章は一切含めないでください。問題が無ければ空配列 [] を返してください。
                originalTextには本文中の該当箇所を、一字一句変えずにそのまま引用してください(位置の特定に使うため)。

                [{"originalText": "本文中の該当箇所", "message": "指摘内容"}]

                本文:
                %s
                """);
        templates.put(ReviewStepKey.STYLE, """
                あなたは文章のトーンと読み口を整える編集者です。以下のブログ記事本文を読み、文体
                (文末表現(です・ます調とだ・である調)の統一、一文の長さ、受動態の多用、記事全体のトーンの一貫性など)
                の観点でのみ問題を指摘してください。
                日本語の文法的な正しさ、表記の正しさ、事実の正誤、読者にとっての分かりやすさは対象外です(別の観点で扱います)。

                出力は必ず次のJSON配列の形式のみとし、他の文章は一切含めないでください。問題が無ければ空配列 [] を返してください。
                originalTextには本文中の該当箇所を、一字一句変えずにそのまま引用してください(位置の特定に使うため)。

                [{"originalText": "本文中の該当箇所", "message": "指摘内容"}]

                本文:
                %s
                """);
        return templates;
    }

    /**
     * issue #1214: 校閲(FACT_CHECK)の1段目。本文から事実主張を抽出し、Web検索に使うクエリを返させる。
     * 抽出と判定を分けるのは、検索クエリを本文全体から機械的に作ると事実主張の裏取りに使えないため
     * (実装レポート参照。LLM呼び出しは抽出・判定の2回)。本文は必ず末尾に置く(E2Eスタブが
     * {@code 本文:}以降を本文として読む)。
     */
    private static final String FACT_CHECK_EXTRACTION_PROMPT_TEMPLATE = """
            あなたはブログ記事の校閲者です。以下のブログ記事本文から、事実確認の対象となる主張を抽出してください。
            対象は数値・固有名詞・日付・製品仕様など、Web検索で裏取りできる客観的な事実の主張です。
            意見・感想・比喩は含めないでください。重要なものから最大%d件までにしてください。

            出力は必ず次のJSON配列の形式のみとし、他の文章は一切含めないでください。対象が無ければ空配列 [] を返してください。
            claimには本文中の主張の該当箇所を、一字一句変えずにそのまま引用してください。
            queryにはその主張の裏取りに使うWeb検索クエリを入れてください。

            [{"claim": "本文中の主張の該当箇所", "query": "Web検索クエリ"}]

            本文:
            %s
            """;

    /**
     * issue #1214: 校閲の2段目。Web検索結果と本文を突き合わせ、裏付けが取れない/矛盾する主張だけを
     * 指摘させる。出典は検索結果の番号で答えさせ、サーバ側でタイトルとURLへ引き直す(URLをLLMに
     * 書かせない=幻覚の防止)。本文は必ず末尾に置く。
     */
    private static final String FACT_CHECK_JUDGE_PROMPT_TEMPLATE = """
            あなたはブログ記事の校閲者です。以下のWeb検索結果と照らして事実確認を行い、ブログ記事本文の中で
            検索結果と矛盾する、または裏付けが取れない事実の主張だけを指摘してください。
            検索結果から裏付けが取れている主張、検索結果から判断できない主張は指摘しないでください。

            出力は必ず次のJSON配列の形式のみとし、他の文章は一切含めないでください。指摘が無ければ空配列 [] を返してください。
            originalTextには本文中の該当箇所を、一字一句変えずにそのまま引用してください(位置の特定に使うため)。
            sourcesには判断の根拠にした検索結果の番号(1始まり)を配列で入れてください。根拠にした検索結果が無い指摘は出力しないでください。

            [{"originalText": "本文中の該当箇所", "message": "指摘内容", "sources": [1]}]

            %s
            本文:
            %s
            """;

    /** 校閲が1回の呼び出しで裏取りする主張の上限。検索回数(=レイテンシ)を抑える。 */
    private static final int FACT_CHECK_MAX_CLAIMS = 3;

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
    private final ReviewStepModelService reviewStepModelService;
    private final CurrentActorService currentActorService;

    public AiAssistService(LlmClient llmClient,
                           LlmModelService llmModelService,
                           GenerationJobRepository generationJobRepository,
                           WebSearchService webSearchService, ObjectMapper objectMapper,
                           ArticlePlanService articlePlanService,
                           ReviewStepModelService reviewStepModelService,
                           CurrentActorService currentActorService) {
        this.llmClient = llmClient;
        this.llmModelService = llmModelService;
        this.generationJobRepository = generationJobRepository;
        this.webSearchService = webSearchService;
        this.objectMapper = objectMapper;
        this.articlePlanService = articlePlanService;
        this.reviewStepModelService = reviewStepModelService;
        this.currentActorService = currentActorService;
    }

    /**
     * issue #574: legacy-apiに残った画像生成・タグ/静的コンテンツ生成(AiAssistService#generateImage/
     * #generateImagePrompt/#suggestImageTagsJson、CustomTagGenerationService、
     * TagDesignGenerationService、StaticContentGenerationService)からのテキスト生成呼び出しを受ける
     * 内部ブリッジ({@code POST /api/internal/ai/generate}が呼ぶ)。
     *
     * <p>projectIdが指定されればそのプロジェクトの選択中モデルを使い、未指定ならシステム既定モデルを使う。
     * プロバイダーはproviderOverride(指定時は最優先) → projectIdが指定されていればそのプロジェクトの
     * 選択中プロバイダー → (いずれも無ければ)LlmClient側でシステム既定プロバイダーへフォールバックする
     * 順に解決する(元のAiAssistService#generateImagePromptと同じ解決順)。
     * 呼び出し元がプロジェクト非依存の解決を望む場合(suggestImageTagsJson等)はprojectIdにnullを渡す。
     */
    public String generateForBridge(Long projectId, String prompt, String providerOverride) {
        llmClient.useProject(projectId);
        String model = projectId != null ? llmModelService.getSelectedModel(projectId) : null;
        AiProvider provider = AiProvider.fromString(providerOverride);
        if (provider == null && projectId != null) {
            provider = llmModelService.getSelectedProvider(projectId);
        }
        return llmClient.generate(prompt, model, provider);
    }

    /**
     * issue #1495: 執筆支援5機能(ask/draft/section/tags/proofread)向けの生成。
     * {@link #generateForBridge}と同じ解決順で、projectIdがあればそのプロジェクトの選択中モデルを使い、
     * providerはリクエストの指定 → プロジェクトの選択中プロバイダー → システム既定の順に決める。
     * projectId未指定(旧バージョンの拡張など)ではモデル解決を呼ばずnullを渡し、
     * LlmClient側でプロバイダー別のシステム既定モデルへフォールバックさせる。
     */
    private String generateForProject(String prompt, Long projectId, String providerOverride) {
        return generateForBridge(projectId, prompt, providerOverride);
    }

    /**
     * チャットメッセージ(と任意の履歴)から、画像生成AI(Stable Diffusion)向けの英語プロンプトを
     * LLMで生成する。issue #583でlegacy-apiの{@code AiAssistService#generateImagePrompt}から移設した。
     *
     * <p>#583で画像生成そのものはmedia-serviceへ移したが、<b>これは純粋なLLM機能</b>
     * (プロンプト文字列を作るだけで画像は生成しない)なので、LLMの所有者であるai-serviceが持つ。
     *
     * <p>「System+履歴+User」形式のプロンプト組み立ては{@code ArticlePlanService#buildChatPrompt}と同じ。
     */
    public AiImagePromptResponse generateImagePrompt(
            Long projectId, List<PlanChatMessage> history, String message, String providerOverride) {
        GenerationJob job = startJob("llm_image_prompt", Map.of(
                "projectId", String.valueOf(projectId),
                "message", message));
        try {
            String prompt = buildImagePromptChat(history, message);
            String result = generateForBridge(projectId, prompt, providerOverride);
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

    /**
     * エディタ右クリックメニュー「Ask AI」からの質問に、Web検索結果を踏まえて回答する(issue #526)。
     */
    public AiAskResponse ask(AiAskRequest request) {
        GenerationJob job = startJob("llm_ask", Map.of("question", request.question()));
        try {
            WebSearchOutcome searchOutcome = webSearchService.searchSafely(buildSearchQuery(request.question()));
            String prompt = WebSearchService.formatForPrompt(searchOutcome)
                    + ASK_PROMPT_TEMPLATE.formatted(request.question());
            String result = generateForProject(prompt, request.projectId(), request.provider());
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
            String result = generateForProject(prompt, request.projectId(), request.provider());
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

            String result = generateForProject(prompt, request.projectId(), request.provider());
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
            String raw = generateForProject(prompt, request.projectId(), request.provider());
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
            String raw = generateForProject(prompt, request.projectId(), request.provider());
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

    /**
     * issue #1213: 多段レビューのステップ別指摘生成。呼び出し元({@link
     * com.letsblog.ai.controller.AiController#reviewStepSuggestions})はパス変数を生の
     * {@code String}のまま渡す({@link ReviewStepKeys}のJavadoc参照、issue #1222)。
     *
     * <p>未知のステップキーはIssue #1222のRequirements「上記のいずれの場合も、generation_jobsに
     * 失敗として記録が残る」の対象。検証に失敗した場合だけジョブを開き、即座にfailJob()で
     * 失敗として記録してから例外を再送出する(レビュー2026-09-28: 検証前にコントローラで
     * 短絡するとジョブ自体が作られず記録が残らないという指摘への対応)。検証を通過した
     * 正常系では、このためのジョブは作らない(既存の#1213のジョブ管理をそのまま使う)。
     */
    public AiReviewStepSuggestionsResponse generateReviewStepSuggestions(
            Long projectId, String rawStepKey, String text) {
        ReviewStepKey stepKey;
        try {
            stepKey = ReviewStepKeys.parse(rawStepKey);
        } catch (RuntimeException e) {
            GenerationJob job = startJob("llm_review_step_invalid", Map.of(
                    "projectId", String.valueOf(projectId),
                    "stepKey", String.valueOf(rawStepKey),
                    "text", String.valueOf(text)));
            failJob(job, e);
            throw e;
        }
        return generateReviewStepSuggestions(projectId, stepKey, text);
    }

    /**
     * issue #1213: 多段レビューのステップ別指摘生成(ステップキー検証済み)。プロンプト・応答形式は
     * ステップごとに異なるが、プロバイダー/モデルの解決は{@link ReviewStepModelService}(issue #1211、
     * ステップ設定 → プロジェクト既定 → グローバル既定)へ委譲する。generation_jobsのtypeに
     * ステップキーを含め、どのステップが生成したか判別できるようにする。
     */
    public AiReviewStepSuggestionsResponse generateReviewStepSuggestions(
            Long projectId, ReviewStepKey stepKey, String text) {
        if (stepKey == ReviewStepKey.FACT_CHECK) {
            return generateFactCheckSuggestions(projectId, text);
        }
        String template = REVIEW_STEP_PROMPT_TEMPLATES.get(stepKey);
        if (template == null) {
            throw new IllegalArgumentException(
                    "このレビューステップのプロンプトはまだ実装されていません: " + stepKey);
        }

        GenerationJob job = startJob("llm_review_step_" + stepKey.name().toLowerCase(), Map.of(
                "projectId", String.valueOf(projectId), "stepKey", stepKey.name(), "text", text));
        try {
            llmClient.useProject(projectId);
            String model = reviewStepModelService.resolveModel(projectId, stepKey);
            AiProvider provider = reviewStepModelService.resolveProvider(projectId, stepKey);
            String prompt = template.formatted(text);
            String raw = llmClient.generate(prompt, model, provider);
            List<ReviewStepSuggestion> suggestions = parseReviewStepSuggestions(raw, text, stepKey);
            completeJob(job, Map.of("result", raw));
            return new AiReviewStepSuggestionsResponse(suggestions);
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
    }

    /**
     * issue #1214: 校閲(FACT_CHECK)。①LLMで事実主張と検索クエリを抽出 → ②主張ごとに
     * {@link WebSearchService#searchSafely}で裏取り → ③検索結果を根拠にLLMが指摘を判定、の順に進む。
     * 指摘の形・識別子・originalText実在チェックは他のステップと同じ({@link #parseReviewStepSuggestions}の
     * 規則を{@link #parseFactCheckSuggestions}が踏襲する)。
     *
     * <p>検索が使えない(APIキー未設定・検索失敗、{@code WebSearchOutcome#succeeded}=false)とき、または
     * 検索結果が1件も得られなかったときは、裏取りできていないのに「問題なし」と返さないよう、
     * 判定を行わず{@code skipped=true}と理由を返す(HTTPエラーにもしない)。
     * 主張が抽出されなかった場合は、検索を要する事実主張が無いということなので、
     * 指摘0件・{@code skipped=false}で返す。
     */
    private AiReviewStepSuggestionsResponse generateFactCheckSuggestions(Long projectId, String text) {
        ReviewStepKey stepKey = ReviewStepKey.FACT_CHECK;
        GenerationJob job = startJob("llm_review_step_fact_check", Map.of(
                "projectId", String.valueOf(projectId), "stepKey", stepKey.name(), "text", text));
        try {
            llmClient.useProject(projectId);
            String model = reviewStepModelService.resolveModel(projectId, stepKey);
            AiProvider provider = reviewStepModelService.resolveProvider(projectId, stepKey);

            String extractionRaw = llmClient.generate(
                    FACT_CHECK_EXTRACTION_PROMPT_TEMPLATE.formatted(FACT_CHECK_MAX_CLAIMS, text), model, provider);
            List<String> queries = parseFactCheckQueries(extractionRaw);
            if (queries.isEmpty()) {
                completeJob(job, Map.of("result", extractionRaw, "skipped", "false"));
                return new AiReviewStepSuggestionsResponse(List.of(), false, null);
            }

            List<BraveSearchResult> results = new ArrayList<>();
            for (String query : queries) {
                WebSearchOutcome outcome = webSearchService.searchSafely(query, projectId);
                if (!outcome.succeeded()) {
                    return skipFactCheck(job, "Web検索を利用できなかったため校閲をスキップしました: "
                            + (outcome.errorMessage() == null || outcome.errorMessage().isBlank()
                                    ? "理由不明" : outcome.errorMessage()));
                }
                results.addAll(outcome.results());
            }
            WebSearchOutcome combined = WebSearchOutcome.success(results);
            if (results.isEmpty()) {
                return skipFactCheck(job, "関連する検索結果が見つからず裏取りできなかったため校閲をスキップしました");
            }

            String raw = llmClient.generate(FACT_CHECK_JUDGE_PROMPT_TEMPLATE.formatted(
                    WebSearchService.formatForPrompt(combined), text), model, provider);
            List<ReviewStepSuggestion> suggestions =
                    parseFactCheckSuggestions(raw, text, WebSearchService.toSources(combined));
            completeJob(job, Map.of("result", raw, "skipped", "false"));
            return new AiReviewStepSuggestionsResponse(suggestions, false, null);
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
    }

    /** スキップはジョブの失敗ではなく完了として、スキップした事実と理由を履歴に残す。 */
    private AiReviewStepSuggestionsResponse skipFactCheck(GenerationJob job, String reason) {
        completeJob(job, Map.of("skipped", "true", "skipReason", reason));
        return new AiReviewStepSuggestionsResponse(List.of(), true, reason);
    }

    /** 抽出結果から検索クエリを取り出す。主張(claim)が空の要素は無視し、queryが無ければ主張で検索する。 */
    private List<String> parseFactCheckQueries(String raw) {
        try {
            JsonNode node = objectMapper.readTree(extractJsonArray(raw));
            if (!node.isArray()) {
                return List.of();
            }
            LinkedHashSet<String> queries = new LinkedHashSet<>();
            for (JsonNode item : node) {
                String claim = item.path("claim").asText("").trim();
                if (claim.isEmpty()) {
                    continue;
                }
                String query = item.path("query").asText("").trim();
                queries.add(query.isEmpty() ? claim : query);
                if (queries.size() >= FACT_CHECK_MAX_CLAIMS) {
                    break;
                }
            }
            return List.copyOf(queries);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * {@link #parseReviewStepSuggestions}と同じ防御(本文に実在しない引用の除外)に加え、出典番号を
     * {@code SourceReference}へ引き直す。有効な出典が1件も無い指摘は、根拠を示せないため除外する。
     */
    private List<ReviewStepSuggestion> parseFactCheckSuggestions(
            String raw, String sourceText, List<SourceReference> sources) {
        try {
            JsonNode node = objectMapper.readTree(extractJsonArray(raw));
            if (!node.isArray()) {
                return List.of();
            }
            List<ReviewStepSuggestion> suggestions = new ArrayList<>();
            for (JsonNode item : node) {
                String originalText = item.path("originalText").asText(null);
                if (originalText == null || originalText.isEmpty() || !sourceText.contains(originalText)) {
                    continue;
                }
                List<SourceReference> cited = new ArrayList<>();
                JsonNode indexes = item.path("sources");
                if (indexes.isArray()) {
                    for (JsonNode index : indexes) {
                        int i = index.asInt(0);
                        if (i >= 1 && i <= sources.size() && !cited.contains(sources.get(i - 1))) {
                            cited.add(sources.get(i - 1));
                        }
                    }
                }
                if (cited.isEmpty()) {
                    continue;
                }
                String message = item.path("message").asText(null);
                suggestions.add(new ReviewStepSuggestion(
                        computeSuggestionId(ReviewStepKey.FACT_CHECK, originalText, message),
                        ReviewStepKey.FACT_CHECK.name(), originalText, message, List.copyOf(cited)));
            }
            return suggestions;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ReviewStepSuggestion> parseReviewStepSuggestions(String raw, String sourceText, ReviewStepKey stepKey) {
        String jsonPart = extractJsonArray(raw);
        try {
            JsonNode node = objectMapper.readTree(jsonPart);
            if (!node.isArray()) {
                return List.of();
            }
            List<ReviewStepSuggestion> suggestions = new ArrayList<>();
            for (JsonNode item : node) {
                String originalText = item.path("originalText").asText(null);
                // parseProofreadResponseと同じ防御: 本文中に実在しない引用は位置特定できないため除外する。
                if (originalText == null || originalText.isEmpty() || !sourceText.contains(originalText)) {
                    continue;
                }
                String message = item.path("message").asText(null);
                String id = computeSuggestionId(stepKey, originalText, message);
                suggestions.add(new ReviewStepSuggestion(id, stepKey.name(), originalText, message));
            }
            return suggestions;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * ステップキー+引用(originalText)+指摘内容(message)から安定した識別子を導く(issue #1213)。
     * 本文中の出現位置を含めないため、前方への加筆で位置がずれても同じ指摘は同じidのままになる。
     * サーバは状態を持たないため、同じ入力からは常に同じidを再計算できるハッシュにしている。
     */
    private String computeSuggestionId(ReviewStepKey stepKey, String originalText, String message) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String payload = stepKey.name() + '\0' + originalText + '\0' + (message == null ? "" : message);
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // JVM は SHA-256 を標準で提供するため実運用では発生しない(issue #1213)。
            throw new IllegalStateException("SHA-256が利用できません", e);
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
        job.setOwnerUserId(currentActorService.getCurrentActorId());
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
