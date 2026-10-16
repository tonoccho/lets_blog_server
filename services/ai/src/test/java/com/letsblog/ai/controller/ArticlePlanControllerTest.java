package com.letsblog.ai.controller;

import com.letsblog.ai.domain.ArticlePlanSession;
import com.letsblog.ai.dto.AcceptPlanRequest;
import com.letsblog.ai.dto.AcceptPlanResponse;
import com.letsblog.ai.dto.AcceptStructureRequest;
import com.letsblog.ai.dto.AcceptStructureResponse;
import com.letsblog.ai.dto.ArticlePlanSessionDetailResponse;
import com.letsblog.ai.dto.ArticlePlanSessionSummaryResponse;
import com.letsblog.ai.dto.AssignIssueResponse;
import com.letsblog.ai.dto.CategoryOption;
import com.letsblog.ai.dto.IssueDescriptionResponse;
import com.letsblog.ai.dto.PlanChatMessage;
import com.letsblog.ai.dto.PlanChatRequest;
import com.letsblog.ai.dto.PlanChatResponse;
import com.letsblog.ai.dto.RepositoryIssueResponse;
import com.letsblog.ai.dto.SuggestMetadataRequest;
import com.letsblog.ai.dto.SuggestMetadataResponse;
import com.letsblog.ai.dto.SuggestStructureRequest;
import com.letsblog.ai.dto.SuggestStructureResponse;
import com.letsblog.ai.dto.SuggestTitlesRequest;
import com.letsblog.ai.dto.SuggestTitlesResponse;
import com.letsblog.ai.service.ForbiddenException;
import com.letsblog.ai.testsupport.AiWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * 各エンドポイントがadminAuthorizationService.requireProjectMemberOrAdmin(projectId)を
 * 呼び出したうえでサービスに委譲すること、認可拒否時にサービスを呼び出さないことを検証する。
 *
 * <p>issue #1824: ArticlePlanService・AdminAuthorizationService・CurrentActorServiceは実物で、identity-service・
 * LLM・GitHub・project-serviceはHTTPフェイク、DBはメモリ上のリポジトリで差し替える。
 */
class ArticlePlanControllerTest {

    private static final String MEMBER_PATH = "/api/internal/identity/projects/1/members/10";
    private static final String GITHUB_ISSUES = "/github/repos/owner/repo/issues";

    private AiWorld world;
    private ArticlePlanController controller;

    @BeforeEach
    void setUp() {
        world = new AiWorld().ollamaDefault("qwen2.5:7b-instruct");
        controller = new ArticlePlanController(
                world.articlePlanService, world.adminAuthorizationService, world.currentActorService);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    /** プロジェクト1のメンバー(ユーザーID 10)として呼ぶ。 */
    private void member() {
        world.member(10L);
        world.http.json("GET", MEMBER_PATH, "true");
    }

    private void github() {
        world.githubAccess(1L, "test-token", "owner", "repo");
    }

    private ArticlePlanSession session(Long projectId, Integer issueNumber) {
        ArticlePlanSession session = new ArticlePlanSession();
        session.setProjectId(projectId);
        session.setTitle("タイトル");
        session.setGithubIssueNumber(issueNumber);
        session.setHistory("[]");
        session.setCreatedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        session.setUpdatedAt(LocalDateTime.of(2026, 1, 2, 10, 0));
        return world.articlePlanSessionRepository.save(session);
    }

    private void verifyAuthorized() {
        assertEquals(1, world.http.requests("GET", MEMBER_PATH).size());
        assertEquals("Bearer t", world.http.requests("GET", MEMBER_PATH).get(0).header("Authorization"));
    }

    @Test
    void chat_認可後にサービスへ委譲する() {
        member();
        world.llmReplies("応答", "セッションのタイトル");
        List<PlanChatMessage> history = List.of(new PlanChatMessage("user", "テーマ"));

        PlanChatResponse response = controller.chat(1L, new PlanChatRequest(history, "追加発言", null, 42));

        assertEquals("応答", response.reply());
        assertEquals(1L, response.sessionId());
        assertTrue(world.llmPrompts().get(0).contains("追加発言"));
        verifyAuthorized();
    }

    @Test
    void listSessions_認可後にサービスへ委譲する() {
        member();
        session(1L, null);
        session(2L, null);

        List<ArticlePlanSessionSummaryResponse> result = controller.listSessions(1L);

        assertEquals(1, result.size());
        assertEquals("タイトル", result.get(0).title());
        verifyAuthorized();
    }

    @Test
    void getSession_認可後にサービスへ委譲する() {
        member();
        session(1L, null);

        ArticlePlanSessionDetailResponse response = controller.getSession(1L, 1L);

        assertEquals(1L, response.id());
        verifyAuthorized();
    }

    @Test
    void getSessionByIssue_認可後にサービスへ委譲する() {
        member();
        session(1L, 42);

        ArticlePlanSessionDetailResponse response = controller.getSessionByIssue(1L, 42);

        assertEquals(42, response.githubIssueNumber());
        verifyAuthorized();
    }

    @Test
    void getIssueDescription_認可後にサービスへ委譲する() {
        member();
        github();
        world.http.json("GET", GITHUB_ISSUES + "/3", "{\"body\":\"本文\"}");

        IssueDescriptionResponse response = controller.getIssueDescription(1L, 3);

        assertEquals("本文", response.body());
        // 呼び出し元の利用者(10)がGitHubアクセス情報の問い合わせに使われる。
        assertEquals("actorUserId=10",
                world.http.requests("GET", "/api/internal/project/projects/1/github-access").get(0).query());
        verifyAuthorized();
    }

    @Test
    void listIssues_認可後にサービスへ委譲する() {
        member();
        github();
        world.http.json("GET", GITHUB_ISSUES, "[{\"number\":1,\"title\":\"issue\","
                + "\"html_url\":\"https://example.com/1\",\"state\":\"open\",\"assignees\":[]}]");

        List<RepositoryIssueResponse> result = controller.listIssues(1L, "open");

        assertEquals(1, result.size());
        assertEquals("issue", result.get(0).title());
        assertEquals("state=open&per_page=100", world.http.requests("GET", GITHUB_ISSUES).get(0).query());
        verifyAuthorized();
    }

    @Test
    void suggestTitles_認可後にサービスへ委譲する() {
        member();
        world.llmReplies("[\"タイトル\"]");

        SuggestTitlesResponse response = controller.suggestTitles(1L, new SuggestTitlesRequest(List.of()));

        assertEquals(List.of("タイトル"), response.titles());
        verifyAuthorized();
    }

    @Test
    void acceptPlan_認可後にサービスへ委譲する() {
        member();
        github();
        world.http.json("POST", GITHUB_ISSUES, "{\"number\":1,\"html_url\":\"https://github.com/owner/repo/issues/1\"}");

        AcceptPlanResponse response = controller.acceptPlan(1L, new AcceptPlanRequest(List.of("タイトル")));

        assertEquals(1, response.results().size());
        assertEquals("タイトル", response.results().get(0).title());
        assertEquals(1, response.results().get(0).issueNumber());
        verifyAuthorized();
    }

    @Test
    void suggestStructure_認可後にサービスへ委譲する() {
        member();
        world.llmReplies("## 構成");

        SuggestStructureResponse response = controller.suggestStructure(1L, new SuggestStructureRequest(List.of()));

        assertEquals("## 構成", response.structure());
        verifyAuthorized();
    }

    @Test
    void acceptStructure_認可後にサービスへ委譲する() {
        member();
        github();
        world.http.json("PATCH", GITHUB_ISSUES + "/42", "{\"number\":42,\"html_url\":\"https://example.com/42\"}");

        AcceptStructureResponse response = controller.acceptStructure(1L, 42, new AcceptStructureRequest("## 構成"));

        assertEquals(42, response.issueNumber());
        assertEquals("https://example.com/42", response.issueUrl());
        assertTrue(world.http.requests("PATCH", GITHUB_ISSUES + "/42").get(0).bodyText().contains("## 構成"));
        verifyAuthorized();
    }

    @Test
    void suggestMetadata_認可後にサービスへ委譲する() {
        member();
        world.llmReplies("{\"titles\":[\"タイトル\"],\"slugs\":[\"slug\"],\"categories\":[],\"tags\":[\"タグ\"]}");

        SuggestMetadataResponse response = controller.suggestMetadata(1L, new SuggestMetadataRequest(List.of()));

        assertEquals(List.of("タイトル"), response.titles());
        assertEquals(List.of("slug"), response.slugs());
        assertEquals(List.of("タグ"), response.tags());
        verifyAuthorized();
    }

    @Test
    void listTags_認可後にサービスへ委譲する() {
        member();
        world.http.json("GET", "/api/internal/ai/projects/1/existing-tags", "[\"Java\",\"AWS\"]");

        assertEquals(List.of("Java", "AWS"), controller.listTags(1L));
        verifyAuthorized();
    }

    // issue #568: 認可マトリクス整備に伴う、requireProjectMemberOrAdmin()を呼ぶ全メソッドのForbiddenパス網羅
    @Test
    void listCategories_認可後にサービスへ委譲する() {
        member();
        world.http.json("GET", "/api/internal/ai/projects/1/existing-categories", "[\"お知らせ\",\"技術\"]");

        assertEquals(List.of("お知らせ", "技術"), controller.listCategories(1L));
        verifyAuthorized();
    }

    @Test
    void listCategoriesWithParents_認可後にサービスへ委譲する() {
        member();
        world.http.json("GET", "/api/internal/ai/projects/1/existing-categories-with-parents",
                "[{\"name\":\"技術\",\"parentName\":\"お知らせ\"}]");

        List<CategoryOption> result = controller.listCategoriesWithParents(1L);

        assertEquals(1, result.size());
        assertEquals("技術", result.get(0).name());
        assertEquals("お知らせ", result.get(0).parentName());
        verifyAuthorized();
    }

    @Test
    void assignIssue_認可後にサービスへ委譲する() {
        member();
        github();
        world.http.json("GET", "/github/user", "{\"login\":\"octocat\"}");
        world.http.json("PATCH", GITHUB_ISSUES + "/42", "{\"number\":42,\"html_url\":\"https://example.com/42\"}");

        AssignIssueResponse response = controller.assignIssue(1L, 42);

        assertEquals("octocat", response.assignedLogin());
        verifyAuthorized();
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> endpoints() {
        return Stream.of(
                arguments("chat", (Consumer<ArticlePlanController>) c ->
                        c.chat(1L, new PlanChatRequest(List.of(), "発言", null, null))),
                arguments("listSessions", (Consumer<ArticlePlanController>) c -> c.listSessions(1L)),
                arguments("getSession", (Consumer<ArticlePlanController>) c -> c.getSession(1L, 5L)),
                arguments("getSessionByIssue", (Consumer<ArticlePlanController>) c -> c.getSessionByIssue(1L, 42)),
                arguments("getIssueDescription", (Consumer<ArticlePlanController>) c -> c.getIssueDescription(1L, 3)),
                arguments("listIssues", (Consumer<ArticlePlanController>) c -> c.listIssues(1L, "open")),
                arguments("suggestTitles", (Consumer<ArticlePlanController>) c ->
                        c.suggestTitles(1L, new SuggestTitlesRequest(List.of()))),
                arguments("acceptPlan", (Consumer<ArticlePlanController>) c ->
                        c.acceptPlan(1L, new AcceptPlanRequest(List.of("タイトル")))),
                arguments("suggestStructure", (Consumer<ArticlePlanController>) c ->
                        c.suggestStructure(1L, new SuggestStructureRequest(List.of()))),
                arguments("acceptStructure", (Consumer<ArticlePlanController>) c ->
                        c.acceptStructure(1L, 42, new AcceptStructureRequest("## 構成"))),
                arguments("suggestMetadata", (Consumer<ArticlePlanController>) c ->
                        c.suggestMetadata(1L, new SuggestMetadataRequest(List.of()))),
                arguments("listTags", (Consumer<ArticlePlanController>) c -> c.listTags(1L)),
                arguments("listCategories", (Consumer<ArticlePlanController>) c -> c.listCategories(1L)),
                arguments("listCategoriesWithParents", (Consumer<ArticlePlanController>) c ->
                        c.listCategoriesWithParents(1L)),
                arguments("assignIssue", (Consumer<ArticlePlanController>) c -> c.assignIssue(1L, 42)));
    }

    @ParameterizedTest(name = "{0}: 認可拒否ならForbiddenで何も呼ばない")
    @MethodSource("endpoints")
    void 認可拒否ならForbidden(String name, Consumer<ArticlePlanController> call) {
        world.member(10L);
        world.http.json("GET", MEMBER_PATH, "false");

        assertThrows(ForbiddenException.class, () -> call.accept(controller));

        // メンバー判定以外の外部通信(LLM・GitHub・project-service・platform-service)も、DBへの保存も無い。
        assertEquals(List.of("/api/identity/me", MEMBER_PATH), world.http.paths());
        assertTrue(world.jobRows.saved.isEmpty());
        assertTrue(world.sessionRows.saved.isEmpty());
    }
}
