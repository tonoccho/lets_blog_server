package com.letsblog.media.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.letsblog.common.auth.ServiceTokenUnavailableException;
import com.letsblog.common.auth.ToolsTokenProvider;
import com.letsblog.media.ai.AiServiceException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** PlantUMLの描画でextensionsのPlantUMLへ付けるトークン(#1804)。宛先は`PLANTUML_BASE_URL`だけ。 */
class PlantUmlClientToolsTokenTest {

    private static final String SOURCE = "@startuml\nA->B\n@enduml";

    private HttpServer server;
    private String url;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/png/", ex -> {
            String header = ex.getRequestHeaders().getFirst("Authorization");
            authorizations.add(header == null ? "(none)" : header);
            byte[] png = {1, 2, 3};
            ex.sendResponseHeaders(200, png.length);
            ex.getResponseBody().write(png);
            ex.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("有効なら、描画の要求に専用クライアントのトークンが付く")
    void 有効ならトークンが付く() {
        new PlantUmlClient(url, ToolsTokenProvider.fromSupplier(() -> "tok-1")).renderPng(SOURCE);

        assertThat(authorizations).containsExactly("Bearer tok-1");
    }

    @Test
    @DisplayName("secret未設定(無効)なら付かない。従来と同じ")
    void 無効なら付かない() {
        new PlantUmlClient(url, ToolsTokenProvider.disabled()).renderPng(SOURCE);
        new PlantUmlClient(url).renderPng(SOURCE);

        assertThat(authorizations).containsExactly("(none)", "(none)");
    }

    @Test
    @DisplayName("トークンが取れないときは、送らずに理由の分かるAiServiceExceptionにする")
    void 取れないときはエラー() {
        PlantUmlClient client = new PlantUmlClient(url, ToolsTokenProvider.fromSupplier(() -> {
            throw new ServiceTokenUnavailableException("サービストークンの取得に失敗しました(client_id=letsblog-tools-client): 401");
        }));

        assertThatThrownBy(() -> client.renderPng(SOURCE))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("KEYCLOAK_TOOLS_CLIENT_SECRET")
                .hasMessageContaining("401");
        assertThat(authorizations).isEmpty();
    }
}
