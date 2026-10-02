package com.letsblog.media.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * ChatGptImageClientの送信ボディ回帰テスト(issue #1085)。
 *
 * <p>ChatGPT(gpt-image-1)はnegative promptを送れないため、{@link ComfyUiGenerationParams#negativePrompt()}
 * にissue #1085の安全側ネガティブプロンプトが連結されていても、このクライアントの送信ボディは
 * 一切変わってはならない(model/prompt/n/sizeの4フィールドのみ)。本Issueでは
 * {@code ImageGenerationService}側でCOMFYUI経路のときだけ連結するため、このクライアント自体は
 * 変更しないが、その前提を送信ボディそのもので固定する。
 */
@DisplayName("media-service: ChatGPT画像生成の送信ボディ(issue #1085回帰)")
class ChatGptImageClientTest {

    private static final String BASE_URL = "http://chatgpt.test";

    private MockRestServiceServer server;
    private ChatGptImageClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final StubConfigProvider configProvider = new StubConfigProvider();
    private final StringBuilder submittedBody = new StringBuilder();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ChatGptImageClient(builder, configProvider);
    }

    private void expectGeneration() {
        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andExpect(request -> submittedBody.append(
                        new String(((MockClientHttpRequest) request).getBodyAsBytes())))
                .andRespond(withSuccess(
                        "{\"data\":[{\"b64_json\":\"AA==\"}]}", MediaType.APPLICATION_JSON));
    }

    private JsonNode submittedJson() throws Exception {
        return mapper.readTree(submittedBody.toString());
    }

    @Test
    void 送信ボディにnegativePromptに由来するフィールドを含まない() throws Exception {
        expectGeneration();

        // ImageGenerationServiceがCOMFYUI経路でのみ安全側ネガティブプロンプトを連結するため、
        // ここでは「連結済みの値がparamsに乗っていたとしても」送信ボディへは反映されないことを示す。
        ComfyUiGenerationParams params = new ComfyUiGenerationParams(
                "a cat", "blurry, nsfw, nude", 20, 7.0, "euler", "normal", null, 1024, 1024, 1,
                null, null, null);

        client.generateImage(params);

        JsonNode body = submittedJson();
        assertEquals("gpt-image-1", body.path("model").asText());
        assertEquals("a cat", body.path("prompt").asText());
        assertEquals(1, body.path("n").asInt());
        assertEquals("1024x1024", body.path("size").asText());

        List<String> fieldNames = new java.util.ArrayList<>();
        for (Iterator<String> it = body.fieldNames(); it.hasNext(); ) {
            fieldNames.add(it.next());
        }
        assertEquals(List.of("model", "prompt", "n", "size"), fieldNames,
                "送信ボディのフィールドはmodel/prompt/n/sizeの4つだけでなければならない");
        assertFalse(submittedBody.toString().toLowerCase(java.util.Locale.ROOT).contains("nsfw"),
                "安全側ネガティブプロンプトの語が送信ボディへ紛れ込んでいない");
    }

    @Test
    void negativePromptがnullでも送信ボディは変わらない() throws Exception {
        expectGeneration();

        ComfyUiGenerationParams params = new ComfyUiGenerationParams(
                "a cat", null, 20, 7.0, "euler", "normal", null, 1024, 1024, 1, null, null, null);

        client.generateImage(params);

        assertTrue(submittedJson().path("prompt").asText().equals("a cat"));
    }

    private static ComfyUiGenerationParams paramsFor(Long projectId) {
        return new ComfyUiGenerationParams(
                "a cat", null, 20, 7.0, "euler", "normal", null, 1024, 1024, 1, null, null, null, projectId);
    }

    @Test
    void プロジェクトに設定されたキーでBearer認証して呼ぶ() {
        configProvider.keys.put(7L, "sk-project-7");
        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andExpect(header("Authorization", "Bearer sk-project-7"))
                .andRespond(withSuccess("{\"data\":[{\"b64_json\":\"AA==\"}]}", MediaType.APPLICATION_JSON));

        client.generateImage(paramsFor(7L));

        server.verify();
    }

    @Test
    void プロジェクトごとに別のキーで呼ぶ() {
        configProvider.keys.put(7L, "sk-project-7");
        configProvider.keys.put(8L, "sk-project-8");
        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andExpect(header("Authorization", "Bearer sk-project-8"))
                .andRespond(withSuccess("{\"data\":[{\"b64_json\":\"AA==\"}]}", MediaType.APPLICATION_JSON));

        client.generateImage(paramsFor(8L));

        server.verify();
    }

    @Test
    void キー未設定のプロジェクトは外部APIを呼ばずキー設定を促すエラーにする() {
        AiServiceException e = assertThrows(AiServiceException.class, () -> client.generateImage(paramsFor(9L)));

        assertTrue(e.getMessage().contains("このプロジェクト"), e.getMessage());
        assertTrue(e.getMessage().contains("APIキー"), e.getMessage());
        server.verify(); // 期待リクエストが0件 = 外部APIは呼ばれていない
    }

    @Test
    void キーが空白だけでも未設定として扱う() {
        configProvider.keys.put(9L, "   ");

        assertThrows(AiServiceException.class, () -> client.generateImage(paramsFor(9L)));
    }

    @Test
    void エラーメッセージにキーの値を含めない() {
        configProvider.keys.put(7L, "sk-secret-value");
        server.expect(requestTo(BASE_URL + "/images/generations"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withServerError());

        AiServiceException e = assertThrows(AiServiceException.class, () -> client.generateImage(paramsFor(7L)));

        assertFalse(String.valueOf(e.getMessage()).contains("sk-secret-value"));
    }

    private static final class StubConfigProvider implements ImageGenerationConfigProvider {
        final Map<Long, String> keys = new HashMap<>();

        StubConfigProvider() {
            keys.put(null, "sk-test");
        }

        @Override
        public String comfyUiBaseUrl(Long projectId) {
            return BASE_URL;
        }

        @Override
        public String chatGptApiKey(Long projectId) {
            return keys.get(projectId);
        }

        @Override
        public String chatGptBaseUrl() {
            return BASE_URL;
        }
    }
}
