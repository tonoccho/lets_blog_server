package com.letsblog.plugins.comfyui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ComfyUI のネイティブ API({@code /prompt} {@code /history} {@code /view} {@code /upload/image}
 * {@code /object_info/*})を呼ぶ。1 つの要求につき再試行しない。生成の後に {@code /api/interrupt} も
 * {@code /free} も呼ばない(#1747: /interrupt は実行中の別の生成を止めるだけで、/free はモデルの再ロードを招く)。
 */
final class ComfyUiClient {

    /** 投入した枚数によらず必要な待ち時間(キュー待ち・モデルのロード・VAE デコードなど)。 */
    static final int BASE_POLL_ATTEMPTS = 60;
    /** 1 枚あたりに上乗せする待ち時間。 */
    static final int POLL_ATTEMPTS_PER_IMAGE = 8;
    /** 枚数によらず必ず与える最低の待ち時間。 */
    static final int MIN_POLL_ATTEMPTS = 120;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration OBJECT_INFO_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final HttpClient http;
    private final Duration pollInterval;

    ComfyUiClient(String baseUrl, HttpClient http, Duration pollInterval) {
        this.baseUrl = baseUrl;
        this.http = http;
        this.pollInterval = pollInterval;
    }

    /** n 枚の生成を待つポーリング回数の上限。{@code max(120, 60 + 8 × n)}(間隔 1 秒なら秒数)。 */
    static int maxPollAttempts(int n) {
        return Math.max(MIN_POLL_ATTEMPTS, BASE_POLL_ATTEMPTS + POLL_ATTEMPTS_PER_IMAGE * n);
    }

    // ---- /object_info/* ----

    /** {@code GET /object_info/<node>} の {@code input.required.<field>[0]} の選択肢。失敗は 502。 */
    List<String> options(String node, String field) throws AdapterException {
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(uri("/object_info/" + node)).GET(),
                OBJECT_INFO_TIMEOUT);
        if (response.statusCode() / 100 != 2) {
            throw failed("選択肢(" + node + ")の取得", response);
        }
        return optionsOf(parse(response), node, field);
    }

    /** {@link #options} と同じだが、ノードが無い ComfyUI(HTTP エラー)では空にする(LoraLoader 用)。 */
    List<String> optionsOrEmpty(String node, String field) throws AdapterException {
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(uri("/object_info/" + node)).GET(),
                OBJECT_INFO_TIMEOUT);
        return response.statusCode() / 100 != 2 ? List.of() : optionsOf(parse(response), node, field);
    }

    private static List<String> optionsOf(JsonNode body, String node, String field) {
        List<String> names = new ArrayList<>();
        body.path(node).path("input").path("required").path(field).path(0).forEach(n -> names.add(n.asText()));
        return names;
    }

    // ---- /upload/image ----

    /** 参照画像を ComfyUI の入力ディレクトリへ送り、LoadImage に渡すファイル名({@code subfolder/name})を返す。 */
    String uploadReferenceImage(byte[] image, String mimeType) throws AdapterException {
        String mime = mimeType.isBlank() ? "image/png" : mimeType;
        String extension = "image/jpeg".equalsIgnoreCase(mime) ? "jpg" : "png";
        Multipart.Writer form = new Multipart.Writer("----lb" + UUID.randomUUID().toString().replace("-", ""))
                .file("image", "letsblog_ref_" + UUID.randomUUID() + "." + extension, mime, image)
                .text("type", "input")
                .text("overwrite", "true");
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(uri("/upload/image"))
                .header("Content-Type", form.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(form.build())), Duration.ofSeconds(MIN_POLL_ATTEMPTS));
        if (response.statusCode() / 100 != 2) {
            throw failed("参照画像のアップロード", response);
        }
        JsonNode body = parse(response);
        String name = body.path("name").asText("");
        if (name.isBlank()) {
            throw AdapterException.badGateway("ComfyUI の参照画像のアップロード応答にファイル名がありません");
        }
        String subfolder = body.path("subfolder").asText("");
        return subfolder.isBlank() ? name : subfolder + "/" + name;
    }

    // ---- /prompt → /history → /view ----

    /** ワークフローを投入し、完了を待って、出力された PNG を全件返す(同期)。 */
    List<byte[]> generate(ObjectNode workflow, int n) throws AdapterException {
        int maxAttempts = maxPollAttempts(n);
        Duration callTimeout = Duration.ofSeconds(maxAttempts);
        ObjectNode submit = MAPPER.createObjectNode();
        submit.set("prompt", workflow);
        submit.put("client_id", UUID.randomUUID().toString());
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(uri("/prompt"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(submit.toString())), callTimeout);
        if (response.statusCode() / 100 != 2) {
            throw failed("ジョブの投入", response);
        }
        String promptId = parse(response).path("prompt_id").asText("");
        if (promptId.isBlank()) {
            throw AdapterException.badGateway("ComfyUI のジョブ投入の応答に prompt_id がありません");
        }

        List<JsonNode> outputs = poll(promptId, maxAttempts, callTimeout);
        List<byte[]> images = new ArrayList<>();
        for (JsonNode output : outputs) {
            images.add(view(output, callTimeout));
        }
        return images;
    }

    private List<JsonNode> poll(String promptId, int maxAttempts, Duration callTimeout) throws AdapterException {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            HttpResponse<byte[]> response = send(HttpRequest.newBuilder(uri("/history/" + promptId)).GET(),
                    callTimeout);
            if (response.statusCode() / 100 != 2) {
                throw failed("生成結果(history)の取得", response);
            }
            for (JsonNode nodeOutput : parse(response).path(promptId).path("outputs")) {
                if (nodeOutput.path("images").size() > 0) {
                    List<JsonNode> images = new ArrayList<>();
                    nodeOutput.get("images").forEach(images::add);
                    return images;
                }
            }
            sleep();
        }
        throw AdapterException.timeout("ComfyUI の画像生成がタイムアウトしました(prompt_id=" + promptId
                + "、待ち時間の上限 " + maxAttempts * pollInterval.toMillis() / 1000.0 + " 秒)");
    }

    private void sleep() throws AdapterException {
        try {
            Thread.sleep(pollInterval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AdapterException.badGateway("ComfyUI の結果待機が中断されました");
        }
    }

    private byte[] view(JsonNode output, Duration timeout) throws AdapterException {
        String query = "filename=" + enc(output.path("filename").asText(""))
                + "&subfolder=" + enc(output.path("subfolder").asText(""))
                + "&type=" + enc(output.path("type").asText("output"));
        HttpResponse<byte[]> response = send(HttpRequest.newBuilder(uri("/view?" + query)).GET(), timeout);
        if (response.statusCode() / 100 != 2) {
            throw failed("生成画像(view)の取得", response);
        }
        return response.body();
    }

    // ---- 共通 ----

    private URI uri(String pathAndQuery) {
        return URI.create(baseUrl + pathAndQuery);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder builder, Duration timeout) throws AdapterException {
        try {
            return http.send(builder.timeout(timeout).build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw AdapterException.badGateway("ComfyUI へ到達できません(接続先: " + baseUrl + "): " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AdapterException.badGateway("ComfyUI の呼び出しが中断されました");
        }
    }

    private static JsonNode parse(HttpResponse<byte[]> response) throws AdapterException {
        try {
            return MAPPER.readTree(response.body());
        } catch (IOException e) {
            throw AdapterException.badGateway("ComfyUI の応答が JSON ではありません");
        }
    }

    private static AdapterException failed(String what, HttpResponse<byte[]> response) {
        return AdapterException.badGateway("ComfyUI の" + what + "に失敗しました: " + response.statusCode() + " "
                + new String(response.body(), StandardCharsets.UTF_8));
    }
}
