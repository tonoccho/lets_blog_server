package com.letsblog.media.ai;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ComfyUIの接続先がプロジェクトにもシステム設定にも無いとき(issue #1567)、接続を試みず、
 * 設定を促す内容の分かるエラーで失敗すること。接続先は環境変数にフォールバックしない。
 */
class ComfyUiClientNotConfiguredTest {

    private static final ComfyUiGenerationParams PARAMS = new ComfyUiGenerationParams(
            "a cat", "blurry", 20, 7.0, "euler", "normal", 1L, 512, 512, 1,
            "checkpoint.safetensors", null, null, 7L);

    private static ComfyUiClient clientWithBaseUrl(String baseUrl) {
        ImageGenerationConfigProvider provider = new ImageGenerationConfigProvider() {
            @Override
            public String comfyUiBaseUrl(Long projectId) {
                return baseUrl;
            }

            @Override
            public String chatGptApiKey(Long projectId) {
                return "unused";
            }

            @Override
            public String chatGptBaseUrl() {
                return "unused";
            }
        };
        return new ComfyUiClient(RestClient.builder(), provider, "default.safetensors", 1);
    }

    private static void assertNotConfigured(Throwable e) {
        assertThat(e).isInstanceOf(AiServiceException.class);
        assertThat(e.getMessage()).contains("ComfyUI").contains("接続先が設定されていません").contains("設定してください");
    }

    @Test
    void 画像生成は接続先が空なら未設定と分かるエラーになる() {
        assertThatThrownBy(() -> clientWithBaseUrl("").generateImage(PARAMS))
                .satisfies(ComfyUiClientNotConfiguredTest::assertNotConfigured);
    }

    @Test
    void 画像生成は接続先がnullでも未設定と分かるエラーになる() {
        assertThatThrownBy(() -> clientWithBaseUrl(null).generateImage(PARAMS))
                .satisfies(ComfyUiClientNotConfiguredTest::assertNotConfigured);
    }

    @Test
    void 一覧取得も接続先が空白だけなら未設定と分かるエラーになる() {
        ComfyUiClient client = clientWithBaseUrl("  ");

        assertThatThrownBy(() -> client.listCheckpoints(7L))
                .satisfies(ComfyUiClientNotConfiguredTest::assertNotConfigured);
        assertThatThrownBy(() -> client.listSamplers(7L))
                .satisfies(ComfyUiClientNotConfiguredTest::assertNotConfigured);
        assertThatThrownBy(() -> client.listSchedulers(7L))
                .satisfies(ComfyUiClientNotConfiguredTest::assertNotConfigured);
        assertThatThrownBy(() -> client.listLoras(7L))
                .satisfies(ComfyUiClientNotConfiguredTest::assertNotConfigured);
    }
}
