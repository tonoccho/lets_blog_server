package com.letsblog.media.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.letsblog.common.net.ForbiddenDestinationException;
import com.letsblog.common.net.GuardedTarget;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 接続時検査で拒否されたComfyUI接続先へ、ComfyUiClientは接続せずに失敗する(issue #1547)。 */
class ComfyUiClientConnectionGuardTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/object_info", exchange -> {
            hits.incrementAndGet();
            byte[] bytes = ("{\"CheckpointLoaderSimple\":{\"input\":{\"required\":{\"ckpt_name\":[[\"a.safetensors\"]]}}},"
                    + "\"KSampler\":{\"input\":{\"required\":{\"sampler_name\":[[\"euler\"]],\"scheduler\":[[\"karras\"]]}}},"
                    + "\"LoraLoader\":{\"input\":{\"required\":{\"lora_name\":[[\"l.safetensors\"]]}}}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static ImageGenerationConfigProvider providerOf(java.util.function.Supplier<GuardedTarget> target) {
        return new ImageGenerationConfigProvider() {
            @Override
            public String comfyUiBaseUrl(Long projectId) {
                return "unused";
            }

            @Override
            public GuardedTarget comfyUiTarget(Long projectId) {
                return target.get();
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
    }

    private static ForbiddenDestinationException forbidden() {
        return new ForbiddenDestinationException(
                "プロジェクト設定の「ComfyUI の接続先」がサーバ内部のネットワーク(または禁止された宛先)のため接続しませんでした");
    }

    @Test
    void forbiddenDestinationFailsEveryOperationWithoutConnecting() throws Exception {
        startServer();
        ComfyUiClient client = new ComfyUiClient(RestClient.builder(), providerOf(() -> {
            throw forbidden();
        }), "ckpt", 1);
        ComfyUiGenerationParams params = new ComfyUiGenerationParams(
                "a cat", "blurry", 20, 7.0, "euler", "normal", 1L, 512, 512, 1,
                "checkpoint.safetensors", null, null, 7L);
        assertThatThrownBy(() -> client.generateImage(params)).isInstanceOf(AiServiceException.class)
                .hasMessageContaining("ComfyUI").hasMessageContaining("サーバ内部のネットワーク");
        assertThatThrownBy(() -> client.listCheckpoints(7L)).isInstanceOf(AiServiceException.class)
                .hasMessageContaining("ComfyUI");
        assertThatThrownBy(() -> client.listSamplers(7L)).isInstanceOf(AiServiceException.class);
        assertThatThrownBy(() -> client.listSchedulers(7L)).isInstanceOf(AiServiceException.class);
        assertThatThrownBy(() -> client.listLoras(7L)).isInstanceOf(AiServiceException.class);
        assertThat(hits.get()).isZero();
    }

    @Test
    void allowedTargetIsConnectedTo() throws Exception {
        String url = startServer();
        ComfyUiClient client = new ComfyUiClient(
                RestClient.builder(), providerOf(() -> new GuardedTarget(url, null)), "ckpt", 1);
        assertThat(client.listCheckpoints(7L)).containsExactly("a.safetensors");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void targetWithSniHostUsesADedicatedClientButStillConnectsToThePinnedAddress() throws Exception {
        String url = startServer();
        ComfyUiClient client = new ComfyUiClient(
                RestClient.builder(), providerOf(() -> new GuardedTarget(url, "comfy.example")), "ckpt", 1);
        assertThat(client.listCheckpoints(7L)).isEqualTo(List.of("a.safetensors"));
        assertThat(client.listSamplers(7L)).containsExactly("euler");
        assertThat(hits.get()).isEqualTo(2);
    }
}
