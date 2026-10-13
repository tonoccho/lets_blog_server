package com.letsblog.media.render;

import com.letsblog.media.ai.AiServiceException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PlantUMLサーバーへ接続できないとき・エラー応答のとき・成功時の{@link PlantUmlClient}の挙動(issue #1759)。 */
@DisplayName("media-service: PlantUmlClient(issue #1759)")
class PlantUmlClientTest {

    private static final String SOURCE = "@startuml\nA->B\n@enduml";

    @Test
    void 接続拒否はAiServiceExceptionになり接続できない旨を含む() {
        PlantUmlClient client = new PlantUmlClient("http://127.0.0.1:1");

        AiServiceException e = assertThrows(AiServiceException.class, () -> client.renderPng(SOURCE));

        assertTrue(e.getMessage().contains("PlantUMLサーバーに接続できません"), e.getMessage());
    }

    @Test
    void 名前解決できない宛先もAiServiceExceptionになる() {
        PlantUmlClient client = new PlantUmlClient("http://plantuml.invalid:8080");

        AiServiceException e = assertThrows(AiServiceException.class, () -> client.renderPng(SOURCE));

        assertTrue(e.getMessage().contains("PlantUMLサーバーに接続できません"), e.getMessage());
    }

    @Test
    void HTTPエラー応答は従来どおりレンダリング失敗のAiServiceExceptionになる() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        server.start();
        try {
            PlantUmlClient client = new PlantUmlClient("http://127.0.0.1:" + server.getAddress().getPort());

            AiServiceException e = assertThrows(AiServiceException.class, () -> client.renderPng(SOURCE));

            assertTrue(e.getMessage().contains("PlantUMLのレンダリングに失敗しました"), e.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 成功時はPNGバイト列を返す() throws Exception {
        byte[] png = {1, 2, 3};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/png/", ex -> {
            ex.sendResponseHeaders(200, png.length);
            ex.getResponseBody().write(png);
            ex.close();
        });
        server.start();
        try {
            PlantUmlClient client = new PlantUmlClient("http://127.0.0.1:" + server.getAddress().getPort());

            assertArrayEquals(png, client.renderPng(SOURCE));
            assertEquals(3, png.length);
        } finally {
            server.stop(0);
        }
    }
}
