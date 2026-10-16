package com.letsblog.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.ai.AiServiceException;
import com.letsblog.ai.ai.BraveSearchResult;
import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.domain.ProjectReviewStepSetting;
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
import com.letsblog.ai.testsupport.AiWorld;
import com.letsblog.ai.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AiAssistServiceの回帰テスト(issue #574でai-serviceへ移設)。draft()/generateSection()への
 * Brave出典統合(検索成功/失敗/0件それぞれでsources・searchNoteが期待通りになること)を中心に検証する。
 *
 * <p>画像生成関連(generateImage/getImageOptions/generateImagePrompt)はこのIssueの移設対象外として
 * legacy-apiに残るため、それらのテストケースは移設していない(legacy-api側のAiAssistServiceTestに
 * 引き続き存在する)。代わりに、legacy-apiに残った画像生成コードからのブリッジ呼び出し先である
 * generateForBridge()のテストを追加している。
 *
 * <p>issue #1823: LlmClient・LlmModelService・WebSearchService・ArticlePlanService・ReviewStepModelService・
 * CurrentActorServiceは実物で、LLM(OpenAI互換のChat Completions)・Brave Search・platform-service・
 * publishing-service・identity-serviceはHTTPフェイク、DBはメモリ上のリポジトリで差し替える。
 * Anthropicのエンドポイントはコード内で固定されておりテストのHTTPフェイクへ向けられないため、プロバイダーの振り分けを
 * 確かめるテストはOllama(システム設定)とOpenAI互換(プロジェクトのキー)で行い、CLAUDEはキー未設定で失敗する経路で確かめる。
 */
class AiAssistServiceTest {

    private static final String BRAVE = "/brave/res/v1/web/search";
    private static final String SYSTEM_KEY_PATH = "/api/internal/platform/system-settings/brave-search-api-key";
    private static final String MODEL = "qwen2.5:7b-instruct";

    private AiWorld world;
    private AiAssistService service;

    @BeforeEach
    void setUp() {
        world = new AiWorld().ollamaDefault(MODEL);
        service = world.aiAssistService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    // ---- 共通の準備 ----

    /** プロジェクトがChatGPT(OpenAI互換)を選び、キーを持つ状態にする。 */
    private void projectUsesOpenAi(long projectId, String key) {
        world.projectAiSettingsService.setLlmProvider(projectId, "OPENAI");
        world.projectAiSettingsService.setOpenAiApiKeyEncrypted(projectId, world.credentialCipher.encrypt(key));
        world.llmConfig(AiProvider.OPENAI, null, "", "gpt-default", List.of("gpt-default"), false);
    }

    private void braveKey() {
        world.http.json("GET", SYSTEM_KEY_PATH, "{\"apiKey\":\"system-key\"}");
    }

    private static String braveJson(List<BraveSearchResult> results) {
        List<Map<String, String>> items = new ArrayList<>();
        for (BraveSearchResult result : results) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("title", result.title());
            item.put("description", result.description());
            item.put("url", result.url());
            items.add(item);
        }
        try {
            return new ObjectMapper().writeValueAsString(Map.of("web", Map.of("results", items)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Web検索が成功し、どのクエリにも与えた結果を返す(システム全体のキーを使う)。 */
    private void braveSucceeds(BraveSearchResult... results) {
        braveKey();
        world.http.json("GET", BRAVE, braveJson(List.of(results)));
    }

    /** クエリ文字列(デコード後)に {@code q=<query>&} を含む検索にだけ応答する。それ以外は先に登録された応答へ回す。 */
    private void braveFor(String query, int status, BraveSearchResult... results) {
        braveKey();
        world.http.on("GET", BRAVE, request -> URLDecoder.decode(request.query(), StandardCharsets.UTF_8)
                .contains("q=" + query + "&")
                ? FakeHttpServer.Response.json(status, status == 200 ? braveJson(List.of(results)) : "{}")
                : null);
    }

    private List<String> searchedQueries() {
        return world.http.requests("GET", BRAVE).stream()
                .map(request -> URLDecoder.decode(request.query(), StandardCharsets.UTF_8))
                .map(query -> query.substring(2, query.indexOf("&count=")))
                .toList();
    }

    private static BraveSearchResult searchResult(int n) {
        return new BraveSearchResult("出典" + n, "説明" + n, "https://example.test/" + n);
    }

    private String lastPrompt() {
        List<String> prompts = world.llmPrompts();
        return prompts.get(prompts.size() - 1);
    }

    private GenerationJob lastJob() {
        return world.jobRows.saved.get(world.jobRows.saved.size() - 1);
    }

    // ---- generateForBridge ----

    @Test
    void generateForBridge_projectId指定時は選択中モデルを使う() {
        projectUsesOpenAi(1L, "sk-project");
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.OPENAI, "llama3");
        world.llmReplies("結果");

        String result = service.generateForBridge(1L, "プロンプト", "OPENAI");

        assertEquals("結果", result);
        assertEquals("llama3", world.llmModelOf(world.llmRequests().get(0)));
        assertEquals("/cloud/chat/completions", world.llmRequests().get(0).path());
        assertEquals("Bearer sk-project", world.llmRequests().get(0).header("Authorization"));
    }

    @Test
    void generateForBridge_生成前にプロジェクトをLLM接続設定の範囲として渡す() throws Exception {
        // プロジェクト1はOllamaの接続先を上書きしている(宛先検査を通る別アドレス)。
        world.lan().on("POST", ".*/chat/completions", FakeHttpServer.Response.ok(
                "{\"choices\":[{\"message\":{\"content\":\"上書き先の結果\"}}]}"));
        world.llmReplies("システムの結果");
        world.projectAiSettingsService.setConnectionUrls(1L, world.lan().lanBaseUrl() + "/v1", null);

        // プロジェクト1の生成: 先にプロジェクトが宣言されていれば、上書き先へ向かう。
        assertEquals("上書き先の結果", service.generateForBridge(1L, "プロンプト", null));
        assertEquals(1, world.lan().requests("POST", ".*/chat/completions").size());
        // プロジェクト非依存の生成: プロジェクトの宣言が外れ、システム設定の接続先へ戻る。
        assertEquals("システムの結果", service.generateForBridge(null, "プロンプト", null));
        assertEquals(1, world.llmRequests().size());
        assertEquals(1, world.lan().requests("POST", ".*/chat/completions").size());
    }

    @Test
    void generateForBridge_projectId未指定時はモデル解決を呼ばずシステム既定を使う() {
        world.llmReplies("結果");

        String result = service.generateForBridge(null, "プロンプト", null);

        assertEquals("結果", result);
        assertEquals(MODEL, world.llmModelOf(world.llmRequests().get(0)));
        verify(world.projectAiSettingsRepository, never()).findByProjectId(any());
    }

    @Test
    void generateForBridge_providerOverride未指定でprojectId指定時はプロジェクトの選択中プロバイダーを使う() {
        projectUsesOpenAi(1L, "sk-project");
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.OPENAI, "llama3");
        world.llmReplies("結果");

        String result = service.generateForBridge(1L, "プロンプト", null);

        assertEquals("結果", result);
        assertEquals("/cloud/chat/completions", world.llmRequests().get(0).path());
        assertEquals("llama3", world.llmModelOf(world.llmRequests().get(0)));
    }

    @Test
    void generateForBridge_プロバイダー上書き時は上書き先のプロバイダーで解決したモデルを渡す() {
        // プロジェクトの選択中はOllama(モデルollama-m)。上書き先のOpenAIでは、OpenAIのモデルを使う。
        projectUsesOpenAi(1L, "sk-project");
        world.projectAiSettingsService.setLlmProvider(1L, "OLLAMA");
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.OLLAMA, "ollama-m");
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.OPENAI, "gpt-b");
        world.llmReplies("結果");

        String result = service.generateForBridge(1L, "プロンプト", "OPENAI");

        assertEquals("結果", result);
        assertEquals("gpt-b", world.llmModelOf(world.llmRequests().get(0)));
        assertEquals("/cloud/chat/completions", world.llmRequests().get(0).path());
    }

    @Test
    void generateForBridge_CLAUDEはプロジェクトにキーが無ければLLMへ送らず失敗する() {
        world.llmConfig(AiProvider.CLAUDE, null, "", "claude-x", List.of("claude-x"), false);

        AiServiceException e = assertThrows(AiServiceException.class,
                () -> service.generateForBridge(1L, "プロンプト", "CLAUDE"));

        assertTrue(e.getMessage().contains("CLAUDE"), e.getMessage());
        assertEquals(0, world.llmRequests().size());
    }

    // ---- 画像入力付き生成(issue #1600) ----

    @Test
    void generateWithImageForBridge_vision対応のプロジェクト設定なら画像付きで生成する() {
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.OLLAMA, "llava:7b");
        world.llmReplies("結果");
        byte[] data = {1, 2, 3};

        String result = service.generateWithImageForBridge(1L, "説明して", "image/png", data);

        assertEquals("結果", result);
        String body = world.llmRequests().get(0).bodyText();
        assertEquals("llava:7b", world.llmModelOf(world.llmRequests().get(0)));
        assertTrue(body.contains("data:image/png;base64,AQID"), body);
        assertTrue(body.contains("説明して"), body);
    }

    @Test
    void generateWithImageForBridge_vision非対応モデルならLLMを呼ばずnullを返す() {
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.OLLAMA, "qwen2.5:7b-instruct");

        String result = service.generateWithImageForBridge(1L, "説明して", "image/png", new byte[] {1});

        assertNull(result);
        assertEquals(0, world.llmRequests().size());
    }

    @Test
    void generateWithImageForBridge_projectId未指定ならシステム既定のプロバイダとモデルで判定する() {
        // projectId未指定ではモデル/プロバイダを解決せず、既定のモデル名が分からないため非対応として扱う。
        String result = service.generateWithImageForBridge(null, "説明して", "image/png", new byte[] {1});

        assertNull(result);
        verify(world.projectAiSettingsRepository, never()).findByProjectId(any());
        assertEquals(0, world.llmRequests().size());
    }

    // ---- 画像プロンプト ----

    @Test
    void generateImagePrompt_履歴が無い場合はSystem_Userのみのプロンプトを組み立てる() {
        world.llmReplies("english, prompt");

        AiImagePromptResponse response = service.generateImagePrompt(null, null, "夕焼けの海辺", null);

        assertEquals("english, prompt", response.prompt());
        assertTrue(lastPrompt().contains("夕焼けの海辺"));
        assertTrue(lastPrompt().trim().endsWith("Assistant:"));
        assertEquals(List.of("llm_image_prompt:running", "llm_image_prompt:done"), world.jobRows.log);
    }

    @Test
    void generateImagePrompt_履歴がある場合はuser_assistantを交えたプロンプトを組み立てる() {
        world.llmReplies("english, prompt 2");
        List<PlanChatMessage> history = List.of(
                new PlanChatMessage("user", "猫を追加して"),
                new PlanChatMessage("assistant", "前回の生成結果"));

        AiImagePromptResponse response = service.generateImagePrompt(1L, history, "もっと明るく", null);

        assertEquals("english, prompt 2", response.prompt());
        String prompt = lastPrompt();
        assertTrue(prompt.contains("User: 猫を追加して"));
        assertTrue(prompt.contains("Assistant: 前回の生成結果"));
        assertTrue(prompt.contains("もっと明るく"));
    }

    @Test
    void generateImagePrompt_LLM呼び出しが失敗したらジョブをfailedにして再送出する() {
        world.llmFails(500, "x");

        assertThrows(RuntimeException.class, () -> service.generateImagePrompt(null, null, "夕焼け", null));

        assertEquals(List.of("llm_image_prompt:running", "llm_image_prompt:failed"), world.jobRows.log);
    }

    // ---- ask ----

    @Test
    void ask_生成ジョブに呼び出し元の利用者を所有者として記録する() {
        world.actor(42L, "user");
        world.llmReplies("回答結果");

        service.ask(new AiAskRequest("所有者は?", null, null));

        assertEquals(42L, world.jobRows.saved.get(0).getOwnerUserId());
    }

    @Test
    void ask_操作者を解決できない場合は所有者不明のままジョブを記録する() {
        world.llmReplies("回答結果");

        service.ask(new AiAskRequest("所有者は?", null, null));

        assertNull(world.jobRows.saved.get(0).getOwnerUserId());
    }

    @Test
    void ask_検索成功時はsourcesを含み検索結果をプロンプトへ付加する() {
        braveSucceeds(new BraveSearchResult("Title", "Desc", "https://example.com"));
        world.llmReplies("回答結果");

        AiAskResponse response = service.ask(new AiAskRequest("Next.js 16の新機能は?", null, null));

        assertEquals("回答結果", response.result());
        assertEquals(1, response.sources().size());
        assertEquals("https://example.com", response.sources().get(0).url());
        assertNull(response.searchNote());
        assertTrue(lastPrompt().contains("参考のWeb検索結果"));
        assertTrue(lastPrompt().contains("Next.js 16の新機能は?"));
        assertEquals("system-key", world.http.requests("GET", BRAVE).get(0).header("X-Subscription-Token"));
    }

    @Test
    void ask_検索失敗時はsourcesが空でsearchNoteが設定される() {
        world.llmReplies("回答結果");

        AiAskResponse response = service.ask(new AiAskRequest("質問", null, null));

        assertEquals(List.of(), response.sources());
        assertEquals("Web検索を利用できなかったため、出典なしで生成しています", response.searchNote());
    }

    @Test
    void ask_検索成功だが0件の場合はその旨のsearchNoteになる() {
        braveSucceeds();
        world.llmReplies("回答結果");

        AiAskResponse response = service.ask(new AiAskRequest("質問", null, null));

        assertEquals(List.of(), response.sources());
        assertEquals("関連する検索結果が見つかりませんでした", response.searchNote());
    }

    @Test
    void ask_LLM呼び出しが失敗したらジョブをfailedにして再送出する() {
        world.llmFails(500, "x");

        assertThrows(RuntimeException.class, () -> service.ask(new AiAskRequest("質問", null, null)));

        assertEquals(List.of("llm_ask:running", "llm_ask:failed"), world.jobRows.log);
    }

    // ---- draft ----

    @Test
    void draft_検索成功時はsourcesを含み検索結果をプロンプトへ付加する() {
        braveSucceeds(new BraveSearchResult("Title", "Desc", "https://example.com"));
        world.llmReplies("生成結果");

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて", null, null));

        assertEquals("生成結果", response.result());
        assertEquals(1, response.sources().size());
        assertEquals("https://example.com", response.sources().get(0).url());
        assertNull(response.searchNote());
        assertTrue(lastPrompt().contains("参考のWeb検索結果"));
    }

    @Test
    void draft_検索失敗時はsourcesが空でsearchNoteが設定される() {
        world.llmReplies("生成結果");

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて", null, null));

        assertEquals(List.of(), response.sources());
        assertEquals("Web検索を利用できなかったため、出典なしで生成しています", response.searchNote());
    }

    @Test
    void draft_検索成功だが0件の場合はその旨のsearchNoteになる() {
        braveSucceeds();
        world.llmReplies("生成結果");

        AiDraftResponse response = service.draft(new AiDraftRequest("draft", "AIブログについて", null, null));

        assertEquals(List.of(), response.sources());
        assertEquals("関連する検索結果が見つかりませんでした", response.searchNote());
    }

    @Test
    void draft_不正なmodeは例外() {
        assertThrows(IllegalArgumentException.class,
                () -> service.draft(new AiDraftRequest("invalid", "text", null, null)));
        assertTrue(world.jobRows.saved.isEmpty());
    }

    @Test
    void draft_LLM呼び出しが失敗したらジョブをfailedにして再送出する() {
        world.llmFails(500, "x");

        assertThrows(RuntimeException.class, () -> service.draft(new AiDraftRequest("proofread", "本文", null, null)));

        assertEquals(List.of("llm_proofread:running", "llm_proofread:failed"), world.jobRows.log);
    }

    // ---- generateSection ----

    @Test
    void generateSection_本文モードは直前の文脈を含むプロンプトを組み立てる() {
        world.llmReplies("セクション本文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", "導入部", "前の段落の文脈", "記事タイトル", null, null, null, null, null));

        assertEquals("セクション本文", response.result());
        assertTrue(lastPrompt().contains("前の段落の文脈"));
        assertTrue(lastPrompt().contains("記事タイトル"));
        assertTrue(lastPrompt().contains("導入部"));
    }

    @Test
    void generateSection_リード文モードは出典を含めて返す() {
        braveSucceeds(new BraveSearchResult("Title", "Desc", "https://example.com"));
        world.llmReplies("リード文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("lead", null, null, "記事タイトル", List.of("導入", "本編", "まとめ"), null, null, null, null));

        assertEquals("リード文", response.result());
        assertEquals(1, response.sources().size());
        assertNull(response.searchNote());
        assertTrue(lastPrompt().contains("導入"));
        assertTrue(lastPrompt().contains("まとめ"));
    }

    @Test
    void generateSection_サブセクション考慮モードは見出しとサブセクション一覧を含むプロンプトを組み立てる() {
        world.llmReplies("セクションリード文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("lead-subsections", "第2章 実装編", null, "記事タイトル",
                        List.of("設計", "実装", "テスト"), null, null, null, null));

        assertEquals("セクションリード文", response.result());
        assertTrue(lastPrompt().contains("第2章 実装編"));
        assertTrue(lastPrompt().contains("設計"));
        assertTrue(lastPrompt().contains("テスト"));
    }

    @Test
    void generateSection_messageが指定されると壁打ち形式のプロンプトを組み立てる() {
        world.llmReplies("再生成された本文");
        List<PlanChatMessage> history = List.of(new PlanChatMessage("assistant", "1回目の生成結果"));

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", "導入部", "前の段落の文脈", "記事タイトル", null,
                        history, "もっと短くして", null, null));

        assertEquals("再生成された本文", response.result());
        String prompt = lastPrompt();
        assertTrue(prompt.contains("System:"));
        assertTrue(prompt.contains("1回目の生成結果"));
        assertTrue(prompt.contains("もっと短くして"));
        assertTrue(prompt.trim().endsWith("Assistant:"));
    }

    @Test
    void generateSection_不正なmodeは例外() {
        assertThrows(IllegalArgumentException.class,
                () -> service.generateSection(
                        new AiSectionRequest("invalid", "見出し", null, null, null, null, null, null, null)));
    }

    @Test
    void generateSection_articleTitle_precedingContext_headingが未指定なら既定値で組み立てる() {
        world.llmReplies("セクション本文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", null, null, null, null, null, null, null, null));

        assertEquals("セクション本文", response.result());
        assertTrue(lastPrompt().contains("(未設定)"));
        assertTrue(lastPrompt().contains("(なし)"));
    }

    @Test
    void generateSection_articleTitle_precedingContext_headingが空白のみなら既定値で組み立てる() {
        world.llmReplies("リード文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("lead-subsections", "   ", "   ", "   ", List.of(), null, null, null, null));

        assertEquals("リード文", response.result());
        assertTrue(lastPrompt().contains("(未設定)"));
        assertTrue(lastPrompt().contains("(なし)"));
    }

    @Test
    void generateSection_messageが空白のみの場合は壁打ちにならず通常のプロンプトを組み立てる() {
        world.llmReplies("セクション本文");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", "導入部", "文脈", "記事タイトル", null, null, "   ", null, null));

        assertEquals("セクション本文", response.result());
        assertTrue(!lastPrompt().contains("System:"));
    }

    @Test
    void generateSection_壁打ちで履歴が無い場合は往復無しでプロンプトを組み立てる() {
        world.llmReplies("再生成結果");

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", "導入部", "文脈", "記事タイトル", null, null, "もっと短く", null, null));

        assertEquals("再生成結果", response.result());
        assertTrue(lastPrompt().contains("System:"));
        assertTrue(lastPrompt().contains("もっと短く"));
    }

    @Test
    void generateSection_壁打ちの履歴にuserロールが含まれる場合はUserとして整形する() {
        world.llmReplies("再生成結果");
        List<PlanChatMessage> history = List.of(new PlanChatMessage("user", "最初の指示"));

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", "導入部", "文脈", "記事タイトル", null, history, "もっと短く", null, null));

        assertEquals("再生成結果", response.result());
        assertTrue(lastPrompt().contains("User: 最初の指示"));
    }

    @Test
    void generateSection_articleTitleと見出しの合計が200文字を超える場合は検索クエリを先頭200文字に丸める() {
        braveSucceeds();
        world.llmReplies("セクション本文");
        String longHeading = "あ".repeat(250);

        AiSectionResponse response = service.generateSection(
                new AiSectionRequest("body", longHeading, "文脈", "記事タイトル", null, null, null, null, null));

        assertEquals("セクション本文", response.result());
        assertEquals(1, searchedQueries().size());
        assertEquals(200, searchedQueries().get(0).length());
    }

    @Test
    void generateSection_articleTitleが無ければ見出しだけで検索する() {
        braveSucceeds();
        world.llmReplies("セクション本文");

        service.generateSection(new AiSectionRequest("body", "導入部", "文脈", null, null, null, null, null, null));

        assertEquals(List.of("導入部"), searchedQueries());
    }

    // ---- suggestTags ----

    @Test
    void suggestTags_projectId未指定なら既存タグを問い合わせずプロンプトはそのまま() {
        world.llmReplies("{\"categories\": [\"技術\"], \"tags\": [\"Java\", \"Spring\"]}");

        AiTagsResponse response = service.suggestTags(new AiTagsRequest("記事本文", null, null));

        assertEquals(List.of("技術"), response.categories());
        assertEquals(List.of("Java", "Spring"), response.tags());
        assertEquals(0, world.http.requests("GET", "/api/internal/ai/projects/.*").size());
        assertTrue(lastPrompt().contains("記事本文"));
        assertTrue(!lastPrompt().contains("既存タグ一覧"));
    }

    @Test
    void suggestTags_既存タグがあればプロンプトへ追加され結果も既存タグ優先で並び替えられる() {
        world.http.json("GET", "/api/internal/ai/projects/1/existing-tags", "[\"Java\",\"AWS\"]");
        world.llmReplies("{\"categories\": [], \"tags\": [\"Kotlin\", \"Java\", \"AWS\", \"Docker\"]}");

        AiTagsResponse response = service.suggestTags(new AiTagsRequest("記事本文", null, 1L));

        assertEquals(List.of("Java", "AWS", "Kotlin", "Docker"), response.tags());
        assertTrue(lastPrompt().contains("既存タグ一覧"));
        assertTrue(lastPrompt().contains("Java, AWS"));
    }

    @Test
    void suggestTags_不正なJSON応答は空のcategories_tagsにフォールバックする() {
        world.llmReplies("これはJSONではありません");

        AiTagsResponse response = service.suggestTags(new AiTagsRequest("記事本文", null, null));

        assertEquals(List.of(), response.categories());
        assertEquals(List.of(), response.tags());
    }

    @Test
    void suggestTags_閉じ括弧のみでJSONオブジェクトが開始しない応答は空にフォールバックする() {
        world.llmReplies("}invalid{");

        AiTagsResponse response = service.suggestTags(new AiTagsRequest("記事本文", null, null));

        assertEquals(List.of(), response.categories());
        assertEquals(List.of(), response.tags());
    }

    @Test
    void suggestTags_categories_tagsキーが無い応答は空のリストになる() {
        world.llmReplies("{\"foo\": 1}");

        AiTagsResponse response = service.suggestTags(new AiTagsRequest("記事本文", null, null));

        assertEquals(List.of(), response.categories());
        assertEquals(List.of(), response.tags());
    }

    @Test
    void suggestTags_categoriesやtagsが配列でない応答は空のリストになる() {
        world.llmReplies("{\"categories\": \"技術\", \"tags\": {\"a\": 1}}");

        AiTagsResponse response = service.suggestTags(new AiTagsRequest("記事本文", null, null));

        assertEquals(List.of(), response.categories());
        assertEquals(List.of(), response.tags());
    }

    @Test
    void suggestTags_開き括弧のみで閉じ括弧が無い応答は空にフォールバックする() {
        world.llmReplies("{\"categories\": [\"技術\"]");

        AiTagsResponse response = service.suggestTags(new AiTagsRequest("記事本文", null, null));

        assertEquals(List.of(), response.categories());
        assertEquals(List.of(), response.tags());
    }

    @Test
    void suggestTags_LLM呼び出しが失敗したらジョブをfailedにして再送出する() {
        world.llmFails(500, "x");

        assertThrows(RuntimeException.class, () -> service.suggestTags(new AiTagsRequest("記事本文", null, null)));

        assertEquals(List.of("llm_tags:running", "llm_tags:failed"), world.jobRows.log);
    }

    // ---- proofreadContent ----

    @Test
    void proofreadContent_正常なJSON配列応答を指摘一覧として返す() {
        world.llmReplies("[{\"type\": \"typo\", \"originalText\": \"こんちには\", "
                + "\"message\": \"誤字です\", \"suggestion\": \"こんにちは\"}]");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんちには世界", null, null));

        assertEquals(1, response.issues().size());
        assertEquals("typo", response.issues().get(0).type());
        assertEquals("こんちには", response.issues().get(0).originalText());
        assertEquals("誤字です", response.issues().get(0).message());
        assertEquals("こんにちは", response.issues().get(0).suggestion());
        assertTrue(lastPrompt().contains("こんちには世界"));
    }

    @Test
    void proofreadContent_本文に存在しないoriginalTextの指摘は除外する() {
        world.llmReplies("[{\"type\": \"typo\", \"originalText\": \"本文に無い文字列\", "
                + "\"message\": \"誤字です\", \"suggestion\": null}]");

        assertEquals(List.of(), service.proofreadContent(new AiProofreadRequest("こんにちは世界", null, null)).issues());
    }

    @Test
    void proofreadContent_不正なJSON応答は空の指摘一覧にフォールバックする() {
        world.llmReplies("これはJSONではありません");

        assertEquals(List.of(), service.proofreadContent(new AiProofreadRequest("こんにちは世界", null, null)).issues());
    }

    @Test
    void proofreadContent_JSON配列でない応答は空の指摘一覧にフォールバックする() {
        world.llmReplies("{}");

        assertEquals(List.of(), service.proofreadContent(new AiProofreadRequest("こんにちは世界", null, null)).issues());
    }

    @Test
    void proofreadContent_開き括弧のみで閉じ括弧が無い応答は空にフォールバックする() {
        world.llmReplies("[{\"originalText\": \"こんにちは\"");

        assertEquals(List.of(), service.proofreadContent(new AiProofreadRequest("こんにちは世界", null, null)).issues());
    }

    @Test
    void proofreadContent_originalTextキーが無い指摘は除外する() {
        world.llmReplies("[{\"type\": \"typo\", \"message\": \"originalTextが無い\"}]");

        assertEquals(List.of(), service.proofreadContent(new AiProofreadRequest("こんにちは世界", null, null)).issues());
    }

    @Test
    void proofreadContent_originalTextが空文字の指摘は除外する() {
        world.llmReplies("[{\"type\": \"typo\", \"originalText\": \"\", \"message\": \"空文字\"}]");

        assertEquals(List.of(), service.proofreadContent(new AiProofreadRequest("こんにちは世界", null, null)).issues());
    }

    @Test
    void proofreadContent_suggestionキーが無い指摘はsuggestionがnullになる() {
        world.llmReplies("[{\"type\": \"readability\", \"originalText\": \"こんにちは\", \"message\": \"読みにくい\"}]");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null, null));

        assertEquals(1, response.issues().size());
        assertNull(response.issues().get(0).suggestion());
    }

    @Test
    void proofreadContent_suggestionが明示的にnullの指摘はsuggestionがnullになる() {
        world.llmReplies("[{\"type\": \"readability\", \"originalText\": \"こんにちは\", "
                + "\"message\": \"読みにくい\", \"suggestion\": null}]");

        AiProofreadResponse response = service.proofreadContent(new AiProofreadRequest("こんにちは世界", null, null));

        assertEquals(1, response.issues().size());
        assertNull(response.issues().get(0).suggestion());
    }

    @Test
    void proofreadContent_LLM呼び出しが失敗したらジョブをfailedにして再送出する() {
        world.llmFails(500, "x");

        assertThrows(RuntimeException.class, () -> service.proofreadContent(new AiProofreadRequest("本文", null, null)));

        assertEquals(List.of("llm_proofread_check:running", "llm_proofread_check:failed"), world.jobRows.log);
    }

    // ---- issue #1213: レビューステップ単位の指摘生成 ----

    /** ステップごとのプロバイダー/モデルの上書きを保存する。 */
    private void stepSetting(long projectId, ReviewStepKey key, String provider, String model) {
        ProjectReviewStepSetting row = new ProjectReviewStepSetting(projectId, key);
        row.setLlmProvider(provider);
        row.setLlmModel(model);
        world.reviewStepSettingRepository.save(row);
    }

    @Test
    void generateReviewStepSuggestions_JAPANESEステップはそのステップキーを持つ指摘一覧を返す() {
        projectUsesOpenAi(1L, "sk-project");
        stepSetting(1L, ReviewStepKey.JAPANESE, "OPENAI", "model-a");
        world.llmReplies("[{\"originalText\": \"ら抜き言葉の例\", \"message\": \"ら抜き言葉です\"}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "これはら抜き言葉の例です");

        assertEquals(1, response.suggestions().size());
        assertEquals("JAPANESE", response.suggestions().get(0).stepKey());
        assertEquals("ら抜き言葉の例", response.suggestions().get(0).originalText());
        assertEquals("ら抜き言葉です", response.suggestions().get(0).message());
        assertEquals("model-a", world.llmModelOf(world.llmRequests().get(0)));
        assertEquals("/cloud/chat/completions", world.llmRequests().get(0).path());
    }

    @Test
    void generateReviewStepSuggestions_生成前にプロジェクトをLLM接続設定の範囲として渡す() {
        // 対象プロジェクトが先に宣言されていなければ、プロジェクトのAPIキーを引けず生成できない。
        projectUsesOpenAi(3L, "sk-project3");
        stepSetting(3L, ReviewStepKey.JAPANESE, "OPENAI", "model-a");
        world.llmReplies("[]");

        service.generateReviewStepSuggestions(3L, ReviewStepKey.JAPANESE, "本文");

        assertEquals("Bearer sk-project3", world.llmRequests().get(0).header("Authorization"));
        assertEquals("model-a", world.llmModelOf(world.llmRequests().get(0)));
    }

    @Test
    void factCheck_抽出と判定の前にプロジェクトをLLM接続設定の範囲として渡す() {
        projectUsesOpenAi(4L, "sk-project4");
        stepSetting(4L, ReviewStepKey.FACT_CHECK, "OPENAI", "model-f");
        braveSucceeds(searchResult(1));
        world.llmReplies(FACT_EXTRACTION, "[]");

        service.generateReviewStepSuggestions(4L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(2, world.llmRequests().size());
        world.llmRequests().forEach(request -> {
            assertEquals("Bearer sk-project4", request.header("Authorization"));
            assertEquals("model-f", world.llmModelOf(request));
        });
    }

    @Test
    void generateReviewStepSuggestions_PROOFREADINGステップはそのステップキーを持つ指摘一覧を返す() {
        projectUsesOpenAi(1L, "sk-project");
        stepSetting(1L, ReviewStepKey.PROOFREADING, "OPENAI", "model-b");
        world.llmReplies("[{\"originalText\": \"衍字の例\", \"message\": \"衍字があります\"}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.PROOFREADING, "これは衍字の例です");

        assertEquals(1, response.suggestions().size());
        assertEquals("PROOFREADING", response.suggestions().get(0).stepKey());
        assertEquals("衍字の例", response.suggestions().get(0).originalText());
        assertEquals("model-b", world.llmModelOf(world.llmRequests().get(0)));
    }

    @Test
    void generateReviewStepSuggestions_本文に存在しないoriginalTextの指摘は除外する() {
        world.llmReplies("[{\"originalText\": \"本文に無い文字列\", \"message\": \"指摘\"}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "こんにちは世界");

        assertEquals(List.of(), response.suggestions());
    }

    @Test
    void generateReviewStepSuggestions_同じステップキーと本文と指摘内容なら識別子が一致する() {
        world.llmReplies("[{\"originalText\": \"対象の指摘\", \"message\": \"指摘内容\"}]");

        AiReviewStepSuggestionsResponse first =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文中に対象の指摘があります");
        AiReviewStepSuggestionsResponse second =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文中に対象の指摘があります");

        assertEquals(1, first.suggestions().size());
        assertEquals(first.suggestions().get(0).id(), second.suggestions().get(0).id());
    }

    @Test
    void generateReviewStepSuggestions_ステップキーが異なれば同じ引用_同じ内容でも識別子が異なる() {
        world.llmReplies("[{\"originalText\": \"対象の指摘\", \"message\": \"指摘内容\"}]");

        AiReviewStepSuggestionsResponse japanese =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文中に対象の指摘があります");
        AiReviewStepSuggestionsResponse proofreading =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.PROOFREADING, "本文中に対象の指摘があります");

        assertNotEquals(japanese.suggestions().get(0).id(), proofreading.suggestions().get(0).id());
    }

    @Test
    void generateReviewStepSuggestions_READER_PERSPECTIVEステップはそのステップキーを持つ指摘一覧を返す() {
        stepSetting(1L, ReviewStepKey.READER_PERSPECTIVE, "OLLAMA", "model-r");
        world.llmReplies("[{\"originalText\": \"CQRSを導入した\", \"message\": \"CQRSの説明が無く初学者には飛躍です\"}]");

        AiReviewStepSuggestionsResponse response = service.generateReviewStepSuggestions(
                1L, ReviewStepKey.READER_PERSPECTIVE, "そこでCQRSを導入したので高速になった");

        assertEquals(1, response.suggestions().size());
        assertEquals("READER_PERSPECTIVE", response.suggestions().get(0).stepKey());
        assertEquals("CQRSを導入した", response.suggestions().get(0).originalText());
        assertEquals("CQRSの説明が無く初学者には飛躍です", response.suggestions().get(0).message());
        assertEquals("model-r", world.llmModelOf(world.llmRequests().get(0)));
        assertEquals("/v1/chat/completions", world.llmRequests().get(0).path());
    }

    @Test
    void generateReviewStepSuggestions_STYLEステップはそのステップキーを持つ指摘一覧を返す() {
        projectUsesOpenAi(1L, "sk-project");
        stepSetting(1L, ReviewStepKey.STYLE, "OPENAI", "model-s");
        world.llmReplies("[{\"originalText\": \"である。\", \"message\": \"文末が「です・ます」調と混在しています\"}]");

        AiReviewStepSuggestionsResponse response = service.generateReviewStepSuggestions(
                1L, ReviewStepKey.STYLE, "これは例です。あれは例である。");

        assertEquals(1, response.suggestions().size());
        assertEquals("STYLE", response.suggestions().get(0).stepKey());
        assertEquals("である。", response.suggestions().get(0).originalText());
        assertEquals("model-s", world.llmModelOf(world.llmRequests().get(0)));
    }

    @Test
    void generateReviewStepSuggestions_READER_PERSPECTIVEとSTYLEも本文に存在しないoriginalTextは除外する() {
        world.llmReplies("[{\"originalText\": \"本文に無い文字列\", \"message\": \"指摘\"}]");

        assertEquals(List.of(), service.generateReviewStepSuggestions(
                1L, ReviewStepKey.READER_PERSPECTIVE, "こんにちは世界").suggestions());
        assertEquals(List.of(), service.generateReviewStepSuggestions(
                1L, ReviewStepKey.STYLE, "こんにちは世界").suggestions());
    }

    @Test
    void generateReviewStepSuggestions_READER_PERSPECTIVEとSTYLEも同じ本文なら識別子が一致し_ステップ間では異なる() {
        world.llmReplies("[{\"originalText\": \"対象の指摘\", \"message\": \"指摘内容\"}]");
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
        world.llmReplies("[]");

        service.generateReviewStepSuggestions(1L, ReviewStepKey.READER_PERSPECTIVE, "本文A");
        service.generateReviewStepSuggestions(1L, ReviewStepKey.STYLE, "本文B");

        String reader = world.llmPrompts().get(0);
        String style = world.llmPrompts().get(1);
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
        world.llmFails(500, "x");

        assertThrows(RuntimeException.class,
                () -> service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文"));

        assertEquals(List.of("llm_review_step_japanese:running", "llm_review_step_japanese:failed"), world.jobRows.log);
    }

    @Test
    void generateReviewStepSuggestions_JSON配列でない応答は空の指摘一覧にフォールバックする() {
        world.llmReplies("{}");

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
        world.llmReplies("{}");

        service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文");

        assertEquals("done", lastJob().getStatus());
    }

    @Test
    void generateReviewStepSuggestions_不正なJSON応答は空の指摘一覧にフォールバックする() {
        world.llmReplies("これはJSONではありません");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文");

        assertEquals(List.of(), response.suggestions());
    }

    @Test
    void generateReviewStepSuggestions_角括弧が閉じていない_または順序が逆の応答は空の指摘一覧にする() {
        world.llmReplies("[{\"originalText\": \"本文\"");
        assertEquals(List.of(),
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文").suggestions());

        world.llmReplies("]逆順[");
        assertEquals(List.of(),
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文").suggestions());
    }

    @Test
    void generateReviewStepSuggestions_originalTextが無い指摘は除外する() {
        world.llmReplies("[{\"message\": \"originalTextが無い指摘\"}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文");

        assertEquals(List.of(), response.suggestions());
    }

    @Test
    void generateReviewStepSuggestions_originalTextが空文字の指摘は除外する() {
        world.llmReplies("[{\"originalText\": \"\", \"message\": \"空文字の指摘\"}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文");

        assertEquals(List.of(), response.suggestions());
    }

    @Test
    void generateReviewStepSuggestions_messageが無い指摘でも識別子を計算して返す() {
        world.llmReplies("[{\"originalText\": \"対象の指摘\"}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文中に対象の指摘があります");

        assertEquals(1, response.suggestions().size());
        assertNull(response.suggestions().get(0).message());
        assertNotEquals("", response.suggestions().get(0).id());
    }

    @Test
    void generateReviewStepSuggestions_未知のステップキーは失敗のジョブを残して例外にする() {
        assertThrows(RuntimeException.class,
                () -> service.generateReviewStepSuggestions(1L, "NO_SUCH_STEP", "本文"));

        assertEquals(List.of("llm_review_step_invalid:running", "llm_review_step_invalid:failed"), world.jobRows.log);
        assertEquals(0, world.llmRequests().size());
    }

    @Test
    void generateReviewStepSuggestions_文字列のステップキーは検証して指摘生成へ進む() {
        world.llmReplies("[]");

        AiReviewStepSuggestionsResponse response = service.generateReviewStepSuggestions(1L, "JAPANESE", "本文");

        assertEquals(List.of(), response.suggestions());
        assertEquals(List.of("llm_review_step_japanese:running", "llm_review_step_japanese:done"), world.jobRows.log);
    }

    // ---- issue #1214: 校閲(FACT_CHECK)ステップ。抽出 → Web検索 → 判定の2回のLLM呼び出し ----

    private static final String FACT_TEXT = "東京タワーの高さは333メートルです。開業は1958年です。";

    private static final String FACT_EXTRACTION =
            "[{\"claim\": \"東京タワーの高さは333メートル\", \"query\": \"東京タワー 高さ\"}]";

    @Test
    void factCheck_検索結果を根拠にした指摘が出典つきで返りスキップではない() {
        braveSucceeds(searchResult(1), searchResult(2));
        world.llmReplies(
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
        assertEquals(List.of("東京タワー 高さ"), searchedQueries());
    }

    @Test
    void factCheck_プロジェクトのモデルで抽出と判定の2回を呼びジョブをfact_checkとして記録する() {
        projectUsesOpenAi(1L, "sk-project");
        stepSetting(1L, ReviewStepKey.FACT_CHECK, "OPENAI", "model-f");
        braveSucceeds(searchResult(1));
        world.llmReplies(FACT_EXTRACTION, "[]");

        service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(2, world.llmRequests().size());
        world.llmRequests().forEach(request -> {
            assertEquals("model-f", world.llmModelOf(request));
            assertEquals("/cloud/chat/completions", request.path());
        });
        assertEquals("llm_review_step_fact_check", lastJob().getType());
        assertEquals("done", lastJob().getStatus());
    }

    @Test
    void factCheck_APIキー未設定でWeb検索が使えないときはスキップと理由を返し判定へ進まない() {
        String reason = "Brave Search APIキーが設定されていません";
        world.llmReplies(FACT_EXTRACTION);

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(Boolean.TRUE, response.skipped());
        assertTrue(response.skipReason().contains(reason));
        assertEquals(List.of(), response.suggestions());
        assertEquals(1, world.llmRequests().size());
    }

    @Test
    void factCheck_Web検索が失敗した場合は検索失敗の理由つきでスキップする() {
        braveKey();
        world.http.status("GET", BRAVE, 500, "{}");
        world.llmReplies(FACT_EXTRACTION);

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(Boolean.TRUE, response.skipped());
        assertTrue(response.skipReason().contains("500"));
    }

    /**
     * 主張の抽出(1回目のLLM呼び出し)のあと、Web検索のためのキー解決でDB(project_ai_settings)が失敗する。
     * DBの障害は{@code RuntimeException}で、メッセージを持たない・空白だけのものがありうる。
     */
    private AiReviewStepSuggestionsResponse factCheckWithDbFailureDuringSearch(RuntimeException failure) {
        java.util.concurrent.atomic.AtomicBoolean extracted = new java.util.concurrent.atomic.AtomicBoolean();
        world.http.on("POST", ".*/chat/completions", request -> {
            extracted.set(true);
            return FakeHttpServer.Response.ok("{\"choices\":[{\"message\":{\"content\":"
                    + "\"[{\\\"claim\\\": \\\"a\\\", \\\"query\\\": \\\"q1\\\"}]\"}}]}");
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            if (extracted.get()) {
                throw failure;
            }
            return java.util.Optional.empty();
        }).when(world.projectAiSettingsRepository).findByProjectId(any());

        return service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);
    }

    @Test
    void factCheck_検索の準備でメッセージを持たない障害が起きても理由不明としてスキップする() {
        AiReviewStepSuggestionsResponse response = factCheckWithDbFailureDuringSearch(new RuntimeException());

        assertEquals(Boolean.TRUE, response.skipped());
        assertTrue(response.skipReason().endsWith("理由不明"), response.skipReason());
    }

    @Test
    void factCheck_検索の準備で空白だけのメッセージの障害が起きても理由不明としてスキップする() {
        AiReviewStepSuggestionsResponse response = factCheckWithDbFailureDuringSearch(new RuntimeException("   "));

        assertEquals(Boolean.TRUE, response.skipped());
        assertTrue(response.skipReason().endsWith("理由不明"), response.skipReason());
    }

    @Test
    void factCheck_検索が途中で失敗したら以降の検索を行わずスキップする() {
        world.llmReplies(
                "[{\"claim\": \"東京タワーの高さは333メートル\", \"query\": \"q1\"},"
                        + " {\"claim\": \"開業は1958年\", \"query\": \"q2\"}]");
        braveFor("q2", 200, searchResult(2));
        braveFor("q1", 500);

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(Boolean.TRUE, response.skipped());
        assertEquals(List.of("q1"), searchedQueries());
    }

    @Test
    void factCheck_検索が失敗したジョブは完了として記録されスキップ情報を残す() {
        braveKey();
        world.http.status("GET", BRAVE, 500, "{}");
        world.llmReplies(FACT_EXTRACTION);

        service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals("done", lastJob().getStatus());
        assertTrue(lastJob().getResultPayload().contains("\"skipped\":\"true\""), lastJob().getResultPayload());
        assertTrue(lastJob().getResultPayload().contains("500"), lastJob().getResultPayload());
    }

    @Test
    void factCheck_抽出された主張が無ければ検索も判定もせず指摘0件でスキップではない() {
        world.llmReplies("[]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of(), response.suggestions());
        assertEquals(Boolean.FALSE, response.skipped());
        assertEquals(0, world.http.requests("GET", BRAVE).size());
        assertEquals(0, world.http.requests("GET", SYSTEM_KEY_PATH).size());
        assertEquals(1, world.llmRequests().size());
    }

    @Test
    void factCheck_抽出結果がJSONでなければ指摘0件でスキップではない() {
        world.llmReplies("これはJSONではありません");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of(), response.suggestions());
        assertEquals(Boolean.FALSE, response.skipped());
    }

    @Test
    void factCheck_抽出結果が配列でなければ指摘0件でスキップではない() {
        world.llmReplies("{}");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of(), response.suggestions());
        assertEquals(Boolean.FALSE, response.skipped());
    }

    @Test
    void factCheck_queryが無い主張は主張文で検索し空の主張は無視する() {
        world.llmReplies("[{\"claim\": \"開業は1958年\"}, {\"query\": \"主張なし\"}, {\"claim\": \"\"}]", "[]");
        braveSucceeds(searchResult(1));

        service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of("開業は1958年"), searchedQueries());
    }

    @Test
    void factCheck_検索する主張は最大3件までに抑える() {
        world.llmReplies(
                "[{\"claim\": \"a\", \"query\": \"q1\"}, {\"claim\": \"b\", \"query\": \"q2\"},"
                        + " {\"claim\": \"c\", \"query\": \"q3\"}, {\"claim\": \"d\", \"query\": \"q4\"}]",
                "[]");
        braveSucceeds(searchResult(1));

        service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of("q1", "q2", "q3"), searchedQueries());
    }

    @Test
    void factCheck_出典を持たない指摘と範囲外の出典番号だけの指摘は除外する() {
        braveSucceeds(searchResult(1));
        world.llmReplies(
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
        braveSucceeds(searchResult(1));
        world.llmReplies(
                FACT_EXTRACTION,
                "[{\"originalText\": \"本文に無い\", \"message\": \"m\", \"sources\": [1]},"
                        + " {\"message\": \"originalTextなし\", \"sources\": [1]}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of(), response.suggestions());
    }

    @Test
    void factCheck_originalTextが空文字の指摘は出典があっても除外する() {
        braveSucceeds(searchResult(1));
        world.llmReplies(FACT_EXTRACTION, "[{\"originalText\": \"\", \"message\": \"m\", \"sources\": [1]}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of(), response.suggestions());
    }

    @Test
    void factCheck_判定結果が不正なら指摘0件でスキップではない() {
        braveSucceeds(searchResult(1));
        world.llmReplies(FACT_EXTRACTION, "壊れた応答");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of(), response.suggestions());
        assertEquals(Boolean.FALSE, response.skipped());
    }

    @Test
    void factCheck_判定結果が配列でなければ指摘0件でスキップではない() {
        braveSucceeds(searchResult(1));
        world.llmReplies(FACT_EXTRACTION, "{}");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of(), response.suggestions());
    }

    @Test
    void factCheck_複数の主張の検索結果は通し番号で判定へ渡され出典番号で引ける() {
        world.llmReplies(
                "[{\"claim\": \"a\", \"query\": \"q1\"}, {\"claim\": \"b\", \"query\": \"q2\"}]",
                "[{\"originalText\": \"開業は1958年\", \"message\": \"m\", \"sources\": [2, 1, 2]}]");
        braveFor("q1", 200, searchResult(1));
        braveFor("q2", 200, searchResult(2));

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(List.of(new SourceReference("出典2", "https://example.test/2"),
                new SourceReference("出典1", "https://example.test/1")), response.suggestions().get(0).sources());
    }

    @Test
    void factCheck_LLM呼び出しが例外を投げたらジョブを失敗にして再送出する() {
        world.llmFails(500, "x");

        assertThrows(RuntimeException.class,
                () -> service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT));

        assertEquals(List.of("llm_review_step_fact_check:running", "llm_review_step_fact_check:failed"),
                world.jobRows.log);
    }

    @Test
    void 既存ステップの応答にはスキップ情報も出典も付かない() {
        world.llmReplies("[{\"originalText\": \"本文\", \"message\": \"指摘\"}]");

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.JAPANESE, "本文");

        assertNull(response.skipped());
        assertNull(response.skipReason());
        assertNull(response.suggestions().get(0).sources());
        // Web検索(システムのキー取得も含む)は一切行わない。
        assertEquals(0, world.http.requests("GET", BRAVE).size());
        assertEquals(0, world.http.requests("GET", SYSTEM_KEY_PATH).size());
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
        braveSucceeds();
        world.llmReplies(FACT_EXTRACTION);

        AiReviewStepSuggestionsResponse response =
                service.generateReviewStepSuggestions(1L, ReviewStepKey.FACT_CHECK, FACT_TEXT);

        assertEquals(Boolean.TRUE, response.skipped());
        assertTrue(response.skipReason().contains("検索結果が見つからず"));
        assertEquals(List.of(), response.suggestions());
        assertEquals(1, world.llmRequests().size());
    }

    // ---- issue #1495: 執筆支援5機能がプロジェクト単位のモデル・プロバイダー選択を使う ----

    /** 5機能のいずれかを、指定のprojectId・providerで実行する(LLMは"[]"を返す)。 */
    private void invokeFeature(String feature, Long projectId, String provider) {
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
        projectUsesOpenAi(7L, "sk-project7");
        world.projectAiSettingsService.setLlmModel(7L, AiProvider.OPENAI, "project-model");
        world.llmReplies("[]");

        invokeFeature(feature, 7L, null);

        assertEquals(1, world.llmRequests().size());
        assertEquals("project-model", world.llmModelOf(world.llmRequests().get(0)));
        assertEquals("/cloud/chat/completions", world.llmRequests().get(0).path());
        assertEquals("Bearer sk-project7", world.llmRequests().get(0).header("Authorization"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ask", "draft", "section", "tags", "proofread"})
    void 執筆支援_provider指定はプロジェクトの選択中プロバイダーより優先される(String feature) {
        projectUsesOpenAi(7L, "sk-project7");
        world.projectAiSettingsService.setLlmProvider(7L, "OLLAMA");
        world.projectAiSettingsService.setLlmModel(7L, AiProvider.OLLAMA, "ollama-model");
        world.projectAiSettingsService.setLlmModel(7L, AiProvider.OPENAI, "project-model");
        world.llmReplies("[]");

        invokeFeature(feature, 7L, "OPENAI");

        assertEquals(1, world.llmRequests().size());
        assertEquals("project-model", world.llmModelOf(world.llmRequests().get(0)));
        assertEquals("/cloud/chat/completions", world.llmRequests().get(0).path());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ask", "draft", "section", "tags", "proofread"})
    void 執筆支援_projectId未指定ならモデルとプロバイダーの解決を呼ばずシステム既定へ委ねる(String feature) {
        world.llmReplies("[]");

        invokeFeature(feature, null, null);

        assertEquals(MODEL, world.llmModelOf(world.llmRequests().get(0)));
        assertEquals("/v1/chat/completions", world.llmRequests().get(0).path());
        verify(world.projectAiSettingsRepository, never()).findByProjectId(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ask", "draft", "section", "tags", "proofread"})
    void 執筆支援_projectId未指定でprovider指定ならそのproviderとシステム既定モデルを渡す(String feature) {
        // プロジェクトが無いのでChatGPTのキー(プロジェクト単位)を引けず、LLMへ送る前にそのproviderで失敗する。
        world.llmConfig(AiProvider.OPENAI, null, "", "gpt-default", List.of("gpt-default"), false);
        AiServiceException e = assertThrows(AiServiceException.class, () -> invokeFeature(feature, null, "OPENAI"));

        assertTrue(e.getMessage().contains("OPENAI"), e.getMessage());
        assertEquals(0, world.llmRequests().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ask", "draft", "section", "tags", "proofread"})
    void 執筆支援_プロジェクトで未選択ならシステム既定のモデルとプロバイダーを使う(String feature) {
        world.llmReplies("[]");

        invokeFeature(feature, 7L, null);

        assertEquals(MODEL, world.llmModelOf(world.llmRequests().get(0)));
        assertEquals("/v1/chat/completions", world.llmRequests().get(0).path());
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
