package com.letsblog.media.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ComfyUiClientがプロジェクトごとの接続先へ向かうこと(issue #1503)。
 * 一覧取得4種(checkpoint/sampler/scheduler/lora)と画像生成のすべてが、そのプロジェクトのbaseUrlを引く。
 */
class ComfyUiClientProjectUrlTest {

    private static final String SYSTEM = "http://system.test";
    private static final String OVERRIDE = "http://override.test";

    private final Map<Long, String> baseUrls = new HashMap<>();
    private MockRestServiceServer server;
    private ComfyUiClient client;

    @BeforeEach
    void setUp() {
        baseUrls.put(7L, OVERRIDE);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        ImageGenerationConfigProvider provider = new ImageGenerationConfigProvider() {
            @Override
            public String comfyUiBaseUrl(Long projectId) {
                return baseUrls.getOrDefault(projectId, SYSTEM);
            }

            @Override
            public String chatGptApiKey(Long projectId) {
                return "unused";
            }

            @Override
            public String chatGptBaseUrl() {
                return "unused";
            }
        };
        client = new ComfyUiClient(builder, provider, "default.safetensors", 1);
    }

    private void expectObjectInfo(String base, String node, String field, String value) {
        server.expect(manyTimes(), requestTo(base + "/object_info/" + node))
                .andRespond(withSuccess(
                        "{\"" + node + "\":{\"input\":{\"required\":{\"" + field + "\":[[\"" + value + "\"]]}}}}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void 一覧取得はプロジェクトの接続先へ向かう() {
        expectObjectInfo(OVERRIDE, "CheckpointLoaderSimple", "ckpt_name", "gpu.safetensors");
        expectObjectInfo(SYSTEM, "CheckpointLoaderSimple", "ckpt_name", "system.safetensors");
        expectObjectInfo(OVERRIDE, "KSampler", "sampler_name", "gpu-sampler");
        expectObjectInfo(OVERRIDE, "LoraLoader", "lora_name", "gpu-lora");

        assertThat(client.listCheckpoints(7L)).containsExactly("gpu.safetensors");
        assertThat(client.listCheckpoints(8L)).containsExactly("system.safetensors");
        assertThat(client.listCheckpoints(null)).containsExactly("system.safetensors");
        assertThat(client.listSamplers(7L)).containsExactly("gpu-sampler");
        assertThat(client.listLoras(7L)).containsExactly("gpu-lora");
        server.verify();
    }

    @Test
    void スケジューラー一覧もプロジェクトの接続先へ向かう() {
        server.expect(manyTimes(), requestTo(OVERRIDE + "/object_info/KSampler"))
                .andRespond(withSuccess(
                        "{\"KSampler\":{\"input\":{\"required\":{\"scheduler\":[[\"karras\"]]}}}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.listSchedulers(7L)).containsExactly("karras");
        server.verify();
    }

    @Test
    void 画像生成はparamsのprojectIdの接続先へ向かう() {
        server.expect(requestTo(OVERRIDE + "/prompt"))
                .andRespond(withSuccess("{\"prompt_id\":\"p1\"}", MediaType.APPLICATION_JSON));
        server.expect(manyTimes(), requestTo(OVERRIDE + "/history/p1"))
                .andRespond(withSuccess(
                        "{\"p1\":{\"outputs\":{\"9\":{\"images\":[{\"filename\":\"a.png\",\"subfolder\":\"\","
                                + "\"type\":\"output\"}]}}}}",
                        MediaType.APPLICATION_JSON));
        server.expect(manyTimes(), requestTo(org.hamcrest.Matchers.startsWith(OVERRIDE + "/view")))
                .andRespond(withSuccess(new byte[] {1, 2, 3}, MediaType.IMAGE_PNG));
        server.expect(manyTimes(), requestTo(OVERRIDE + "/api/interrupt"))
                .andRespond(withSuccess());
        ComfyUiGenerationParams params = new ComfyUiGenerationParams(
                "a cat", "blurry", 20, 7.0, "euler", "normal", 1L, 512, 512, 1,
                "checkpoint.safetensors", null, null, 7L);

        List<ComfyUiImage> images = client.generateImage(params);

        assertThat(images).hasSize(1);
        assertThat(params.projectId()).isEqualTo(7L);
        server.verify();
    }

    @Test
    void 既存の13引数コンストラクタはprojectIdをnull_システム設定として扱う() {
        ComfyUiGenerationParams params = new ComfyUiGenerationParams(
                "p", "n", 20, 7.0, "euler", "normal", 1L, 512, 512, 1, null, null, null);

        assertThat(params.projectId()).isNull();
        assertThat(ComfyUiGenerationParams.withDefaults("p").projectId()).isNull();
    }
}
