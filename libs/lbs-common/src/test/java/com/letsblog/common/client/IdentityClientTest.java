package com.letsblog.common.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * issue #829: identity-serviceの401/403(認証・認可の結果)と、サービス障害(5xx・通信断)を
 * 区別できていることを固定する。
 *
 * <p>区別が無いと、無効化されたユーザーがブラウザを開いたままにしているだけで各サービスが
 * 502を返し続け、本当のidentity-service障害と区別できなくなる。逆に障害まで
 * 「操作者なし」へ縮退させると、権限チェックが素通りする方向の不具合になりうる。
 */
class IdentityClientTest {

    private HttpServer httpServer;

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    private IdentityClient clientFor(int status, String body) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        httpServer.start();
        return new IdentityClient(RestClient.builder(), "http://localhost:" + httpServer.getAddress().getPort());
    }

    @Test
    @DisplayName("正常応答は操作者として解決する")
    void 正常応答() throws IOException {
        IdentityClient client = clientFor(200, "{\"id\":7,\"email\":\"a@example.com\",\"role\":\"admin\"}");

        Optional<ActorProfile> profile = client.lookupProfile("Bearer token");

        assertTrue(profile.isPresent());
        assertEquals(7L, profile.get().id());
    }

    @Test
    @DisplayName("403(無効化ユーザー等)は障害ではなく「操作者なし」として扱う")
    void 応答403は操作者なし() throws IOException {
        // #816以降、無効化されたユーザーはidentity-serviceが403を返す。これは正常な判定結果で、
        // 例外として伝播させると呼び出し元が502へ翻訳してしまう。
        IdentityClient client = clientFor(403, "{\"error\":\"Forbidden\"}");

        assertEquals(Optional.empty(), client.lookupProfile("Bearer token"));
    }

    @Test
    @DisplayName("401も「操作者なし」として扱う")
    void 応答401は操作者なし() throws IOException {
        IdentityClient client = clientFor(401, "{\"error\":\"Unauthorized\"}");

        assertEquals(Optional.empty(), client.lookupProfile("Bearer token"));
    }

    @Test
    @DisplayName("5xxは従来どおり例外のまま伝播させる(障害を握り潰さない)")
    void 応答5xxは例外() throws IOException {
        // ここを一緒に「操作者なし」へ縮退させると、identity-service障害時に
        // 権限チェックが素通りする方向の不具合になりうる。
        IdentityClient client = clientFor(500, "{\"error\":\"boom\"}");

        assertThrows(SyncServiceException.class, () -> client.lookupProfile("Bearer token"));
    }

    @Test
    @DisplayName("404など401/403以外の4xxも例外のまま伝播させる")
    void 応答404は例外() throws IOException {
        // 401/403だけが「操作者を解決できない」という判定結果。それ以外の4xxは
        // 呼び出し方の誤り(パス違い等)であり、静かに縮退させるべきではない。
        IdentityClient client = clientFor(404, "{\"error\":\"Not Found\"}");

        assertThrows(SyncServiceException.class, () -> client.lookupProfile("Bearer token"));
    }

    @Test
    @DisplayName("通信断は従来どおり例外のまま伝播させる")
    void 通信断は例外() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        int port = socket.getLocalPort();
        socket.close();
        IdentityClient client = new IdentityClient(RestClient.builder(), "http://localhost:" + port);

        assertThrows(SyncServiceException.class, () -> client.lookupProfile("Bearer token"));
    }
}
