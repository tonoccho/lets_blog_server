package com.letsblog.ai.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
 */
class LlmClientTest {

    private static final String OPENAI_STYLE_RESPONSE =
            "{\"choices\":[{\"message\":{\"content\":\"こんにちは\"}}]}";

    private HttpServer httpServer;

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
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
    void generate_OLLAMAはchat_completionsへPOSTしAuthorizationヘッダを送らない() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, OPENAI_STYLE_RESPONSE);
        });
        // platform-serviceのapiKeyFor(OLLAMA)は空を返す(issue #1086 / R4)
        LlmClient ollamaClient = new LlmClient(
                new StubConfigProvider(AiProvider.OLLAMA, baseUrl() + "/v1", "", "qwen2.5:7b-instruct"));

        String result = ollamaClient.generate("こんにちは");

        assertEquals("こんにちは", result);
        assertEquals("/v1/chat/completions", path.get());
        assertNull(authorization.get(), "OLLAMAにはAuthorizationヘッダを送らない。実際: " + authorization.get());
    }

    @Test
    void generate_OPENAIは従来どおりBearerトークンを送る() throws IOException {
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, OPENAI_STYLE_RESPONSE);
        });
        LlmClient openAiClient = new LlmClient(
                new StubConfigProvider(AiProvider.OPENAI, baseUrl() + "/v1", "sk-test", "gpt-4o-mini"));

        openAiClient.generate("こんにちは");

        assertEquals("Bearer sk-test", authorization.get());
    }

    @Test
    void generate_モデル未取得の404はモデル名と取得中である旨を含むメッセージにする() throws IOException {
        startServer(exchange -> respond(exchange, 404,
                "{\"error\":{\"message\":\"model \\\"qwen2.5:7b-instruct\\\" not found, try pulling it first\","
                        + "\"type\":\"api_error\",\"param\":null,\"code\":null}}"));
        LlmClient ollamaClient = new LlmClient(
                new StubConfigProvider(AiProvider.OLLAMA, baseUrl() + "/v1", "", "qwen2.5:7b-instruct"));

        AiServiceException e = assertThrows(AiServiceException.class, () -> ollamaClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("qwen2.5:7b-instruct"), "実際: " + e.getMessage());
        assertTrue(e.getMessage().contains("取得"), "取得中であることが分かる文言にする。実際: " + e.getMessage());
        assertFalse(e.getMessage().contains("api_error"), "生の404本文をそのまま流さない。実際: " + e.getMessage());
    }

    /** 実機のOllama 0.x が返す本文(#1086の手動確認で採取)。文言が上のテストと異なる。 */
    @Test
    void generate_実機Ollamaのモデル未取得応答も取得中である旨のメッセージにする() throws IOException {
        startServer(exchange -> respond(exchange, 404,
                "{\"error\":{\"message\":\"model 'qwen2.5:7b-instruct' not found\","
                        + "\"type\":\"not_found_error\",\"param\":null,\"code\":null}}"));
        LlmClient ollamaClient = new LlmClient(
                new StubConfigProvider(AiProvider.OLLAMA, baseUrl() + "/v1", "", "qwen2.5:7b-instruct"));

        AiServiceException e = assertThrows(AiServiceException.class, () -> ollamaClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("qwen2.5:7b-instruct"), "実際: " + e.getMessage());
        assertTrue(e.getMessage().contains("取得"), "実際: " + e.getMessage());
        assertFalse(e.getMessage().contains("not_found_error"), "生の404本文をそのまま流さない。実際: " + e.getMessage());
    }

    @Test
    void generate_モデル未取得以外の404は従来どおり応答内容を添えたメッセージにする() throws IOException {
        startServer(exchange -> respond(exchange, 404, "{\"error\":\"no such endpoint\"}"));
        LlmClient ollamaClient = new LlmClient(
                new StubConfigProvider(AiProvider.OLLAMA, baseUrl() + "/v1", "", "qwen2.5:7b-instruct"));

        AiServiceException e = assertThrows(AiServiceException.class, () -> ollamaClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("LLM呼び出しに失敗しました"), "実際: " + e.getMessage());
        assertTrue(e.getMessage().contains("no such endpoint"), "実際: " + e.getMessage());
    }

    /**
     * モデル未取得の案内はOllama固有(「docker logs -f lbs-ollama-model-init で確認」)なので、
     * OLLAMA以外のプロバイダに適用してはならない。OpenAI互換エンドポイントは
     * OpenAI本体に限らずGroq/OpenRouter/自前サーバ等でもよく(.env.example の LLM_BASE_URL)、
     * それらが返す404の本文に "not found" が含まれることは十分ありうる。
     */
    @Test
    void generate_OPENAIの404は本文にnotfoundを含んでもOllama向けの案内にしない() throws IOException {
        startServer(exchange -> respond(exchange, 404,
                "{\"error\":{\"message\":\"The model 'gpt-4o-mini' does not exist or you do not have access"
                        + " to it: not found\",\"type\":\"invalid_request_error\"}}"));
        LlmClient openAiClient = new LlmClient(
                new StubConfigProvider(AiProvider.OPENAI, baseUrl() + "/v1", "sk-test", "gpt-4o-mini"));

        AiServiceException e = assertThrows(AiServiceException.class, () -> openAiClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("LLM呼び出しに失敗しました"), "実際: " + e.getMessage());
        assertFalse(e.getMessage().contains("Ollama"),
                "OLLAMA以外にOllamaの案内を出さない。実際: " + e.getMessage());
        assertFalse(e.getMessage().contains("ollama-model-init"),
                "OLLAMA以外にOllamaの案内を出さない。実際: " + e.getMessage());
    }

    @Test
    void generate_401などモデル未取得以外の失敗は従来どおりのメッセージ() throws IOException {
        startServer(exchange -> respond(exchange, 401, "{\"error\":\"Incorrect API key\"}"));
        LlmClient openAiClient = new LlmClient(
                new StubConfigProvider(AiProvider.OPENAI, baseUrl() + "/v1", "sk-bad", "gpt-4o-mini"));

        AiServiceException e = assertThrows(AiServiceException.class, () -> openAiClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("LLM呼び出しに失敗しました"), "実際: " + e.getMessage());
        assertTrue(e.getMessage().contains("Incorrect API key"), "実際: " + e.getMessage());
    }

    @Test
    void generate_モデル名を指定するとそのモデルで呼び出す() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        startServer(exchange -> {
            body.set(readBody(exchange));
            respond(exchange, 200, OPENAI_STYLE_RESPONSE);
        });
        LlmClient ollamaClient = new LlmClient(
                new StubConfigProvider(AiProvider.OLLAMA, baseUrl() + "/v1", "", "qwen2.5:7b-instruct"));

        ollamaClient.generate("こんにちは", "qwen3:8b");

        assertTrue(body.get().contains("\"model\":\"qwen3:8b\""), "実際: " + body.get());
    }

    @Test
    void generate_モデル名が空白なら既定モデルで呼び出す() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        startServer(exchange -> {
            body.set(readBody(exchange));
            respond(exchange, 200, OPENAI_STYLE_RESPONSE);
        });
        LlmClient ollamaClient = new LlmClient(
                new StubConfigProvider(AiProvider.OLLAMA, baseUrl() + "/v1", "", "qwen2.5:7b-instruct"));

        ollamaClient.generate("こんにちは", "   ");

        assertTrue(body.get().contains("\"model\":\"qwen2.5:7b-instruct\""), "実際: " + body.get());
    }

    @Test
    void generate_プロバイダ上書きが優先される() throws IOException {
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, OPENAI_STYLE_RESPONSE);
        });
        // 既定はOPENAIだが、呼び出し側がOLLAMAを指定する(issue #530のプロジェクト単位上書き相当)
        LlmClient client2 = new LlmClient(
                new StubConfigProvider(AiProvider.OPENAI, baseUrl() + "/v1", "", "gpt-4o-mini"));

        client2.generate("こんにちは", null, AiProvider.OLLAMA);

        assertNull(authorization.get(), "上書き先のOLLAMAとして扱う。実際: " + authorization.get());
    }

    @Test
    void generate_OLLAMA以外はAPIキー未設定なら呼び出す前に例外() {
        LlmClient openAiClient = new LlmClient(
                new StubConfigProvider(AiProvider.OPENAI, "http://127.0.0.1:1/v1", "", "gpt-4o-mini"));

        AiServiceException e = assertThrows(AiServiceException.class, () -> openAiClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("このプロジェクトでAPIキーを設定してください"), "実際: " + e.getMessage());
    }

    @Test
    void generate_APIキーがnullでも呼び出す前に例外() {
        LlmClient claudeClient = new LlmClient(
                new StubConfigProvider(AiProvider.CLAUDE, "http://127.0.0.1:1/v1", null, "claude-3-5-haiku-20241022"));

        AiServiceException e = assertThrows(AiServiceException.class, () -> claudeClient.generate("こんにちは"));

        assertTrue(e.getMessage().contains("このプロジェクトでAPIキーを設定してください"), "実際: " + e.getMessage());
    }

    @Test
    void generate_接続できない場合はネットワークエラーのメッセージにする() {
        LlmClient ollamaClient = new LlmClient(
                new StubConfigProvider(AiProvider.OLLAMA, "http://127.0.0.1:1/v1", "", "qwen2.5:7b-instruct"));

        AiServiceException e = assertThrows(AiServiceException.class, () -> ollamaClient.generate("こんにちは"));

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
    void generate_画像付きOLLAMAはimage_urlを含む本文をchat_completionsへ送る() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        startServer(exchange -> {
            body.set(readBody(exchange));
            respond(exchange, 200, OPENAI_STYLE_RESPONSE);
        });
        LlmClient ollamaClient = new LlmClient(
                new StubConfigProvider(AiProvider.OLLAMA, baseUrl() + "/v1", "", "llava:7b"));

        String result = ollamaClient.generate("説明して", PNG_INPUT, "llava:7b", null);

        assertEquals("こんにちは", result);
        assertTrue(body.get().contains("\"image_url\""), "実際: " + body.get());
        assertTrue(body.get().contains("data:image/png;base64,AQID"), "実際: " + body.get());
        assertTrue(body.get().contains("\"model\":\"llava:7b\""), "実際: " + body.get());
    }

    @Test
    void generate_画像付きOPENAIもimage_urlを送りBearerトークンを付ける() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            body.set(readBody(exchange));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, OPENAI_STYLE_RESPONSE);
        });
        LlmClient openAiClient = new LlmClient(
                new StubConfigProvider(AiProvider.OPENAI, baseUrl() + "/v1", "sk-test", "gpt-4o-mini"));

        openAiClient.generate("説明して", PNG_INPUT, null, null);

        assertTrue(body.get().contains("data:image/png;base64,AQID"), "実際: " + body.get());
        assertEquals("Bearer sk-test", authorization.get());
    }

    @Test
    void generate_画像付きでもOLLAMA以外はAPIキー未設定なら呼び出す前に例外() {
        LlmClient openAiClient = new LlmClient(
                new StubConfigProvider(AiProvider.OPENAI, "http://127.0.0.1:1/v1", "", "gpt-4o-mini"));

        AiServiceException e = assertThrows(AiServiceException.class,
                () -> openAiClient.generate("説明して", PNG_INPUT, null, null));

        assertTrue(e.getMessage().contains("このプロジェクトでAPIキーを設定してください"), "実際: " + e.getMessage());
    }

    // ---- テスト用の土台 ----

    private void startServer(HttpHandler handler) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", handler);
        httpServer.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }

    private static String readBody(HttpExchange exchange) {
        try (java.io.InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** LlmConfigProviderのテスト用実装。プロバイダごとの解決はplatform-service側の責務なので固定値を返す。 */
    private record StubConfigProvider(AiProvider provider, String baseUrl, String apiKey, String model)
            implements LlmConfigProvider {

        @Override
        public String defaultModel() {
            return model;
        }

        @Override
        public long requestTimeoutSeconds() {
            return 10L;
        }

        @Override
        public String apiKeyFor(AiProvider requested) {
            return apiKey;
        }

        @Override
        public String defaultModelFor(AiProvider requested) {
            return model;
        }

        @Override
        public String baseUrlFor(AiProvider requested) {
            return requested == AiProvider.CLAUDE ? LlmClient.ANTHROPIC_BASE_URL : baseUrl;
        }

        @Override
        public List<String> availableModels() {
            return List.of(model);
        }

        @Override
        public List<String> availableModelsFor(AiProvider requested) {
            return List.of(model);
        }
    }

    @Test
    void useProject_接続設定プロバイダーへ委譲する() {
        LlmConfigProvider provider = org.mockito.Mockito.mock(LlmConfigProvider.class);

        new LlmClient(provider).useProject(9L);

        org.mockito.Mockito.verify(provider).useProject(9L);
    }

    @Test
    void useProject_既定実装は何もしない() {
        // テスト専用コンストラクタが作る匿名のLlmConfigProviderはuseProjectを実装しない(defaultの空実装)。
        new LlmClient("https://api.openai.com/v1", "k", "m", 1L).useProject(9L);
    }
}
