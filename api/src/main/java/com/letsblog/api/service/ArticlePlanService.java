package com.letsblog.api.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.BraveSearchResult;
import com.letsblog.api.ai.OllamaClient;
import com.letsblog.api.domain.ArticlePlanSession;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.domain.Project;
import com.letsblog.api.dto.AcceptPlanResponse;
import com.letsblog.api.dto.AcceptPlanResultItem;
import com.letsblog.api.dto.AcceptStructureResponse;
import com.letsblog.api.dto.ArticlePlanSessionDetailResponse;
import com.letsblog.api.dto.ArticlePlanSessionSummaryResponse;
import com.letsblog.api.dto.AssignIssueResponse;
import com.letsblog.api.dto.IssueDescriptionResponse;
import com.letsblog.api.dto.PlanChatMessage;
import com.letsblog.api.dto.PlanChatResponse;
import com.letsblog.api.dto.RepositoryIssueResponse;
import com.letsblog.api.dto.SuggestMetadataResponse;
import com.letsblog.api.dto.SuggestStructureResponse;
import com.letsblog.api.dto.SuggestTitlesResponse;
import com.letsblog.api.github.GithubClient;
import com.letsblog.api.github.GithubIssue;
import com.letsblog.api.github.GithubIssueSummary;
import com.letsblog.api.github.GithubUser;
import com.letsblog.api.repository.ArticlePlanSessionRepository;
import com.letsblog.api.repository.GenerationJobRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ollamaを利用した記事企画の壁打ちチャットとタイトル提案。
 * 会話履歴はサーバー側で保持せず、呼び出しごとにフロントから全履歴を受け取る。
 */
@Service
public class ArticlePlanService {

    private static final String SYSTEM_PROMPT =
            "あなたはブログ記事企画の壁打ち相手です。ユーザーの提案内容を聞き、鋭い質問や追加の視点を返しながら、"
            + "テーマを深掘りするのを手伝ってください。簡潔で前向きな応答を心がけてください。";

    private static final String TITLE_SUGGESTION_INSTRUCTION =
            "上記の会話を踏まえて、記事タイトル案をJSON配列形式で、最大5件、簡潔に提案してください。"
            + "出力はJSON配列のみとし、他の説明文は含めないでください。例: [\"タイトル1\", \"タイトル2\"]";

    private static final String TITLE_GENERATION_INSTRUCTION =
            "上記のユーザーの発言内容を、10〜20文字程度の日本語の短い見出しに要約してください。"
            + "出力は見出しの文字列のみとし、記号や説明文、前置きは含めないでください。";

    private static final String STRUCTURE_SUGGESTION_INSTRUCTION =
            "上記の会話を踏まえて、この記事の構成案をMarkdown形式の見出し構造(##や-のリスト等)で提案してください。"
            + "出力は構成案のMarkdownのみとし、他の説明文や前置きは含めないでください。";

    private static final String METADATA_SUGGESTION_INSTRUCTION =
            "上記の会話から、記事の以下の情報をJSON形式で提案してください:\n"
            + "{\n"
            + "  \"title\": \"記事タイトル(20-50文字程度)\",\n"
            + "  \"slug\": \"article-slug(URLに適した英数字)\",\n"
            + "  \"categories\": [\"カテゴリ1\", \"カテゴリ2\"],\n"
            + "  \"tags\": [\"タグ1\", \"タグ2\", \"タグ3\"]\n"
            + "}\n\n"
            + "出力はJSONオブジェクトのみとし、説明文は含めないでください。";

    private final OllamaClient ollamaClient;
    private final OllamaModelService ollamaModelService;
    private final WebSearchService webSearchService;
    private final GenerationJobRepository generationJobRepository;
    private final ObjectMapper objectMapper;
    private final GithubClient githubClient;
    private final UserService userService;
    private final ProjectService projectService;
    private final ArticlePlanSessionRepository articlePlanSessionRepository;

    public ArticlePlanService(
            OllamaClient ollamaClient,
            OllamaModelService ollamaModelService,
            WebSearchService webSearchService,
            GenerationJobRepository generationJobRepository,
            ObjectMapper objectMapper,
            GithubClient githubClient,
            UserService userService,
            ProjectService projectService,
            ArticlePlanSessionRepository articlePlanSessionRepository) {
        this.ollamaClient = ollamaClient;
        this.ollamaModelService = ollamaModelService;
        this.webSearchService = webSearchService;
        this.generationJobRepository = generationJobRepository;
        this.objectMapper = objectMapper;
        this.githubClient = githubClient;
        this.userService = userService;
        this.projectService = projectService;
        this.articlePlanSessionRepository = articlePlanSessionRepository;
    }

    /**
     * マルチターンチャット。sessionIdが未指定(初回発言)の場合は新規セッションを作成し、
     * AIにタイトルを生成させて保存する。指定済みの場合は既存セッションの履歴を更新する。
     */
    public PlanChatResponse chat(
            Long projectId, List<PlanChatMessage> history, String message, Long sessionId, Integer githubIssueNumber) {
        GenerationJob job = startJob("plan_chat", Map.of(
                "projectId", String.valueOf(projectId),
                "message", message,
                "historyLength", String.valueOf(history.size())
        ));
        try {
            String model = ollamaModelService.getSelectedModel(projectId);
            WebSearchOutcome searchOutcome = webSearchService.searchSafely(message);
            String prompt = buildChatPrompt(history, message, searchOutcome);
            String reply = ollamaClient.generate(prompt, model);
            completeJob(job, Map.of(
                    "reply", reply,
                    "webSearchAttempted", "true",
                    "webSearchSucceeded", String.valueOf(searchOutcome.succeeded()),
                    "webSearchError", String.valueOf(searchOutcome.errorMessage())
            ));

            List<PlanChatMessage> updatedHistory = new ArrayList<>(history);
            updatedHistory.add(new PlanChatMessage("user", message));
            updatedHistory.add(new PlanChatMessage("assistant", reply));

            Long resolvedSessionId = sessionId == null
                    ? createSession(projectId, message, updatedHistory, githubIssueNumber, model)
                    : appendToSession(projectId, sessionId, updatedHistory);

            return new PlanChatResponse(reply, resolvedSessionId);
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
    }

    private Long createSession(
            Long projectId, String firstMessage, List<PlanChatMessage> history, Integer githubIssueNumber, String model) {
        String title = generateSessionTitle(firstMessage, model);

        ArticlePlanSession session = new ArticlePlanSession();
        session.setProjectId(projectId);
        session.setGithubIssueNumber(githubIssueNumber);
        session.setTitle(title);
        session.setHistory(toJson(history));
        return articlePlanSessionRepository.save(session).getId();
    }

    private Long appendToSession(Long projectId, Long sessionId, List<PlanChatMessage> history) {
        ArticlePlanSession session = findSession(projectId, sessionId);
        session.setHistory(toJson(history));
        return articlePlanSessionRepository.save(session).getId();
    }

    private ArticlePlanSession findSession(Long projectId, Long sessionId) {
        ArticlePlanSession session = articlePlanSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ArticlePlanSessionNotFoundException(
                        "id " + sessionId + " の壁打ちセッションは見つかりません"));
        if (!session.getProjectId().equals(projectId)) {
            throw new ArticlePlanSessionNotFoundException(
                    "id " + sessionId + " の壁打ちセッションは見つかりません");
        }
        return session;
    }

    private String generateSessionTitle(String firstMessage, String model) {
        GenerationJob job = startJob("plan_session_title", Map.of("message", firstMessage));
        try {
            String prompt = "User: " + firstMessage + "\n\n" + TITLE_GENERATION_INSTRUCTION;
            String raw = ollamaClient.generate(prompt, model);
            String title = sanitizeTitle(raw);
            completeJob(job, Map.of("title", title));
            return title;
        } catch (RuntimeException e) {
            failJob(job, e);
            return sanitizeTitle(firstMessage);
        }
    }

    private String sanitizeTitle(String raw) {
        String cleaned = raw.strip()
                .replaceAll("^[\"'「『]+", "")
                .replaceAll("[\"'」』]+$", "")
                .replaceAll("\\s+", " ");
        if (cleaned.isBlank()) {
            return "無題の壁打ち";
        }
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    /**
     * プロジェクトの壁打ちセッション一覧を、更新日時の新しい順に返す。
     */
    public List<ArticlePlanSessionSummaryResponse> listSessions(Long projectId) {
        return articlePlanSessionRepository.findByProjectIdOrderByUpdatedAtDesc(projectId).stream()
                .map(s -> new ArticlePlanSessionSummaryResponse(
                        s.getId(), s.getTitle(), s.getGithubIssueNumber(), s.getCreatedAt(), s.getUpdatedAt()))
                .toList();
    }

    /**
     * 壁打ちセッションの詳細(会話履歴含む)を取得する。チャットの再開に使う。
     */
    public ArticlePlanSessionDetailResponse getSession(Long projectId, Long sessionId) {
        ArticlePlanSession session = findSession(projectId, sessionId);
        return toDetailResponse(session);
    }

    /**
     * issue番号に紐づく最新の壁打ちセッションを取得する。issue一覧の「計画」導線から使う。
     */
    public ArticlePlanSessionDetailResponse getSessionByIssue(Long projectId, Integer githubIssueNumber) {
        ArticlePlanSession session = articlePlanSessionRepository
                .findFirstByProjectIdAndGithubIssueNumberOrderByUpdatedAtDesc(projectId, githubIssueNumber)
                .orElseThrow(() -> new ArticlePlanSessionNotFoundException(
                        "issue #" + githubIssueNumber + " に紐づく壁打ちセッションは見つかりません"));
        return toDetailResponse(session);
    }

    private ArticlePlanSessionDetailResponse toDetailResponse(ArticlePlanSession session) {
        List<PlanChatMessage> history = fromJson(session.getHistory());
        return new ArticlePlanSessionDetailResponse(
                session.getId(), session.getTitle(), session.getGithubIssueNumber(), history,
                session.getCreatedAt(), session.getUpdatedAt());
    }

    /**
     * リポジトリに登録済みのissue一覧を取得する。
     */
    public List<RepositoryIssueResponse> listRepositoryIssues(Long projectId, Long userId, String state) {
        GithubAccess access = resolveGithubAccess(projectId, userId);
        List<GithubIssueSummary> issues = githubClient.listIssues(access.token(), access.owner(), access.repo(), state);
        return issues.stream()
                .map(i -> new RepositoryIssueResponse(i.number(), i.title(), i.htmlUrl(), i.state(), i.assignees()))
                .toList();
    }

    private record GithubAccess(String token, String owner, String repo) {
    }

    private GithubAccess resolveGithubAccess(Long projectId, Long userId) {
        Project project = projectService.getProjectEntity(projectId);
        if (!project.isGithubRepositoryConfigured()) {
            throw new IllegalStateException(
                    "このプロジェクトにGitHubリポジトリが紐付けられていません。プロジェクト詳細ページから設定してください。");
        }

        String token = userService.getDecryptedGithubToken(userId);
        String[] repoParts = project.getGithubRepository().split("/", 2);
        return new GithubAccess(token, repoParts[0], repoParts[1]);
    }

    /**
     * ユーザーが選択したタイトルを1件1issueとしてGitHubに登録する。
     * 1件の作成に失敗しても他のタイトルの登録は続け、結果を個別に集約して返す。
     */
    public AcceptPlanResponse acceptPlan(Long projectId, Long userId, List<String> titles) {
        GithubAccess access = resolveGithubAccess(projectId, userId);

        List<AcceptPlanResultItem> results = new ArrayList<>();
        for (String title : titles) {
            try {
                GithubIssue issue = githubClient.createIssue(access.token(), access.owner(), access.repo(), title, "");
                results.add(new AcceptPlanResultItem(title, issue.number(), issue.htmlUrl(), null));
            } catch (RuntimeException e) {
                results.add(new AcceptPlanResultItem(title, null, null, e.getMessage()));
            }
        }
        return new AcceptPlanResponse(results);
    }

    /**
     * issueの現在のdescription(body)を取得する。「計画」導線から壁打ちを再開した際、
     * 既に構成案で更新済みのissueであれば現状の内容をそのまま表示できるようにする。
     */
    public IssueDescriptionResponse getIssueDescription(Long projectId, Long userId, Integer issueNumber) {
        GithubAccess access = resolveGithubAccess(projectId, userId);
        String body = githubClient.getIssueBody(access.token(), access.owner(), access.repo(), issueNumber);
        return new IssueDescriptionResponse(body);
    }

    /**
     * 提案された記事構成で、指定issueのdescription(body)を上書きする。
     */
    public AcceptStructureResponse acceptStructure(Long projectId, Long userId, Integer issueNumber, String structure) {
        GithubAccess access = resolveGithubAccess(projectId, userId);
        GithubIssue issue = githubClient.updateIssueBody(
                access.token(), access.owner(), access.repo(), issueNumber, structure);
        return new AcceptStructureResponse(issue.number(), issue.htmlUrl());
    }

    /**
     * 指定issueをログイン中のユーザーに割り当て、in-progressラベルを付与する。
     */
    public AssignIssueResponse assignIssueToActor(Long projectId, Long userId, Integer issueNumber) {
        GithubAccess access = resolveGithubAccess(projectId, userId);

        GithubUser authUser = githubClient.getAuthenticatedUser(access.token());

        GithubIssue issue = githubClient.assignAndLabelIssue(
                access.token(),
                access.owner(),
                access.repo(),
                issueNumber,
                List.of(authUser.login()),
                List.of("in-progress"));

        return new AssignIssueResponse(issue.number(), issue.htmlUrl(), authUser.login());
    }

    public SuggestTitlesResponse suggestTitles(Long projectId, List<PlanChatMessage> history) {
        GenerationJob job = startJob("plan_suggest_titles", Map.of(
                "projectId", String.valueOf(projectId),
                "historyLength", String.valueOf(history.size())
        ));
        try {
            String model = ollamaModelService.getSelectedModel(projectId);
            String raw = ollamaClient.generate(buildTitleSuggestionPrompt(history), model);
            List<String> titles = parseTitles(raw);
            completeJob(job, Map.of("titlesCount", String.valueOf(titles.size()), "raw", raw));
            return new SuggestTitlesResponse(titles);
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
    }

    /**
     * 会話履歴から記事の構成案(Markdown)を提案する。issueのdescription上書きに使う。
     */
    public SuggestStructureResponse suggestStructure(Long projectId, List<PlanChatMessage> history) {
        GenerationJob job = startJob("plan_suggest_structure", Map.of(
                "projectId", String.valueOf(projectId),
                "historyLength", String.valueOf(history.size())
        ));
        try {
            String model = ollamaModelService.getSelectedModel(projectId);
            String structure = ollamaClient.generate(buildStructureSuggestionPrompt(history), model).strip();
            completeJob(job, Map.of("structureLength", String.valueOf(structure.length())));
            return new SuggestStructureResponse(structure);
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
    }

    /**
     * 会話履歴から記事のtitle/slug/categories/tagsをJSONで提案する。VSCode拡張のスキャフォールド生成に使う。
     */
    public SuggestMetadataResponse suggestMetadata(Long projectId, List<PlanChatMessage> history) {
        GenerationJob job = startJob("plan_suggest_metadata", Map.of(
                "projectId", String.valueOf(projectId),
                "historyLength", String.valueOf(history.size())
        ));
        try {
            String model = ollamaModelService.getSelectedModel(projectId);
            String raw = ollamaClient.generate(buildMetadataSuggestionPrompt(history), model);
            SuggestMetadataResponse response = parseMetadata(raw);
            completeJob(job, Map.of("raw", raw));
            return response;
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
    }

    private String buildMetadataSuggestionPrompt(List<PlanChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("System: ").append(SYSTEM_PROMPT).append("\n\n");
        appendHistory(sb, history);
        sb.append("\n").append(METADATA_SUGGESTION_INSTRUCTION).append("\n");
        return sb.toString();
    }

    private SuggestMetadataResponse parseMetadata(String raw) {
        String jsonPart = extractJsonObject(raw);
        try {
            JsonNode node = objectMapper.readTree(jsonPart);
            String title = node.path("title").asText("");
            String slug = node.path("slug").asText("");
            List<String> categories = parseStringArray(node.get("categories"));
            List<String> tags = parseStringArray(node.get("tags"));
            return new SuggestMetadataResponse(title, slug, categories, tags);
        } catch (Exception e) {
            return new SuggestMetadataResponse("", "", List.of(), List.of());
        }
    }

    private String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < 0 || end < start) {
            return "{}";
        }
        return raw.substring(start, end + 1);
    }

    private List<String> parseStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            String str = item.asText("").strip();
            if (!str.isBlank()) {
                result.add(str);
            }
        }
        return result;
    }

    private String buildChatPrompt(List<PlanChatMessage> history, String message, WebSearchOutcome searchOutcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("System: ").append(SYSTEM_PROMPT).append("\n\n");
        appendSearchResults(sb, searchOutcome);
        appendHistory(sb, history);
        sb.append("User: ").append(message).append("\n");
        sb.append("Assistant: ");
        return sb.toString();
    }

    private void appendSearchResults(StringBuilder sb, WebSearchOutcome searchOutcome) {
        if (!searchOutcome.succeeded() || searchOutcome.results().isEmpty()) {
            return;
        }
        sb.append("参考のWeb検索結果:\n");
        int i = 1;
        for (BraveSearchResult result : searchOutcome.results()) {
            sb.append(i++).append(". ").append(result.title()).append(" - ").append(result.description())
                    .append(" (").append(result.url()).append(")\n");
        }
        sb.append("\n");
    }

    private String buildTitleSuggestionPrompt(List<PlanChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("System: ").append(SYSTEM_PROMPT).append("\n\n");
        appendHistory(sb, history);
        sb.append("\n").append(TITLE_SUGGESTION_INSTRUCTION).append("\n");
        return sb.toString();
    }

    private String buildStructureSuggestionPrompt(List<PlanChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("System: ").append(SYSTEM_PROMPT).append("\n\n");
        appendHistory(sb, history);
        sb.append("\n").append(STRUCTURE_SUGGESTION_INSTRUCTION).append("\n");
        return sb.toString();
    }

    private void appendHistory(StringBuilder sb, List<PlanChatMessage> history) {
        for (PlanChatMessage msg : history) {
            sb.append("user".equals(msg.role()) ? "User" : "Assistant")
                    .append(": ")
                    .append(msg.content())
                    .append("\n");
        }
    }

    private List<String> parseTitles(String raw) {
        String jsonPart = extractJsonArray(raw);
        try {
            JsonNode node = objectMapper.readTree(jsonPart);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> titles = new ArrayList<>();
            node.forEach(n -> {
                String title = n.asText();
                if (!title.isBlank()) {
                    titles.add(title);
                }
            });
            return titles.stream().limit(5).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private String extractJsonArray(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0 || end < 0 || end < start) {
            return "[]";
        }
        return raw.substring(start, end + 1);
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

    private List<PlanChatMessage> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<PlanChatMessage>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }
}
