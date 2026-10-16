package com.letsblog.ai.ai;

import com.letsblog.ai.testsupport.AiWorld;
import com.letsblog.ai.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LlmClientの回帰テスト。推論モデル経由で出力に混入しうる<think>...</think>ブロックの
 * 除去ロジックに加え、issue #1086 でOLLAMAプロバイダの呼び出し(Authorizationヘッダを送らない、
 * モデル未取得404のメッセージ整形)を検証する。HTTPはJDK標準のHttpServerで受ける
 * (LlmClientはRestClientを内部で組み立てるためMockRestServiceServerを差し込めない。
 * ContentServiceClientTest / GenerationJobClientTestと同じ手法)。
 *
 * <p>issue #1824: LlmClientとLlmConfigProvider(RemoteLlmConfigProvider、固定のクラウドホストだけ向け替え)は実物で、
 * platform-serviceとLLMの相手はHTTPフェイク、DBはメモリ上のリポジトリで差し替える。
 */
class LlmClientTest {

    private AiWorld world;

    @BeforeEach
    void setUp() {
        world = new AiWorld().ollamaDefault("qwen2.5:7b-instruct");
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private final LlmClient client = new LlmClient("https://api.openai.com/v1", "test-key", "gpt-4o-mini", 120L);

    @Test
    void stripThinkingBlocks_thinkブロックを除去する() {
        String raw = "<think>この記事はADHDについてで、タイトルは{ダミー}にしよう</think>{\"title\":\"タイトル\"}";

        String result = client.stripThinkingBlocks(raw);

        assertEquals("{\"title\":\"タイトル\"}", result);
    }

    @Test
    void stripThinkingBlocks_複数行のthinkブロックにも対応する() {
        String raw = "<think>\n複数行の思考\n{ここにも波括弧}\n</think>\n{\"title\":\"タイトル\"}";

        String result = client.stripThinkingBlocks(raw);

        assertEquals("{\"title\":\"タイトル\"}", result);
    }

    @Test
    void stripThinkingBlocks_thinkブロックがなければそのまま返す() {
        String raw = "{\"title\":\"タイトル\"}";

        String result = client.stripThinkingBlocks(raw);

        assertEquals("{\"title\":\"タイトル\"}", result);
    }

    // ---- OLLAMAプロバイダ(issue #1086) ----

    @Test
    void generate_OLLAMAはchat_completionsへPOSTしAuthorizationヘッダを送らない() {
        // platform-serviceのapiKeyFor(OLLAMA)は空を返す(issue #1086 / R4)
        world.llmReplies("こんにちは");

        String result = world.llmClient.generate("こんにちは");

        assertEquals("こんにちは", result);
        FakeHttpServer.Request request = world.llmRequests().get(0);
        assertEquals("/v1/chat/completions", request.path());
        assertNull(request.header("Authorization"), "OLLAMAにはAuthorizationヘッダを送らない。実際: " + request.header("Authorization"));
    }

    @Test
    void generate_OPENAIは従来どおりBearerトークンを送る() {
        openAiDefaultWithProjectKey("sk-test");
        world.llmReplies("こんにちは");

        world.llmClient.generate("こんにちは");

        assertEquals("Bearer sk-test", world.llmRequests().get(0).header("Authorization"));
        assertEquals("gpt-4o-mini", world.llmModelOf(world.llmRequests().get(0)));
    }

    @Test
    void generate_モデル未取得の404はモデル名と取得中である旨を含むメッセージにする() {
        world.llmFails(404,
                "{\"error\":{\"message\":\"model \\\"qwen2.5:7b-instruct\\\" not found, try pulling it first\","
                        + "\"type\":\"api_error\",\"param\":null,\"code\":null}}");

        AiServiceException e = assertThrows(AiServiceException.class, () -> world.llmClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("qwen2.5:7b-instruct"), "実際: " + e.getMessage());
        assertTrue(e.getMessage().contains("取得"), "取得中であることが分かる文言にする。実際: " + e.getMessage());
        assertFalse(e.getMessage().contains("api_error"), "生の404本文をそのまま流さない。実際: " + e.getMessage());
        assertTrue(e.getMessage().contains("ollama pull qwen2.5:7b-instruct"),
                "Ollamaのあるホストでの ollama pull を案内する。実際: " + e.getMessage());
        assertFalse(e.getMessage().contains("lbs-ollama-model-init"),
                "存在しないコンテナを案内しない。実際: " + e.getMessage());
    }

    // ---- Ollama接続先が未設定(issue #1567) ----

    @Test
    void generate_OLLAMAの接続先が空なら接続を試みず未設定と分かるエラーにする() {
        world.llmConfig(AiProvider.OLLAMA, "", "", "qwen2.5:7b-instruct", List.of("qwen2.5:7b-instruct"), true);

        AiServiceException e = assertThrows(AiServiceException.class, () -> world.llmClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("Ollama"), "実際: " + e.getMessage());
        assertTrue(e.getMessage().contains("接続先が設定されていません"), "実際: " + e.getMessage());
        assertTrue(e.getMessage().contains("設定してください"), "設定を促す文言にする。実際: " + e.getMessage());
        assertTrue(world.llmRequests().isEmpty());
    }

    @Test
    void generate_OLLAMAの接続先がnullでも未設定として扱う() {
        world.llmConfig(AiProvider.OLLAMA, null, "", "qwen2.5:7b-instruct", List.of("qwen2.5:7b-instruct"), true);

        AiServiceException e = assertThrows(AiServiceException.class, () -> world.llmClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("接続先が設定されていません"), "実際: " + e.getMessage());
    }

    @Test
    void generate_OLLAMAの接続先が空白だけでも未設定として扱う() {
        world.llmConfig(AiProvider.OLLAMA, "   ", "", "qwen2.5:7b-instruct", List.of("qwen2.5:7b-instruct"), true);

        AiServiceException e = assertThrows(AiServiceException.class, () -> world.llmClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("接続先が設定されていません"), "実際: " + e.getMessage());
    }

    /** 実機のOllama 0.x が返す本文(#1086の手動確認で採取)。文言が上のテストと異なる。 */
    @Test
    void generate_実機Ollamaのモデル未取得応答も取得中である旨のメッセージにする() {
        world.llmFails(404, "{\"error\":{\"message\":\"model 'qwen2.5:7b-instruct' not found\","
                + "\"type\":\"not_found_error\",\"param\":null,\"code\":null}}");

        AiServiceException e = assertThrows(AiServiceException.class, () -> world.llmClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("qwen2.5:7b-instruct"), "実際: " + e.getMessage());
        assertTrue(e.getMessage().contains("取得"), "実際: " + e.getMessage());
        assertFalse(e.getMessage().contains("not_found_error"), "生の404本文をそのまま流さない。実際: " + e.getMessage());
    }

    @Test
    void generate_モデル未取得以外の404は従来どおり応答内容を添えたメッセージにする() {
        world.llmFails(404, "{\"error\":\"no such endpoint\"}");

        AiServiceException e = assertThrows(AiServiceException.class, () -> world.llmClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("LLM呼び出しに失敗しました"), "実際: " + e.getMessage());
        assertTrue(e.getMessage().contains("no such endpoint"), "実際: " + e.getMessage());
    }

    /**
     * モデル未取得の案内はOllama固有(「Ollamaのあるホストで ollama pull」)なので、
     * OLLAMA以外のプロバイダに適用してはならない。OpenAI互換エンドポイントは
     * OpenAI本体に限らずGroq/OpenRouter/自前サーバ等でもよく(.env.example の LLM_BASE_URL)、
     * それらが返す404の本文に "not found" が含まれることは十分ありうる。
     */
    @Test
    void generate_OPENAIの404は本文にnotfoundを含んでもOllama向けの案内にしない() {
        openAiDefaultWithProjectKey("sk-test");
        world.llmFails(404, "{\"error\":{\"message\":\"The model 'gpt-4o-mini' does not exist or you do not have access"
                + " to it: not found\",\"type\":\"invalid_request_error\"}}");

        AiServiceException e = assertThrows(AiServiceException.class, () -> world.llmClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("LLM呼び出しに失敗しました"), "実際: " + e.getMessage());
        assertFalse(e.getMessage().contains("Ollama"),
                "OLLAMA以外にOllamaの案内を出さない。実際: " + e.getMessage());
        assertFalse(e.getMessage().contains("ollama-model-init"),
                "OLLAMA以外にOllamaの案内を出さない。実際: " + e.getMessage());
    }

    @Test
    void generate_401などモデル未取得以外の失敗は従来どおりのメッセージ() {
        openAiDefaultWithProjectKey("sk-bad");
        world.llmFails(401, "{\"error\":\"Incorrect API key\"}");

        AiServiceException e = assertThrows(AiServiceException.class, () -> world.llmClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("LLM呼び出しに失敗しました"), "実際: " + e.getMessage());
        assertTrue(e.getMessage().contains("Incorrect API key"), "実際: " + e.getMessage());
    }

    @Test
    void generate_モデル名を指定するとそのモデルで呼び出す() {
        world.llmReplies("こんにちは");

        world.llmClient.generate("こんにちは", "qwen3:8b");

        assertTrue(world.llmRequests().get(0).bodyText().contains("\"model\":\"qwen3:8b\""),
                "実際: " + world.llmRequests().get(0).bodyText());
    }

    @Test
    void generate_モデル名が空白なら既定モデルで呼び出す() {
        world.llmReplies("こんにちは");

        world.llmClient.generate("こんにちは", "   ");

        assertTrue(world.llmRequests().get(0).bodyText().contains("\"model\":\"qwen2.5:7b-instruct\""),
                "実際: " + world.llmRequests().get(0).bodyText());
    }

    @Test
    void generate_プロバイダ上書きが優先される() {
        // 既定はOPENAIだが、呼び出し側がOLLAMAを指定する(issue #530のプロジェクト単位上書き相当)
        world.llmConfig(AiProvider.OPENAI, null, "", "gpt-4o-mini", List.of("gpt-4o-mini"), true);
        world.llmConfig(AiProvider.OLLAMA, world.http.baseUrl() + "/v1", "", "qwen2.5:7b-instruct",
                List.of("qwen2.5:7b-instruct"), false);
        world.llmReplies("こんにちは");

        world.llmClient.generate("こんにちは", null, AiProvider.OLLAMA);

        FakeHttpServer.Request request = world.llmRequests().get(0);
        assertNull(request.header("Authorization"), "上書き先のOLLAMAとして扱う。実際: " + request.header("Authorization"));
        assertEquals("/v1/chat/completions", request.path());
    }

    @Test
    void generate_OLLAMA以外はAPIキー未設定なら呼び出す前に例外() {
        world.llmConfig(AiProvider.OPENAI, null, "", "gpt-4o-mini", List.of("gpt-4o-mini"), true);

        AiServiceException e = assertThrows(AiServiceException.class, () -> world.llmClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("このプロジェクトでAPIキーを設定してください"), "実際: " + e.getMessage());
        assertTrue(world.llmRequests().isEmpty());
    }

    @Test
    void generate_APIキーがnullでも呼び出す前に例外() {
        // テスト専用コンストラクタが作る固定値の接続設定は、nullのキーをそのまま返す。
        LlmClient nullKeyClient = new LlmClient("http://127.0.0.1:1/v1", null, "gpt-4o-mini", 1L);

        AiServiceException e = assertThrows(AiServiceException.class, () -> nullKeyClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("このプロジェクトでAPIキーを設定してください"), "実際: " + e.getMessage());
    }

    @Test
    void generate_APIキーが空白だけでも呼び出す前に例外() {
        LlmClient blankKeyClient = new LlmClient("http://127.0.0.1:1/v1", "   ", "gpt-4o-mini", 1L);

        AiServiceException e = assertThrows(AiServiceException.class, () -> blankKeyClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("このプロジェクトでAPIキーを設定してください"), "実際: " + e.getMessage());
    }

    @Test
    void generate_CLAUDEもAPIキー未設定なら呼び出す前に例外() {
        world.llmConfig(AiProvider.CLAUDE, null, "", "claude-3-5-haiku-20241022",
                List.of("claude-3-5-haiku-20241022"), true);

        AiServiceException e = assertThrows(AiServiceException.class, () -> world.llmClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("このプロジェクトでAPIキーを設定してください"), "実際: " + e.getMessage());
        assertTrue(world.llmRequests().isEmpty());
    }

    @Test
    void generate_接続できない場合はネットワークエラーのメッセージにする() {
        world.llmConfig(AiProvider.OLLAMA, "http://127.0.0.1:1/v1", "", "qwen2.5:7b-instruct",
                List.of("qwen2.5:7b-instruct"), true);

        AiServiceException e = assertThrows(AiServiceException.class, () -> world.llmClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("タイムアウトまたはネットワークエラー"), "実際: " + e.getMessage());
    }

    // ---- 画像入力(issue #1600) ----

    private static final LlmClient.ImageInput PNG_INPUT =
            new LlmClient.ImageInput("image/png", new byte[] {1, 2, 3});

    @Test
    void openAiUserMessage_画像なしは従来どおり文字列のcontent() {
        com.fasterxml.jackson.databind.JsonNode message = LlmClient.openAiUserMessage("こんにちは", null);

        assertEquals("user", message.get("role").asText());
        assertEquals("こんにちは", message.get("content").asText());
    }

    @Test
    void openAiUserMessage_画像ありはtextとimage_urlのdataURLを並べる() {
        com.fasterxml.jackson.databind.JsonNode content = LlmClient.openAiUserMessage("説明して", PNG_INPUT).get("content");

        assertEquals("text", content.get(0).get("type").asText());
        assertEquals("説明して", content.get(0).get("text").asText());
        assertEquals("image_url", content.get(1).get("type").asText());
        assertEquals("data:image/png;base64,AQID", content.get(1).get("image_url").get("url").asText());
    }

    @Test
    void claudeUserMessage_画像なしは従来どおり文字列のcontent() {
        assertEquals("こんにちは", LlmClient.claudeUserMessage("こんにちは", null).get("content").asText());
    }

    @Test
    void claudeUserMessage_画像ありはimageブロックとtextブロックを並べる() {
        com.fasterxml.jackson.databind.JsonNode content = LlmClient.claudeUserMessage("説明して", PNG_INPUT).get("content");

        assertEquals("image", content.get(0).get("type").asText());
        assertEquals("base64", content.get(0).get("source").get("type").asText());
        assertEquals("image/png", content.get(0).get("source").get("media_type").asText());
        assertEquals("AQID", content.get(0).get("source").get("data").asText());
        assertEquals("text", content.get(1).get("type").asText());
        assertEquals("説明して", content.get(1).get("text").asText());
    }

    @Test
    void generate_画像付きOLLAMAはimage_urlを含む本文をchat_completionsへ送る() {
        world.llmReplies("こんにちは");

        String result = world.llmClient.generate("説明して", PNG_INPUT, "llava:7b", null);

        assertEquals("こんにちは", result);
        String body = world.llmRequests().get(0).bodyText();
        assertTrue(body.contains("\"image_url\""), "実際: " + body);
        assertTrue(body.contains("data:image/png;base64,AQID"), "実際: " + body);
        assertTrue(body.contains("\"model\":\"llava:7b\""), "実際: " + body);
    }

    @Test
    void generate_画像付きOPENAIもimage_urlを送りBearerトークンを付ける() {
        openAiDefaultWithProjectKey("sk-test");
        world.llmReplies("こんにちは");

        world.llmClient.generate("説明して", PNG_INPUT, null, null);

        assertTrue(world.llmRequests().get(0).bodyText().contains("data:image/png;base64,AQID"),
                "実際: " + world.llmRequests().get(0).bodyText());
        assertEquals("Bearer sk-test", world.llmRequests().get(0).header("Authorization"));
    }

    @Test
    void generate_画像付きでもOLLAMA以外はAPIキー未設定なら呼び出す前に例外() {
        world.llmConfig(AiProvider.OPENAI, null, "", "gpt-4o-mini", List.of("gpt-4o-mini"), true);

        AiServiceException e = assertThrows(AiServiceException.class,
                () -> world.llmClient.generate("説明して", PNG_INPUT, null, null));

        assertTrue(e.getMessage().contains("このプロジェクトでAPIキーを設定してください"), "実際: " + e.getMessage());
        assertTrue(world.llmRequests().isEmpty());
    }

    // ---- テスト用の土台 ----

    /** 既定プロバイダーがChatGPT(OpenAI互換)で、プロジェクト7がキーを持つ状態にする。呼び出しはプロジェクト7を宣言して行う。 */
    private void openAiDefaultWithProjectKey(String key) {
        world.llmConfig(AiProvider.OPENAI, null, "", "gpt-4o-mini", List.of("gpt-4o-mini"), true);
        world.projectAiSettingsService.setOpenAiApiKeyEncrypted(7L, world.credentialCipher.encrypt(key));
        world.llmClient.useProject(7L);
    }

    @Test
    void useProject_接続設定プロバイダーへ委譲する() {
        // 宣言したプロジェクトのキーで呼ばれる(宣言が無ければ、キー未設定で呼び出す前に失敗する)。
        openAiDefaultWithProjectKey("sk-project");
        world.llmReplies("こんにちは");

        world.llmClient.useProject(7L);
        world.llmClient.generate("こんにちは");

        assertEquals("Bearer sk-project", world.llmRequests().get(0).header("Authorization"));
        world.llmClient.useProject(null);
        assertThrows(AiServiceException.class, () -> world.llmClient.generate("こんにちは"));
    }

    @Test
    void useProject_既定実装は何もしない() {
        // テスト専用コンストラクタが作る匿名のLlmConfigProviderはuseProjectを実装しない(defaultの空実装)。
        new LlmClient("https://api.openai.com/v1", "k", "m", 1L).useProject(9L);
    }
}
