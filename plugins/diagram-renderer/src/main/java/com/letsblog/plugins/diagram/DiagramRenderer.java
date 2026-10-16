package com.letsblog.plugins.diagram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 図のレンダラー(#1828)。図のレンダリングの契約(docs/plugins/CONTRACTS.md §3)を話す。
 *
 * <ul>
 *   <li>{@code POST /render/plantuml} → PlantUML サーバーの {@code GET /png/<エンコード>}(§3.1)</li>
 *   <li>{@code GET /capabilities} → {@code {"notations":["plantuml"]}}(§3.3)</li>
 * </ul>
 *
 * <p>受けた {@code Authorization} は、そのまま PlantUML サーバーへ転送する(アダプターは秘密を持たない)。
 * 再試行はしない。JSON 本文の上限は既定 10 MiB(超えたら 413)。
 */
public final class DiagramRenderer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 起動設定。{@code port} が 0 なら空きポートを使う。 */
    public record Config(int port, String plantUmlBaseUrl, Duration timeout, long maxBodyBytes) {

        static final int DEFAULT_PORT = 8080;
        static final String DEFAULT_PLANTUML_BASE_URL = "http://localhost:8081";
        static final long DEFAULT_TIMEOUT_SECONDS = 30;
        static final long DEFAULT_MAX_BODY_BYTES = 10L * 1024 * 1024;

        /** 環境変数 PORT / PLANTUML_BASE_URL / PLANTUML_REQUEST_TIMEOUT_SECONDS / RENDER_MAX_BODY_BYTES から作る。空は既定値。 */
        static Config fromEnv(Map<String, String> env) {
            return new Config(
                    (int) number(env, "PORT", DEFAULT_PORT),
                    value(env, "PLANTUML_BASE_URL", DEFAULT_PLANTUML_BASE_URL).replaceAll("/+$", ""),
                    Duration.ofSeconds(number(env, "PLANTUML_REQUEST_TIMEOUT_SECONDS", DEFAULT_TIMEOUT_SECONDS)),
                    number(env, "RENDER_MAX_BODY_BYTES", DEFAULT_MAX_BODY_BYTES));
        }

        private static String value(Map<String, String> env, String key, String fallback) {
            String v = env.getOrDefault(key, "");
            return v.isBlank() ? fallback : v.strip();
        }

        private static long number(Map<String, String> env, String key, long fallback) {
            return Long.parseLong(value(env, key, String.valueOf(fallback)));
        }
    }

    private final Config config;
    private final PlantUmlClient plantUml;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private DiagramRenderer(Config config) throws IOException {
        this.config = config;
        this.plantUml = new PlantUmlClient(config.plantUmlBaseUrl(), config.timeout());
        this.server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        this.server.setExecutor(executor);
        this.server.createContext("/", this::handle);
    }

    public static DiagramRenderer start(Config config) throws IOException {
        DiagramRenderer renderer = new DiagramRenderer(config);
        renderer.server.start();
        return renderer;
    }

    public static void main(String[] args) throws IOException {
        DiagramRenderer renderer = start(Config.fromEnv(System.getenv()));
        System.out.println("diagram-renderer listening on :" + renderer.port());
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
            String method = exchange.getRequestMethod();
            switch (path) {
                case "/render/plantuml" -> {
                    requireMethod(method, "POST");
                    byte[] png = plantUml.renderPng(
                            sourceOf(readBody(exchange)), exchange.getRequestHeaders().getFirst("Authorization"));
                    respond(exchange, 200, "image/png", png);
                }
                case "/capabilities" -> {
                    requireMethod(method, "GET");
                    ObjectNode body = MAPPER.createObjectNode();
                    body.putArray("notations").add("plantuml");
                    respondJson(exchange, 200, body);
                }
                default -> throw new RenderException(404, "not_found", "未対応のパスです: " + path);
            }
        } catch (RenderException e) {
            respondJson(exchange, e.status(), errorBody(e));
        } catch (RuntimeException e) {
            // 想定外の例外(不正な Authorization の文字・不正な PLANTUML_BASE_URL など)。詳細は返さない。
            respondJson(exchange, 500, errorBody(new RenderException(500, "render_failed", "レンダラーの内部エラーです")));
        }
    }

    private static void requireMethod(String actual, String expected) throws RenderException {
        if (!expected.equals(actual)) {
            throw new RenderException(405, "method_not_allowed", expected + " だけを受け付けます");
        }
    }

    // ---- 本文 ----

    /** 宣言された長さ、または読んだ量が上限を超えたら 413(RenderBodySizeLimitFilter の移植)。 */
    private byte[] readBody(HttpExchange exchange) throws IOException, RenderException {
        String declared = exchange.getRequestHeaders().getFirst("Content-Length");
        if (declared != null && Long.parseLong(declared.strip()) > config.maxBodyBytes()) {
            throw tooLarge();
        }
        byte[] body = exchange.getRequestBody().readNBytes((int) Math.min(config.maxBodyBytes() + 1, Integer.MAX_VALUE));
        if (body.length > config.maxBodyBytes()) {
            throw tooLarge();
        }
        return body;
    }

    private RenderException tooLarge() {
        return new RenderException(413, "payload_too_large",
                "リクエスト本文が上限(" + config.maxBodyBytes() + " バイト)を超えています");
    }

    private static String sourceOf(byte[] body) throws RenderException {
        JsonNode source;
        try {
            source = MAPPER.readTree(body).path("source");
        } catch (IOException e) {
            throw new RenderException(400, "invalid_request", "JSON として読めません");
        }
        if (!source.isTextual() || source.asText().isBlank()) {
            throw new RenderException(400, "invalid_request", "source は空でない文字列が必要です");
        }
        return source.asText();
    }

    // ---- 応答 ----

    private static ObjectNode errorBody(RenderException e) {
        ObjectNode error = MAPPER.createObjectNode().put("type", e.type()).put("message", e.getMessage());
        if (e.line() != null) {
            error.put("line", e.line());
        }
        return MAPPER.createObjectNode().set("error", error);
    }

    private static void respondJson(HttpExchange exchange, int status, JsonNode body) throws IOException {
        respond(exchange, status, "application/json; charset=utf-8", MAPPER.writeValueAsBytes(body));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
