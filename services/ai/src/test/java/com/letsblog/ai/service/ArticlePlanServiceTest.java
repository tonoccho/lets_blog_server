package com.letsblog.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.ai.ai.LlmClient;
import com.letsblog.ai.client.LegacyApiBridgeClient;
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
import com.letsblog.ai.github.GithubClient;
import com.letsblog.ai.github.GithubIssue;
import com.letsblog.ai.github.GithubIssueSummary;
import com.letsblog.ai.github.GithubUser;
import com.letsblog.ai.repository.ArticlePlanSessionRepository;
import com.letsblog.ai.repository.GenerationJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ArticlePlanServiceの回帰テスト(issue #574でai-serviceへ移設)。
 *
 * <p>GitHubトークン解決とマスター環境サイトの既存カテゴリ/タグ取得はlegacy-apiに残る
 * Project/Site/CmsAdapter/ProjectApiKeyServiceへの直接依存から、{@link LegacyApiBridgeClient}経由の
 * 内部ブリッジ呼び出しへ置き換わった(ArticlePlanServiceのJavadoc参照)。そのため、これらに関する
 * テストはProject/Site/CmsAdapterのモックではなくLegacyApiBridgeClientのモックで表現している。
 */
@ExtendWith(MockitoExtension.class)
class ArticlePlanServiceTest {

    @Mock
    private LlmClient llmClient;

    @Mock
    private LlmModelService llmModelService;

    @Mock
    private WebSearchService webSearchService;

    @Mock
    private GenerationJobRepository generationJobRepository;

    @Mock
    private GithubClient githubClient;

    @Mock
    private ArticlePlanSessionRepository articlePlanSessionRepository;

    @Mock
    private LegacyApiBridgeClient legacyApiBridgeClient;

    @Mock
    private CurrentActorService currentActorService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // GenerationJob はミュータブルなため、save() 呼び出しの都度その時点のstatusをスナップショットして記録する
    private final List<String> savedStatuses = new ArrayList<>();

    private ArticlePlanService service() {
        lenient().when(generationJobRepository.save(any(GenerationJob.class))).thenAnswer(invocation -> {
            GenerationJob job = invocation.getArgument(0);
            savedStatuses.add(job.getStatus());
            return job;
        });
        lenient().when(articlePlanSessionRepository.save(any(ArticlePlanSession.class))).thenAnswer(invocation -> {
            ArticlePlanSession session = invocation.getArgument(0);
            if (session.getId() == null) {
                session.setId(100L);
            }
            return session;
        });
        lenient().when(llmModelService.getSelectedModel(any())).thenReturn("qwen2.5:7b-instruct");
        lenient().when(webSearchService.searchSafely(anyString(), any()))
                .thenReturn(WebSearchOutcome.failure("テストではWeb検索を行わない"));
        lenient().when(currentActorService.getAuthorizationHeader()).thenReturn(null);
        return new ArticlePlanService(
                llmClient, llmModelService, webSearchService, generationJobRepository, objectMapper,
                githubClient, articlePlanSessionRepository, legacyApiBridgeClient, currentActorService);
    }

    private void stubGithubAccess(Long projectId, Long userId, String token, String owner, String repo) {
        when(legacyApiBridgeClient.resolveGithubAccess(projectId, userId, null))
                .thenReturn(new LegacyApiBridgeClient.GithubAccess(token, owner, repo));
    }

    private void stubGithubAccessNotConfigured(Long projectId, Long userId) {
        when(legacyApiBridgeClient.resolveGithubAccess(projectId, userId, null))
                .thenThrow(new IllegalStateException(
                        "このプロジェクトにGitHubリポジトリが紐付けられていません。プロジェクト詳細ページから設定してください。"));
    }

    private ArticlePlanSession existingSession(Long id, Long projectId, List<PlanChatMessage> history) {
        ArticlePlanSession session = new ArticlePlanSession();
        session.setId(id);
        session.setProjectId(projectId);
        session.setTitle("既存の壁打ち");
        session.setHistory(toJson(history));
        session.setCreatedAt(LocalDateTime.of(2026, 1, 1, 10, 0));
        session.setUpdatedAt(LocalDateTime.of(2026, 1, 2, 10, 0));
        return session;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void chat_履歴とメッセージを含むプロンプトを組み立ててLLMを呼び出す() {
        ArticlePlanService service = service();
        List<PlanChatMessage> history = List.of(
                new PlanChatMessage("user", "AIブログの企画を考えたい"),
                new PlanChatMessage("assistant", "どんな読者層を想定していますか?")
        );
        when(articlePlanSessionRepository.findById(99L))
                .thenReturn(Optional.of(existingSession(99L, 1L, history)));
        when(llmClient.generate(anyString(), anyString())).thenReturn("初心者エンジニア向けはどうでしょう");

        PlanChatResponse response = service.chat(1L, history, "初心者向けにしたいです", 99L, null);

        assertEquals("初心者エンジニア向けはどうでしょう", response.reply());
        assertEquals(99L, response.sessionId());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generate(promptCaptor.capture(), anyString());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("AIブログの企画を考えたい"));
        assertTrue(prompt.contains("どんな読者層を想定していますか?"));
        assertTrue(prompt.contains("初心者向けにしたいです"));
        assertTrue(prompt.startsWith("System:"));
    }

    @Test
    void chat_生成ジョブがplan_chatとして記録される() {
        ArticlePlanService service = service();
        when(articlePlanSessionRepository.findById(99L))
                .thenReturn(Optional.of(existingSession(99L, 1L, List.of())));
        when(llmClient.generate(anyString(), anyString())).thenReturn("応答");

        service.chat(1L, List.of(), "テーマ", 99L, null);

        assertEquals(List.of("running", "done"), savedStatuses);

        ArgumentCaptor<GenerationJob> jobCaptor = ArgumentCaptor.forClass(GenerationJob.class);
        verify(generationJobRepository, atLeastOnce()).save(jobCaptor.capture());
        assertEquals("plan_chat", jobCaptor.getValue().getType());
    }

    @Test
    void chat_LLM呼び出しが失敗した場合ジョブがfailedになり例外を再送出する() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString())).thenThrow(new RuntimeException("接続できません"));

        assertThrows(RuntimeException.class, () -> service.chat(1L, List.of(), "テーマ", null, null));

        assertEquals(List.of("running", "failed"), savedStatuses);
    }

    @Test
    void chat_sessionId未指定の初回発言では新規セッションを作成しタイトルを生成する() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString()))
                .thenReturn("AIとのやり取りの応答")
                .thenReturn("生成されたタイトル");

        PlanChatResponse response = service.chat(1L, List.of(), "AIブログの企画を考えたい", null, null);

        assertEquals(100L, response.sessionId());
        verify(articlePlanSessionRepository, never()).findById(any());

        ArgumentCaptor<ArticlePlanSession> sessionCaptor = ArgumentCaptor.forClass(ArticlePlanSession.class);
        verify(articlePlanSessionRepository).save(sessionCaptor.capture());
        ArticlePlanSession saved = sessionCaptor.getValue();
        assertEquals(1L, saved.getProjectId());
        assertEquals("生成されたタイトル", saved.getTitle());
        assertTrue(saved.getHistory().contains("AIブログの企画を考えたい"));
        assertTrue(saved.getHistory().contains("AIとのやり取りの応答"));

        // 1回目: チャット応答生成、2回目: タイトル生成
        verify(llmClient, org.mockito.Mockito.times(2)).generate(anyString(), anyString());
    }

    @Test
    void chat_sessionId指定時は既存セッションの履歴に追記する() {
        ArticlePlanService service = service();
        List<PlanChatMessage> existingHistory = List.of(new PlanChatMessage("user", "前回の発言"));
        when(articlePlanSessionRepository.findById(5L))
                .thenReturn(Optional.of(existingSession(5L, 1L, existingHistory)));
        when(llmClient.generate(anyString(), anyString())).thenReturn("続きの応答");

        PlanChatResponse response = service.chat(1L, existingHistory, "追加の発言", 5L, null);

        assertEquals(5L, response.sessionId());
        // タイトル生成は行われないため、LLM呼び出しは1回のみ
        verify(llmClient, org.mockito.Mockito.times(1)).generate(anyString(), anyString());

        ArgumentCaptor<ArticlePlanSession> sessionCaptor = ArgumentCaptor.forClass(ArticlePlanSession.class);
        verify(articlePlanSessionRepository).save(sessionCaptor.capture());
        String savedHistory = sessionCaptor.getValue().getHistory();
        assertTrue(savedHistory.contains("前回の発言"));
        assertTrue(savedHistory.contains("追加の発言"));
        assertTrue(savedHistory.contains("続きの応答"));
    }

    @Test
    void chat_異なるプロジェクトのsessionIdを指定すると例外() {
        ArticlePlanService service = service();
        when(articlePlanSessionRepository.findById(5L))
                .thenReturn(Optional.of(existingSession(5L, 999L, List.of())));
        when(llmClient.generate(anyString(), anyString())).thenReturn("応答");

        assertThrows(ArticlePlanSessionNotFoundException.class,
                () -> service.chat(1L, List.of(), "発言", 5L, null));
    }

    @Test
    void chat_存在しないsessionIdを指定すると例外() {
        ArticlePlanService service = service();
        when(articlePlanSessionRepository.findById(5L)).thenReturn(Optional.empty());
        when(llmClient.generate(anyString(), anyString())).thenReturn("応答");

        assertThrows(ArticlePlanSessionNotFoundException.class,
                () -> service.chat(1L, List.of(), "発言", 5L, null));
    }

    @Test
    void chat_githubIssueNumber指定時は新規セッションにissue番号が保存される() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString()))
                .thenReturn("応答")
                .thenReturn("生成されたタイトル");

        PlanChatResponse response = service.chat(1L, List.of(), "issueの記事について壁打ち", null, 42);

        assertEquals(100L, response.sessionId());
        ArgumentCaptor<ArticlePlanSession> sessionCaptor = ArgumentCaptor.forClass(ArticlePlanSession.class);
        verify(articlePlanSessionRepository).save(sessionCaptor.capture());
        assertEquals(42, sessionCaptor.getValue().getGithubIssueNumber());
    }

    @Test
    void getSessionByIssue_issue番号に紐づく最新セッションの詳細を返す() {
        ArticlePlanService service = service();
        ArticlePlanSession session = existingSession(7L, 1L, List.of(new PlanChatMessage("user", "こんにちは")));
        session.setGithubIssueNumber(42);
        when(articlePlanSessionRepository.findFirstByProjectIdAndGithubIssueNumberOrderByUpdatedAtDesc(1L, 42))
                .thenReturn(Optional.of(session));

        ArticlePlanSessionDetailResponse response = service.getSessionByIssue(1L, 42);

        assertEquals(7L, response.id());
        assertEquals(42, response.githubIssueNumber());
        assertEquals(1, response.history().size());
    }

    @Test
    void getSessionByIssue_見つからない場合は例外() {
        ArticlePlanService service = service();
        when(articlePlanSessionRepository.findFirstByProjectIdAndGithubIssueNumberOrderByUpdatedAtDesc(1L, 42))
                .thenReturn(Optional.empty());

        assertThrows(ArticlePlanSessionNotFoundException.class, () -> service.getSessionByIssue(1L, 42));
    }

    @Test
    void listSessions_更新日時降順のサマリー一覧を返す() {
        ArticlePlanService service = service();
        ArticlePlanSession session = existingSession(1L, 1L, List.of());
        when(articlePlanSessionRepository.findByProjectIdOrderByUpdatedAtDesc(1L)).thenReturn(List.of(session));

        List<ArticlePlanSessionSummaryResponse> result = service.listSessions(1L);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).id());
        assertEquals("既存の壁打ち", result.get(0).title());
    }

    @Test
    void getSession_履歴を含む詳細を返す() {
        ArticlePlanService service = service();
        List<PlanChatMessage> history = List.of(new PlanChatMessage("user", "こんにちは"));
        when(articlePlanSessionRepository.findById(1L))
                .thenReturn(Optional.of(existingSession(1L, 1L, history)));

        ArticlePlanSessionDetailResponse response = service.getSession(1L, 1L);

        assertEquals(1L, response.id());
        assertEquals(1, response.history().size());
        assertEquals("こんにちは", response.history().get(0).content());
    }

    @Test
    void getSession_存在しない場合は例外() {
        ArticlePlanService service = service();
        when(articlePlanSessionRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ArticlePlanSessionNotFoundException.class, () -> service.getSession(1L, 1L));
    }

    @Test
    void suggestTitles_タイトル提案の指示がプロンプトに含まれる() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString())).thenReturn("[\"タイトル1\", \"タイトル2\"]");

        service.suggestTitles(1L, List.of(new PlanChatMessage("user", "テーマ案")));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generate(promptCaptor.capture(), anyString());
        assertTrue(promptCaptor.getValue().contains("JSON配列形式"));
    }

    @Test
    void suggestTitles_JSON配列を正しくパースする() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString())).thenReturn("[\"タイトル1\", \"タイトル2\"]");

        SuggestTitlesResponse response = service.suggestTitles(1L, List.of());

        assertEquals(List.of("タイトル1", "タイトル2"), response.titles());
    }

    @Test
    void suggestTitles_前後に説明文が付いていてもJSON配列部分だけを抽出する() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString()))
                .thenReturn("以下が提案です。\n[\"タイトルA\", \"タイトルB\"]\nご確認ください。");

        SuggestTitlesResponse response = service.suggestTitles(1L, List.of());

        assertEquals(List.of("タイトルA", "タイトルB"), response.titles());
    }

    @Test
    void suggestTitles_不正なJSONの場合は空リストを返す() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString())).thenReturn("JSONではない応答です");

        SuggestTitlesResponse response = service.suggestTitles(1L, List.of());

        assertEquals(List.of(), response.titles());
    }

    @Test
    void suggestTitles_6件以上の提案は5件に制限される() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString()))
                .thenReturn("[\"1\", \"2\", \"3\", \"4\", \"5\", \"6\", \"7\"]");

        SuggestTitlesResponse response = service.suggestTitles(1L, List.of());

        assertEquals(5, response.titles().size());
    }

    @Test
    void suggestTitles_生成ジョブがplan_suggest_titlesとして記録される() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString())).thenReturn("[]");

        service.suggestTitles(1L, List.of());

        assertEquals(List.of("running", "done"), savedStatuses);

        ArgumentCaptor<GenerationJob> jobCaptor = ArgumentCaptor.forClass(GenerationJob.class);
        verify(generationJobRepository, atLeastOnce()).save(jobCaptor.capture());
        assertEquals("plan_suggest_titles", jobCaptor.getValue().getType());
    }

    @Test
    void acceptPlan_リポジトリ未設定の場合は例外をスローする() {
        ArticlePlanService service = service();
        stubGithubAccessNotConfigured(1L, 10L);

        assertThrows(IllegalStateException.class, () -> service.acceptPlan(1L, 10L, List.of("タイトル")));
    }

    @Test
    void acceptPlan_成功時はissue番号とURLを含む結果を返す() {
        ArticlePlanService service = service();
        stubGithubAccess(1L, 10L, "test-token", "owner", "repo");
        when(githubClient.createIssue("test-token", "owner", "repo", "タイトル1", ""))
                .thenReturn(new GithubIssue(1, "https://github.com/owner/repo/issues/1"));

        AcceptPlanResponse response = service.acceptPlan(1L, 10L, List.of("タイトル1"));

        assertEquals(1, response.results().size());
        AcceptPlanResultItem item = response.results().get(0);
        assertEquals("タイトル1", item.title());
        assertEquals(1, item.issueNumber());
        assertEquals("https://github.com/owner/repo/issues/1", item.issueUrl());
        assertNull(item.error());
    }

    @Test
    void acceptPlan_一部のissue作成に失敗しても他のタイトルの登録は続行する() {
        ArticlePlanService service = service();
        stubGithubAccess(1L, 10L, "test-token", "owner", "repo");
        when(githubClient.createIssue("test-token", "owner", "repo", "成功タイトル", ""))
                .thenReturn(new GithubIssue(1, "https://github.com/owner/repo/issues/1"));
        when(githubClient.createIssue("test-token", "owner", "repo", "失敗タイトル", ""))
                .thenThrow(new GithubApiException("GitHub issueの作成に失敗しました"));

        AcceptPlanResponse response = service.acceptPlan(1L, 10L, List.of("成功タイトル", "失敗タイトル"));

        assertEquals(2, response.results().size());
        assertNull(response.results().get(0).error());
        assertEquals("GitHub issueの作成に失敗しました", response.results().get(1).error());
        assertNull(response.results().get(1).issueNumber());
    }

    @Test
    void suggestStructure_構成案の指示がプロンプトに含まれる() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString())).thenReturn("## 見出し1\n- 小見出しA");

        service.suggestStructure(1L, List.of(new PlanChatMessage("user", "テーマ案")));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generate(promptCaptor.capture(), anyString());
        assertTrue(promptCaptor.getValue().contains("構成案"));
    }

    @Test
    void suggestStructure_LLMの応答をそのまま返す() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString())).thenReturn("## 見出し1\n- 小見出しA");

        var response = service.suggestStructure(1L, List.of());

        assertEquals("## 見出し1\n- 小見出しA", response.structure());
    }

    @Test
    void suggestStructure_生成ジョブがplan_suggest_structureとして記録される() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString())).thenReturn("構成案");

        service.suggestStructure(1L, List.of());

        assertEquals(List.of("running", "done"), savedStatuses);
        ArgumentCaptor<GenerationJob> jobCaptor = ArgumentCaptor.forClass(GenerationJob.class);
        verify(generationJobRepository, atLeastOnce()).save(jobCaptor.capture());
        assertEquals("plan_suggest_structure", jobCaptor.getValue().getType());
    }

    @Test
    void getIssueDescription_GithubClientから取得したbodyを返す() {
        ArticlePlanService service = service();
        stubGithubAccess(1L, 10L, "test-token", "owner", "repo");
        when(githubClient.getIssueBody("test-token", "owner", "repo", 3)).thenReturn("## 現状の構成");

        var response = service.getIssueDescription(1L, 10L, 3);

        assertEquals("## 現状の構成", response.body());
    }

    @Test
    void getIssueDescription_リポジトリ未設定の場合は例外をスローする() {
        ArticlePlanService service = service();
        stubGithubAccessNotConfigured(1L, 10L);

        assertThrows(IllegalStateException.class, () -> service.getIssueDescription(1L, 10L, 3));
    }

    @Test
    void acceptStructure_成功時はissue番号とURLを含む結果を返す() {
        ArticlePlanService service = service();
        stubGithubAccess(1L, 10L, "test-token", "owner", "repo");
        when(githubClient.updateIssueBody("test-token", "owner", "repo", 42, "## 構成案"))
                .thenReturn(new GithubIssue(42, "https://github.com/owner/repo/issues/42"));

        var response = service.acceptStructure(1L, 10L, 42, "## 構成案");

        assertEquals(42, response.issueNumber());
        assertEquals("https://github.com/owner/repo/issues/42", response.issueUrl());
    }

    @Test
    void acceptStructure_リポジトリ未設定の場合は例外をスローする() {
        ArticlePlanService service = service();
        stubGithubAccessNotConfigured(1L, 10L);

        assertThrows(IllegalStateException.class, () -> service.acceptStructure(1L, 10L, 42, "## 構成案"));
    }

    @Test
    void listRepositoryIssues_GithubClientに委譲して結果を変換する() {
        ArticlePlanService service = service();
        stubGithubAccess(1L, 10L, "test-token", "owner", "repo");
        when(githubClient.listIssues("test-token", "owner", "repo", "open"))
                .thenReturn(List.of(new GithubIssueSummary(
                        3, "記事タイトル", "https://github.com/owner/repo/issues/3", "open", List.of())));

        List<RepositoryIssueResponse> result = service.listRepositoryIssues(1L, 10L, "open");

        assertEquals(1, result.size());
        assertEquals(3, result.get(0).number());
        assertEquals("記事タイトル", result.get(0).title());
        assertEquals("open", result.get(0).state());
    }

    @Test
    void listRepositoryIssues_リポジトリ未設定の場合は例外をスローする() {
        ArticlePlanService service = service();
        stubGithubAccessNotConfigured(1L, 10L);

        assertThrows(IllegalStateException.class, () -> service.listRepositoryIssues(1L, 10L, "open"));
    }

    @Test
    void suggestMetadata_JSONオブジェクトを正しくパースする() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString())).thenReturn(
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
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString()))
                .thenReturn("{\"titles\":[],\"slugs\":[],\"categories\":[],\"tags\":[]}")
                .thenReturn("{\"titles\":[\"タイトル案\"],\"slugs\":[\"article-slug\"],\"categories\":[],\"tags\":[\"tag1\"]}");

        SuggestMetadataResponse response = service.suggestMetadata(1L, List.of());

        assertEquals(List.of("タイトル案"), response.titles());
        assertEquals(List.of("article-slug"), response.slugs());
        assertEquals(List.of("tag1"), response.tags());
        verify(llmClient, org.mockito.Mockito.times(2)).generate(anyString(), anyString());
    }

    @Test
    void suggestMetadata_2回とも空のJSONなら空のまま返し3回目は試行しない() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString()))
                .thenReturn("{\"titles\":[],\"slugs\":[],\"categories\":[],\"tags\":[]}");

        SuggestMetadataResponse response = service.suggestMetadata(1L, List.of());

        assertEquals(List.of(), response.titles());
        assertEquals(List.of(), response.tags());
        verify(llmClient, org.mockito.Mockito.times(2)).generate(anyString(), anyString());
    }

    @Test
    void suggestMetadata_前後に説明文が付いていてもJSONオブジェクト部分だけを抽出する() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString())).thenReturn(
                "以下が提案です。\n{\"titles\":[\"タイトル\"],\"slugs\":[\"slug\"],\"categories\":[],\"tags\":[]}\nご確認ください。");

        SuggestMetadataResponse response = service.suggestMetadata(1L, List.of());

        assertEquals(List.of("タイトル"), response.titles());
        assertEquals(List.of("slug"), response.slugs());
    }

    @Test
    void suggestMetadata_既存カテゴリが取得できる場合はAI提案を既存カテゴリのみに絞り込む() {
        ArticlePlanService service = service();
        when(legacyApiBridgeClient.listExistingCategories(1L, null)).thenReturn(List.of("お知らせ", "技術"));
        when(llmClient.generate(anyString(), anyString())).thenReturn(
                "{\"titles\":[\"タイトル\"],\"slugs\":[\"slug\"],"
                + "\"categories\":[\"お知らせ\",\"存在しないカテゴリ\"],\"tags\":[]}");

        SuggestMetadataResponse response = service.suggestMetadata(1L, List.of());

        assertEquals(List.of("お知らせ"), response.categories());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).generate(promptCaptor.capture(), anyString());
        assertTrue(promptCaptor.getValue().contains("お知らせ, 技術"));
    }

    @Test
    void listExistingTags_マスターサイトのタグ名一覧を返す() {
        ArticlePlanService service = service();
        when(legacyApiBridgeClient.listExistingTags(1L, null)).thenReturn(List.of("Java", "AWS"));

        List<String> tags = service.listExistingTags(1L);

        assertEquals(List.of("Java", "AWS"), tags);
    }

    @Test
    void listExistingTags_マスターサイト未紐付けなら空リストを返す() {
        ArticlePlanService service = service();
        when(legacyApiBridgeClient.listExistingTags(1L, null)).thenReturn(List.of());

        List<String> tags = service.listExistingTags(1L);

        assertEquals(List.of(), tags);
    }

    @Test
    void suggestMetadata_不正なJSONの場合はすべて空のレスポンスを返す() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString())).thenReturn("JSONではない応答です");

        SuggestMetadataResponse response = service.suggestMetadata(1L, List.of());

        assertEquals(List.of(), response.titles());
        assertEquals(List.of(), response.slugs());
        assertEquals(List.of(), response.categories());
        assertEquals(List.of(), response.tags());
    }

    @Test
    void suggestMetadata_生成ジョブがplan_suggest_metadataとして記録される() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString())).thenReturn("{}");

        service.suggestMetadata(1L, List.of());

        assertEquals(List.of("running", "done"), savedStatuses);
        ArgumentCaptor<GenerationJob> jobCaptor = ArgumentCaptor.forClass(GenerationJob.class);
        verify(generationJobRepository, atLeastOnce()).save(jobCaptor.capture());
        assertEquals("plan_suggest_metadata", jobCaptor.getValue().getType());
    }

    @Test
    void suggestMetadata_LLM呼び出しが失敗した場合ジョブがfailedになり例外を再送出する() {
        ArticlePlanService service = service();
        when(llmClient.generate(anyString(), anyString())).thenThrow(new RuntimeException("接続できません"));

        assertThrows(RuntimeException.class, () -> service.suggestMetadata(1L, List.of()));

        assertEquals(List.of("running", "failed"), savedStatuses);
    }

    @Test
    void assignIssueToActor_成功時はassigneeとURLを含む結果を返す() {
        ArticlePlanService service = service();
        stubGithubAccess(1L, 10L, "test-token", "owner", "repo");
        when(githubClient.getAuthenticatedUser("test-token")).thenReturn(new GithubUser("octocat"));
        when(githubClient.assignAndLabelIssue(
                "test-token", "owner", "repo", 42, List.of("octocat"), List.of("in-progress")))
                .thenReturn(new GithubIssue(42, "https://github.com/owner/repo/issues/42"));

        AssignIssueResponse response = service.assignIssueToActor(1L, 10L, 42);

        assertEquals(42, response.issueNumber());
        assertEquals("https://github.com/owner/repo/issues/42", response.htmlUrl());
        assertEquals("octocat", response.assignedLogin());
    }

    @Test
    void assignIssueToActor_リポジトリ未設定の場合は例外をスローする() {
        ArticlePlanService service = service();
        stubGithubAccessNotConfigured(1L, 10L);

        assertThrows(IllegalStateException.class, () -> service.assignIssueToActor(1L, 10L, 42));
    }
}
