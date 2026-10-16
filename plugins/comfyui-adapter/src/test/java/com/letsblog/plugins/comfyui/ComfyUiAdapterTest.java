package com.letsblog.plugins.comfyui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** アダプターを実際に起動し、ComfyUI を模したスタブに対して契約(CONTRACTS.md §2・§4.4)を確認する。 */
class ComfyUiAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] REFERENCE = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4, 5};

    private ComfyUiStub comfy;
    private ComfyUiAdapter adapter;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        comfy = new ComfyUiStub();
        adapter = startAdapter(comfy.baseUrl());
    }

    @AfterEach
    void tearDown() {
        adapter.close();
        comfy.close();
    }

    private static ComfyUiAdapter startAdapter(String comfyUrl) throws IOException {
        return ComfyUiAdapter.start(new ComfyUiAdapter.Config(
                0, comfyUrl, "default.safetensors", Duration.ofMillis(1)));
    }

    private HttpResponse<String> send(String method, String path, String contentType, byte[] body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + adapter.port() + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> generate(String json) throws Exception {
        return send("POST", "/images/generations", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode json(String text) throws IOException {
        return MAPPER.readTree(text);
    }

    private static void assertOpenAiError(HttpResponse<String> response, int status, String type) throws IOException {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode error = json(response.body()).get("error");
        assertThat(error.get("message").asText()).isNotBlank();
        assertThat(error.get("type").asText()).isEqualTo(type);
        assertThat(error.get("code").asText()).isNotBlank();
    }

    /** スタブが受けた /prompt の本文から、ワークフロー(prompt)を取り出す。 */
    private JsonNode submittedWorkflow() throws IOException {
        List<ComfyUiStub.Received> prompts = comfy.receivedAt("/prompt");
        assertThat(prompts).hasSize(1);
        return json(new String(prompts.get(0).body(), StandardCharsets.UTF_8)).get("prompt");
    }

    private static JsonNode nodeOfType(JsonNode workflow, String classType) {
        for (JsonNode node : workflow) {
            if (classType.equals(node.get("class_type").asText())) {
                return node.get("inputs");
            }
        }
        return null;
    }

    private static JsonNode param(JsonNode caps, String name) {
        for (JsonNode p : caps.get("extraParameters")) {
            if (name.equals(p.get("name").asText())) {
                return p;
            }
        }
        throw new AssertionError("extraParameters に " + name + " がありません");
    }

    private static List<String> options(JsonNode parameter) {
        List<String> out = new ArrayList<>();
        parameter.get("options").forEach(o -> out.add(o.asText()));
        return out;
    }

    private void assertNoInterruptOrFree() {
        assertThat(comfy.received()).noneMatch(r -> r.path().contains("interrupt") || r.path().equals("/free"));
    }

    // ---- GET /capabilities ----

    @Test
    void capabilitiesDeclareLimitsAndExtraParametersWithOptionsFromObjectInfo() throws Exception {
        HttpResponse<String> response = send("GET", "/capabilities", null, null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode caps = json(response.body());
        assertThat(caps.get("maxBatchSize").asInt()).isEqualTo(16);
        assertThat(caps.get("supportsEdits").asBoolean()).isTrue();
        assertThat(caps.get("models").get(0).get("id").asText()).isEqualTo(caps.get("defaultModel").asText());
        assertThat(caps.get("sizes").get("min").asInt()).isEqualTo(64);
        assertThat(caps.get("sizes").get("max").asInt()).isEqualTo(2048);
        assertThat(caps.get("sizes").get("step").asInt()).isEqualTo(8);

        List<String> names = new ArrayList<>();
        caps.get("extraParameters").forEach(p -> names.add(p.get("name").asText()));
        assertThat(names).containsExactly("negativePrompt", "steps", "cfgScale", "samplerName", "scheduler", "seed",
                "checkpoint", "loraName", "loraWeight", "denoise");

        assertThat(param(caps, "checkpoint").get("type").asText()).isEqualTo("enum");
        assertThat(options(param(caps, "checkpoint"))).containsExactly("a.safetensors", "b.safetensors");
        assertThat(param(caps, "checkpoint").get("default").asText()).isEqualTo("default.safetensors");
        assertThat(options(param(caps, "samplerName"))).containsExactly("euler", "dpmpp_2m");
        assertThat(options(param(caps, "scheduler"))).containsExactly("normal", "karras");
        assertThat(options(param(caps, "loraName"))).containsExactly("style.safetensors");

        JsonNode steps = param(caps, "steps");
        assertThat(steps.get("type").asText()).isEqualTo("integer");
        assertThat(steps.get("min").asInt()).isEqualTo(1);
        assertThat(steps.get("max").asInt()).isEqualTo(150);
        assertThat(steps.get("default").asInt()).isEqualTo(20);
        JsonNode cfg = param(caps, "cfgScale");
        assertThat(cfg.get("max").asDouble()).isEqualTo(30.0);
        assertThat(cfg.get("default").asDouble()).isEqualTo(7.0);
        assertThat(param(caps, "loraWeight").get("max").asDouble()).isEqualTo(2.0);
        JsonNode denoise = param(caps, "denoise");
        assertThat(denoise.get("max").asDouble()).isEqualTo(1.0);
        assertThat(denoise.get("default").asDouble()).isEqualTo(0.6);
        assertThat(denoise.get("appliesTo")).hasSize(1);
        assertThat(denoise.get("appliesTo").get(0).asText()).isEqualTo("edits");
        assertThat(param(caps, "steps").get("appliesTo")).hasSize(2);
        assertThat(param(caps, "seed").get("max").asLong()).isEqualTo(4294967295L);
    }

    @Test
    void capabilitiesHaveEmptyLoraOptionsWhenTheLoraNodeIsMissing() throws Exception {
        comfy.loraInfoStatus = 500;

        JsonNode caps = json(send("GET", "/capabilities", null, null).body());

        assertThat(options(param(caps, "loraName"))).isEmpty();
    }

    @Test
    void capabilitiesFailWith502WhenComfyUiObjectInfoFails() throws Exception {
        try (ComfyUiAdapter unreachable = startAdapter("http://127.0.0.1:1")) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + unreachable.port() + "/capabilities"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertOpenAiError(response, 502, "api_error");
            assertThat(json(response.body()).get("error").get("message").asText()).contains("http://127.0.0.1:1");
        }
    }

    // ---- ルーティング ----

    @Test
    void wrongMethodIs405AndUnknownPathIs404() throws Exception {
        assertOpenAiError(send("POST", "/capabilities", "application/json", new byte[0]), 405,
                "invalid_request_error");
        assertOpenAiError(send("GET", "/images/generations", null, null), 405, "invalid_request_error");
        assertOpenAiError(send("GET", "/images/edits", null, null), 405, "invalid_request_error");
        assertOpenAiError(send("GET", "/nope", null, null), 404, "invalid_request_error");
    }

    // ---- POST /images/generations ----

    @Test
    void generationsSubmitAWorkflowAndReturnNPngsAsB64Json() throws Exception {
        comfy.outputCount = 2;

        HttpResponse<String> response = generate("""
                {"model":"comfyui","prompt":"a red cat","n":2,"size":"768x512"}""");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = json(response.body()).get("data");
        assertThat(data).hasSize(2);
        assertThat(Base64.getDecoder().decode(data.get(0).get("b64_json").asText()))
                .isEqualTo(ComfyUiStub.imageBytes("letsblog_1.png"));
        assertThat(Base64.getDecoder().decode(data.get(1).get("b64_json").asText()))
                .isEqualTo(ComfyUiStub.imageBytes("letsblog_2.png"));

        JsonNode workflow = submittedWorkflow();
        assertThat(nodeOfType(workflow, "CLIPTextEncode").get("text").asText()).isEqualTo("a red cat");
        JsonNode latent = nodeOfType(workflow, "EmptyLatentImage");
        assertThat(latent.get("width").asInt()).isEqualTo(768);
        assertThat(latent.get("height").asInt()).isEqualTo(512);
        assertThat(latent.get("batch_size").asInt()).isEqualTo(2);
        JsonNode sampler = nodeOfType(workflow, "KSampler");
        assertThat(sampler.get("steps").asInt()).isEqualTo(20);
        assertThat(sampler.get("cfg").asDouble()).isEqualTo(7.0);
        assertThat(sampler.get("sampler_name").asText()).isEqualTo("euler");
        assertThat(sampler.get("scheduler").asText()).isEqualTo("normal");
        assertThat(sampler.get("denoise").asDouble()).isEqualTo(1.0);
        assertThat(sampler.get("seed").asLong()).isBetween(0L, 4294967295L);
        assertThat(nodeOfType(workflow, "CheckpointLoaderSimple").get("ckpt_name").asText())
                .isEqualTo("default.safetensors");
        assertThat(nodeOfType(workflow, "LoraLoader")).isNull();
        assertThat(comfy.receivedAt("/upload/image")).isEmpty();
        assertNoInterruptOrFree();
        // 完了の確認は、/history の応答に出力が現れるまで(スタブは 1 回目で完了を返す)。
        assertThat(comfy.historyCallCount()).isEqualTo(1);
    }

    @Test
    void generationsApplyDeclaredExtraParameters() throws Exception {
        HttpResponse<String> response = generate("""
                {"prompt":"p","extra":{"negativePrompt":"ugly","steps":30,"cfgScale":5.5,"samplerName":"dpmpp_2m",
                 "scheduler":"karras","seed":42,"checkpoint":"b.safetensors","loraName":"style.safetensors",
                 "loraWeight":0.8}}""");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response.body()).get("data")).hasSize(1);
        JsonNode workflow = submittedWorkflow();
        JsonNode sampler = nodeOfType(workflow, "KSampler");
        assertThat(sampler.get("steps").asInt()).isEqualTo(30);
        assertThat(sampler.get("cfg").asDouble()).isEqualTo(5.5);
        assertThat(sampler.get("sampler_name").asText()).isEqualTo("dpmpp_2m");
        assertThat(sampler.get("scheduler").asText()).isEqualTo("karras");
        assertThat(sampler.get("seed").asLong()).isEqualTo(42L);
        assertThat(nodeOfType(workflow, "CheckpointLoaderSimple").get("ckpt_name").asText())
                .isEqualTo("b.safetensors");
        JsonNode lora = nodeOfType(workflow, "LoraLoader");
        assertThat(lora.get("lora_name").asText()).isEqualTo("style.safetensors");
        assertThat(lora.get("strength_model").asDouble()).isEqualTo(0.8);
        assertThat(lora.get("strength_clip").asDouble()).isEqualTo(0.8);
        // 既定の 512x512、LoRA の既定の重みは使われない。
        assertThat(nodeOfType(workflow, "EmptyLatentImage").get("width").asInt()).isEqualTo(512);
        boolean negativeFound = false;
        for (JsonNode node : workflow) {
            if ("CLIPTextEncode".equals(node.get("class_type").asText())
                    && "ugly".equals(node.get("inputs").get("text").asText())) {
                negativeFound = true;
            }
        }
        assertThat(negativeFound).isTrue();
    }

    @Test
    void loraWithoutWeightUsesWeightOneAndBlankLoraIsIgnored() throws Exception {
        generate("{\"prompt\":\"p\",\"extra\":{\"loraName\":\"style.safetensors\"}}");
        assertThat(nodeOfType(submittedWorkflow(), "LoraLoader").get("strength_model").asDouble()).isEqualTo(1.0);
    }

    @Test
    void blankLoraNameAddsNoLoraNode() throws Exception {
        generate("{\"prompt\":\"p\",\"extra\":{\"loraName\":\" \"}}");
        assertThat(nodeOfType(submittedWorkflow(), "LoraLoader")).isNull();
    }

    @Test
    void autoSizeAndMissingNUseDefaults() throws Exception {
        HttpResponse<String> response = generate("{\"prompt\":\"p\",\"size\":\"auto\"}");

        assertThat(json(response.body()).get("data")).hasSize(1);
        JsonNode latent = nodeOfType(submittedWorkflow(), "EmptyLatentImage");
        assertThat(latent.get("width").asInt()).isEqualTo(512);
        assertThat(latent.get("batch_size").asInt()).isEqualTo(1);
    }

    @Test
    void v1PrefixIsAccepted() throws Exception {
        HttpResponse<String> response = send("POST", "/v1/images/generations", "application/json",
                "{\"prompt\":\"p\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void unknownExtraParameterIs400AndNothingIsSubmitted() throws Exception {
        HttpResponse<String> response = generate("{\"prompt\":\"p\",\"extra\":{\"frobnicate\":1}}");

        assertOpenAiError(response, 400, "invalid_request_error");
        assertThat(json(response.body()).get("error").get("message").asText()).contains("frobnicate");
        assertThat(comfy.received()).isEmpty();
    }

    @Test
    void denoiseIsNotDeclaredForGenerations() throws Exception {
        assertOpenAiError(generate("{\"prompt\":\"p\",\"extra\":{\"denoise\":0.5}}"), 400, "invalid_request_error");
        assertThat(comfy.received()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json",
            "[1]",
            "{}",
            "{\"prompt\":\"  \"}",
            "{\"prompt\":5}",
            "{\"prompt\":\"p\",\"n\":0}",
            "{\"prompt\":\"p\",\"n\":17}",
            "{\"prompt\":\"p\",\"n\":\"2\"}",
            "{\"prompt\":\"p\",\"n\":1.5}",
            "{\"prompt\":\"p\",\"size\":\"big\"}",
            "{\"prompt\":\"p\",\"size\":\"63x512\"}",
            "{\"prompt\":\"p\",\"size\":\"512x2056\"}",
            "{\"prompt\":\"p\",\"size\":\"513x512\"}",
            "{\"prompt\":\"p\",\"size\":512}",
            "{\"prompt\":\"p\",\"size\":\"5120000x512\"}",
            "{\"prompt\":\"p\",\"extra\":[]}",
            "{\"prompt\":\"p\",\"extra\":{\"steps\":0}}",
            "{\"prompt\":\"p\",\"extra\":{\"steps\":151}}",
            "{\"prompt\":\"p\",\"extra\":{\"steps\":\"20\"}}",
            "{\"prompt\":\"p\",\"extra\":{\"steps\":2.5}}",
            "{\"prompt\":\"p\",\"extra\":{\"cfgScale\":-0.1}}",
            "{\"prompt\":\"p\",\"extra\":{\"cfgScale\":30.1}}",
            "{\"prompt\":\"p\",\"extra\":{\"cfgScale\":\"7\"}}",
            "{\"prompt\":\"p\",\"extra\":{\"loraWeight\":2.1}}",
            "{\"prompt\":\"p\",\"extra\":{\"seed\":-1}}",
            "{\"prompt\":\"p\",\"extra\":{\"seed\":4294967296}}",
            "{\"prompt\":\"p\",\"extra\":{\"seed\":1.5}}",
            "{\"prompt\":\"p\",\"extra\":{\"samplerName\":1}}",
            "{\"prompt\":\"p\",\"extra\":{\"negativePrompt\":false}}",
            "{\"prompt\":\"p\",\"extra\":{\"checkpoint\":[]}}"
    })
    void invalidRequestsAre400AndNothingReachesComfyUi(String body) throws Exception {
        assertOpenAiError(generate(body), 400, "invalid_request_error");
        assertThat(comfy.received()).isEmpty();
    }

    @Test
    void boundaryValuesOfExtraParametersAreAccepted() throws Exception {
        HttpResponse<String> response = generate("""
                {"prompt":"p","n":16,"size":"64x2048","extra":{"steps":150,"cfgScale":0,"loraWeight":2,"seed":0}}""");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode sampler = nodeOfType(submittedWorkflow(), "KSampler");
        assertThat(sampler.get("steps").asInt()).isEqualTo(150);
        assertThat(sampler.get("cfg").asDouble()).isEqualTo(0.0);
        assertThat(sampler.get("seed").asLong()).isZero();
    }

    @Test
    void nullExtraValuesAreTreatedAsUnset() throws Exception {
        HttpResponse<String> response = generate("{\"prompt\":\"p\",\"extra\":{\"steps\":null,\"seed\":null}}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(nodeOfType(submittedWorkflow(), "KSampler").get("steps").asInt()).isEqualTo(20);
    }

    // ---- ComfyUI 側の失敗 ----

    @Test
    void unreachableComfyUiIs502WithTheBaseUrlInTheMessage() throws Exception {
        try (ComfyUiAdapter unreachable = startAdapter("http://127.0.0.1:1")) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + unreachable.port() + "/images/generations"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"prompt\":\"p\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertOpenAiError(response, 502, "api_error");
            assertThat(json(response.body()).get("error").get("message").asText()).contains("http://127.0.0.1:1");
        }
    }

    @Test
    void submitRejectionIs502WithTheComfyUiBody() throws Exception {
        comfy.promptStatus = 400;

        HttpResponse<String> response = generate("{\"prompt\":\"p\"}");

        assertOpenAiError(response, 502, "api_error");
        assertThat(json(response.body()).get("error").get("message").asText()).contains("400").contains("bad workflow");
        assertThat(comfy.receivedAt("/prompt")).hasSize(1);
        assertThat(comfy.receivedAt("/history/p1")).isEmpty();
        assertNoInterruptOrFree();
    }

    @Test
    void submitResponseWithoutPromptIdIs502() throws Exception {
        comfy.promptResponse = "{}";
        assertOpenAiError(generate("{\"prompt\":\"p\"}"), 502, "api_error");
    }

    @Test
    void submitResponseThatIsNotJsonIs502() throws Exception {
        comfy.promptResponse = "oops";
        assertOpenAiError(generate("{\"prompt\":\"p\"}"), 502, "api_error");
    }

    @Test
    void historyFailureIs502() throws Exception {
        comfy.historyStatus = 500;
        assertOpenAiError(generate("{\"prompt\":\"p\"}"), 502, "api_error");
    }

    @Test
    void viewFailureIs502() throws Exception {
        comfy.viewStatus = 404;
        assertOpenAiError(generate("{\"prompt\":\"p\"}"), 502, "api_error");
    }

    @Test
    void generationIsAwaitedBySynchronousPolling() throws Exception {
        comfy.pendingPolls = 3;

        HttpResponse<String> response = generate("{\"prompt\":\"p\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(comfy.historyCallCount()).isEqualTo(4);
        assertThat(comfy.receivedAt("/prompt")).hasSize(1);
    }

    @Test
    void waitIsBoundedByMaxOf120And60Plus8nPollsAndIsNotRetried() throws Exception {
        comfy.pendingPolls = Integer.MAX_VALUE;

        HttpResponse<String> one = generate("{\"prompt\":\"p\",\"n\":1}");

        assertOpenAiError(one, 504, "api_error");
        assertThat(json(one.body()).get("error").get("code").asText()).isEqualTo("timeout");
        assertThat(comfy.historyCallCount()).isEqualTo(120);
        assertThat(comfy.receivedAt("/prompt")).hasSize(1);
        assertNoInterruptOrFree();
    }

    @Test
    void waitGrowsWithNBeyondTheFloor() throws Exception {
        comfy.pendingPolls = Integer.MAX_VALUE;

        HttpResponse<String> sixteen = generate("{\"prompt\":\"p\",\"n\":16}");

        assertOpenAiError(sixteen, 504, "api_error");
        assertThat(comfy.historyCallCount()).isEqualTo(188);
        assertThat(comfy.receivedAt("/prompt")).hasSize(1);
        assertNoInterruptOrFree();
    }

    @Test
    void unexpectedFailuresAre500WithoutDetails() throws Exception {
        try (ComfyUiAdapter broken = startAdapter("not a url")) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + broken.port() + "/images/generations"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"prompt\":\"p\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertOpenAiError(response, 500, "api_error");
            assertThat(json(response.body()).get("error").get("message").asText()).isNotBlank();
        }
    }

    // ---- POST /images/edits ----

    private static final String BOUNDARY = "----testboundary";

    private static final class Multipart {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Multipart text(String name, String value) {
            write("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value
                    + "\r\n");
            return this;
        }

        Multipart file(String name, String filename, String contentType, byte[] bytes) {
            write("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\""
                    + filename + "\"\r\nContent-Type: " + contentType + "\r\n\r\n");
            out.writeBytes(bytes);
            write("\r\n");
            return this;
        }

        byte[] build() {
            write("--" + BOUNDARY + "--\r\n");
            return out.toByteArray();
        }

        private void write(String text) {
            out.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    private HttpResponse<String> edit(byte[] multipartBody) throws Exception {
        return send("POST", "/images/edits", "multipart/form-data; boundary=" + BOUNDARY, multipartBody);
    }

    @Test
    void editsUploadTheReferenceImageAndGenerateWithDenoise() throws Exception {
        comfy.outputCount = 2;
        comfy.uploadResponse = "{\"name\":\"ref_abc.png\",\"subfolder\":\"letsblog\",\"type\":\"input\"}";

        HttpResponse<String> response = edit(new Multipart()
                .file("image", "photo.png", "image/png", REFERENCE)
                .text("prompt", "make it blue").text("model", "comfyui").text("n", "2").text("size", "640x384")
                .text("extra", "{\"denoise\":0.4,\"steps\":10}").build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response.body()).get("data")).hasSize(2);

        List<ComfyUiStub.Received> uploads = comfy.receivedAt("/upload/image");
        assertThat(uploads).hasSize(1);
        assertThat(uploads.get(0).contentType()).startsWith("multipart/form-data");
        String upload = uploads.get(0).bodyText();
        assertThat(upload).contains("name=\"image\"").contains(".png\"").contains("Content-Type: image/png")
                .contains(new String(REFERENCE, StandardCharsets.ISO_8859_1))
                .contains("name=\"type\"").contains("input").contains("name=\"overwrite\"").contains("true");

        JsonNode workflow = submittedWorkflow();
        assertThat(nodeOfType(workflow, "LoadImage").get("image").asText()).isEqualTo("letsblog/ref_abc.png");
        JsonNode scale = nodeOfType(workflow, "ImageScale");
        assertThat(scale.get("width").asInt()).isEqualTo(640);
        assertThat(scale.get("height").asInt()).isEqualTo(384);
        assertThat(nodeOfType(workflow, "VAEEncode")).isNotNull();
        assertThat(nodeOfType(workflow, "EmptyLatentImage")).isNull();
        assertThat(nodeOfType(workflow, "RepeatLatentBatch").get("amount").asInt()).isEqualTo(2);
        JsonNode sampler = nodeOfType(workflow, "KSampler");
        assertThat(sampler.get("denoise").asDouble()).isEqualTo(0.4);
        assertThat(sampler.get("steps").asInt()).isEqualTo(10);
        assertNoInterruptOrFree();
        // アップロードは生成の投入より前。
        List<String> order = comfy.received().stream().map(ComfyUiStub.Received::path).toList();
        assertThat(order.indexOf("/upload/image")).isLessThan(order.indexOf("/prompt"));
    }

    @Test
    void editsDefaultToDenoise06AndSingleImageNeedsNoRepeat() throws Exception {
        HttpResponse<String> response = edit(new Multipart()
                .file("image", "photo.jpg", "image/jpeg", REFERENCE).text("prompt", "p").build());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response.body()).get("data")).hasSize(1);
        JsonNode workflow = submittedWorkflow();
        assertThat(nodeOfType(workflow, "KSampler").get("denoise").asDouble()).isEqualTo(0.6);
        assertThat(nodeOfType(workflow, "RepeatLatentBatch")).isNull();
        assertThat(nodeOfType(workflow, "LoadImage").get("image").asText()).isEqualTo("ref.png");
        String upload = comfy.receivedAt("/upload/image").get(0).bodyText();
        assertThat(upload).contains(".jpg\"").contains("Content-Type: image/jpeg");
    }

    @Test
    void editsAcceptEmptyExtraAndExtraWithoutDenoise() throws Exception {
        assertThat(edit(new Multipart().file("image", "a.png", "image/png", REFERENCE).text("prompt", "p")
                .text("extra", "").build()).statusCode()).isEqualTo(200);
    }

    @Test
    void editsRejectUnknownExtraAndInvalidInputsBeforeUploading() throws Exception {
        List<byte[]> bodies = List.of(
                new Multipart().file("image", "a.png", "image/png", REFERENCE).text("prompt", "p")
                        .text("extra", "{\"frobnicate\":1}").build(),
                new Multipart().file("image", "a.png", "image/png", REFERENCE).text("prompt", "p")
                        .text("extra", "{\"denoise\":1.5}").build(),
                new Multipart().file("image", "a.png", "image/png", REFERENCE).text("prompt", "p")
                        .text("extra", "nope").build(),
                new Multipart().file("image", "a.png", "image/png", REFERENCE).text("prompt", "p")
                        .text("extra", "[1]").build(),
                new Multipart().file("image", "a.png", "image/png", REFERENCE).text("prompt", "p")
                        .text("n", "x").build(),
                new Multipart().file("image", "a.png", "image/png", REFERENCE).text("prompt", "p")
                        .text("n", "17").build(),
                new Multipart().file("image", "a.png", "image/png", REFERENCE).build(),
                new Multipart().text("prompt", "p").build(),
                new Multipart().file("image", "a.png", "image/png", new byte[0]).text("prompt", "p").build());
        for (byte[] body : bodies) {
            assertOpenAiError(edit(body), 400, "invalid_request_error");
        }
        assertThat(comfy.received()).isEmpty();
    }

    @Test
    void editsRequireMultipartWithABoundary() throws Exception {
        assertOpenAiError(send("POST", "/images/edits", "application/json", "{}".getBytes(StandardCharsets.UTF_8)),
                400, "invalid_request_error");
        assertOpenAiError(send("POST", "/images/edits", null, new byte[0]), 400, "invalid_request_error");
        assertOpenAiError(send("POST", "/images/edits", "multipart/form-data", new byte[0]), 400,
                "invalid_request_error");
        assertOpenAiError(send("POST", "/images/edits", "multipart/form-data; boundary=" + BOUNDARY,
                "garbage".getBytes(StandardCharsets.UTF_8)), 400, "invalid_request_error");
    }

    @Test
    void editsAcceptAQuotedBoundaryAndDefaultTheImageType() throws Exception {
        byte[] body = new Multipart().file("image", "a", "", REFERENCE).text("prompt", "p").build();
        HttpResponse<String> response = send("POST", "/images/edits",
                "multipart/form-data; charset=utf-8; boundary=\"" + BOUNDARY + "\"", body);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(comfy.receivedAt("/upload/image").get(0).bodyText()).contains(".png\"");
    }

    @Test
    void capabilitiesFailWith502WhenTheCheckpointListCannotBeRead() throws Exception {
        comfy.checkpointInfoStatus = 500;
        assertOpenAiError(send("GET", "/capabilities", null, null), 502, "api_error");
    }

    @Test
    void jsonNullsForNSizeAndExtraMeanUnset() throws Exception {
        HttpResponse<String> response = generate("{\"prompt\":\"p\",\"n\":null,\"size\":null,\"extra\":null}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(nodeOfType(submittedWorkflow(), "EmptyLatentImage").get("batch_size").asInt()).isEqualTo(1);
    }

    @Test
    void editsRejectAMultipartThatIsNotMultipartFormData() throws Exception {
        assertOpenAiError(send("POST", "/images/edits", "multipart/mixed; boundary=" + BOUNDARY, new byte[0]), 400,
                "invalid_request_error");
    }

    @Test
    void editsRejectTruncatedOrHeaderlessMultipartBodies() throws Exception {
        String type = "multipart/form-data; boundary=" + BOUNDARY;
        // 区切りの直後に CRLF が無い
        assertOpenAiError(send("POST", "/images/edits", type,
                ("--" + BOUNDARY + "x").getBytes(StandardCharsets.ISO_8859_1)), 400, "invalid_request_error");
        // ヘッダーの終わりが無い
        assertOpenAiError(send("POST", "/images/edits", type,
                ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"prompt\"")
                        .getBytes(StandardCharsets.ISO_8859_1)), 400, "invalid_request_error");
        // 本文の終わり(次の区切り)が無い
        assertOpenAiError(send("POST", "/images/edits", type,
                ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"prompt\"\r\n\r\nabc")
                        .getBytes(StandardCharsets.ISO_8859_1)), 400, "invalid_request_error");
        assertThat(comfy.received()).isEmpty();
    }

    @Test
    void editsIgnoreAPartWithoutANameAndRequireTheImageToBeAFile() throws Exception {
        byte[] noName = ("--" + BOUNDARY + "\r\nContent-Type: text/plain\r\n\r\nabc\r\n--" + BOUNDARY + "--\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        assertOpenAiError(edit(noName), 400, "invalid_request_error");
        // image という名前でもファイルでなければ参照画像ではない
        assertOpenAiError(edit(new Multipart().text("image", "not a file").text("prompt", "p").build()), 400,
                "invalid_request_error");
        assertThat(comfy.received()).isEmpty();
    }

    @Test
    void uploadFailureIs502AndNothingIsSubmitted() throws Exception {
        comfy.uploadStatus = 500;

        HttpResponse<String> response = edit(new Multipart()
                .file("image", "a.png", "image/png", REFERENCE).text("prompt", "p").build());

        assertOpenAiError(response, 502, "api_error");
        assertThat(comfy.receivedAt("/prompt")).isEmpty();
    }

    @Test
    void uploadResponseWithoutNameIs502() throws Exception {
        comfy.uploadResponse = "{}";
        assertOpenAiError(edit(new Multipart()
                .file("image", "a.png", "image/png", REFERENCE).text("prompt", "p").build()), 502, "api_error");
        comfy.uploadResponse = "oops";
        assertOpenAiError(edit(new Multipart()
                .file("image", "a.png", "image/png", REFERENCE).text("prompt", "p").build()), 502, "api_error");
    }

    @Test
    void editsTimeOutLikeGenerationsWithoutRetry() throws Exception {
        comfy.pendingPolls = Integer.MAX_VALUE;

        HttpResponse<String> response = edit(new Multipart()
                .file("image", "a.png", "image/png", REFERENCE).text("prompt", "p").build());

        assertOpenAiError(response, 504, "api_error");
        assertThat(comfy.historyCallCount()).isEqualTo(120);
        assertThat(comfy.receivedAt("/upload/image")).hasSize(1);
        assertThat(comfy.receivedAt("/prompt")).hasSize(1);
        assertNoInterruptOrFree();
    }

    @Test
    void editsToAnUnreachableComfyUiAre502() throws Exception {
        try (ComfyUiAdapter unreachable = startAdapter("http://127.0.0.1:1")) {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + unreachable.port() + "/images/edits"))
                            .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(new Multipart()
                                    .file("image", "a.png", "image/png", REFERENCE).text("prompt", "p").build()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertOpenAiError(response, 502, "api_error");
        }
    }
}
