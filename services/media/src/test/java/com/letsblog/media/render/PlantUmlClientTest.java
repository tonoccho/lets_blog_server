package com.letsblog.media.render;

import com.letsblog.media.ai.AiServiceException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    void HTTPエラー応答はAiServiceExceptionではなく拒否の専用例外になる() throws Exception {
        withServer(500, null, null, client -> {
            RuntimeException e = assertThrows(RuntimeException.class, () -> client.renderPng(SOURCE));

            assertInstanceOf(PlantUmlRenderRejectedException.class, e);
            assertFalse(e instanceof AiServiceException);
            assertTrue(e.getMessage().startsWith("PlantUML が図を描けませんでした: "), e.getMessage());
            assertTrue(e.getMessage().contains("500"), e.getMessage());
        });
    }

    @Test
    void エラーヘッダーが無ければstatusコードを詳細にする() throws Exception {
        withServer(414, null, null, client -> {
            PlantUmlRenderRejectedException e =
                    assertThrows(PlantUmlRenderRejectedException.class, () -> client.renderPng(SOURCE));

            assertEquals("PlantUML が図を描けませんでした: " + HttpStatus.URI_TOO_LONG, e.getMessage());
            assertFalse(e.getMessage().contains("接続できません"));
        });
    }

    @Test
    void エラーヘッダーが空ならstatusコードを詳細にする() throws Exception {
        withServer(400, "", "3", client -> {
            PlantUmlRenderRejectedException e =
                    assertThrows(PlantUmlRenderRejectedException.class, () -> client.renderPng(SOURCE));

            assertEquals("PlantUML が図を描けませんでした: " + HttpStatus.BAD_REQUEST, e.getMessage());
        });
    }

    @Test
    void エラーヘッダーの値を詳細にする() throws Exception {
        withServer(400, "Syntax Error?", null, client -> {
            PlantUmlRenderRejectedException e =
                    assertThrows(PlantUmlRenderRejectedException.class, () -> client.renderPng(SOURCE));

            assertEquals("PlantUML が図を描けませんでした: Syntax Error?", e.getMessage());
        });
    }

    @Test
    void 行番号ヘッダーがあれば行目を後ろに付ける() throws Exception {
        withServer(400, "Syntax Error?", "2", client -> {
            PlantUmlRenderRejectedException e =
                    assertThrows(PlantUmlRenderRejectedException.class, () -> client.renderPng(SOURCE));

            assertEquals("PlantUML が図を描けませんでした: Syntax Error? (2 行目)", e.getMessage());
        });
    }

    @Test
    void 行番号ヘッダーが空なら行目を付けない() throws Exception {
        withServer(400, "Syntax Error?", " ", client -> {
            PlantUmlRenderRejectedException e =
                    assertThrows(PlantUmlRenderRejectedException.class, () -> client.renderPng(SOURCE));

            assertEquals("PlantUML が図を描けませんでした: Syntax Error?", e.getMessage());
        });
    }

    @Test
    void 制御文字は半角スペースに置き換え前後の空白を除く() throws Exception {
        withServer(400, "\tSyntax\tError?\t", null, client -> {
            PlantUmlRenderRejectedException e =
                    assertThrows(PlantUmlRenderRejectedException.class, () -> client.renderPng(SOURCE));

            assertEquals("PlantUML が図を描けませんでした: Syntax Error?", e.getMessage());
        });
    }

    @Test
    void 詳細が200文字を超えたら200文字で切り末尾に省略記号を付ける() throws Exception {
        withServer(400, "x".repeat(250), null, client -> {
            PlantUmlRenderRejectedException e =
                    assertThrows(PlantUmlRenderRejectedException.class, () -> client.renderPng(SOURCE));

            assertEquals("PlantUML が図を描けませんでした: " + "x".repeat(200) + "…", e.getMessage());
        });
    }

    @Test
    void 詳細がちょうど200文字なら切らない() throws Exception {
        withServer(400, "y".repeat(200), null, client -> {
            PlantUmlRenderRejectedException e =
                    assertThrows(PlantUmlRenderRejectedException.class, () -> client.renderPng(SOURCE));

            assertEquals("PlantUML が図を描けませんでした: " + "y".repeat(200), e.getMessage());
        });
    }

    private interface ClientAssertion {
        void run(PlantUmlClient client) throws Exception;
    }

    private static void withServer(int status, String errorHeader, String lineHeader, ClientAssertion assertion)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            if (errorHeader != null) {
                ex.getResponseHeaders().add("X-PlantUML-Diagram-Error", errorHeader);
            }
            if (lineHeader != null) {
                ex.getResponseHeaders().add("X-PlantUML-Diagram-Error-Line", lineHeader);
            }
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
        try {
            assertion.run(new PlantUmlClient("http://127.0.0.1:" + server.getAddress().getPort()));
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
