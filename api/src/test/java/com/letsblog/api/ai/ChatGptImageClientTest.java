package com.letsblog.api.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/**
 * ChatGptImageClientの回帰テスト。ComfyUiClientTestと同様、MockRestServiceServerでHTTP通信を検証する。
 */
class ChatGptImageClientTest {

    private static final String BASE_URL = "http://chatgpt.test";

    private ChatGptImageClient client;
    private MockRestServiceServer server;
    private String apiKey;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        ImageGenerationConfigProvider configProvider = new ImageGenerationConfigProvider() {
            @Override
            public String comfyUiBaseUrl() {
                return "";
            }

            @Override
            public String chatGptApiKey() {
                return apiKey;
            }

            @Override
            public String chatGptBaseUrl() {
                return BASE_URL;
            }
        };
        apiKey = "sk-test-key";
        client = new ChatGptImageClient(builder, configProvider);
    }

    @Test
    void generateImage_レスポンスのbase64画像をデコードして返す() {
        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer sk-test-key"))
                .andExpect(content().string(containsString("\"model\":\"gpt-image-1\"")))
                .andExpect(content().string(containsString("\"prompt\":\"a cat\"")))
                .andRespond(withSuccess(
                        "{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}) + "\"}]}",
                        MediaType.APPLICATION_JSON));

        List<ComfyUiImage> images = client.generateImage(ComfyUiGenerationParams.withDefaults("a cat"));

        assertEquals(1, images.size());
        assertEquals("image/png", images.get(0).mimeType());
        assertEquals(3, images.get(0).data().length);
        server.verify();
    }

    @Test
    void generateImage_batchSize分のnパラメータを送信する() {
        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andExpect(content().string(containsString("\"n\":4")))
                .andRespond(withSuccess(
                        "{\"data\":[" +
                                "{\"b64_json\":\"AQID\"},{\"b64_json\":\"AQID\"}," +
                                "{\"b64_json\":\"AQID\"},{\"b64_json\":\"AQID\"}]}",
                        MediaType.APPLICATION_JSON));

        ComfyUiGenerationParams params = new ComfyUiGenerationParams(
                "a cat", "bad", 20, 7.0, "euler", "normal", null, 512, 512, 4, null, null, null);
        List<ComfyUiImage> images = client.generateImage(params);

        assertEquals(4, images.size());
        server.verify();
    }

    @Test
    void generateImage_正方形指定時は1024x1024へ丸める() {
        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andExpect(content().string(containsString("\"size\":\"1024x1024\"")))
                .andRespond(withSuccess("{\"data\":[{\"b64_json\":\"AQID\"}]}", MediaType.APPLICATION_JSON));

        ComfyUiGenerationParams params = new ComfyUiGenerationParams(
                "a cat", null, null, null, null, null, null, 512, 512, 1, null, null, null);
        client.generateImage(params);

        server.verify();
    }

    @Test
    void generateImage_横長指定時は1536x1024へ丸める() {
        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andExpect(content().string(containsString("\"size\":\"1536x1024\"")))
                .andRespond(withSuccess("{\"data\":[{\"b64_json\":\"AQID\"}]}", MediaType.APPLICATION_JSON));

        ComfyUiGenerationParams params = new ComfyUiGenerationParams(
                "a cat", null, null, null, null, null, null, 1920, 1080, 1, null, null, null);
        client.generateImage(params);

        server.verify();
    }

    @Test
    void generateImage_縦長指定時は1024x1536へ丸める() {
        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andExpect(content().string(containsString("\"size\":\"1024x1536\"")))
                .andRespond(withSuccess("{\"data\":[{\"b64_json\":\"AQID\"}]}", MediaType.APPLICATION_JSON));

        ComfyUiGenerationParams params = new ComfyUiGenerationParams(
                "a cat", null, null, null, null, null, null, 1080, 1920, 1, null, null, null);
        client.generateImage(params);

        server.verify();
    }

    @Test
    void generateImage_APIキー未設定時は例外を投げHTTP呼び出しを行わない() {
        apiKey = "";

        assertThrows(AiServiceException.class,
                () -> client.generateImage(ComfyUiGenerationParams.withDefaults("a cat")));
        server.verify();
    }

    @Test
    void generateImage_APIエラー時は例外に変換される() {
        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andRespond(withUnauthorizedRequest());

        assertThrows(AiServiceException.class,
                () -> client.generateImage(ComfyUiGenerationParams.withDefaults("a cat")));
        server.verify();
    }
}
