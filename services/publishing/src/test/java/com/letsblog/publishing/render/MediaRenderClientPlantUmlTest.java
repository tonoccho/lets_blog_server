package com.letsblog.publishing.render;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * media-serviceが返したPlantUML拒否(400)と、それ以外の失敗を区別する(issue #1776)。
 * 実際のHTTPサーバーに対して{@link MediaRenderClient}を動かす。
 */
@DisplayName("publishing-service: MediaRenderClient.renderPlantUml の拒否判定(issue #1776)")
class MediaRenderClientPlantUmlTest {

    private static final String PREFIX = "PlantUML が図を描けませんでした";

    private interface ClientAssertion {
        void run(MediaRenderClient client) throws Exception;
    }

    private static void withMedia(int status, String body, ClientAssertion assertion) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                ex.getResponseBody().write(bytes);
            }
            ex.close();
        });
        server.start();
        try {
            MediaRenderClient client = new MediaRenderClient(
                    RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(),
                    new MockHttpServletRequest());
            assertion.run(client);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 拒否の400は本文のerrorを文言にして専用例外にする() throws Exception {
        withMedia(400, "{\"error\":\"" + PREFIX + ": Syntax Error? (2 行目)\"}", client -> {
            PlantUmlRenderRejectedException e =
                    assertThrows(PlantUmlRenderRejectedException.class, () -> client.renderPlantUml("x"));

            assertEquals(PREFIX + ": Syntax Error? (2 行目)", e.getMessage());
        });
    }

    @Test
    void 拒否以外の文言の400は定型文になる() throws Exception {
        withMedia(400, "{\"error\":\"source は必須です\"}", client -> {
            PlantUmlRenderRejectedException e =
                    assertThrows(PlantUmlRenderRejectedException.class, () -> client.renderPlantUml("x"));

            assertEquals(PREFIX + ": media-service が 400 を返しました", e.getMessage());
        });
    }

    @Test
    void errorが無いJSONの400は定型文になる() throws Exception {
        withMedia(400, "{\"other\":1}", client -> {
            PlantUmlRenderRejectedException e =
                    assertThrows(PlantUmlRenderRejectedException.class, () -> client.renderPlantUml("x"));

            assertEquals(PREFIX + ": media-service が 400 を返しました", e.getMessage());
        });
    }

    @Test
    void errorが文字列でないJSONの400は定型文になる() throws Exception {
        withMedia(400, "{\"error\":123}", client -> {
            PlantUmlRenderRejectedException e =
                    assertThrows(PlantUmlRenderRejectedException.class, () -> client.renderPlantUml("x"));

            assertEquals(PREFIX + ": media-service が 400 を返しました", e.getMessage());
        });
    }

    @Test
    void JSONでない本文の400は定型文になる() throws Exception {
        withMedia(400, "not json", client -> {
            PlantUmlRenderRejectedException e =
                    assertThrows(PlantUmlRenderRejectedException.class, () -> client.renderPlantUml("x"));

            assertEquals(PREFIX + ": media-service が 400 を返しました", e.getMessage());
        });
    }

    @Test
    void 本文が空の400は定型文になる() throws Exception {
        withMedia(400, "", client -> {
            PlantUmlRenderRejectedException e =
                    assertThrows(PlantUmlRenderRejectedException.class, () -> client.renderPlantUml("x"));

            assertEquals(PREFIX + ": media-service が 400 を返しました", e.getMessage());
        });
    }

    @Test
    void 拒否の専用例外はMediaRenderExceptionではない() {
        assertFalse(MediaRenderException.class.isAssignableFrom(PlantUmlRenderRejectedException.class));
    }

    @Test
    void 他の4xxは従来どおりMediaRenderExceptionになる() throws Exception {
        withMedia(404, "{\"error\":\"x\"}", client ->
                assertThrows(MediaRenderException.class, () -> client.renderPlantUml("x")));
    }

    @Test
    void 接続できないときは従来どおりMediaRenderExceptionになる() {
        MediaRenderClient client = new MediaRenderClient(
                RestClient.builder(), "http://127.0.0.1:1", new MockHttpServletRequest());

        assertThrows(MediaRenderException.class, () -> client.renderPlantUml("x"));
    }

    @Test
    void 成功時はPNGを返す() throws Exception {
        withMedia(200, "abc", client ->
                assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), client.renderPlantUml("x")));
    }
}
