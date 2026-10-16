package com.letsblog.plugins.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ComfyUI アダプター(#1813)。画像生成の契約(docs/plugins/CONTRACTS.md §2・§4.4)を話し、ComfyUI のワークフローへ翻訳する。
 *
 * <ul>
 *   <li>{@code GET /capabilities} → ComfyUI の {@code /object_info/*} から選択肢を取って、追加パラメータを宣言する</li>
 *   <li>{@code POST /images/generations}(JSON)→ txt2img のワークフローを投入し、完了まで応答を保留する</li>
 *   <li>{@code POST /images/edits}(multipart)→ 参照画像を {@code /upload/image} で送り、img2img のワークフローを投入する</li>
 * </ul>
 *
 * <p>待ち時間の上限は {@code max(120, 60 + 8 × n)} 秒。再試行しない。生成の前後で {@code /api/interrupt} は呼ばない。
 * 失敗は OpenAI 互換のエラー本文で返す(§0。ComfyUI に届かない・失敗は 502、待ち時間の超過は 504)。
 * アダプター自身は受けた Authorization を検査しない(ADR-0012)。
 */
public final class ComfyUiAdapter implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MODEL_ID = "comfyui";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 起動設定。{@code port} が 0 なら空きポートを使う。{@code pollInterval} は /history を引く間隔で、
     * 待ち時間の上限(秒)はこの間隔が 1 秒のときの値。テストは短くして上限を実時間なしで確かめる。
     */
    public record Config(int port, String comfyUiBaseUrl, String defaultCheckpoint, Duration pollInterval) {

        static final int DEFAULT_PORT = 8080;
        static final String DEFAULT_COMFYUI_BASE_URL = "http://127.0.0.1:8188";
        static final String DEFAULT_CHECKPOINT = "v1-5-pruned-emaonly.safetensors";
        static final long DEFAULT_POLL_INTERVAL_MILLIS = 1000;

        /** 環境変数 PORT / COMFYUI_BASE_URL / COMFYUI_CHECKPOINT / COMFYUI_POLL_INTERVAL_MILLIS から作る。空は既定値。 */
        static Config fromEnv(Map<String, String> env) {
            String baseUrl = valueOr(env, "COMFYUI_BASE_URL", DEFAULT_COMFYUI_BASE_URL);
            return new Config(
                    Integer.parseInt(valueOr(env, "PORT", String.valueOf(DEFAULT_PORT))),
                    baseUrl.replaceAll("/+$", ""),
                    valueOr(env, "COMFYUI_CHECKPOINT", DEFAULT_CHECKPOINT),
                    Duration.ofMillis(Long.parseLong(
                            valueOr(env, "COMFYUI_POLL_INTERVAL_MILLIS", String.valueOf(DEFAULT_POLL_INTERVAL_MILLIS)))));
        }

        private static String valueOr(Map<String, String> env, String key, String fallback) {
            String value = env.getOrDefault(key, "");
            return value.isBlank() ? fallback : value.strip();
        }
    }

    private final Config config;
    private final ComfyUiClient comfy;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private ComfyUiAdapter(Config config) throws IOException {
        this.config = config;
        HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.comfy = new ComfyUiClient(config.comfyUiBaseUrl(), http, config.pollInterval());
        this.server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        this.server.setExecutor(executor);
        this.server.createContext("/", this::handle);
    }

    public static ComfyUiAdapter start(Config config) throws IOException {
        ComfyUiAdapter adapter = new ComfyUiAdapter(config);
        adapter.server.start();
        return adapter;
    }

    public static void main(String[] args) throws IOException {
        ComfyUiAdapter adapter = start(Config.fromEnv(System.getenv()));
        System.out.println("comfyui-adapter listening on :" + adapter.port());
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    /** n 枚の生成を待つ上限(秒)。{@code max(120, 60 + 8 × n)}(契約 §2.5)。 */
    static int maxWaitSeconds(int n) {
        return ComfyUiClient.maxPollAttempts(n);
    }

    // ---- ルーティング ----

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String route = path.startsWith("/v1/") ? path.substring("/v1".length()) : path;
            String method = exchange.getRequestMethod();
            switch (route) {
                case "/capabilities" -> {
                    requireMethod(method, "GET");
                    respond(exchange, 200, capabilities());
                }
                case "/images/generations" -> {
                    requireMethod(method, "POST");
                    respond(exchange, 200, generations(exchange.getRequestBody().readAllBytes()));
                }
                case "/images/edits" -> {
                    requireMethod(method, "POST");
                    respond(exchange, 200, edits(exchange.getRequestHeaders().getFirst("Content-Type"),
                            exchange.getRequestBody().readAllBytes()));
                }
                default -> throw new AdapterException(404, "invalid_request_error", "not_found", "未対応のパスです: " + path);
            }
        } catch (AdapterException e) {
            respond(exchange, e.status(), errorBody(e));
        } catch (RuntimeException e) {
            // 想定外の例外(不正な COMFYUI_BASE_URL など)。詳細は返さない。
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

    // ---- GET /capabilities ----

    private ObjectNode capabilities() throws AdapterException {
        List<String> checkpoints = comfy.options("CheckpointLoaderSimple", "ckpt_name");
        List<String> samplers = comfy.options("KSampler", "sampler_name");
        List<String> schedulers = comfy.options("KSampler", "scheduler");
        List<String> loras = comfy.optionsOrEmpty("LoraLoader", "lora_name");

        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.putArray("models").addObject().put("id", MODEL_ID).put("label", "ComfyUI");
        out.put("defaultModel", MODEL_ID);
        out.put("maxBatchSize", GenerationRequest.MAX_BATCH_SIZE);
        out.put("supportsEdits", true);
        out.putObject("sizes").put("min", GenerationRequest.MIN_SIDE).put("max", GenerationRequest.MAX_SIDE)
                .put("step", GenerationRequest.SIDE_STEP);
        ArrayNode extra = out.putArray("extraParameters");
        for (ExtraParameters.Spec spec : ExtraParameters.SPECS) {
            Object defaultValue = spec.defaultValue();
            List<String> options = null;
            switch (spec.name()) {
                case ExtraParameters.CHECKPOINT -> {
                    options = checkpoints;
                    defaultValue = config.defaultCheckpoint();
                }
                case ExtraParameters.SAMPLER_NAME -> options = samplers;
                case ExtraParameters.SCHEDULER -> options = schedulers;
                case ExtraParameters.LORA_NAME -> options = loras;
                default -> { }
            }
            extra.add(ExtraParameters.declaration(spec, defaultValue, options));
        }
        return out;
    }

    // ---- POST /images/generations ----

    private ObjectNode generations(byte[] requestBody) throws AdapterException {
        JsonNode body;
        try {
            body = MAPPER.readTree(requestBody);
        } catch (IOException e) {
            throw AdapterException.invalidRequest("リクエスト本文が JSON ではありません");
        }
        if (!body.isObject()) {
            throw AdapterException.invalidRequest("リクエスト本文は JSON オブジェクトにしてください");
        }
        GenerationRequest request = GenerationRequest.of(
                body.get("prompt"), body.get("n"), body.get("size"), body.get("extra"), false);
        return run(request, null);
    }

    // ---- POST /images/edits ----

    private ObjectNode edits(String contentType, byte[] requestBody) throws AdapterException {
        List<Multipart.Part> parts = Multipart.parse(contentType, requestBody);
        Multipart.Part image = null;
        Map<String, String> fields = new java.util.HashMap<>();
        for (Multipart.Part part : parts) {
            if ("image".equals(part.name()) && part.filename() != null) {
                image = part;
            } else {
                fields.put(part.name(), part.text());
            }
        }
        if (image == null || image.data().length == 0) {
            throw AdapterException.invalidRequest("image(参照画像のファイル)が必要です");
        }
        GenerationRequest request = GenerationRequest.of(
                textNode(fields.get("prompt")), integerNode(fields.get("n")), textNode(fields.get("size")),
                jsonNode(fields.get("extra")), true);
        return run(request, image);
    }

    private static JsonNode textNode(String value) {
        return value == null ? null : JsonNodeFactory.instance.textNode(value);
    }

    /** 整数に読める文字列だけ整数、ほかは文字列のまま(検証で 400 にする)。 */
    private static JsonNode integerNode(String value) {
        if (value == null) {
            return null;
        }
        return value.strip().matches("-?\\d{1,9}")
                ? JsonNodeFactory.instance.numberNode(Integer.parseInt(value.strip()))
                : JsonNodeFactory.instance.textNode(value);
    }

    /** extra は JSON 文字列。空なら無し、JSON でなければ 400。 */
    private static JsonNode jsonNode(String value) throws AdapterException {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(value);
        } catch (IOException e) {
            throw AdapterException.invalidRequest("extra は JSON 文字列にしてください");
        }
    }

    // ---- 生成 ----

    /** 検証済みの要求を ComfyUI で生成する。{@code reference} があれば img2img。 */
    private ObjectNode run(GenerationRequest request, Multipart.Part reference) throws AdapterException {
        String referenceName = reference == null
                ? null : comfy.uploadReferenceImage(reference.data(), reference.contentType());
        long seed = request.seed() != null
                ? request.seed() : ThreadLocalRandom.current().nextLong(ExtraParameters.MAX_SEED + 1);
        String checkpoint = request.checkpoint() != null ? request.checkpoint() : config.defaultCheckpoint();
        List<byte[]> images = comfy.generate(WorkflowBuilder.build(request, checkpoint, seed, referenceName),
                request.n());

        ObjectNode out = JsonNodeFactory.instance.objectNode();
        ArrayNode data = out.putArray("data");
        List<String> encoded = new ArrayList<>();
        images.forEach(image -> encoded.add(Base64.getEncoder().encodeToString(image)));
        encoded.forEach(b64 -> data.addObject().put("b64_json", b64));
        return out;
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
