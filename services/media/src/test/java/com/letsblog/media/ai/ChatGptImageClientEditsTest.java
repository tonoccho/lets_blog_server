package com.letsblog.media.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 参照画像付きのChatGPT生成は/images/editsへmultipartで送る(issue #1602)。
 * 参照画像が無い場合の/images/generations(JSON)は{@link ChatGptImageClientTest}が固定している。
 */
@DisplayName("media-service: ChatGPT画像生成のimages/edits(issue #1602)")
class ChatGptImageClientEditsTest {

    private static final String BASE_URL = "http://chatgpt.test";
    private static final String OK_BODY = "{\"data\":[{\"b64_json\":\"AA==\"},{\"b64_json\":\"AQ==\"}]}";

    private MockRestServiceServer server;
    private ChatGptImageClient client;
    private final java.util.Map<Long, String> keys = new java.util.HashMap<>();
    private final StringBuilder multipart = new StringBuilder();
    private final StringBuilder contentType = new StringBuilder();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        keys.put(3L, "sk-edit");
        client = new ChatGptImageClient(builder, new ImageGenerationConfigProvider() {
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
        });
    }

    private void expectEdits() {
        server.expect(requestTo(BASE_URL + "/images/edits"))
                .andExpect(header("Authorization", "Bearer sk-edit"))
                .andExpect(request -> {
                    MockClientHttpRequest mock = (MockClientHttpRequest) request;
                    contentType.append(mock.getHeaders().getContentType());
                    multipart.append(new String(mock.getBodyAsBytes(), StandardCharsets.ISO_8859_1));
                })
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));
    }

    private static ComfyUiGenerationParams params(ReferenceImage ref, Integer width, Integer height, Integer n) {
        return new ComfyUiGenerationParams(
                "make the background night", null, 20, 7.0, "euler", "normal", null, width, height, n,
                null, null, null, 3L, ref, 0.3);
    }

    private static ReferenceImage png() {
        return new ReferenceImage(new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "image/png");
    }

    @Test
    void 参照画像と指示とモデルをmultipartでimages_editsへ送り結果を画像として返す() {
        expectEdits();

        List<ComfyUiImage> images = client.generateImage(params(png(), 1024, 1536, 2));

        server.verify();
        assertTrue(contentType.toString().startsWith("multipart/form-data"), contentType.toString());
        String body = multipart.toString();
        assertTrue(body.contains("name=\"image\"; filename=\"reference.png\""), body);
        assertTrue(body.contains("Content-Type: image/png"), body);
        assertTrue(body.contains("\u0089PNG"), "参照画像のバイト列が送られている");
        assertTrue(body.contains("name=\"prompt\""), body);
        assertTrue(body.contains("make the background night"), body);
        assertTrue(body.contains("name=\"model\""), body);
        assertTrue(body.contains("gpt-image-1"), body);
        assertTrue(body.contains("name=\"n\""), body);
        assertTrue(body.contains("name=\"size\""), body);
        assertTrue(body.contains("1024x1536"), body);
        assertFalse(body.contains("denoise"), "ChatGPTはdenoiseを受け付けない");
        assertEquals(2, images.size());
        assertEquals("chatgpt_1.png", images.get(0).fileName());
        assertEquals("chatgpt_2.png", images.get(1).fileName());
        assertEquals("image/png", images.get(0).mimeType());
    }

    @Test
    void JPEGの参照画像は拡張子jpgで送る() {
        expectEdits();

        client.generateImage(params(new ReferenceImage(new byte[] {1}, "image/jpeg"), 512, 512, 1));

        assertTrue(multipart.toString().contains("filename=\"reference.jpg\""), multipart.toString());
    }

    @Test
    void WebPの参照画像は拡張子webpで送る() {
        expectEdits();

        client.generateImage(params(new ReferenceImage(new byte[] {1}, "image/webp"), 512, 512, 1));

        assertTrue(multipart.toString().contains("filename=\"reference.webp\""), multipart.toString());
    }

    @Test
    void MIME不明の参照画像はpngとして送る() {
        expectEdits();

        client.generateImage(params(new ReferenceImage(new byte[] {1}, null), null, null, null));

        String body = multipart.toString();
        assertTrue(body.contains("filename=\"reference.png\""), body);
        assertTrue(body.contains("Content-Type: image/png"), body);
        assertTrue(body.contains("auto"), "サイズ未指定はautoのまま送る");
    }

    @Test
    void 横長の生成サイズは1536x1024へ丸める() {
        expectEdits();

        client.generateImage(params(png(), 1600, 900, 1));

        assertTrue(multipart.toString().contains("1536x1024"), multipart.toString());
    }

    @Test
    void キー未設定なら参照画像付きでも外部APIを呼ばずキー設定を促すエラーにする() {
        keys.remove(3L);

        AiServiceException e = assertThrows(AiServiceException.class,
                () -> client.generateImage(params(png(), 512, 512, 1)));

        assertTrue(e.getMessage().contains("このプロジェクト"), e.getMessage());
        assertTrue(e.getMessage().contains("APIキー"), e.getMessage());
        server.verify();
    }

    @Test
    void images_editsがエラーを返したらeditsの失敗として状態コードを含めて報告する() {
        server.expect(requestTo(BASE_URL + "/images/edits"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));

        AiServiceException e = assertThrows(AiServiceException.class,
                () -> client.generateImage(params(png(), 512, 512, 1)));

        assertTrue(e.getMessage().contains("ChatGPT"), e.getMessage());
        assertTrue(e.getMessage().contains("images/edits"), e.getMessage());
        assertTrue(e.getMessage().contains("500"), e.getMessage());
    }

    @Test
    void edits呼び出し中の想定外の例外もAiServiceExceptionにする() {
        server.expect(requestTo(BASE_URL + "/images/edits"))
                .andRespond(withSuccess("{\"unexpected\":true}", MediaType.APPLICATION_JSON));

        AiServiceException e = assertThrows(AiServiceException.class,
                () -> client.generateImage(params(png(), 512, 512, 1)));

        assertTrue(e.getMessage().contains("images/edits"), e.getMessage());
    }

    @Test
    void エラーメッセージにキーの値を含めない() {
        keys.put(3L, "sk-secret-value");
        server.expect(requestTo(BASE_URL + "/images/edits")).andRespond(withServerError());

        AiServiceException e = assertThrows(AiServiceException.class,
                () -> client.generateImage(params(png(), 512, 512, 1)));

        assertFalse(String.valueOf(e.getMessage()).contains("sk-secret-value"));
    }
}
