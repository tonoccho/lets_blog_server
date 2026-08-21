package com.letsblog.api.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ComfyUiClientの回帰テスト。GithubClientTest/WordPressAdapterTestと同様、MockRestServiceServerでHTTP通信を検証する。
 * /prompt投入時のワークフローJSON(buildWorkflowの出力)の内容を content().string(containsString(...)) で確認する。
 */
class ComfyUiClientTest {

    private static final String BASE_URL = "http://comfyui.test";

    private ComfyUiClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ImageGenerationConfigProvider configProvider = new ImageGenerationConfigProvider() {
            @Override
            public String comfyUiBaseUrl() {
                return BASE_URL;
            }

            @Override
            public String chatGptApiKey() {
                return "";
            }

            @Override
            public String chatGptBaseUrl() {
                return "";
            }
        };
        client = new ComfyUiClient(builder, configProvider, "default-checkpoint.safetensors");
    }

    private void expectPromptAndHistory(String expectedBodyFragment) {
        server.expect(requestTo(BASE_URL + "/prompt"))
                .andExpect(method(POST))
                .andExpect(content().string(containsString(expectedBodyFragment)))
                .andRespond(withSuccess("{\"prompt_id\":\"job-1\"}", MediaType.APPLICATION_JSON));

        server.expect(requestTo(BASE_URL + "/history/job-1"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"job-1\":{\"outputs\":{\"9\":{\"images\":[" +
                                "{\"filename\":\"letsblog_00001_.png\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}",
                        MediaType.APPLICATION_JSON));

        server.expect(requestToUriTemplate(BASE_URL + "/view?filename={filename}&subfolder={subfolder}&type={type}",
                        "letsblog_00001_.png", "", "output"))
                .andExpect(method(GET))
                .andRespond(withSuccess(new byte[] {1, 2, 3}, MediaType.IMAGE_PNG));
    }

    @Test
    void generateImage_デフォルトパラメータでワークフローを生成する() {
        expectPromptAndHistory("\"text\":\"a cat\"");

        ComfyUiGenerationParams params = ComfyUiGenerationParams.withDefaults("a cat");
        List<ComfyUiImage> images = client.generateImage(params);

        assertEquals(1, images.size());
        assertEquals("letsblog_00001_.png", images.get(0).fileName());
        assertEquals("image/png", images.get(0).mimeType());
        server.verify();
    }

    @Test
    void generateImage_batch_sizeが複数の場合はimages配列全件をviewで取得する() {
        server.expect(requestTo(BASE_URL + "/prompt"))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("\"batch_size\":4")))
                .andRespond(withSuccess("{\"prompt_id\":\"job-batch\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/history/job-batch"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"job-batch\":{\"outputs\":{\"9\":{\"images\":["
                                + "{\"filename\":\"letsblog_00001_.png\",\"subfolder\":\"\",\"type\":\"output\"},"
                                + "{\"filename\":\"letsblog_00002_.png\",\"subfolder\":\"\",\"type\":\"output\"},"
                                + "{\"filename\":\"letsblog_00003_.png\",\"subfolder\":\"\",\"type\":\"output\"},"
                                + "{\"filename\":\"letsblog_00004_.png\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}",
                        MediaType.APPLICATION_JSON));
        for (int i = 1; i <= 4; i++) {
            server.expect(requestToUriTemplate(BASE_URL + "/view?filename={filename}&subfolder={subfolder}&type={type}",
                            "letsblog_0000" + i + "_.png", "", "output"))
                    .andExpect(method(GET))
                    .andRespond(withSuccess(new byte[] {(byte) i}, MediaType.IMAGE_PNG));
        }

        ComfyUiGenerationParams params = new ComfyUiGenerationParams(
                "a cat", "bad", 20, 7.0, "euler", "normal", null, 512, 512, 4, null, null, null);
        List<ComfyUiImage> images = client.generateImage(params);

        assertEquals(4, images.size());
        assertEquals("letsblog_00001_.png", images.get(0).fileName());
        assertEquals("letsblog_00004_.png", images.get(3).fileName());
        server.verify();
    }

    @Test
    void generateImage_checkpoint未指定時はコンストラクタのデフォルトを使う() {
        expectPromptAndHistory("\"ckpt_name\":\"default-checkpoint.safetensors\"");

        client.generateImage(ComfyUiGenerationParams.withDefaults("a cat"));

        server.verify();
    }

    @Test
    void generateImage_パラメータ変更がワークフローJSONへ反映される() {
        ComfyUiGenerationParams params = new ComfyUiGenerationParams(
                "a dog", "low quality", 42, 12.5, "dpm_2", "karras", 1234L,
                768, 640, 2, "custom.safetensors", null, null);

        server.expect(requestTo(BASE_URL + "/prompt"))
                .andExpect(content().string(containsString("\"steps\":42")))
                .andExpect(content().string(containsString("\"cfg\":12.5")))
                .andExpect(content().string(containsString("\"sampler_name\":\"dpm_2\"")))
                .andExpect(content().string(containsString("\"scheduler\":\"karras\"")))
                .andExpect(content().string(containsString("\"seed\":1234")))
                .andExpect(content().string(containsString("\"width\":768")))
                .andExpect(content().string(containsString("\"height\":640")))
                .andExpect(content().string(containsString("\"batch_size\":2")))
                .andExpect(content().string(containsString("\"ckpt_name\":\"custom.safetensors\"")))
                .andRespond(withSuccess("{\"prompt_id\":\"job-2\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/history/job-2"))
                .andRespond(withSuccess(
                        "{\"job-2\":{\"outputs\":{\"9\":{\"images\":[" +
                                "{\"filename\":\"letsblog_00002_.png\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestToUriTemplate(BASE_URL + "/view?filename={filename}&subfolder={subfolder}&type={type}",
                        "letsblog_00002_.png", "", "output"))
                .andRespond(withSuccess(new byte[] {1}, MediaType.IMAGE_PNG));

        client.generateImage(params);

        server.verify();
    }

    @Test
    void generateImage_LoRA指定時はLoraLoaderノードを挿入しmodel参照を切り替える() {
        server.expect(requestTo(BASE_URL + "/prompt"))
                .andExpect(content().string(containsString("\"class_type\":\"LoraLoader\"")))
                .andExpect(content().string(containsString("\"lora_name\":\"my-lora.safetensors\"")))
                .andExpect(content().string(containsString("\"strength_model\":0.8")))
                .andRespond(withSuccess("{\"prompt_id\":\"job-lora\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/history/job-lora"))
                .andRespond(withSuccess(
                        "{\"job-lora\":{\"outputs\":{\"9\":{\"images\":[" +
                                "{\"filename\":\"out.png\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestToUriTemplate(BASE_URL + "/view?filename={filename}&subfolder={subfolder}&type={type}",
                        "out.png", "", "output"))
                .andRespond(withSuccess(new byte[] {1}, MediaType.IMAGE_PNG));

        ComfyUiGenerationParams params = new ComfyUiGenerationParams(
                "a cat", "bad", 20, 7.0, "euler", "normal", null, 512, 512, 1,
                null, "my-lora.safetensors", 0.8);
        client.generateImage(params);

        server.verify();
    }

    @Test
    void generateImage_LoRA未指定時はLoraLoaderノードを含まない() {
        server.expect(requestTo(BASE_URL + "/prompt"))
                .andExpect(content().string(not(containsString("LoraLoader"))))
                .andRespond(withSuccess("{\"prompt_id\":\"job-nolora\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/history/job-nolora"))
                .andRespond(withSuccess(
                        "{\"job-nolora\":{\"outputs\":{\"9\":{\"images\":[" +
                                "{\"filename\":\"out.png\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestToUriTemplate(BASE_URL + "/view?filename={filename}&subfolder={subfolder}&type={type}",
                        "out.png", "", "output"))
                .andRespond(withSuccess(new byte[] {1}, MediaType.IMAGE_PNG));

        client.generateImage(ComfyUiGenerationParams.withDefaults("a cat"));

        server.verify();
    }

    @Test
    void generateImage_seedがnullなら自動生成される() {
        server.expect(requestTo(BASE_URL + "/prompt"))
                .andExpect(content().string(not(containsString("\"seed\":-"))))
                .andRespond(withSuccess("{\"prompt_id\":\"job-seed\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE_URL + "/history/job-seed"))
                .andRespond(withSuccess(
                        "{\"job-seed\":{\"outputs\":{\"9\":{\"images\":[" +
                                "{\"filename\":\"out.png\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestToUriTemplate(BASE_URL + "/view?filename={filename}&subfolder={subfolder}&type={type}",
                        "out.png", "", "output"))
                .andRespond(withSuccess(new byte[] {1}, MediaType.IMAGE_PNG));

        ComfyUiGenerationParams params = new ComfyUiGenerationParams(
                "a cat", "bad", 20, 7.0, "euler", "normal", -1L, 512, 512, 1, null, null, null);
        client.generateImage(params);

        server.verify();
    }

    @Test
    void listSamplers_KSamplerのsampler_nameオプションを抽出する() {
        server.expect(requestTo(BASE_URL + "/object_info/KSampler"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"KSampler\":{\"input\":{\"required\":{" +
                                "\"sampler_name\":[[\"euler\",\"euler_ancestral\",\"dpm_2\"]]," +
                                "\"scheduler\":[[\"normal\",\"karras\"]]}}}}",
                        MediaType.APPLICATION_JSON));

        List<String> samplers = client.listSamplers();

        assertEquals(List.of("euler", "euler_ancestral", "dpm_2"), samplers);
        server.verify();
    }

    @Test
    void listSchedulers_KSamplerのschedulerオプションを抽出する() {
        server.expect(requestTo(BASE_URL + "/object_info/KSampler"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"KSampler\":{\"input\":{\"required\":{" +
                                "\"sampler_name\":[[\"euler\"]]," +
                                "\"scheduler\":[[\"normal\",\"karras\",\"exponential\"]]}}}}",
                        MediaType.APPLICATION_JSON));

        List<String> schedulers = client.listSchedulers();

        assertEquals(List.of("normal", "karras", "exponential"), schedulers);
        server.verify();
    }

    @Test
    void listLoras_LoraLoaderのlora_nameオプションを抽出する() {
        server.expect(requestTo(BASE_URL + "/object_info/LoraLoader"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"LoraLoader\":{\"input\":{\"required\":{" +
                                "\"lora_name\":[[\"lora_a.safetensors\",\"lora_b.safetensors\"]]}}}}",
                        MediaType.APPLICATION_JSON));

        List<String> loras = client.listLoras();

        assertEquals(List.of("lora_a.safetensors", "lora_b.safetensors"), loras);
        server.verify();
    }

    @Test
    void listLoras_LoraLoaderノード未実装時は空リストを返し例外を投げない() {
        server.expect(requestTo(BASE_URL + "/object_info/LoraLoader"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        List<String> loras = client.listLoras();

        assertTrue(loras.isEmpty());
    }
}
