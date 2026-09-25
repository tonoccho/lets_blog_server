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
import com.letsblog.ai.repository.GenerationJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

        AiAskResponse response = service.ask(new AiAskRequest("Next.js 16の新機能は?", null));

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

        AiAskResponse response = service.ask(new AiAskRequest("質問", null));

        assertEquals(List.of(), response.sources());
        assertEquals("Web検索を利用できなかったため、出典なしで生成しています", response.searchNote());
    }

    @Test
    void ask_検索成功だが0件の場合はその旨のsearchNoteになる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.success(List.of()));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("回答結果");

        AiAskResponse response = service.ask(new AiAskRequest("質問", null));

        assertEquals(List.of(), response.sources());
        assertEquals("関連する検索結果が見つかりませんでした", response.searchNote());
    }

    @Test
    void draft_検索成功時はsourcesを含み検索結果をプロンプトへ付加する() {
        when(webSearchService.searchSafely(anyString())).thenReturn(
                WebSearchOutcome.success(List.of(new BraveSearchResult("Title", "Desc", "https://example.com"))));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("生成結果");

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて", null));

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

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて", null));

        assertEquals(List.of(), response.sources());
        assertEquals("Web検索を利用できなかったため、出典なしで生成しています", response.searchNote());
    }

    @Test
    void draft_検索成功だが0件の場合はその旨のsearchNoteになる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.success(List.of()));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("生成結果");

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて", null));

        assertEquals(List.of(), response.sources());
        assertEquals("関連する検索結果が見つかりませんでした", response.searchNote());
    }

    @Test
    void draft_不正なmodeは例外() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.draft(new AiDraftRequest("invalid", "text", null)));
    }

    @Test
    void generateSection_本文モードは直前の文脈を含むプロンプトを組み立てる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("セクション本文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", "導入部", "前の段落の文脈", "記事タイトル", null, null, null, null));

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
                new AiSectionRequest("lead", null, null, "記事タイトル", List.of("導入", "本編", "まとめ"), null, null, null));

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
                        List.of("設計", "実装", "テスト"), null, null, null));

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
                        history, "もっと短くして", null));

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
                        new AiSectionRequest("invalid", "見出し", null, null, null, null, null, null)));
    }

    @Test
    void generateSection_articleTitle_precedingContext_headingが未指定なら既定値で組み立てる() {
        when(webSearchService.searchSafely(anyString())).thenReturn(WebSearchOutcome.failure("未設定"));
        when(llmClient.generate(anyString(), any(), any())).thenReturn("セクション本文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", null, null, null, null, null, null, null));

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
                new AiSectionRequest("lead-subsections", "   ", "   ", "   ", List.of(), null, null, null));

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
                new AiSectionRequest("body", "導入部", "文脈", "記事タイトル", null, null, "   ", null));

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
                new AiSectionRequest("body", "導入部", "文脈", "記事タイトル", null, null, "もっと短く", null));

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
                new AiSectionRequest("body", "導入部", "文脈", "記事タイトル", null, history, "もっと短く", null));

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
                new AiSectionRequest("body", longHeading, "文脈", "記事タイトル", null, null, null, null));

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

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんちには世界", null));

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

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null));

        assertEquals(List.of(), response.issues());
    }

    @Test
    void proofreadContent_不正なJSON応答は空の指摘一覧にフォールバックする() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("これはJSONではありません");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null));

        assertEquals(List.of(), response.issues());
    }

    @Test
    void proofreadContent_JSON配列でない応答は空の指摘一覧にフォールバックする() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("{}");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null));

        assertEquals(List.of(), response.issues());
    }

    @Test
    void proofreadContent_開き括弧のみで閉じ括弧が無い応答は空にフォールバックする() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn("[{\"originalText\": \"こんにちは\"");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null));

        assertEquals(List.of(), response.issues());
    }

    @Test
    void proofreadContent_originalTextキーが無い指摘は除外する() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"type\": \"typo\", \"message\": \"originalTextが無い\"}]");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null));

        assertEquals(List.of(), response.issues());
    }

    @Test
    void proofreadContent_originalTextが空文字の指摘は除外する() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"type\": \"typo\", \"originalText\": \"\", \"message\": \"空文字\"}]");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null));

        assertEquals(List.of(), response.issues());
    }

    @Test
    void proofreadContent_suggestionキーが無い指摘はsuggestionがnullになる() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"type\": \"readability\", \"originalText\": \"こんにちは\", \"message\": \"読みにくい\"}]");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null));

        assertEquals(1, response.issues().size());
        assertNull(response.issues().get(0).suggestion());
    }

    @Test
    void proofreadContent_suggestionが明示的にnullの指摘はsuggestionがnullになる() {
        when(llmClient.generate(anyString(), any(), any())).thenReturn(
                "[{\"type\": \"readability\", \"originalText\": \"こんにちは\", "
                        + "\"message\": \"読みにくい\", \"suggestion\": null}]");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null));

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
    void generateReviewStepSuggestions_プロンプト未実装のステップキーは例外() {
        assertThrows(IllegalArgumentException.class,
                () -> service.generateReviewStepSuggestions(1L, ReviewStepKey.STYLE, "本文"));
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
}
