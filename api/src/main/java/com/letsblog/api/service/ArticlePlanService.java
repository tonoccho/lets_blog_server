package com.letsblog.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.OllamaClient;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.domain.Project;
import com.letsblog.api.dto.AcceptPlanResponse;
import com.letsblog.api.dto.AcceptPlanResultItem;
import com.letsblog.api.dto.PlanChatMessage;
import com.letsblog.api.dto.PlanChatResponse;
import com.letsblog.api.dto.SuggestTitlesResponse;
import com.letsblog.api.github.GithubClient;
import com.letsblog.api.github.GithubIssue;
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

    private final OllamaClient ollamaClient;
    private final GenerationJobRepository generationJobRepository;
    private final ObjectMapper objectMapper;
    private final GithubClient githubClient;
    private final UserService userService;
    private final ProjectService projectService;

    public ArticlePlanService(
            OllamaClient ollamaClient,
            GenerationJobRepository generationJobRepository,
            ObjectMapper objectMapper,
            GithubClient githubClient,
            UserService userService,
            ProjectService projectService) {
        this.ollamaClient = ollamaClient;
        this.generationJobRepository = generationJobRepository;
        this.objectMapper = objectMapper;
        this.githubClient = githubClient;
        this.userService = userService;
        this.projectService = projectService;
    }

    public PlanChatResponse chat(Long projectId, List<PlanChatMessage> history, String message) {
        GenerationJob job = startJob("plan_chat", Map.of(
                "projectId", String.valueOf(projectId),
                "message", message,
                "historyLength", String.valueOf(history.size())
        ));
        try {
            String prompt = buildChatPrompt(history, message);
            String reply = ollamaClient.generate(prompt);
            completeJob(job, Map.of("reply", reply));
            return new PlanChatResponse(reply);
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
    }

    /**
     * ユーザーが選択したタイトルを1件1issueとしてGitHubに登録する。
     * 1件の作成に失敗しても他のタイトルの登録は続け、結果を個別に集約して返す。
     */
    public AcceptPlanResponse acceptPlan(Long projectId, Long userId, List<String> titles) {
        Project project = projectService.getProjectEntity(projectId);
        if (!project.isGithubRepositoryConfigured()) {
            throw new IllegalStateException(
                    "このプロジェクトにGitHubリポジトリが紐付けられていません。プロジェクト詳細ページから設定してください。");
        }

        String token = userService.getDecryptedGithubToken(userId);
        String[] repoParts = project.getGithubRepository().split("/", 2);
        String owner = repoParts[0];
        String repo = repoParts[1];

        List<AcceptPlanResultItem> results = new ArrayList<>();
        for (String title : titles) {
            try {
                GithubIssue issue = githubClient.createIssue(token, owner, repo, title, "");
                results.add(new AcceptPlanResultItem(title, issue.number(), issue.htmlUrl(), null));
            } catch (RuntimeException e) {
                results.add(new AcceptPlanResultItem(title, null, null, e.getMessage()));
            }
        }
        return new AcceptPlanResponse(results);
    }

    public SuggestTitlesResponse suggestTitles(Long projectId, List<PlanChatMessage> history) {
        GenerationJob job = startJob("plan_suggest_titles", Map.of(
                "projectId", String.valueOf(projectId),
                "historyLength", String.valueOf(history.size())
        ));
        try {
            String raw = ollamaClient.generate(buildTitleSuggestionPrompt(history));
            List<String> titles = parseTitles(raw);
            completeJob(job, Map.of("titlesCount", String.valueOf(titles.size()), "raw", raw));
            return new SuggestTitlesResponse(titles);
        } catch (RuntimeException e) {
            failJob(job, e);
            throw e;
        }
    }

    private String buildChatPrompt(List<PlanChatMessage> history, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("System: ").append(SYSTEM_PROMPT).append("\n\n");
        appendHistory(sb, history);
        sb.append("User: ").append(message).append("\n");
        sb.append("Assistant: ");
        return sb.toString();
    }

    private String buildTitleSuggestionPrompt(List<PlanChatMessage> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("System: ").append(SYSTEM_PROMPT).append("\n\n");
        appendHistory(sb, history);
        sb.append("\n").append(TITLE_SUGGESTION_INSTRUCTION).append("\n");
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
}
