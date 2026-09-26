package com.letsblog.media.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ComfyUIへ到達できないとき(issue #1126)、本文の空な409ではなく、到達できない旨と接続先を含む
 * {@link AiServiceException}(=502)になることを固定する。実際のRestClientで存在しない宛先へ
 * 接続するので、名前解決不可・接続拒否の両方の実例外({@code ResourceAccessException})を通る。
 */
@DisplayName("media-service: ComfyUI不到達時の応答(issue #1126)")
class ComfyUiClientUnreachableTest {

    private static final String REFUSED_URL = "http://127.0.0.1:1";
    private static final String UNRESOLVABLE_URL = "http://comfyui.invalid:8188";

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger clientLogger = (Logger) LoggerFactory.getLogger(ComfyUiClient.class);

    @BeforeEach
    void attachAppender() {
        appender.start();
        clientLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        clientLogger.detachAppender(appender);
    }

    private static ComfyUiClient clientFor(String baseUrl) {
        return new ComfyUiClient(RestClient.builder(), new ImageGenerationConfigProvider() {
            @Override
            public String comfyUiBaseUrl() {
                return baseUrl;
            }

            @Override
            public String chatGptApiKey() {
                return "unused";
            }

            @Override
            public String chatGptBaseUrl() {
                return baseUrl;
            }
        }, "default.safetensors");
    }

    private static ComfyUiGenerationParams params() {
        return new ComfyUiGenerationParams(
                "a cat", "blurry", 20, 7.0, "euler", "normal", 1L, 512, 512, 1,
                "checkpoint.safetensors", null, null);
    }

    private static void assertUnreachableMessage(AiServiceException e, String baseUrl) {
        assertTrue(e.getMessage().contains("到達できません"), e.getMessage());
        assertTrue(e.getMessage().contains(baseUrl), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {REFUSED_URL, UNRESOLVABLE_URL})
    void チェックポイント一覧は到達不可を理由と接続先つきで返す(String baseUrl) {
        AiServiceException e = assertThrows(AiServiceException.class, () -> clientFor(baseUrl).listCheckpoints());
        assertUnreachableMessage(e, baseUrl);
    }

    @ParameterizedTest
    @ValueSource(strings = {REFUSED_URL, UNRESOLVABLE_URL})
    void サンプラー一覧は到達不可を理由と接続先つきで返す(String baseUrl) {
        AiServiceException e = assertThrows(AiServiceException.class, () -> clientFor(baseUrl).listSamplers());
        assertUnreachableMessage(e, baseUrl);
    }

    @ParameterizedTest
    @ValueSource(strings = {REFUSED_URL, UNRESOLVABLE_URL})
    void スケジューラー一覧は到達不可を理由と接続先つきで返す(String baseUrl) {
        AiServiceException e = assertThrows(AiServiceException.class, () -> clientFor(baseUrl).listSchedulers());
        assertUnreachableMessage(e, baseUrl);
    }

    @Test
    void LoRA一覧も到達不可を空リストへ丸めず理由つきで返す() {
        AiServiceException e = assertThrows(AiServiceException.class, () -> clientFor(REFUSED_URL).listLoras());
        assertUnreachableMessage(e, REFUSED_URL);
    }

    @ParameterizedTest
    @ValueSource(strings = {REFUSED_URL, UNRESOLVABLE_URL})
    void 画像生成は到達不可を理由と接続先つきで返す(String baseUrl) {
        AiServiceException e = assertThrows(AiServiceException.class, () -> clientFor(baseUrl).generateImage(params()));
        assertUnreachableMessage(e, baseUrl);
    }

    @Test
    void 到達不可はサーバーログにWARNが1件残る() {
        assertThrows(AiServiceException.class, () -> clientFor(REFUSED_URL).listCheckpoints());

        List<ILoggingEvent> warns = appender.list.stream().filter(ev -> ev.getLevel() == Level.WARN).toList();
        assertEquals(1, warns.size());
        assertTrue(warns.get(0).getFormattedMessage().contains(REFUSED_URL), warns.get(0).getFormattedMessage());
    }

    @Test
    void 不到達は型で判別できるComfyUiUnreachableExceptionになる() {
        assertThrows(ComfyUiUnreachableException.class, () -> clientFor(REFUSED_URL).listCheckpoints());
        assertThrows(ComfyUiUnreachableException.class, () -> clientFor(REFUSED_URL).generateImage(params()));
    }
}
