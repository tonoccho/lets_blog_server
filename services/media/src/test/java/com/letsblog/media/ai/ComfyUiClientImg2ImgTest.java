package com.letsblog.media.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ComfyUIのimg2imgワークフロー(issue #1601)。参照画像があるときだけ、参照画像を
 * {@code /upload/image}で送り、LoadImage → ImageScale(生成サイズへ) → VAEEncode → KSampler の
 * 経路を組む。参照画像が無いときは従来のtxt2img(EmptyLatentImage・denoise 1.0)のまま変わらない。
 */
@DisplayName("media-service: ComfyUI img2imgワークフロー(issue #1601)")
class ComfyUiClientImg2ImgTest {

    private static final String BASE_URL = "http://comfyui.test";
    private static final byte[] REF_BYTES = {9, 8, 7, 6};

    private MockRestServiceServer server;
    private ComfyUiClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final StringBuilder submittedWorkflow = new StringBuilder();
    private final StringBuilder uploadedBody = new StringBuilder();
    private final StringBuilder uploadedContentType = new StringBuilder();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        client = new ComfyUiClient(builder, new FixedConfigProvider(), "default.safetensors");
    }

    private static ComfyUiGenerationParams params(ReferenceImage reference, Double denoise, int batchSize) {
        return new ComfyUiGenerationParams(
                "a cat", "blurry", 20, 7.0, "euler", "normal", 11L, 768, 512, batchSize,
                "checkpoint.safetensors", null, null, null, reference, denoise);
    }

    private static ReferenceImage reference() {
        return new ReferenceImage(REF_BYTES, "image/png");
    }

    private void expectUpload(String responseBody) {
        server.expect(requestTo(BASE_URL + "/upload/image"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(request -> {
                    MockClientHttpRequest mock = (MockClientHttpRequest) request;
                    uploadedContentType.append(String.valueOf(mock.getHeaders().getContentType()));
                    uploadedBody.append(new String(mock.getBodyAsBytes(), StandardCharsets.ISO_8859_1));
                })
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));
    }

    private void expectGeneration(int imageCount) {
        server.expect(requestTo(BASE_URL + "/prompt"))
                .andExpect(request -> submittedWorkflow.append(
                        new String(((MockClientHttpRequest) request).getBodyAsBytes())))
                .andRespond(withSuccess("{\"prompt_id\":\"p1\"}", MediaType.APPLICATION_JSON));
        StringBuilder images = new StringBuilder();
        for (int i = 0; i < imageCount; i++) {
            if (i > 0) {
                images.append(',');
            }
            images.append("{\"filename\":\"img").append(i).append(".png\",\"subfolder\":\"\",\"type\":\"output\"}");
        }
        server.expect(manyTimes(), requestTo(BASE_URL + "/history/p1"))
                .andRespond(withSuccess(
                        "{\"p1\":{\"outputs\":{\"9\":{\"images\":[" + images + "]}}}}",
                        MediaType.APPLICATION_JSON));
        for (int i = 0; i < imageCount; i++) {
            server.expect(requestTo(BASE_URL + "/view?filename=img" + i + ".png&subfolder=&type=output"))
                    .andRespond(withSuccess(new byte[] {1}, MediaType.IMAGE_PNG));
        }
        server.expect(requestTo(BASE_URL + "/api/interrupt")).andRespond(withSuccess());
    }

    private JsonNode graph() throws Exception {
        return mapper.readTree(submittedWorkflow.toString()).get("prompt");
    }

    private static JsonNode nodeOf(JsonNode graph, String classType) {
        Iterator<Map.Entry<String, JsonNode>> fields = graph.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (classType.equals(entry.getValue().path("class_type").asText())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String idOf(JsonNode graph, String classType) {
        Iterator<Map.Entry<String, JsonNode>> fields = graph.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (classType.equals(entry.getValue().path("class_type").asText())) {
                return entry.getKey();
            }
        }
        return null;
    }

    @Test
    void 参照画像をuploadしLoadImageからVAEEncodeを経てKSamplerへ繋ぐ() throws Exception {
        expectUpload("{\"name\":\"ref_1.png\",\"subfolder\":\"\",\"type\":\"input\"}");
        expectGeneration(1);

        List<ComfyUiImage> images = client.generateImage(params(reference(), 0.3, 1));

        assertEquals(1, images.size());
        JsonNode graph = graph();
        JsonNode load = nodeOf(graph, "LoadImage");
        assertNotNull(load, "LoadImageノードがある");
        assertEquals("ref_1.png", load.path("inputs").path("image").asText());

        String loadId = idOf(graph, "LoadImage");
        JsonNode scale = nodeOf(graph, "ImageScale");
        assertNotNull(scale, "生成サイズへリサイズするImageScaleノードがある");
        assertEquals(loadId, scale.path("inputs").path("image").get(0).asText());
        assertEquals(768, scale.path("inputs").path("width").asInt());
        assertEquals(512, scale.path("inputs").path("height").asInt());

        JsonNode encode = nodeOf(graph, "VAEEncode");
        assertNotNull(encode, "VAEEncodeノードがある");
        assertEquals(idOf(graph, "ImageScale"), encode.path("inputs").path("pixels").get(0).asText());
        assertEquals("4", encode.path("inputs").path("vae").get(0).asText());

        JsonNode sampler = nodeOf(graph, "KSampler");
        assertEquals(0.3, sampler.path("inputs").path("denoise").asDouble(), 1e-9);
        assertEquals(idOf(graph, "VAEEncode"), sampler.path("inputs").path("latent_image").get(0).asText());
        assertEquals(11L, sampler.path("inputs").path("seed").asLong());
        assertNull(nodeOf(graph, "EmptyLatentImage"), "img2imgではEmptyLatentImageを使わない");
        server.verify();
    }

    private static void assertNull(Object value, String message) {
        org.junit.jupiter.api.Assertions.assertNull(value, message);
    }

    @Test
    void denoise未指定なら既定の0_6を使う() throws Exception {
        expectUpload("{\"name\":\"ref_1.png\"}");
        expectGeneration(1);

        client.generateImage(params(reference(), null, 1));

        assertEquals(0.6, nodeOf(graph(), "KSampler").path("inputs").path("denoise").asDouble(), 1e-9);
    }

    @Test
    void uploadは参照画像のバイト列をmultipartのimageパートとして送る() throws Exception {
        expectUpload("{\"name\":\"ref_1.png\"}");
        expectGeneration(1);

        client.generateImage(params(reference(), 0.5, 1));

        assertTrue(uploadedContentType.toString().startsWith("multipart/form-data"), uploadedContentType.toString());
        String body = uploadedBody.toString();
        assertTrue(body.contains("name=\"image\""), body);
        assertTrue(body.contains("filename=\""), body);
        assertTrue(body.contains(new String(REF_BYTES, StandardCharsets.ISO_8859_1)), "バイト列がそのまま載る");
        assertTrue(body.contains("name=\"type\""), body);
        assertTrue(body.contains("input"), body);
    }

    @Test
    void JPEGの参照画像はjpg拡張子で送る() {
        expectUpload("{\"name\":\"ref_1.jpg\"}");
        expectGeneration(1);

        client.generateImage(params(new ReferenceImage(REF_BYTES, "image/jpeg"), 0.5, 1));

        assertTrue(uploadedBody.toString().contains(".jpg\""), uploadedBody.toString());
    }

    @Test
    void batchSizeが2以上なら潜在画像を枚数分に複製してから渡す() throws Exception {
        expectUpload("{\"name\":\"ref_1.png\"}");
        expectGeneration(3);

        List<ComfyUiImage> images = client.generateImage(params(reference(), 0.6, 3));

        assertEquals(3, images.size());
        JsonNode graph = graph();
        JsonNode repeat = nodeOf(graph, "RepeatLatentBatch");
        assertNotNull(repeat, "RepeatLatentBatchノードがある");
        assertEquals(3, repeat.path("inputs").path("amount").asInt());
        assertEquals(idOf(graph, "VAEEncode"), repeat.path("inputs").path("samples").get(0).asText());
        assertEquals(idOf(graph, "RepeatLatentBatch"),
                nodeOf(graph, "KSampler").path("inputs").path("latent_image").get(0).asText());
    }

    @Test
    void batchSizeが1ならRepeatLatentBatchは挟まない() throws Exception {
        expectUpload("{\"name\":\"ref_1.png\"}");
        expectGeneration(1);

        client.generateImage(params(reference(), 0.6, 1));

        assertNull(nodeOf(graph(), "RepeatLatentBatch"), "1枚なら複製不要");
    }

    @Test
    void LoRAを指定してもimg2imgのKSamplerはLoRA経由のモデルを使う() throws Exception {
        expectUpload("{\"name\":\"ref_1.png\"}");
        expectGeneration(1);
        ComfyUiGenerationParams withLora = new ComfyUiGenerationParams(
                "a cat", "blurry", 20, 7.0, "euler", "normal", 11L, 768, 512, 1,
                "checkpoint.safetensors", "style.safetensors", 0.8, null, reference(), 0.6);

        client.generateImage(withLora);

        JsonNode graph = graph();
        assertEquals(idOf(graph, "LoraLoader"),
                nodeOf(graph, "KSampler").path("inputs").path("model").get(0).asText());
    }

    @Test
    void 参照画像が無ければuploadせず従来のtxt2imgのまま() throws Exception {
        expectGeneration(1);

        client.generateImage(params(null, null, 1));

        JsonNode graph = graph();
        assertNull(nodeOf(graph, "LoadImage"), "txt2imgにLoadImageは無い");
        assertNull(nodeOf(graph, "VAEEncode"), "txt2imgにVAEEncodeは無い");
        assertNull(nodeOf(graph, "ImageScale"), "txt2imgにImageScaleは無い");
        assertNotNull(nodeOf(graph, "EmptyLatentImage"));
        assertEquals(1.0, nodeOf(graph, "KSampler").path("inputs").path("denoise").asDouble(), 1e-9);
        assertEquals("5", nodeOf(graph, "KSampler").path("inputs").path("latent_image").get(0).asText());
        server.verify();
    }

    @Test
    void 参照画像が無いときdenoiseを渡されても無視して1_0のまま() throws Exception {
        expectGeneration(1);

        client.generateImage(params(null, 0.2, 1));

        assertEquals(1.0, nodeOf(graph(), "KSampler").path("inputs").path("denoise").asDouble(), 1e-9);
    }

    @Test
    void upload失敗はAiServiceExceptionにしてジョブ投入へ進まない() {
        server.expect(requestTo(BASE_URL + "/upload/image")).andRespond(withServerError());

        AiServiceException e = assertThrows(
                AiServiceException.class, () -> client.generateImage(params(reference(), 0.6, 1)));

        assertTrue(e.getMessage().contains("参照画像"), e.getMessage());
        server.verify();
    }

    @Test
    void uploadの応答にファイル名が無ければAiServiceExceptionにする() {
        expectUpload("{}");

        AiServiceException e = assertThrows(
                AiServiceException.class, () -> client.generateImage(params(reference(), 0.6, 1)));

        assertTrue(e.getMessage().contains("参照画像"), e.getMessage());
        assertFalse(submittedWorkflow.length() > 0, "ワークフローは投入されない");
    }

    @Test
    void uploadがサブフォルダを返したらLoadImageにはサブフォルダ付きの名前を渡す() throws Exception {
        expectUpload("{\"name\":\"ref_1.png\",\"subfolder\":\"letsblog\",\"type\":\"input\"}");
        expectGeneration(1);

        client.generateImage(params(reference(), 0.6, 1));

        assertEquals("letsblog/ref_1.png", nodeOf(graph(), "LoadImage").path("inputs").path("image").asText());
    }

    @Test
    void uploadの応答が空ならAiServiceExceptionにする() {
        server.expect(requestTo(BASE_URL + "/upload/image")).andRespond(withSuccess());

        assertThrows(AiServiceException.class, () -> client.generateImage(params(reference(), 0.6, 1)));
    }

    @Test
    void batchSizeが未指定のimg2imgは1枚として扱いRepeatLatentBatchを挟まない() throws Exception {
        expectUpload("{\"name\":\"ref_1.png\"}");
        expectGeneration(1);
        ComfyUiGenerationParams noBatchSize = new ComfyUiGenerationParams(
                "a cat", "blurry", 20, 7.0, "euler", "normal", 11L, 768, 512, null,
                "checkpoint.safetensors", null, null, null, reference(), 0.6);

        client.generateImage(noBatchSize);

        assertNull(nodeOf(graph(), "RepeatLatentBatch"), "batchSize未指定は1枚");
    }

    private static final class FixedConfigProvider implements ImageGenerationConfigProvider {
        @Override
        public String comfyUiBaseUrl(Long projectId) {
            return BASE_URL;
        }

        @Override
        public String chatGptApiKey(Long projectId) {
            return "unused";
        }

        @Override
        public String chatGptBaseUrl() {
            return BASE_URL;
        }
    }
}
