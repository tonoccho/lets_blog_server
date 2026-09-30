package com.letsblog.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.ai.BraveSearchResult;
import com.letsblog.ai.ai.LlmClient;
import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.domain.ReviewStepKey;
import com.letsblog.ai.dto.AiAskRequest;
import com.letsblog.ai.dto.AiAskResponse;
import com.letsblog.ai.dto.AiDraftRequest;
import com.letsblog.ai.dto.AiDraftResponse;
import com.letsblog.ai.dto.AiImagePromptResponse;
import com.letsblog.ai.dto.AiProofreadRequest;
import com.letsblog.ai.dto.AiProofreadResponse;
import com.letsblog.ai.dto.AiReviewStepSuggestionsResponse;
import com.letsblog.ai.dto.AiSectionRequest;
import com.letsblog.ai.dto.AiSectionResponse;
import com.letsblog.ai.dto.AiTagsRequest;
import com.letsblog.ai.dto.AiTagsResponse;
import com.letsblog.ai.dto.PlanChatMessage;
import com.letsblog.ai.dto.ReviewStepSuggestion;
import com.letsblog.ai.dto.SourceReference;
import com.letsblog.ai.repository.GenerationJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AiAssistServiceの回帰テスト(issue #574でai-serviceへ移設)。draft()/generateSection()への
 * Brave出典統合(検索成功/失敗/0件それぞれでsources・searchNoteが期待通りになること)を中心に検証する。
 *
 * <p>画像生成関連(generateImage/getImageOptions/generateImagePrompt)はこのIssueの移設対象外として
 * legacy-apiに残るため、それらのテストケースは移設していない(legacy-api側のAiAssistServiceTestに
 * 引き続き存在する)。代わりに、legacy-apiに残った画像生成コードからのブリッジ呼び出し先である
 * generateForBridge()のテストを追加している。
 */
@ExtendWith(MockitoExtension.class)
class AiAssistServiceTest {

    @Mock
    private LlmClient llmClient;
    @Mock
    private LlmModelService llmModelService;
    @Mock
    private GenerationJobRepository generationJobRepository;
    @Mock
    private WebSearchService webSearchService;
    @Mock
    private ArticlePlanService articlePlanService;
    @Mock
    private ReviewStepModelService reviewStepModelService;

    private AiAssistService service;

    @BeforeEach
    void setUp() {
        service = new AiAssistService(
                llmClient, llmModelService, generationJobRepository, webSearchService, new ObjectMapper(),
                articlePlanService, reviewStepModelService);

        lenient().when(generationJobRepository.save(any())).thenAnswer(inv -> {
            GenerationJob job = inv.getArgument(0);
            if (job.getId() == null) {
                job.setId(1L);
            }
            return job;
        });
    }

    @Test
    void generateForBridge_projectId指定時は選択中モデルを使う() {
        when(llmModelService.getSelectedModel(1L)).thenReturn("llama3");
        when(llmClient.generate("プロンプト", "llama3", AiProvider.CLAUDE)).thenReturn("結果");

        String result = service.generateForBridge(1L, "プロンプト", "CLAUDE");

        assertEquals("結果", result);
    }

    @Test
    void generateForBridge_生成前にプロジェクトをLLM接続設定の範囲として渡す() {
        when(llmClient.generate("プロンプト", null, null)).thenReturn("結果");

        service.generateForBridge(null, "プロンプト", null);
        org.mockito.Mockito.verify(llmClient).useProject(null);

        when(llmModelService.getSelectedModel(1L)).thenReturn("llama3");
        when(llmModelService.getSelectedProvider(1L)).thenReturn(AiProvider.OLLAMA);
        when(llmClient.generate("プロンプト", "llama3", AiProvider.OLLAMA)).thenReturn("結果");

        service.generateForBridge(1L, "プロンプト", null);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(llmClient);
        order.verify(llmClient).useProject(1L);
        order.verify(llmClient).generate("プロンプト", "llama3", AiProvider.OLLAMA);
    }

    @Test
    void generateForBridge_projectId未指定時はモデル解決を呼ばずシステム既定を使う() {
        when(llmClient.generate("プロンプト", null, null)).thenReturn("結果");

        String result = service.generateForBridge(null, "プロンプト", null);

        assertEquals("結果", result);
        org.mockito.Mockito.verify(llmModelService, org.mockito.Mockito.never()).getSelectedModel(any());
    }

    @Test
    void generateForBridge_providerOverride未指定でprojectId指定時はプロジェクトの選択中プロバイダーを使う() {
        when(llmModelService.getSelectedModel(1L)).thenReturn("llama3");
        when(llmModelService.getSelectedProvider(1L)).thenReturn(AiProvider.OPENAI);
        when(llmClient.generate("プロンプト", "llama3", AiProvider.OPENAI)).thenReturn("結果");

        String result = service.generateForBridge(1L, "プロンプト", null);

        assertEquals("結果", result);
        org.mockito.Mockito.verify(llmModelService).getSelectedProvider(1L);
    }

    @Test
    void generateImagePrompt_履歴が無い場合はSystem_Userのみのプロンプトを組み立てる() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("english, prompt");

        AiImagePromptResponse response = service.generateImagePrompt(null, null, "夕焼けの海辺", null);

        assertEquals("english, prompt", response.prompt());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("夕焼けの海辺"));
        assertTrue(promptCaptor.getValue().trim().endsWith("Assistant:"));
    }

    @Test
    void generateImagePrompt_履歴がある場合はuser_assistantを交えたプロンプトを組み立てる() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("english, prompt 2");

        List<PlanChatMessage> history = List.of(
                new PlanChatMessage("user", "猫を追加して"),
                new PlanChatMessage("assistant", "前回の生成結果"));

        AiImagePromptResponse response = service.generateImagePrompt(1L, history, "もっと明るく", null);

        assertEquals("english, prompt 2", response.prompt());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("User: 猫を追加して"));
        assertTrue(prompt.contains("Assistant: 前回の生成結果"));
        assertTrue(prompt.contains("もっと明るく"));
    }

    @Test
    void ask_検索成功時はsourcesを含み検索結果をプロンプトへ付加する() {
        when(webSearchService.searchSafely(anyString())).thenReturn(
                WebSearchOutcome.success(List.of(new BraveSearchResult("Title", "Desc", "https://example.com"))));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("回答結果");

        AiAskResponse response = service.ask(new AiAskRequest("Next.js 16の新機能は?", null, null));

        assertEquals("回答結果", response.result());
        assertEquals(1, response.sources().size());
        assertEquals("https://example.com", response.sources().get(0).url());
        assertNull(response.searchNote());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("参考のWeb検索結果"));
        assertTrue(promptCaptor.getValue().contains("Next.js 16の新機能は?"));
    }

    @Test
    void ask_検索失敗時はsourcesが空でsearchNoteが設定される() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("APIキー未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("回答結果");

        AiAskResponse response = service.ask(new AiAskRequest("質問", null, null));

        assertEquals(List.of(), response.sources());
        assertEquals("Web検索を利用できなかったため、出典なしで生成しています", response.searchNote());
    }

    @Test
    void ask_検索成功だが0件の場合はその旨のsearchNoteになる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.success(List.of()));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("回答結果");

        AiAskResponse response = service.ask(new AiAskRequest("質問", null, null));

        assertEquals(List.of(), response.sources());
        assertEquals("関連する検索結果が見つかりませんでした", response.searchNote());
    }

    @Test
    void draft_検索成功時はsourcesを含み検索結果をプロンプトへ付加する() {
        when(webSearchService.searchSafely(anyString())).thenReturn(
                WebSearchOutcome.success(List.of(new BraveSearchResult("Title", "Desc", "https://example.com"))));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("生成結果");

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて", null, null));

        assertEquals("生成結果", response.result());
        assertEquals(1, response.sources().size());
        assertEquals("https://example.com", response.sources().get(0).url());
        assertNull(response.searchNote());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("参考のWeb検索結果"));
    }

    @Test
    void draft_検索失敗時はsourcesが空でsearchNoteが設定される() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("APIキー未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("生成結果");

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて", null, null));

        assertEquals(List.of(), response.sources());
        assertEquals("Web検索を利用できなかったため、出典なしで生成しています", response.searchNote());
    }

    @Test
    void draft_検索成功だが0件の場合はその旨のsearchNoteになる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.success(List.of()));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("生成結果");

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて", null, null));

        assertEquals(List.of(), response.sources());
        assertEquals("関連する検索結果が見つかりませんでした", response.searchNote());
    }

    @Test
    void draft_不正なmodeは例外() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.draft(new AiDraftRequest("invalid", "text", null, null)));
    }

    @Test
    void generateSection_本文モードは直前の文脈を含むプロンプトを組み立てる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("セクション本文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", "導入部", "前の段落の文脈", "記事タイトル", null, null, null, null, null));

        assertEquals("セクション本文", response.result());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("前の段落の文脈"));
        assertTrue(promptCaptor.getValue().contains("記事タイトル"));
        assertTrue(promptCaptor.getValue().contains("導入部"));
    }

    @Test
    void generateSection_リード文モードは出典を含めて返す() {
        when(webSearchService.searchSafely(anyString())).thenReturn(
                WebSearchOutcome.success(List.of(new BraveSearchResult("Title", "Desc", "https://example.com"))));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("リード文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("lead", null, null, "記事タイトル", List.of("導入", "本編", "まとめ"), null, null, null, null));

        assertEquals("リード文", response.result());
        assertEquals(1, response.sources().size());
        assertNull(response.searchNote());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("導入"));
        assertTrue(promptCaptor.getValue().contains("まとめ"));
    }

    @Test
    void generateSection_サブセクション考慮モードは見出しとサブセクション一覧を含むプロンプトを組み立てる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("セクションリード文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("lead-subsections", "第2章 実装編", null, "記事タイトル",
                        List.of("設計", "実装", "テスト"), null, null, null, null));

        assertEquals("セクションリード文", response.result());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("第2章 実装編"));
        assertTrue(promptCaptor.getValue().contains("設計"));
        assertTrue(promptCaptor.getValue().contains("テスト"));
    }

    @Test
    void generateSection_messageが指定されると壁打ち形式のプロンプトを組み立てる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("再生成された本文");

        List<PlanChatMessage> history = List.of(
                new PlanChatMessage("assistant", "1回目の生成結果"));

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", "導入部", "前の段落の文脈", "記事タイトル", null,
                        history, "もっと短くして", null, null));

        assertEquals("再生成された本文", response.result());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("System:"));
        assertTrue(prompt.contains("1回目の生成結果"));
        assertTrue(prompt.contains("もっと短くして"));
        assertTrue(prompt.trim().endsWith("Assistant:"));
    }

    @Test
    void generateSection_不正なmodeは例外() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.generateSection(
                        new AiSectionRequest("invalid", "見出し", null, null, null, null, null, null, null)));
    }

    @Test
    void generateSection_articleTitle_precedingContext_headingが未指定なら既定値で組み立てる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("セクション本文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", null, null, null, null, null, null, null, null));

        assertEquals("セクション本文", response.result());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("(未設定)"));
        assertTrue(promptCaptor.getValue().contains("(なし)"));
    }

    @Test
    void generateSection_articleTitle_precedingContext_headingが空白のみなら既定値で組み立てる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("リード文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("lead-subsections", "   ", "   ", "   ", List.of(), null, null, null, null));

        assertEquals("リード文", response.result());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("(未設定)"));
        assertTrue(promptCaptor.getValue().contains("(なし)"));
    }

    @Test
    void generateSection_messageが空白のみの場合は壁打ちにならず通常のプロンプトを組み立てる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("セクション本文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", "導入部", "文脈", "記事タイトル", null, null, "   ", null, null));

        assertEquals("セクション本文", response.result());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(!promptCaptor.getValue().contains("System:"));
    }

    @Test
    void generateSection_壁打ちで履歴が無い場合は往復無しでプロンプトを組み立てる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("再生成結果");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", "導入部", "文脈", "記事タイトル", null, null, "もっと短く", null, null));

        assertEquals("再生成結果", response.result());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("System:"));
        assertTrue(prompt.contains("もっと短く"));
    }

    @Test
    void generateSection_壁打ちの履歴にuserロールが含まれる場合はUserとして整形する() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("再生成結果");

        List<PlanChatMessage> history = List.of(new PlanChatMessage("user", "最初の指示"));

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", "導入部", "文脈", "記事タイトル", null, history, "もっと短く", null, null));

        assertEquals("再生成結果", response.result());
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("User: 最初の指示"));
    }

    @Test
    void generateSection_articleTitleと見出しの合計が200文字を超える場合は検索クエリを先頭200文字に丸める() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("セクション本文");
        String longHeading = "あ".repeat(250);

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", longHeading, "文脈", "記事タイトル", null, null, null, null, null));

        assertEquals("セクション本文", response.result());
        ArgumentCaptor<String> searchQueryCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(webSearchService).searchSafely(searchQueryCaptor.capture());
        assertEquals(200, searchQueryCaptor.getValue().length());
    }

    @Test
    void suggestTags_projectId未指定なら既存タグを問い合わせずプロンプトはそのまま() {
        when(llmClient.generate(anyString(), any(), any()))
                .thenReturn("{\"categories\": [\"技術\"], \"tags\": [\"Java\", \"Spring\"]}");

        AiTagsResponse response = service.suggestTags(new AiTagsRequest("記事本文", null, null));

        assertEquals(List.of("技術"), response.categories());
        assertEquals(List.of("Java", "Spring"), response.tags());
        org.mockito.Mockito.verify(articlePlanService, org.mockito.Mockito.never()).listExistingTags(any());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("記事本文"));
        assertTrue(!promptCaptor.getValue().contains("既存タグ一覧"));
    }

    @Test
    void suggestTags_既存タグがあればプロンプトへ追加され結果も既存タグ優先で並び替えられる() {
        when(articlePlanService.listExistingTags(1L)).thenReturn(List.of("Java", "AWS"));
        when(llmClient.generate(anyString(), any(), any()))
                .thenReturn("{\"categories\": [], \"tags\": [\"Kotlin\", \"Java\", \"AWS\", \"Docker\"]}");

        AiTagsResponse response = service.suggestTags(new AiTagsRequest("記事本文", null, 1L));

        assertEquals(List.of("Java", "AWS", "Kotlin", "Docker"), response.tags());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("既存タグ一覧"));
        assertTrue(promptCaptor.getValue().contains("Java, AWS"));
    }

    @Test
    void suggestTags_不正なJSON応答は空のcategories_tagsにフォールバックする() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("これはJSONではありません");

        AiTagsResponse response = service.suggestTags(new AiTagsRequest("記事本文", null, null));

        assertEquals(List.of(), response.categories());
        assertEquals(List.of(), response.tags());
    }

    @Test
    void suggestTags_閉じ括弧のみでJSONオブジェクトが開始しない応答は空にフォールバックする() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("}invalid{");

        AiTagsResponse response = service.suggestTags(new AiTagsRequest("記事本文", null, null));

        assertEquals(List.of(), response.categories());
        assertEquals(List.of(), response.tags());
    }

    @Test
    void suggestTags_categories_tagsキーが無い応答は空のリストになる() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("{\"foo\": 1}");

        AiTagsResponse response = service.suggestTags(new AiTagsRequest("記事本文", null, null));

        assertEquals(List.of(), response.categories());
        assertEquals(List.of(), response.tags());
    }

    @Test
    void suggestTags_開き括弧のみで閉じ括弧が無い応答は空にフォールバックする() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("{\"categories\": [\"技術\"]");

        AiTagsResponse response = service.suggestTags(new AiTagsRequest("記事本文", null, null));

        assertEquals(List.of(), response.categories());
        assertEquals(List.of(), response.tags());
    }

    @Test
    void proofreadContent_正常なJSON配列応答を指摘一覧として返す() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"type\": \"typo\", \"originalText\": \"こんちには\", "
                        + "\"message\": \"誤字です\", \"suggestion\": \"こんにちは\"}]");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんちには世界", null, null));

        assertEquals(1, response.issues().size());
        assertEquals("typo", response.issues().get(0).type());
        assertEquals("こんちには", response.issues().get(0).originalText());
        assertEquals("誤字です", response.issues().get(0).message());
        assertEquals("こんにちは", response.issues().get(0).suggestion());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient).generate(promptCaptor.capture(), any(), any());
        assertTrue(promptCaptor.getValue().contains("こんちには世界"));
    }

    @Test
    void proofreadContent_本文に存在しないoriginalTextの指摘は除外する() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"type\": \"typo\", \"originalText\": \"本文に無い文字列\", "
                        + "\"message\": \"誤字です\", \"suggestion\": null}]");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null, null));

        assertEquals(List.of(), response.issues());
    }

    @Test
    void proofreadContent_不正なJSON応答は空の指摘一覧にフォールバックする() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("これはJSONではありません");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null, null));

        assertEquals(List.of(), response.issues());
    }

    @Test
    void proofreadContent_JSON配列でない応答は空の指摘一覧にフォールバックする() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("{}");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null, null));

        assertEquals(List.of(), response.issues());
    }

    @Test
    void proofreadContent_開き括弧のみで閉じ括弧が無い応答は空にフォールバックする() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("[{\"originalText\": \"こんにちは\"");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null, null));

        assertEquals(List.of(), response.issues());
    }

    @Test
    void proofreadContent_originalTextキーが無い指摘は除外する() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"type\": \"typo\", \"message\": \"originalTextが無い\"}]");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null, null));

        assertEquals(List.of(), response.issues());
    }

    @Test
    void proofreadContent_originalTextが空文字の指摘は除外する() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"type\": \"typo\", \"originalText\": \"\", \"message\": \"空文字\"}]");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null, null));

        assertEquals(List.of(), response.issues());
    }

    @Test
    void proofreadContent_suggestionキーが無い指摘はsuggestionがnullになる() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"type\": \"readability\", \"originalText\": \"こんにちは\", \"message\": \"読みにくい\"}]");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null, null));

        assertEquals(1, response.issues().size());
        assertNull(response.issues().get(0).suggestion());
    }

    @Test
    void proofreadContent_suggestionが明示的にnullの指摘はsuggestionがnullになる() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"type\": \"readability\", \"originalText\": \"こんにちは\", "
                        + "\"message\": \"読みにくい\", \"suggestion\": null}]");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null, null));

        assertEquals(1, response.issues().size());
        assertNull(response.issues().get(0).suggestion());
    }

    // ---- issue #1213: レビューステップ単位の指摘生成 ----

    @Test
    void generateReviewStepSuggestions_JAPANESEステップはそのステップキーを持つ指摘一覧を返す() {
        when(reviewStepModelService.resolveModel(1L, ReviewStepKey.JAPANESE)).thenReturn("model-a");
        when(reviewStepModelService.resolveProvider(1L, ReviewStepKey.JAPANESE)).thenReturn(AiProvider.OPENAI);
        when(llmClient.generate(anyString(), eq("model-a"), eq(AiProvider.OPENAI))).thenReturn(
                "[{\"originalText\": \"ら抜き言葉の例\", \"message\": \"ら抜き言葉です\"}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "これはら抜き言葉の例です");

        assertEquals(1, response.suggestions().size());
        assertEquals("JAPANESE", response.suggestions().get(0).stepKey());
        assertEquals("ら抜き言葉の例", response.suggestions().get(0).originalText());
        assertEquals("ら抜き言葉です", response.suggestions().get(0).message());
    }

    @Test
    void generateReviewStepSuggestions_生成前にプロジェクトをLLM接続設定の範囲として渡す() {
        when(reviewStepModelService.resolveModel(3L, ReviewStepKey.JAPANESE)).thenReturn("model-a");
        when(reviewStepModelService.resolveProvider(3L, ReviewStepKey.JAPANESE)).thenReturn(AiProvider.OLLAMA);
        when(llmClient.generate(anyString(), eq("model-a"), eq(AiProvider.OLLAMA))).thenReturn("[]");

        service.generateReviewStepSuggestions(3L, ReviewStepKey.JAPANESE, "本文");

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(llmClient);
        order.verify(llmClient).useProject(3L);
        order.verify(llmClient).generate(anyString(), eq("model-a"), eq(AiProvider.OLLAMA));
    }

    @Test
    void factCheck_抽出と判定の前にプロジェクトをLLM接続設定の範囲として渡す() {
        when(reviewStepModelService.resolveModel(4L, ReviewStepKey.FACT_CHECK)).thenReturn("model-f");
        when(reviewStepModelService.resolveProvider(4L, ReviewStepKey.FACT_CHECK)).thenReturn(AiProvider.OLLAMA);
        when(webSearchService.searchSafely(anyString(), eq(4L)))
                .thenReturn(WebSearchOutcome.success(List.of(searchResult(1))));
        when(llmClient.generate(anyString(), eq("model-f"), eq(AiProvider.OLLAMA))).thenReturn(FACT_EXTRACTION, "[]");

        service.generateReviewStepSuggestions(4L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(llmClient);
        order.verify(llmClient).useProject(4L);
        order.verify(llmClient, org.mockito.Mockito.atLeastOnce())
                .generate(anyString(), eq("model-f"), eq(AiProvider.OLLAMA));
    }

    @Test
    void generateReviewStepSuggestions_PROOFREADINGステップはそのステップキーを持つ指摘一覧を返す() {
        when(reviewStepModelService.resolveModel(1L, ReviewStepKey.PROOFREADING)).thenReturn("model-b");
        when(reviewStepModelService.resolveProvider(1L, ReviewStepKey.PROOFREADING)).thenReturn(AiProvider.CLAUDE);
        when(llmClient.generate(anyString(), eq("model-b"), eq(AiProvider.CLAUDE))).thenReturn(
                "[{\"originalText\": \"衍字の例\", \"message\": \"衍字があります\"}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.PROOFREADING, "これは衍字の例です");

        assertEquals(1, response.suggestions().size());
        assertEquals("PROOFREADING", response.suggestions().get(0).stepKey());
        assertEquals("衍字の例", response.suggestions().get(0).originalText());
    }

    @Test
    void generateReviewStepSuggestions_本文に存在しないoriginalTextの指摘は除外する() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"originalText\": \"本文に無い文字列\", \"message\": \"指摘\"}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "こんにちは世界");

        assertEquals(List.of(), response.suggestions());
    }

    @Test
    void generateReviewStepSuggestions_同じステップキーと本文と指摘内容なら識別子が一致する() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"originalText\": \"対象の指摘\", \"message\": \"指摘内容\"}]");

        AiReviewStepSuggestionsResponse first =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文中に対象の指摘があります");
        AiReviewStepSuggestionsResponse second =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文中に対象の指摘があります");

        assertEquals(1, first.suggestions().size());
        assertEquals(first.suggestions().get(0).id(), second.suggestions().get(0).id());
    }

    @Test
    void generateReviewStepSuggestions_ステップキーが異なれば同じ引用_同じ内容でも識別子が異なる() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"originalText\": \"対象の指摘\", \"message\": \"指摘内容\"}]");

        AiReviewStepSuggestionsResponse japanese =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文中に対象の指摘があります");
        AiReviewStepSuggestionsResponse proofreading =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.PROOFREADING, "本文中に対象の指摘があります");

        assertNotEquals(japanese.suggestions().get(0).id(), proofreading.suggestions().get(0).id());
    }

    @Test
    void generateReviewStepSuggestions_READER_PERSPECTIVEステップはそのステップキーを持つ指摘一覧を返す() {
        when(reviewStepModelService.resolveModel(1L, ReviewStepKey.READER_PERSPECTIVE)).thenReturn("model-r");
        when(reviewStepModelService.resolveProvider(1L, ReviewStepKey.READER_PERSPECTIVE))
                .thenReturn(AiProvider.OLLAMA);
        when(llmClient.generate(anyString(), eq("model-r"), eq(AiProvider.OLLAMA))).thenReturn(
                "[{\"originalText\": \"CQRSを導入した\", \"message\": \"CQRSの説明が無く初学者には飛躍です\"}]");

        AiReviewStepSuggestionsResponse response = service.generateReviewStepSuggestions(
                1L, ReviewStepKey.READER_PERSPECTIVE, "そこでCQRSを導入したので高速になった");

        assertEquals(1, response.suggestions().size());
        assertEquals("READER_PERSPECTIVE", response.suggestions().get(0).stepKey());
        assertEquals("CQRSを導入した", response.suggestions().get(0).originalText());
        assertEquals("CQRSの説明が無く初学者には飛躍です", response.suggestions().get(0).message());
    }

    @Test
    void generateReviewStepSuggestions_STYLEステップはそのステップキーを持つ指摘一覧を返す() {
        when(reviewStepModelService.resolveModel(1L, ReviewStepKey.STYLE)).thenReturn("model-s");
        when(reviewStepModelService.resolveProvider(1L, ReviewStepKey.STYLE)).thenReturn(AiProvider.CLAUDE);
        when(llmClient.generate(anyString(), eq("model-s"), eq(AiProvider.CLAUDE))).thenReturn(
                "[{\"originalText\": \"である。\", \"message\": \"文末が「です・ます」調と混在しています\"}]");

        AiReviewStepSuggestionsResponse response = service.generateReviewStepSuggestions(
                1L, ReviewStepKey.STYLE, "これは例です。あれは例である。");

        assertEquals(1, response.suggestions().size());
        assertEquals("STYLE", response.suggestions().get(0).stepKey());
        assertEquals("である。", response.suggestions().get(0).originalText());
    }

    @Test
    void generateReviewStepSuggestions_READER_PERSPECTIVEとSTYLEも本文に存在しないoriginalTextは除外する() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"originalText\": \"本文に無い文字列\", \"message\": \"指摘\"}]");

        assertEquals(List.of(), service.generateReviewStepSuggestions(
                1L, ReviewStepKey.READER_PERSPECTIVE, "こんにちは世界").suggestions());
        assertEquals(List.of(), service.generateReviewStepSuggestions(
                1L, ReviewStepKey.STYLE, "こんにちは世界").suggestions());
    }

    @Test
    void generateReviewStepSuggestions_READER_PERSPECTIVEとSTYLEも同じ本文なら識別子が一致し_ステップ間では異なる() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"originalText\": \"対象の指摘\", \"message\": \"指摘内容\"}]");
        String body = "本文中に対象の指摘があります";

        for (ReviewStepKey key : List.of(ReviewStepKey.READER_PERSPECTIVE, ReviewStepKey.STYLE)) {
            String first = service.generateReviewStepSuggestions(1L, key, body).suggestions().get(0).id();
            String second = service.generateReviewStepSuggestions(1L, key, body).suggestions().get(0).id();
            assertEquals(first, second);
        }
        assertNotEquals(
                service.generateReviewStepSuggestions(1L, ReviewStepKey.READER_PERSPECTIVE, body)
                        .suggestions().get(0).id(),
                service.generateReviewStepSuggestions(1L, ReviewStepKey.STYLE, body).suggestions().get(0).id());
    }

    @Test
    void generateReviewStepSuggestions_READER_PERSPECTIVEとSTYLEはそれぞれ自分の観点だけを問うプロンプトを送る() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("[]");

        service.generateReviewStepSuggestions(1L, ReviewStepKey.READER_PERSPECTIVE, "本文A");
        service.generateReviewStepSuggestions(1L, ReviewStepKey.STYLE, "本文B");

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.times(2)).generate(prompts.capture(), any(), any());
        String reader = prompts.getAllValues().get(0);
        String style = prompts.getAllValues().get(1);
        assertTrue(reader.contains("前提知識") && reader.contains("説明不足"));
        assertTrue(reader.endsWith("本文A\n"));
        assertTrue(style.contains("文末表現") && style.contains("受動態") && style.contains("一文の長さ"));
        assertTrue(style.endsWith("本文B\n"));
        // 観点の取り違え(スタブのマーカー判定の前提)を防ぐ: 互いの・他ステップの固有語を含まない
        assertTrue(!reader.contains("文末表現") && !style.contains("前提知識"));
        for (String other : List.of("ら抜き言葉", "衍字", "事実確認")) {
            assertTrue(!reader.contains(other) && !style.contains(other), other);
        }
    }

    @Test
    void generateReviewStepSuggestions_LLM呼び出しが例外を投げたらジョブを失敗にして再送出する() {
        when(llmClient.generate(anyString(), any(), any())).thenThrow(new RuntimeException("LLM呼び出し失敗"));

        assertThrows(RuntimeException.class,
                () -> service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文"));
    }

    @Test
    void generateReviewStepSuggestions_JSON配列でない応答は空の指摘一覧にフォールバックする() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("{}");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文");

        assertEquals(List.of(), response.suggestions());
    }

    /**
     * issue #1222 レビュー(2026-09-28)対応: 非JSON応答は既存の{@code parseProofreadResponse}と同じ
     * 契約(例外を伝播させず空配列で返す)を踏襲するとIssueが明示しているため、このケースを
     * failJob()に変更することはしない(#1213/#1214が確立した既存挙動を踏襲する意図的な判断)。
     * ただし「generation_jobsに記録が残る」というRequirementsの文言が実際に満たされている
     * ことを、思い込みではなく検証で残す。ジョブは"done"として記録される
     * (=失敗としてではないが、記録自体は残る)。
     */
    @Test
    void generateReviewStepSuggestions_JSON配列でない応答でもgeneration_jobsには完了として記録が残る() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("{}");

        service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文");

        ArgumentCaptor<GenerationJob> captor = ArgumentCaptor.forClass(GenerationJob.class);
        org.mockito.Mockito.verify(generationJobRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        GenerationJob lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals("done", lastSaved.getStatus());
    }

    @Test
    void generateReviewStepSuggestions_不正なJSON応答は空の指摘一覧にフォールバックする() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("これはJSONではありません");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文");

        assertEquals(List.of(), response.suggestions());
    }

    @Test
    void generateReviewStepSuggestions_originalTextが無い指摘は除外する() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"message\": \"originalTextが無い指摘\"}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文");

        assertEquals(List.of(), response.suggestions());
    }

    @Test
    void generateReviewStepSuggestions_originalTextが空文字の指摘は除外する() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"originalText\": \"\", \"message\": \"空文字の指摘\"}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文");

        assertEquals(List.of(), response.suggestions());
    }

    @Test
    void generateReviewStepSuggestions_messageが無い指摘でも識別子を計算して返す() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"originalText\": \"対象の指摘\"}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文中に対象の指摘があります");

        assertEquals(1, response.suggestions().size());
        assertNull(response.suggestions().get(0).message());
        assertNotEquals("", response.suggestions().get(0).id());
    }

    // ---- issue #1214: 校閲(FACT_CHECK)ステップ。抽出 → Web検索 → 判定の2回のLLM呼び出し ----

    private static final String FACT_TEXT = "東京タワーの高さは333メートルです。開業は1958年です。";

    private static final String FACT_EXTRACTION =
            "[{\"claim\": \"東京タワーの高さは333メートル\", \"query\": \"東京タワー 高さ\"}]";

    private static BraveSearchResult searchResult(int n) {
        return new BraveSearchResult("出典" + n, "説明" + n, "https://example.test/" + n);
    }

    @Test
    void factCheck_検索結果を根拠にした指摘が出典つきで返りスキップではない() {
        when(webSearchService.searchSafely("東京タワー 高さ", 1L)).thenReturn(
                WebSearchOutcome.success(List.of(searchResult(1), searchResult(2))));
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                FACT_EXTRACTION,
                "[{\"originalText\": \"東京タワーの高さは333メートル\", \"message\": \"333mではなく332.6mです\","
                        + " \"sources\": [2]}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(1, response.suggestions().size());
        ReviewStepSuggestion suggestion = response.suggestions().get(0);
        assertEquals("FACT_CHECK", suggestion.stepKey());
        assertEquals("東京タワーの高さは333メートル", suggestion.originalText());
        assertEquals(List.of(new SourceReference("出典2", "https://example.test/2")), suggestion.sources());
        assertEquals(Boolean.FALSE, response.skipped());
        assertNull(response.skipReason());
    }

    @Test
    void factCheck_プロジェクトのモデルで抽出と判定の2回を呼びジョブをfact_checkとして記録する() {
        when(reviewStepModelService.resolveModel(1L, ReviewStepKey.FACT_CHECK)).thenReturn("model-f");
        when(reviewStepModelService.resolveProvider(1L, ReviewStepKey.FACT_CHECK)).thenReturn(AiProvider.CLAUDE);
        when(webSearchService.searchSafely(anyString(), eq(1L)))
                .thenReturn(WebSearchOutcome.success(List.of(searchResult(1))));
        when(llmClient.generate(anyString(), eq("model-f"), eq(AiProvider.CLAUDE))).thenReturn(FACT_EXTRACTION, "[]");

        service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.times(2))
                .generate(anyString(), eq("model-f"), eq(AiProvider.CLAUDE));
        ArgumentCaptor<GenerationJob> captor = ArgumentCaptor.forClass(GenerationJob.class);
        org.mockito.Mockito.verify(generationJobRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        GenerationJob last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals("llm_review_step_fact_check", last.getType());
        assertEquals("done", last.getStatus());
    }

    @Test
    void factCheck_APIキー未設定でWeb検索が使えないときはスキップと理由を返し判定へ進まない() {
        String reason = "Brave Search APIキーが設定されていません";
        when(webSearchService.searchSafely(anyString(), eq(1L))).thenReturn(WebSearchOutcome.failure(reason));
        when(llmClient.generate(anyString(), any(), any())).thenReturn(FACT_EXTRACTION);

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(Boolean.TRUE, response.skipped());
        assertTrue(response.skipReason().contains(reason));
        assertEquals(List.of(), response.suggestions());
        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.times(1)).generate(anyString(), any(), any());
    }

    @Test
    void factCheck_Web検索が失敗した場合は検索失敗の理由つきでスキップする() {
        when(webSearchService.searchSafely(anyString(), eq(1L)))
                .thenReturn(WebSearchOutcome.failure("Brave Search呼び出しに失敗しました: 500"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn(FACT_EXTRACTION);

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(Boolean.TRUE, response.skipped());
        assertTrue(response.skipReason().contains("500"));
    }

    @Test
    void factCheck_失敗理由が無い検索失敗でも理由文字列は空にならない() {
        when(webSearchService.searchSafely(anyString(), eq(1L))).thenReturn(WebSearchOutcome.failure(null));
        when(llmClient.generate(anyString(), any(), any())).thenReturn(FACT_EXTRACTION);

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(Boolean.TRUE, response.skipped());
        assertTrue(!response.skipReason().isBlank());
    }

    @Test
    void factCheck_検索が途中で失敗したら以降の検索を行わずスキップする() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"claim\": \"東京タワーの高さは333メートル\", \"query\": \"q1\"},"
                        + " {\"claim\": \"開業は1958年\", \"query\": \"q2\"}]");
        when(webSearchService.searchSafely("q1", 1L)).thenReturn(WebSearchOutcome.failure("失敗"));

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(Boolean.TRUE, response.skipped());
        org.mockito.Mockito.verify(webSearchService, org.mockito.Mockito.never()).searchSafely(eq("q2"), any());
    }

    @Test
    void factCheck_検索が失敗したジョブは完了として記録されスキップ情報を残す() {
        when(webSearchService.searchSafely(anyString(), eq(1L))).thenReturn(WebSearchOutcome.failure("理由X"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn(FACT_EXTRACTION);

        service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        ArgumentCaptor<GenerationJob> captor = ArgumentCaptor.forClass(GenerationJob.class);
        org.mockito.Mockito.verify(generationJobRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        GenerationJob last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals("done", last.getStatus());
        assertTrue(last.getResultPayload().contains("理由X"));
    }

    @Test
    void factCheck_抽出された主張が無ければ検索も判定もせず指摘0件でスキップではない() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("[]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of(), response.suggestions());
        assertEquals(Boolean.FALSE, response.skipped());
        org.mockito.Mockito.verifyNoInteractions(webSearchService);
        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.times(1)).generate(anyString(), any(), any());
    }

    @Test
    void factCheck_抽出結果がJSONでなければ指摘0件でスキップではない() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("これはJSONではありません");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of(), response.suggestions());
        assertEquals(Boolean.FALSE, response.skipped());
    }

    @Test
    void factCheck_抽出結果が配列でなければ指摘0件でスキップではない() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("{}");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of(), response.suggestions());
        assertEquals(Boolean.FALSE, response.skipped());
    }

    @Test
    void factCheck_queryが無い主張は主張文で検索し空の主張は無視する() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"claim\": \"開業は1958年\"}, {\"query\": \"主張なし\"}, {\"claim\": \"\"}]", "[]");
        when(webSearchService.searchSafely("開業は1958年", 1L))
                .thenReturn(WebSearchOutcome.success(List.of(searchResult(1))));

        service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        org.mockito.Mockito.verify(webSearchService).searchSafely("開業は1958年", 1L);
        org.mockito.Mockito.verify(webSearchService, org.mockito.Mockito.times(1)).searchSafely(anyString(), any());
    }

    @Test
    void factCheck_検索する主張は最大3件までに抑える() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"claim\": \"a\", \"query\": \"q1\"}, {\"claim\": \"b\", \"query\": \"q2\"},"
                        + " {\"claim\": \"c\", \"query\": \"q3\"}, {\"claim\": \"d\", \"query\": \"q4\"}]",
                "[]");
        when(webSearchService.searchSafely(anyString(), eq(1L)))
                .thenReturn(WebSearchOutcome.success(List.of(searchResult(1))));

        service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        org.mockito.Mockito.verify(webSearchService, org.mockito.Mockito.times(3)).searchSafely(anyString(), eq(1L));
    }

    @Test
    void factCheck_出典を持たない指摘と範囲外の出典番号だけの指摘は除外する() {
        when(webSearchService.searchSafely(anyString(), eq(1L)))
                .thenReturn(WebSearchOutcome.success(List.of(searchResult(1))));
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                FACT_EXTRACTION,
                "[{\"originalText\": \"東京タワーの高さは333メートル\", \"message\": \"出典なし\"},"
                        + " {\"originalText\": \"開業は1958年\", \"message\": \"範囲外\", \"sources\": [0, 9]},"
                        + " {\"originalText\": \"東京タワー\", \"message\": \"型違い\", \"sources\": \"1\"}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of(), response.suggestions());
        assertEquals(Boolean.FALSE, response.skipped());
    }

    @Test
    void factCheck_本文に実在しない引用の指摘は出典があっても除外する() {
        when(webSearchService.searchSafely(anyString(), eq(1L)))
                .thenReturn(WebSearchOutcome.success(List.of(searchResult(1))));
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                FACT_EXTRACTION,
                "[{\"originalText\": \"本文に無い\", \"message\": \"m\", \"sources\": [1]},"
                        + " {\"message\": \"originalTextなし\", \"sources\": [1]}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of(), response.suggestions());
    }

    @Test
    void factCheck_判定結果が不正なら指摘0件でスキップではない() {
        when(webSearchService.searchSafely(anyString(), eq(1L)))
                .thenReturn(WebSearchOutcome.success(List.of(searchResult(1))));
        when(llmClient.generate(anyString(), any(), any())).thenReturn(FACT_EXTRACTION, "壊れた応答");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of(), response.suggestions());
        assertEquals(Boolean.FALSE, response.skipped());
    }

    @Test
    void factCheck_判定結果が配列でなければ指摘0件でスキップではない() {
        when(webSearchService.searchSafely(anyString(), eq(1L)))
                .thenReturn(WebSearchOutcome.success(List.of(searchResult(1))));
        when(llmClient.generate(anyString(), any(), any())).thenReturn(FACT_EXTRACTION, "{}");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of(), response.suggestions());
    }

    @Test
    void factCheck_複数の主張の検索結果は通し番号で判定へ渡され出典番号で引ける() {
        when(webSearchService.searchSafely("q1", 1L))
                .thenReturn(WebSearchOutcome.success(List.of(searchResult(1))));
        when(webSearchService.searchSafely("q2", 1L))
                .thenReturn(WebSearchOutcome.success(List.of(searchResult(2))));
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"claim\": \"a\", \"query\": \"q1\"}, {\"claim\": \"b\", \"query\": \"q2\"}]",
                "[{\"originalText\": \"開業は1958年\", \"message\": \"m\", \"sources\": [2, 1]}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of(new SourceReference("出典2", "https://example.test/2"),
                new SourceReference("出典1", "https://example.test/1")), response.suggestions().get(0).sources());
    }

    @Test
    void factCheck_LLM呼び出しが例外を投げたらジョブを失敗にして再送出する() {
        when(llmClient.generate(anyString(), any(), any())).thenThrow(new RuntimeException("LLM呼び出し失敗"));

        assertThrows(RuntimeException.class,
                () -> service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT));
    }

    @Test
    void 既存ステップの応答にはスキップ情報も出典も付かない() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"originalText\": \"本文\", \"message\": \"指摘\"}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文");

        assertNull(response.skipped());
        assertNull(response.skipReason());
        assertNull(response.suggestions().get(0).sources());
        org.mockito.Mockito.verifyNoInteractions(webSearchService);
    }

    @Test
    void 応答のJSONは既存ステップではスキップ情報と出典のキーを含まずFACT_CHECKでは含む() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String legacy = mapper.writeValueAsString(new AiReviewStepSuggestionsResponse(
                List.of(new ReviewStepSuggestion("i", "JAPANESE", "o", "m"))));
        assertTrue(!legacy.contains("skipped") && !legacy.contains("skipReason") && !legacy.contains("sources"));

        String factCheck = mapper.writeValueAsString(new AiReviewStepSuggestionsResponse(
                List.of(new ReviewStepSuggestion("i", "FACT_CHECK", "o", "m",
                        List.of(new SourceReference("t", "u")))), false, null));
        assertTrue(factCheck.contains("\"skipped\":false") && factCheck.contains("\"sources\""));
        assertTrue(!factCheck.contains("skipReason"));
    }

    @Test
    void factCheck_検索は成功したが結果が1件も無ければ裏取りできないためスキップし判定へ進まない() {
        when(webSearchService.searchSafely(anyString(), eq(1L))).thenReturn(WebSearchOutcome.success(List.of()));
        when(llmClient.generate(anyString(), any(), any())).thenReturn(FACT_EXTRACTION);

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(Boolean.TRUE, response.skipped());
        assertTrue(response.skipReason().contains("検索結果が見つからず"));
        assertEquals(List.of(), response.suggestions());
        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.times(1)).generate(anyString(), any(), any());
    }

    // ---- issue #1495: 執筆支援5機能がプロジェクト単位のモデル・プロバイダー選択を使う ----

    private static final String[] PROJECT_SELECTION_FEATURES = {"ask", "draft", "section", "tags", "proofread"};

    /** 5機能のいずれかを、指定のprojectId・providerで実行する(LlmClientは"[]"を返す)。 */
    private void invokeFeature(String feature, Long projectId, String provider) {
        lenient().when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.success(List.of()));
        switch (feature) {
            case "ask" -> service.ask(new AiAskRequest("質問", provider, projectId));
            case "draft" -> service.draft(new AiDraftRequest("draft", "本文", provider, projectId));
            case "section" -> service.generateSection(new AiSectionRequest(
                    "body", "見出し", "文脈", "タイトル", null, null, null, provider, projectId));
            case "tags" -> service.suggestTags(new AiTagsRequest("本文", provider, projectId));
            case "proofread" -> service.proofreadContent(new AiProofreadRequest("本文", provider, projectId));
            default -> throw new IllegalArgumentException(feature);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ask", "draft", "section", "tags", "proofread"})
    void 執筆支援_projectId指定かつprovider未指定ならプロジェクトの選択中モデルとプロバイダーを使う(String feature) {
        when(llmModelService.getSelectedModel(7L)).thenReturn("project-model");
        when(llmModelService.getSelectedProvider(7L)).thenReturn(AiProvider.OPENAI);
        when(llmClient.generate(anyString(), eq("project-model"), eq(AiProvider.OPENAI))).thenReturn("[]");

        invokeFeature(feature, 7L, null);

        org.mockito.Mockito.verify(llmClient).generate(anyString(), eq("project-model"), eq(AiProvider.OPENAI));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ask", "draft", "section", "tags", "proofread"})
    void 執筆支援_provider指定はプロジェクトの選択中プロバイダーより優先される(String feature) {
        when(llmModelService.getSelectedModel(7L)).thenReturn("project-model");
        when(llmClient.generate(anyString(), eq("project-model"), eq(AiProvider.CLAUDE))).thenReturn("[]");

        invokeFeature(feature, 7L, "CLAUDE");

        org.mockito.Mockito.verify(llmClient).generate(anyString(), eq("project-model"), eq(AiProvider.CLAUDE));
        org.mockito.Mockito.verify(llmModelService, org.mockito.Mockito.never()).getSelectedProvider(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ask", "draft", "section", "tags", "proofread"})
    void 執筆支援_projectId未指定ならモデルとプロバイダーの解決を呼ばずシステム既定へ委ねる(String feature) {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("[]");

        invokeFeature(feature, null, null);

        org.mockito.Mockito.verify(llmClient).generate(anyString(), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull());
        org.mockito.Mockito.verify(llmModelService, org.mockito.Mockito.never()).getSelectedModel(any());
        org.mockito.Mockito.verify(llmModelService, org.mockito.Mockito.never()).getSelectedProvider(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ask", "draft", "section", "tags", "proofread"})
    void 執筆支援_projectId未指定でprovider指定ならそのproviderとnullモデルを渡す(String feature) {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("[]");

        invokeFeature(feature, null, "OPENAI");

        org.mockito.Mockito.verify(llmClient).generate(anyString(), org.mockito.ArgumentMatchers.isNull(),
                eq(AiProvider.OPENAI));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ask", "draft", "section", "tags", "proofread"})
    void 執筆支援_プロジェクトで未選択ならプロバイダー解決結果のnullをそのまま渡す(String feature) {
        when(llmModelService.getSelectedModel(7L)).thenReturn("default-model");
        when(llmModelService.getSelectedProvider(7L)).thenReturn(null);
        when(llmClient.generate(anyString(), eq("default-model"), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn("[]");

        invokeFeature(feature, 7L, null);

        org.mockito.Mockito.verify(llmClient).generate(anyString(), eq("default-model"),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void computeSuggestionId_区切りのNUL文字を含むpayloadのSHA256を返す_1496() throws Exception {
        java.lang.reflect.Method m = AiAssistService.class.getDeclaredMethod(
                "computeSuggestionId", ReviewStepKey.class, String.class, String.class);
        m.setAccessible(true);

        assertEquals("5b2db6318dcfb5563d500384a137538f88362cfa4992852101f2145d304f3528",
                m.invoke(service, ReviewStepKey.FACT_CHECK, "本文", "msg"));
        assertEquals("c583bf53b3a900ded5b3577d568c7cee92438d450ee3f4e40bb9bd5084f6ffb4",
                m.invoke(service, ReviewStepKey.FACT_CHECK, "本文", null));
    }
}
