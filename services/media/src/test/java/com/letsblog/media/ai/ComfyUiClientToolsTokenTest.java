package com.letsblog.media.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.letsblog.common.auth.ServiceTokenUnavailableException;
import com.letsblog.common.auth.ToolsTokenProvider;
import com.letsblog.common.net.GuardedTarget;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * ComfyUIの呼び出しでextensionsのComfyUIへ付けるトークン(#1804)。システム設定の接続先にだけ付け、
 * プロジェクト単位の上書き先・secret未設定のときは付けない。取れないときは送らずに理由の分かるエラーにする。
 */
class ComfyUiClientToolsTokenTest {

    private HttpServer server;
    private String url;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/object_info", ex -> {
            record(ex);
            respond(ex, "application/json", ("{\"CheckpointLoaderSimple\":{\"input\":{\"required\":"
                    + "{\"ckpt_name\":[[\"a.safetensors\"]]}}}}").getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/prompt", ex -> {
            record(ex);
            respond(ex, "application/json", "{\"prompt_id\":\"p1\"}".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/history/p1", ex -> {
            record(ex);
            respond(ex, "application/json", ("{\"p1\":{\"outputs\":{\"9\":{\"images\":"
                    + "[{\"filename\":\"i.png\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/view", ex -> {
            record(ex);
            respond(ex, "image/png", new byte[] {1, 2, 3});
        });
        server.createContext("/free", ex -> {
            record(ex);
            respond(ex, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void record(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        authorizations.add(header == null ? "(none)" : header);
    }

    private static void respond(HttpExchange ex, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    private ComfyUiClient client(boolean systemTarget, ToolsTokenProvider tools) {
        return client(systemTarget, tools, null);
    }

    private ComfyUiClient client(boolean systemTarget, ToolsTokenProvider tools, String sniHost) {
        ImageGenerationConfigProvider config = new ImageGenerationConfigProvider() {
            @Override
            public String comfyUiBaseUrl(Long projectId) {
                return url;
            }

            @Override
            public GuardedTarget comfyUiTarget(Long projectId) {
                return new GuardedTarget(url, sniHost);
            }

            @Override
            public boolean comfyUiToolsAuthorized(Long projectId) {
                return systemTarget;
            }

            @Override
            public String chatGptApiKey(Long projectId) {
                return null;
            }

            @Override
            public String chatGptBaseUrl() {
                return "unused";
            }
        };
        return new ComfyUiClient(RestClient.builder(), config, "default.safetensors", 10, tools);
    }

    private static ComfyUiGenerationParams params() {
        return new ComfyUiGenerationParams(
                "a cat", "blurry", 20, 7.0, "euler", "normal", 1L, 512, 512, 1,
                "checkpoint.safetensors", null, null);
    }

    @Test
    @DisplayName("元がホスト名の接続先(SNI固定の専用クライアント)でも、システム設定の接続先にはトークンが付く")
    void SNI固定の専用クライアントにもトークンが付く() {
        List<String> names =
                client(true, ToolsTokenProvider.fromSupplier(() -> "tok-1"), "comfy.example").listCheckpoints(null);

        assertThat(names).containsExactly("a.safetensors");
        assertThat(authorizations).isNotEmpty().allMatch("Bearer tok-1"::equals);
    }

    @Test
    @DisplayName("元がホスト名の接続先でも、トークンを付けない設定ならAuthorizationは付かない")
    void SNI固定の専用クライアントはトークンなしの設定なら付けない() {
        List<String> names =
                client(false, ToolsTokenProvider.fromSupplier(() -> "tok-1"), "comfy.example").listCheckpoints(null);

        assertThat(names).containsExactly("a.safetensors");
        assertThat(authorizations).isNotEmpty().allMatch("(none)"::equals);
    }

    @Test
    @DisplayName("システム設定の接続先の呼び出しに、専用クライアントのトークンが付く(一覧取得)")
    void システム設定の接続先にはトークンが付く() {
        List<String> names = client(true, ToolsTokenProvider.fromSupplier(() -> "tok-1")).listCheckpoints(null);

        assertThat(names).containsExactly("a.safetensors");
        assertThat(authorizations).containsExactly("Bearer tok-1");
    }

    @Test
    @DisplayName("画像生成の全ての要求(投入・履歴・取得)に付く")
    void 画像生成の全ての要求に付く() {
        List<ComfyUiImage> images = client(true, ToolsTokenProvider.fromSupplier(() -> "tok-1")).generateImage(params());

        assertThat(images).hasSize(1);
        assertThat(authorizations).isNotEmpty().allMatch("Bearer tok-1"::equals);
    }

    @Test
    @DisplayName("プロジェクト単位の上書き先には付かない")
    void 上書き先には付かない() {
        client(false, ToolsTokenProvider.fromSupplier(() -> "tok-1")).listCheckpoints(7L);
        client(false, ToolsTokenProvider.fromSupplier(() -> "tok-1")).generateImage(params());

        assertThat(authorizations).isNotEmpty().allMatch("(none)"::equals);
    }

    @Test
    @DisplayName("secret未設定(無効)なら付かない")
    void 無効なら付かない() {
        client(true, ToolsTokenProvider.disabled()).listCheckpoints(null);

        assertThat(authorizations).containsExactly("(none)");
    }

    @Test
    @DisplayName("トークンが取れないときは、送らずに理由の分かるAiServiceExceptionにする")
    void 取れないときはエラー() {
        ComfyUiClient client = client(true, ToolsTokenProvider.fromSupplier(() -> {
            throw new ServiceTokenUnavailableException("サービストークンの取得に失敗しました(client_id=letsblog-tools-client): 401");
        }));

        assertThatThrownBy(() -> client.listCheckpoints(null))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("KEYCLOAK_TOOLS_CLIENT_SECRET")
                .hasMessageContaining("401");
        assertThatThrownBy(() -> client.generateImage(params())).isInstanceOf(AiServiceException.class);
        assertThat(authorizations).isEmpty();
    }
}
