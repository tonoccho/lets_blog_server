package com.letsblog.plugins.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Claude アダプター(#1812)。LLM の契約(docs/plugins/CONTRACTS.md §1)を話し、Anthropic Messages API へ翻訳する。
 *
 * <ul>
 *   <li>{@code POST /chat/completions} → Anthropic {@code POST /v1/messages}</li>
 *   <li>{@code GET /models} → Anthropic {@code GET /v1/models?limit=1000}(各モデルに {@code capabilities.image_input})</li>
 * </ul>
 *
 * <p>受けた {@code Authorization: Bearer <値>} を Anthropic の API キー({@code x-api-key})としてそのまま使う
 * (アダプターは秘密を持たない)。再試行はしない。失敗は OpenAI 互換のエラー本文で返す(§0)。
 */
public final class ClaudeAdapter implements AutoCloseable {

    static final String ANTHROPIC_VERSION = "2023-06-01";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String MODELS_QUERY = "?limit=1000";

    /** 起動設定。{@code port} が 0 なら空きポートを使う。 */
    public record Config(int port, String anthropicBaseUrl, Duration timeout) {

        static final int DEFAULT_PORT = 8080;
        static final String DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com";
        static final int DEFAULT_TIMEOUT_SECONDS = 120;

        /** 環境変数 PORT / ANTHROPIC_BASE_URL / LLM_REQUEST_TIMEOUT_SECONDS から作る。空は既定値。 */
        static Config fromEnv(Map<String, String> env) {
            String baseUrl = env.getOrDefault("ANTHROPIC_BASE_URL", "").isBlank()
                    ? DEFAULT_ANTHROPIC_BASE_URL : env.get("ANTHROPIC_BASE_URL").strip();
            return new Config(
                    Integer.parseInt(env.getOrDefault("PORT", "").isBlank()
                            ? String.valueOf(DEFAULT_PORT) : env.get("PORT").strip()),
                    baseUrl.replaceAll("/+$", ""),
                    Duration.ofSeconds(Long.parseLong(env.getOrDefault("LLM_REQUEST_TIMEOUT_SECONDS", "").isBlank()
                            ? String.valueOf(DEFAULT_TIMEOUT_SECONDS) : env.get("LLM_REQUEST_TIMEOUT_SECONDS").strip())));
        }
    }

    private final Config config;
    private final HttpClient http;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private ClaudeAdapter(Config config) throws IOException {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
        this.server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        this.server.setExecutor(executor);
        this.server.createContext("/", this::handle);
    }

    public static ClaudeAdapter start(Config config) throws IOException {
        ClaudeAdapter adapter = new ClaudeAdapter(config);
        adapter.server.start();
        return adapter;
    }

    public static void main(String[] args) throws IOException {
        ClaudeAdapter adapter = start(Config.fromEnv(System.getenv()));
        System.out.println("claude-adapter listening on :" + adapter.port());
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    // ---- ルーティング ----

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String route = path.startsWith("/v1/") ? path.substring("/v1".length()) : path;
            String method = exchange.getRequestMethod();
            switch (route) {
                case "/chat/completions" -> {
                    requireMethod(method, "POST");
                    respond(exchange, 200, chatCompletions(apiKey(exchange), exchange.getRequestBody().readAllBytes()));
                }
                case "/models" -> {
                    requireMethod(method, "GET");
                    respond(exchange, 200, models(apiKey(exchange)));
                }
                default -> throw new AdapterException(404, "invalid_request_error", "not_found", "未対応のパスです: " + path);
            }
        } catch (AdapterException e) {
            respond(exchange, e.status(), errorBody(e));
        } catch (RuntimeException e) {
            // 想定外の例外(不正な Bearer 文字・不正な ANTHROPIC_BASE_URL など)。キーを含む詳細は返さない。
            respond(exchange, 500, errorBody(
                    new AdapterException(500, "api_error", "internal_error", "アダプターの内部エラーです")));
        }
    }

    private static void requireMethod(String actual, String expected) throws AdapterException {
        if (!expected.equals(actual)) {
            throw new AdapterException(405, "invalid_request_error", "method_not_allowed",
                    expected + " だけを受け付けます");
        }
    }

    /** Authorization: Bearer の値(= Anthropic の API キー)。無い・空なら 401。 */
    private static String apiKey(HttpExchange exchange) throws AdapterException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String token = header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())
                ? header.substring(BEARER_PREFIX.length()).strip() : "";
        if (token.isEmpty()) {
            throw new AdapterException(401, "authentication_error", "missing_api_key",
                    "Authorization: Bearer <Anthropic の API キー> が必要です");
        }
        return token;
    }

    // ---- /chat/completions ----

    private ObjectNode chatCompletions(String apiKey, byte[] requestBody) throws AdapterException {
        JsonNode request = parseRequest(requestBody);
        ObjectNode anthropicRequest = MessageTranslator.toAnthropicRequest(request);
        JsonNode response = callAnthropic(
                HttpRequest.newBuilder(anthropicUri("/v1/messages"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(anthropicRequest.toString())),
                apiKey);
        String text = firstText(response);
        ObjectNode out = JsonNodeFactory.instance.objectNode()
                .put("object", "chat.completion")
                .put("model", anthropicRequest.get("model").asText());
        ObjectNode choice = out.putArray("choices").addObject().put("index", 0).put("finish_reason", "stop");
        choice.putObject("message").put("role", "assistant").put("content", text);
        return out;
    }

    private static JsonNode parseRequest(byte[] body) throws AdapterException {
        try {
            JsonNode request = MAPPER.readTree(body);
            if (!request.isObject()) {
                throw AdapterException.invalidRequest("リクエスト本文は JSON オブジェクトにしてください");
            }
            return request;
        } catch (IOException e) {
            throw AdapterException.invalidRequest("リクエスト本文が JSON ではありません");
        }
    }

    private static String firstText(JsonNode response) throws AdapterException {
        for (JsonNode block : response.path("content")) {
            if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) {
                return block.get("text").asText();
            }
        }
        throw badGateway("Anthropic の応答に text ブロックがありません");
    }

    // ---- /models ----

    private ObjectNode models(String apiKey) throws AdapterException {
        JsonNode response = callAnthropic(
                HttpRequest.newBuilder(anthropicUri("/v1/models" + MODELS_QUERY)).GET(), apiKey);
        if (!response.path("data").isArray()) {
            throw badGateway("Anthropic のモデル一覧に data がありません");
        }
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ArrayNode data = out.putArray("data");
        for (JsonNode model : response.get("data")) {
            String id = model.path("id").asText("");
            if (!id.isBlank()) {
                ObjectNode entry = data.addObject().put("id", id).put("object", "model");
                entry.putObject("capabilities").put("image_input", VisionSupport.supportsImageInput(id));
            }
        }
        return out;
    }

    // ---- Anthropic への呼び出し ----

    private URI anthropicUri(String pathAndQuery) {
        return URI.create(config.anthropicBaseUrl() + pathAndQuery);
    }

    /** 再試行せずに 1 回だけ呼ぶ。2xx 以外・届かない・応答が JSON でないときは AdapterException。 */
    private JsonNode callAnthropic(HttpRequest.Builder builder, String apiKey) throws AdapterException {
        HttpRequest request = builder
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .timeout(config.timeout())
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw badGateway("Anthropic API に届きません(タイムアウトまたはネットワークエラーの可能性があります): " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw badGateway("Anthropic API の呼び出しが中断されました");
        }
        if (response.statusCode() / 100 != 2) {
            throw upstreamError(response.statusCode(), response.body());
        }
        try {
            return MAPPER.readTree(response.body());
        } catch (JsonProcessingException e) {
            throw badGateway("Anthropic の応答が JSON ではありません");
        }
    }

    /** Anthropic のエラーを、同じステータスと OpenAI 互換のエラー本文にする。 */
    private static AdapterException upstreamError(int status, String body) {
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            parsed = MissingNode.getInstance();
        }
        JsonNode error = parsed.path("error");
        String detail = error.path("message").isTextual() ? error.get("message").asText() : body;
        String type = error.path("type").isTextual() ? error.get("type").asText() : "api_error";
        return new AdapterException(status >= 400 ? status : 502, type, "upstream_http_" + status,
                "Anthropic API がエラーを返しました(HTTP " + status + "): " + (detail.isBlank() ? "(本文なし)" : detail));
    }

    private static AdapterException badGateway(String message) {
        return new AdapterException(502, "api_error", "bad_gateway", message);
    }

    // ---- 応答 ----

    private static ObjectNode errorBody(AdapterException e) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.putObject("error").put("message", e.getMessage()).put("type", e.type()).put("code", e.code());
        return out;
    }

    private static void respond(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
