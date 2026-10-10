package com.letsblog.media.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** ComfyUiClientの外部呼び出しが1呼び出し1行で記録される(issue #1734)。 */
class ComfyUiClientExternalCallLogTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ExternalCallLoggingInterceptor.class);

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    void ComfyUIへの各呼び出しがtarget_comfyuiの行を1行ずつ出す() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        String base = "http://comfyui.test";
        server.expect(requestTo(base + "/prompt"))
                .andRespond(withSuccess("{\"prompt_id\":\"p1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(base + "/history/p1"))
                .andRespond(withSuccess("{\"p1\":{\"outputs\":{\"9\":{\"images\":[{\"filename\":\"a.png\","
                        + "\"subfolder\":\"s\",\"type\":\"output\"}]}}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(base + "/view?filename=a.png&subfolder=s&type=output"))
                .andRespond(withSuccess(new byte[] {1, 2, 3}, MediaType.IMAGE_PNG));
        server.expect(requestTo(base + "/api/interrupt")).andRespond(withSuccess());
        ComfyUiClient client = new ComfyUiClient(builder, new ImageGenerationConfigProvider() {
            @Override
            public String comfyUiBaseUrl(Long projectId) {
                return base;
            }

            @Override
            public String chatGptApiKey(Long projectId) {
                return "unused";
            }

            @Override
            public String chatGptBaseUrl() {
                return base;
            }
        }, "default.safetensors");

        client.generateImage(new ComfyUiGenerationParams(
                "a cat", "blurry", 20, 7.0, "euler", "normal", 1L, 512, 512, 1,
                "checkpoint.safetensors", null, null));

        assertEquals(4, appender.list.size(), "4回の呼び出しで4行");
        assertTrue(appender.list.stream().allMatch(e -> e.getFormattedMessage()
                .startsWith("external call: target=comfyui host=comfyui.test method=")));
        assertTrue(appender.list.get(0).getFormattedMessage().contains("path=/prompt status=200"));
        assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains("filename=")),
                "クエリは出さない");
    }
}
