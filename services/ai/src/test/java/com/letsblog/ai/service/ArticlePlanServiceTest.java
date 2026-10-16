package com.letsblog.ai.service;

import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.domain.ArticlePlanSession;
import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.dto.AcceptPlanResponse;
import com.letsblog.ai.dto.AcceptPlanResultItem;
import com.letsblog.ai.dto.ArticlePlanSessionDetailResponse;
import com.letsblog.ai.dto.ArticlePlanSessionSummaryResponse;
import com.letsblog.ai.dto.AssignIssueResponse;
import com.letsblog.ai.dto.PlanChatMessage;
import com.letsblog.ai.dto.PlanChatResponse;
import com.letsblog.ai.dto.RepositoryIssueResponse;
import com.letsblog.ai.dto.SuggestMetadataResponse;
import com.letsblog.ai.dto.SuggestTitlesResponse;
import com.letsblog.ai.github.GithubApiException;
import com.letsblog.ai.testsupport.AiWorld;
import com.letsblog.ai.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ArticlePlanServiceの回帰テスト(issue #574でai-serviceへ移設)。
 *
 * <p>GitHubトークン解決とマスター環境サイトの既存カテゴリ/タグ取得はlegacy-apiに残る
 * Project/Site/CmsAdapter/ProjectApiKeyServiceへの直接依存から、{@link com.letsblog.ai.client.ProjectBridgeClient}経由の
 * 内部ブリッジ呼び出しへ置き換わった(ArticlePlanServiceのJavadoc参照)。
 *
 * <p>issue #1823: LlmClient・LlmModelService・WebSearchService・GithubClient・ProjectBridgeClient・
 * PublishingServiceClient・CurrentActorServiceは実物で、LLM・GitHub・project-service・publishing-service・
 * identity-service・platform-serviceはHTTPフェイク、DBはメモリ上のリポジトリで差し替える。
 */
class ArticlePlanServiceTest {

    private static final String MODEL = "qwen2.5:7b-instruct";
    private static final String GITHUB_ISSUES = "/github/repos/owner/repo/issues";

    private AiWorld world;
    private ArticlePlanService service;

    @BeforeEach
    void setUp() {
        world = new AiWorld().ollamaDefault(MODEL);
        service = world.articlePlanService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private ArticlePlanSession existingSession(Long id, Long projectId, List<PlanChatMessage> history) {
        ArticlePlanSession session = new ArticlePlanSession();
        session.setId(id);
        session.setProjectId(projectId);
        session.setTitle("既存の壁打ち");
        session.setHistory(toJson(history));
        session.setCreatedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        session.setUpdatedAt(LocalDateTime.of(2026, 1, 2, 10, 0));
        return world.articlePlanSessionRepository.save(session);
    }

    private String toJson(Object value) {
        try {
            return world.objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void githubNotConfigured() {
        world.http.status("GET", "/api/internal/project/projects/1/github-access", 409,
                "このプロジェクトにGitHubリポジトリが紐付けられていません。プロジェクト詳細ページから設定してください。");
    }

    private GenerationJob job(int index) {
        return world.jobRows.saved.get(index);
    }

    @Test
    void chat_履歴とメッセージを含むプロンプトを組み立ててLLMを呼び出す() {
        List<PlanChatMessage> history = List.of(
                new PlanChatMessage("user", "AIブログの企画を考えたい"),
                new PlanChatMessage("assistant", "どんな読者層を想定していますか?"));
        existingSession(99L, 1L, history);
        world.llmReplies("初心者エンジニア向けはどうでしょう");

        PlanChatResponse response = service.chat(1L, history, "初心者向けにしたいです", 99L, null);

        assertEquals("初心者エンジニア向けはどうでしょう", response.reply());
        assertEquals(99L, response.sessionId());

        assertEquals(1, world.llmPrompts().size());
        String prompt = world.llmPrompts().get(0);
        assertTrue(prompt.contains("AIブログの企画を考えたい"));
        assertTrue(prompt.contains("どんな読者層を想定していますか?"));
        assertTrue(prompt.contains("初心者向けにしたいです"));
        assertTrue(prompt.startsWith("System:"));
        assertEquals(MODEL, world.llmModelOf(world.llmRequests().get(0)));
    }

    @Test
    void chat_生成ジョブがplan_chatとして記録される() {
        existingSession(99L, 1L, List.of());
        world.llmReplies("応答");

        service.chat(1L, List.of(), "テーマ", 99L, null);

        assertEquals(List.of("plan_chat:running", "plan_chat:done"), world.jobRows.log);
    }

    @Test
    void chat_生成ジョブに呼び出し元の利用者を所有者として記録する() {
        world.actor(42L, "user");
        existingSession(99L, 1L, List.of());
        world.llmReplies("応答");

        service.chat(1L, List.of(), "テーマ", 99L, null);

        assertEquals(42L, job(0).getOwnerUserId());
    }

    @Test
    void chat_LLM呼び出しが失敗した場合ジョブがfailedになり例外を再送出する() {
        world.llmFails(500, "接続できません");

        assertThrows(RuntimeException.class, () -> service.chat(1L, List.of(), "テーマ", null, null));

        assertEquals(List.of("plan_chat:running", "plan_chat:failed"), world.jobRows.log);
        assertTrue(job(0).getResultPayload().contains("LLM呼び出しに失敗しました"), job(0).getResultPayload());
    }

    @Test
    void chat_sessionId未指定の初回発言では新規セッションを作成しタイトルを生成する() {
        world.llmReplies("AIとのやり取りの応答", "生成されたタイトル");

        PlanChatResponse response = service.chat(1L, List.of(), "AIブログの企画を考えたい", null, null);

        verify(world.articlePlanSessionRepository, never()).findById(any());
        ArticlePlanSession saved = world.sessionRows.saved.get(0);
        assertEquals(saved.getId(), response.sessionId());
        assertEquals(1L, saved.getProjectId());
        assertEquals("生成されたタイトル", saved.getTitle());
        assertTrue(saved.getHistory().contains("AIブログの企画を考えたい"));
        assertTrue(saved.getHistory().contains("AIとのやり取りの応答"));
        // 1回目: チャット応答生成、2回目: タイトル生成
        assertEquals(2, world.llmRequests().size());
        assertEquals(List.of("plan_chat:running", "plan_chat:done", "plan_session_title:running", "plan_session_title:done"),
                world.jobRows.log);
    }

    @Test
    void chat_タイトル生成が失敗しても最初のメッセージからタイトルを作って保存する() {
        world.http.sequence("POST", ".*/chat/completions",
                FakeHttpServer.Response.ok("{\"choices\":[{\"message\":{\"content\":\"応答\"}}]}"),
                FakeHttpServer.Response.json(500, "{}"));

        PlanChatResponse response = service.chat(1L, List.of(), "「企画の相談」", null, null);

        ArticlePlanSession saved = world.sessionRows.saved.get(0);
        assertEquals(saved.getId(), response.sessionId());
        assertEquals("企画の相談", saved.getTitle());
        assertEquals("plan_session_title:failed", world.jobRows.log.get(world.jobRows.log.size() - 1));
    }

    @Test
    void chat_タイトルが空なら無題_長ければ40文字に切り詰める() {
        world.llmReplies("応答", "\"\"");
        service.chat(1L, List.of(), "a", null, null);
        assertEquals("無題の壁打ち", world.sessionRows.saved.get(0).getTitle());

        world.llmReplies("応答", "あ".repeat(60));
        service.chat(1L, List.of(), "b", null, null);
        assertEquals(40, world.sessionRows.saved.get(1).getTitle().length());
    }

    @Test
    void chat_sessionId指定時は既存セッションの履歴に追記する() {
        List<PlanChatMessage> existingHistory = List.of(new PlanChatMessage("user", "前回の発言"));
        existingSession(5L, 1L, existingHistory);
        world.llmReplies("続きの応答");

        PlanChatResponse response = service.chat(1L, existingHistory, "追加の発言", 5L, null);

        assertEquals(5L, response.sessionId());
        // タイトル生成は行われないため、LLM呼び出しは1回のみ
        assertEquals(1, world.llmRequests().size());
        String savedHistory = world.sessionRows.saved.get(world.sessionRows.saved.size() - 1).getHistory();
        assertTrue(savedHistory.contains("前回の発言"));
        assertTrue(savedHistory.contains("追加の発言"));
        assertTrue(savedHistory.contains("続きの応答"));
    }

    @Test
    void chat_異なるプロジェクトのsessionIdを指定すると例外() {
        existingSession(5L, 999L, List.of());
        world.llmReplies("応答");

        assertThrows(ArticlePlanSessionNotFoundException.class,
                () -> service.chat(1L, List.of(), "発言", 5L, null));
    }

    @Test
    void chat_存在しないsessionIdを指定すると例外() {
        world.llmReplies("応答");

        assertThrows(ArticlePlanSessionNotFoundException.class,
                () -> service.chat(1L, List.of(), "発言", 5L, null));
    }

    @Test
    void chat_githubIssueNumber指定時は新規セッションにissue番号が保存される() {
        world.llmReplies("応答", "生成されたタイトル");

        PlanChatResponse response = service.chat(1L, List.of(), "issueの記事について壁打ち", null, 42);

        assertEquals(world.sessionRows.saved.get(0).getId(), response.sessionId());
        assertEquals(42, world.sessionRows.saved.get(0).getGithubIssueNumber());
    }

    @Test
    void chat_Web検索に成功すれば検索結果をプロンプトに含めジョブに記録する() {
        world.http.json("GET", "/api/internal/platform/system-settings/brave-search-api-key", "{\"apiKey\":\"system-key\"}");
        world.http.json("GET", "/brave/res/v1/web/search",
                "{\"web\":{\"results\":[{\"title\":\"検索タイトル\",\"description\":\"検索概要\",\"url\":\"https://example.com/x\"}]}}");
        existingSession(99L, 1L, List.of());
        world.llmReplies("応答");

        service.chat(1L, List.of(), "テーマ", 99L, null);

        assertTrue(world.llmPrompts().get(0).contains("検索タイトル - https://example.com/x"), world.llmPrompts().get(0));
        assertTrue(job(1).getResultPayload().contains("\"webSearchSucceeded\":\"true\""), job(1).getResultPayload());
    }

    @Test
    void getSessionByIssue_issue番号に紐づく最新セッションの詳細を返す() {
        ArticlePlanSession session = existingSession(7L, 1L, List.of(new PlanChatMessage("user", "こんにちは")));
        session.setGithubIssueNumber(42);

        ArticlePlanSessionDetailResponse response = service.getSessionByIssue(1L, 42);

        assertEquals(7L, response.id());
        assertEquals(42, response.githubIssueNumber());
        assertEquals(1, response.history().size());
    }

    @Test
    void getSessionByIssue_見つからない場合は例外() {
        assertThrows(ArticlePlanSessionNotFoundException.class, () -> service.getSessionByIssue(1L, 42));
    }

    @Test
    void listSessions_更新日時降順のサマリー一覧を返す() {
        existingSession(1L, 1L, List.of());

        List<ArticlePlanSessionSummaryResponse> result = service.listSessions(1L);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
        assertEquals("既存の壁打ち", result.get(0).title());
    }

    @Test
    void getSession_履歴を含む詳細を返す() {
        existingSession(1L, 1L, List.of(new PlanChatMessage("user", "こんにちは")));

        ArticlePlanSessionDetailResponse response = service.getSession(1L, 1L);

        assertEquals(1L, response.id());
        assertEquals(1, response.history().size());
        assertEquals("こんにちは", response.history().get(0).content());
    }

    @Test
    void getSession_履歴のJSONが壊れていれば空の履歴で返す() {
        ArticlePlanSession session = existingSession(1L, 1L, List.of());
        session.setHistory("not json");

        assertEquals(List.of(), service.getSession(1L, 1L).history());
    }

    @Test
    void getSession_存在しない場合は例外() {
        assertThrows(ArticlePlanSessionNotFoundException.class, () -> service.getSession(1L, 1L));
    }

    @Test
    void suggestTitles_タイトル提案の指示がプロンプトに含まれる() {
        world.llmReplies("[\"タイトル1\", \"タイトル2\"]");

        service.suggestTitles(1L, List.of(new PlanChatMessage("user", "テーマ案")));

        assertTrue(world.llmPrompts().get(0).contains("JSON配列形式"));
    }

    @Test
    void suggestTitles_JSON配列を正しくパースする() {
        world.llmReplies("[\"タイトル1\", \"タイトル2\"]");

        SuggestTitlesResponse response = service.suggestTitles(1L, List.of());

        assertEquals(List.of("タイトル1", "タイトル2"), response.titles());
    }

    @Test
    void suggestTitles_前後に説明文が付いていてもJSON配列部分だけを抽出する() {
        world.llmReplies("以下が提案です。\n[\"タイトルA\", \"タイトルB\"]\nご確認ください。");

        SuggestTitlesResponse response = service.suggestTitles(1L, List.of());

        assertEquals(List.of("タイトルA", "タイトルB"), response.titles());
    }

    @Test
    void suggestTitles_不正なJSONの場合は空リストを返す() {
        world.llmReplies("JSONではない応答です");

        assertEquals(List.of(), service.suggestTitles(1L, List.of()).titles());
    }

    @Test
    void suggestTitles_JSON配列として壊れていれば空リスト_配列でなければ空リスト() {
        world.llmReplies("[\"a\", ");
        assertEquals(List.of(), service.suggestTitles(1L, List.of()).titles());

        world.llmReplies("{\"a\":1}");
        assertEquals(List.of(), service.suggestTitles(1L, List.of()).titles());
    }

    @Test
    void suggestTitles_空白だけのタイトルは捨てる() {
        world.llmReplies("[\"a\", \"  \", \"b\"]");

        assertEquals(List.of("a", "b"), service.suggestTitles(1L, List.of()).titles());
    }

    @Test
    void suggestTitles_6件以上の提案は5件に制限される() {
        world.llmReplies("[\"1\", \"2\", \"3\", \"4\", \"5\", \"6\", \"7\"]");

        assertEquals(5, service.suggestTitles(1L, List.of()).titles().size());
    }

    @Test
    void suggestTitles_生成ジョブがplan_suggest_titlesとして記録される() {
        world.llmReplies("[]");

        service.suggestTitles(1L, List.of());

        assertEquals(List.of("plan_suggest_titles:running", "plan_suggest_titles:done"), world.jobRows.log);
    }

    @Test
    void suggestTitles_LLM呼び出しが失敗した場合ジョブがfailedになり例外を再送出する() {
        world.llmFails(500, "x");

        assertThrows(RuntimeException.class, () -> service.suggestTitles(1L, List.of()));

        assertEquals(List.of("plan_suggest_titles:running", "plan_suggest_titles:failed"), world.jobRows.log);
    }

    @Test
    void acceptPlan_リポジトリ未設定の場合は例外をスローする() {
        githubNotConfigured();

        assertThrows(IllegalStateException.class, () -> service.acceptPlan(1L, 10L, List.of("タイトル")));
    }

    @Test
    void acceptPlan_成功時はissue番号とURLを含む結果を返す() {
        world.githubAccess(1L, "test-token", "owner", "repo");
        world.http.json("POST", GITHUB_ISSUES, "{\"number\":1,\"html_url\":\"https://github.com/owner/repo/issues/1\"}");

        AcceptPlanResponse response = service.acceptPlan(1L, 10L, List.of("タイトル1"));

        assertEquals(1, response.results().size());
        AcceptPlanResultItem item = response.results().get(0);
        assertEquals("タイトル1", item.title());
        assertEquals(1, item.issueNumber());
        assertEquals("https://github.com/owner/repo/issues/1", item.issueUrl());
        assertNull(item.error());
        FakeHttpServer.Request request = world.http.requests("POST", GITHUB_ISSUES).get(0);
        assertEquals("Bearer test-token", request.header("Authorization"));
        assertTrue(request.bodyText().contains("\"title\":\"タイトル1\""), request.bodyText());
        assertEquals("actorUserId=10",
                world.http.requests("GET", "/api/internal/project/projects/1/github-access").get(0).query());
    }

    @Test
    void acceptPlan_一部のissue作成に失敗しても他のタイトルの登録は続行する() {
        world.githubAccess(1L, "test-token", "owner", "repo");
        world.http.on("POST", GITHUB_ISSUES, request -> request.bodyText().contains("失敗タイトル")
                ? FakeHttpServer.Response.json(500, "{}")
                : FakeHttpServer.Response.ok("{\"number\":1,\"html_url\":\"https://github.com/owner/repo/issues/1\"}"));

        AcceptPlanResponse response = service.acceptPlan(1L, 10L, List.of("成功タイトル", "失敗タイトル"));

        assertEquals(2, response.results().size());
        assertNull(response.results().get(0).error());
        assertTrue(response.results().get(1).error().startsWith("GitHub issueの作成に失敗しました"),
                response.results().get(1).error());
        assertNull(response.results().get(1).issueNumber());
    }

    @Test
    void suggestStructure_構成案の指示がプロンプトに含まれる() {
        world.llmReplies("## 見出し1\n- 小見出しA");

        service.suggestStructure(1L, List.of(new PlanChatMessage("user", "テーマ案")));

        assertTrue(world.llmPrompts().get(0).contains("構成案"));
    }

    @Test
    void suggestStructure_LLMの応答をそのまま返す() {
        world.llmReplies("## 見出し1\n- 小見出しA");

        assertEquals("## 見出し1\n- 小見出しA", service.suggestStructure(1L, List.of()).structure());
    }

    @Test
    void suggestStructure_生成ジョブがplan_suggest_structureとして記録される() {
        world.llmReplies("構成案");

        service.suggestStructure(1L, List.of());

        assertEquals(List.of("plan_suggest_structure:running", "plan_suggest_structure:done"), world.jobRows.log);
    }

    @Test
    void suggestStructure_LLM呼び出しが失敗した場合ジョブがfailedになり例外を再送出する() {
        world.llmFails(500, "x");

        assertThrows(RuntimeException.class, () -> service.suggestStructure(1L, List.of()));

        assertEquals(List.of("plan_suggest_structure:running", "plan_suggest_structure:failed"), world.jobRows.log);
    }

    @Test
    void getIssueDescription_GithubClientから取得したbodyを返す() {
        world.githubAccess(1L, "test-token", "owner", "repo");
        world.http.json("GET", GITHUB_ISSUES + "/3", "{\"body\":\"## 現状の構成\"}");

        assertEquals("## 現状の構成", service.getIssueDescription(1L, 10L, 3).body());
    }

    @Test
    void getIssueDescription_リポジトリ未設定の場合は例外をスローする() {
        githubNotConfigured();

        assertThrows(IllegalStateException.class, () -> service.getIssueDescription(1L, 10L, 3));
    }

    @Test
    void acceptStructure_成功時はissue番号とURLを含む結果を返す() {
        world.githubAccess(1L, "test-token", "owner", "repo");
        world.http.json("PATCH", GITHUB_ISSUES + "/42", "{\"number\":42,\"html_url\":\"https://github.com/owner/repo/issues/42\"}");

        var response = service.acceptStructure(1L, 10L, 42, "## 構成案");

        assertEquals(42, response.issueNumber());
        assertEquals("https://github.com/owner/repo/issues/42", response.issueUrl());
        assertTrue(world.http.requests("PATCH", GITHUB_ISSUES + "/42").get(0).bodyText().contains("## 構成案"));
    }

    @Test
    void acceptStructure_リポジトリ未設定の場合は例外をスローする() {
        githubNotConfigured();

        assertThrows(IllegalStateException.class, () -> service.acceptStructure(1L, 10L, 42, "## 構成案"));
    }

    @Test
    void listRepositoryIssues_GithubClientに委譲して結果を変換する() {
        world.githubAccess(1L, "test-token", "owner", "repo");
        world.http.json("GET", GITHUB_ISSUES, "[{\"number\":3,\"title\":\"記事タイトル\","
                + "\"html_url\":\"https://github.com/owner/repo/issues/3\",\"state\":\"open\",\"assignees\":[]},"
                + "{\"number\":4,\"title\":\"PR\",\"html_url\":\"u\",\"state\":\"open\",\"pull_request\":{}}]");

        List<RepositoryIssueResponse> result = service.listRepositoryIssues(1L, 10L, "open");

        assertEquals(1, result.size());
        assertEquals(3, result.get(0).number());
        assertEquals("記事タイトル", result.get(0).title());
        assertEquals("open", result.get(0).state());
        assertEquals("state=open&per_page=100", world.http.requests("GET", GITHUB_ISSUES).get(0).query());
    }

    @Test
    void listRepositoryIssues_リポジトリ未設定の場合は例外をスローする() {
        githubNotConfigured();

        assertThrows(IllegalStateException.class, () -> service.listRepositoryIssues(1L, 10L, "open"));
    }

    @Test
    void suggestMetadata_JSONオブジェクトを正しくパースする() {
        world.llmReplies(
                "{\"titles\":[\"タイトル案1\",\"タイトル案2\"],\"slugs\":[\"article-slug-1\",\"article-slug-2\"],"
                + "\"categories\":[\"カテゴリ1\"],\"tags\":[\"tag1\",\"tag2\"]}");

        SuggestMetadataResponse response = service.suggestMetadata(1L, List.of());

        assertEquals(List.of("タイトル案1", "タイトル案2"), response.titles());
        assertEquals(List.of("article-slug-1", "article-slug-2"), response.slugs());
        assertEquals(List.of("カテゴリ1"), response.categories());
        assertEquals(List.of("tag1", "tag2"), response.tags());
    }

    @Test
    void suggestMetadata_全て空のJSONが返った場合は1回だけ再試行する() {
        world.llmReplies(
                "{\"titles\":[],\"slugs\":[],\"categories\":[],\"tags\":[]}",
                "{\"titles\":[\"タイトル案\"],\"slugs\":[\"article-slug\"],\"categories\":[],\"tags\":[\"tag1\"]}");

        SuggestMetadataResponse response = service.suggestMetadata(1L, List.of());

        assertEquals(List.of("タイトル案"), response.titles());
        assertEquals(List.of("article-slug"), response.slugs());
        assertEquals(List.of("tag1"), response.tags());
        assertEquals(2, world.llmRequests().size());
    }

    @Test
    void suggestMetadata_2回とも空のJSONなら空のまま返し3回目は試行しない() {
        world.llmReplies("{\"titles\":[],\"slugs\":[],\"categories\":[],\"tags\":[]}");

        SuggestMetadataResponse response = service.suggestMetadata(1L, List.of());

        assertEquals(List.of(), response.titles());
        assertEquals(List.of(), response.tags());
        assertEquals(2, world.llmRequests().size());
    }

    @Test
    void suggestMetadata_前後に説明文が付いていてもJSONオブジェクト部分だけを抽出する() {
        world.llmReplies(
                "以下が提案です。\n{\"titles\":[\"タイトル\"],\"slugs\":[\"slug\"],\"categories\":[],\"tags\":[]}\nご確認ください。");

        SuggestMetadataResponse response = service.suggestMetadata(1L, List.of());

        assertEquals(List.of("タイトル"), response.titles());
        assertEquals(List.of("slug"), response.slugs());
    }

    @Test
    void suggestMetadata_配列でない項目や空白の要素は無視する() {
        world.llmReplies("{\"titles\":\"not-array\",\"slugs\":[\"  \",\"s\"],\"tags\":[\"t\"]}");

        SuggestMetadataResponse response = service.suggestMetadata(1L, List.of());

        assertEquals(List.of(), response.titles());
        assertEquals(List.of("s"), response.slugs());
        assertEquals(List.of(), response.categories());
        assertEquals(List.of("t"), response.tags());
    }

    @Test
    void suggestMetadata_既存カテゴリが取得できる場合はAI提案を既存カテゴリのみに絞り込む() {
        world.http.json("GET", "/api/internal/ai/projects/1/existing-categories", "[\"お知らせ\",\"技術\"]");
        world.llmReplies(
                "{\"titles\":[\"タイトル\"],\"slugs\":[\"slug\"],"
                + "\"categories\":[\"お知らせ\",\"存在しないカテゴリ\"],\"tags\":[]}");

        SuggestMetadataResponse response = service.suggestMetadata(1L, List.of());

        assertEquals(List.of("お知らせ"), response.categories());
        assertTrue(world.llmPrompts().get(0).contains("お知らせ, 技術"));
    }

    @Test
    void suggestMetadata_titlesが空でもslugsかtagsがあれば再試行せずそのまま返す() {
        world.llmReplies("{\"titles\":[],\"slugs\":[\"only-slug\"],\"categories\":[],\"tags\":[]}");
        assertEquals(List.of("only-slug"), service.suggestMetadata(1L, List.of()).slugs());
        assertEquals(1, world.llmRequests().size());

        world.llmReplies("{\"titles\":[],\"slugs\":[],\"categories\":[],\"tags\":[\"only-tag\"]}");
        assertEquals(List.of("only-tag"), service.suggestMetadata(1L, List.of()).tags());
        assertEquals(2, world.llmRequests().size());
    }

    @Test
    void suggestMetadata_波括弧が閉じていない_または順序が逆の応答は空のレスポンスで再試行する() {
        world.llmReplies("{\"titles\":[\"a\"");

        assertEquals(List.of(), service.suggestMetadata(1L, List.of()).titles());
        assertEquals(2, world.llmRequests().size());

        world.llmReplies("}逆順{");
        assertEquals(List.of(), service.suggestMetadata(1L, List.of()).titles());
        assertEquals(4, world.llmRequests().size());
    }

    @Test
    void suggestTitles_角括弧が閉じていない_または順序が逆の応答は空リストを返す() {
        world.llmReplies("[\"a\", \"b\"");
        assertEquals(List.of(), service.suggestTitles(1L, List.of()).titles());

        world.llmReplies("]逆順[");
        assertEquals(List.of(), service.suggestTitles(1L, List.of()).titles());
    }

    @Test
    void listExistingCategoriesWithParents_親カテゴリ名つきの一覧を返す() {
        world.http.json("GET", "/api/internal/ai/projects/1/existing-categories-with-parents",
                "[{\"name\":\"子\",\"parentName\":\"親\"}]");

        var categories = service.listExistingCategoriesWithParents(1L);

        assertEquals(1, categories.size());
        assertEquals("子", categories.get(0).name());
        assertEquals("親", categories.get(0).parentName());
    }

    @Test
    void listExistingTags_マスターサイトのタグ名一覧を返す() {
        world.http.json("GET", "/api/internal/ai/projects/1/existing-tags", "[\"Java\",\"AWS\"]");

        assertEquals(List.of("Java", "AWS"), service.listExistingTags(1L));
    }

    @Test
    void listExistingTags_マスターサイト未紐付けなら空リストを返す() {
        world.http.json("GET", "/api/internal/ai/projects/1/existing-tags", "[]");

        assertEquals(List.of(), service.listExistingTags(1L));
    }

    @Test
    void suggestMetadata_不正なJSONの場合はすべて空のレスポンスを返す() {
        world.llmReplies("JSONではない応答です");

        SuggestMetadataResponse response = service.suggestMetadata(1L, List.of());

        assertEquals(List.of(), response.titles());
        assertEquals(List.of(), response.slugs());
        assertEquals(List.of(), response.categories());
        assertEquals(List.of(), response.tags());
    }

    @Test
    void suggestMetadata_生成ジョブがplan_suggest_metadataとして記録される() {
        world.llmReplies("{}");

        service.suggestMetadata(1L, List.of());

        assertEquals(List.of("plan_suggest_metadata:running", "plan_suggest_metadata:done"), world.jobRows.log);
    }

    @Test
    void suggestMetadata_LLM呼び出しが失敗した場合ジョブがfailedになり例外を再送出する() {
        world.llmFails(500, "x");

        assertThrows(RuntimeException.class, () -> service.suggestMetadata(1L, List.of()));

        assertEquals(List.of("plan_suggest_metadata:running", "plan_suggest_metadata:failed"), world.jobRows.log);
    }

    @Test
    void assignIssueToActor_成功時はassigneeとURLを含む結果を返す() {
        world.githubAccess(1L, "test-token", "owner", "repo");
        world.http.json("GET", "/github/user", "{\"login\":\"octocat\"}");
        world.http.json("PATCH", GITHUB_ISSUES + "/42", "{\"number\":42,\"html_url\":\"https://github.com/owner/repo/issues/42\"}");

        AssignIssueResponse response = service.assignIssueToActor(1L, 10L, 42);

        assertEquals(42, response.issueNumber());
        assertEquals("https://github.com/owner/repo/issues/42", response.htmlUrl());
        assertEquals("octocat", response.assignedLogin());
        String body = world.http.requests("PATCH", GITHUB_ISSUES + "/42").get(0).bodyText();
        assertTrue(body.contains("\"assignees\":[\"octocat\"]") && body.contains("\"labels\":[\"in-progress\"]"), body);
    }

    @Test
    void assignIssueToActor_リポジトリ未設定の場合は例外をスローする() {
        githubNotConfigured();

        assertThrows(IllegalStateException.class, () -> service.assignIssueToActor(1L, 10L, 42));
    }

    @Test
    void GitHubの失敗はGithubApiExceptionとして伝わる() {
        world.githubAccess(1L, "test-token", "owner", "repo");
        world.http.status("GET", "/github/user", 401, "{}");

        assertThrows(GithubApiException.class, () -> service.assignIssueToActor(1L, 10L, 42));
    }

    // ---- issue #1503: 生成前にプロジェクトをLLM接続設定の範囲として渡す ----

    private void projectUsesOpenAi(long projectId, String key) {
        world.projectAiSettingsService.setLlmProvider(projectId, "OPENAI");
        world.projectAiSettingsService.setOpenAiApiKeyEncrypted(projectId, world.credentialCipher.encrypt(key));
        world.llmConfig(AiProvider.OPENAI, null, "", "gpt-4o-mini", List.of("gpt-4o-mini"), false);
    }

    @Test
    void chat_生成前にプロジェクトをLLM接続設定の範囲として渡す() {
        // 対象プロジェクトが先に宣言されていなければ、プロジェクトのAPIキーを引けず生成できない。
        projectUsesOpenAi(5L, "sk-project5");
        world.llmReplies("返答");

        service.chat(5L, List.of(), "こんにちは", null, null);

        assertEquals("Bearer sk-project5", world.llmRequests().get(0).header("Authorization"));
        assertEquals("/cloud/chat/completions", world.llmRequests().get(0).path());
    }

    @Test
    void suggestTitles_生成前にプロジェクトをLLM接続設定の範囲として渡す() {
        projectUsesOpenAi(5L, "sk-project5");
        world.llmReplies("[]");

        service.suggestTitles(5L, List.of());

        assertEquals("Bearer sk-project5", world.llmRequests().get(0).header("Authorization"));
    }

    @Test
    void suggestStructure_生成前にプロジェクトをLLM接続設定の範囲として渡す() {
        projectUsesOpenAi(5L, "sk-project5");
        world.llmReplies("## 見出し");

        service.suggestStructure(5L, List.of());

        assertEquals("Bearer sk-project5", world.llmRequests().get(0).header("Authorization"));
    }

    @Test
    void suggestMetadata_生成前にプロジェクトをLLM接続設定の範囲として渡す() {
        projectUsesOpenAi(5L, "sk-project5");
        world.llmReplies("{\"titles\":[\"a\"]}");

        service.suggestMetadata(5L, List.of());

        assertEquals("Bearer sk-project5", world.llmRequests().get(0).header("Authorization"));
    }

    // ---- プロジェクトの選択プロバイダー(issue #1643)

    @Test
    void chat_プロジェクトの選択プロバイダーで生成する_初回発言のタイトル生成も同じ() {
        projectUsesOpenAi(1L, "sk-project1");
        world.llmReplies("応答");

        service.chat(1L, List.of(), "こんにちは", null, null);

        assertEquals(2, world.llmRequests().size());
        world.llmRequests().forEach(request -> assertEquals("/cloud/chat/completions", request.path()));
    }

    @Test
    void chat_プロバイダー未設定ならシステム既定へ任せる() {
        world.llmReplies("応答");

        service.chat(1L, List.of(), "こんにちは", null, null);

        assertEquals(2, world.llmRequests().size());
        world.llmRequests().forEach(request -> assertEquals("/v1/chat/completions", request.path()));
    }

    @Test
    void suggestTitles_プロジェクトの選択プロバイダーで生成する() {
        world.projectAiSettingsService.setLlmProvider(1L, "CLAUDE");
        world.llmConfig(AiProvider.CLAUDE, null, "", "claude-x", List.of("claude-x"), false);

        // CLAUDEで生成しようとするが、プロジェクトにAPIキーが無いので、LLMへは送らずCLAUDEのキー未設定で失敗する。
        RuntimeException e = assertThrows(RuntimeException.class, () -> service.suggestTitles(1L, List.of()));

        assertTrue(e.getMessage().contains("CLAUDE"), e.getMessage());
        assertEquals(0, world.llmRequests().size());
    }

    @Test
    void suggestTitles_プロバイダー未設定ならシステム既定で生成する() {
        world.llmReplies("[\"a\"]");

        service.suggestTitles(1L, List.of());

        assertEquals("/v1/chat/completions", world.llmRequests().get(0).path());
    }

    @Test
    void suggestStructure_プロジェクトの選択プロバイダーで生成する() {
        projectUsesOpenAi(1L, "sk-project1");
        world.llmReplies("## 見出し");

        service.suggestStructure(1L, List.of());

        assertEquals("/cloud/chat/completions", world.llmRequests().get(0).path());
    }

    @Test
    void suggestMetadata_再試行を含めプロジェクトの選択プロバイダーで生成する() {
        projectUsesOpenAi(1L, "sk-project1");
        world.llmReplies(
                "{\"titles\":[],\"slugs\":[],\"categories\":[],\"tags\":[]}",
                "{\"titles\":[\"t\"],\"slugs\":[\"s\"],\"categories\":[],\"tags\":[\"x\"]}");

        service.suggestMetadata(1L, List.of());

        assertEquals(2, world.llmRequests().size());
        world.llmRequests().forEach(request -> assertEquals("/cloud/chat/completions", request.path()));
    }
}
