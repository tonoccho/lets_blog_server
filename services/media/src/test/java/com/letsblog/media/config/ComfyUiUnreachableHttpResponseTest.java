package com.letsblog.media.config;

import com.letsblog.media.ai.ComfyUiClient;
import com.letsblog.media.ai.ImageGenerationConfigProvider;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ComfyUIへ到達できないとき、HTTP応答が本文の空な409ではなく、理由と接続先を含む502になる
 * (issue #1126)。DBを要する統合テストを介さず、実際の{@link ComfyUiClient}(存在しない宛先)と
 * {@link GlobalExceptionHandler}をMockMvcで結ぶ。実サービスの3経路(image-options / 画像生成 /
 * チェックポイント一覧)はいずれもComfyUiClientの呼び出しでこの例外に至る。
 */
@DisplayName("media-service: ComfyUI不到達のHTTP応答(issue #1126)")
class ComfyUiUnreachableHttpResponseTest {

    private static final String BASE_URL = "http://127.0.0.1:1";

    @RestController
    static class Probe {
        private final ComfyUiClient client = new ComfyUiClient(new ImageGenerationConfigProvider() {
            @Override
            public String comfyUiBaseUrl() {
                return BASE_URL;
            }

            @Override
            public String chatGptApiKey() {
                return "unused";
            }

            @Override
            public String chatGptBaseUrl() {
                return BASE_URL;
            }
        }, "default.safetensors");

        @GetMapping("/probe")
        List<String> probe() {
            return client.listCheckpoints();
        }
    }

    @Test
    void 不到達は502で理由と接続先を本文に含める() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe())
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/probe"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error", containsString("到達できません")))
                .andExpect(jsonPath("$.error", containsString(BASE_URL)));
    }
}
