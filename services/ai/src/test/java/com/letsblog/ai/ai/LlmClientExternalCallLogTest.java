package com.letsblog.ai.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LlmClientの外部呼び出しが1呼び出し1行で記録される(issue #1734)。キーは出ない。 */
class LlmClientExternalCallLogTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ExternalCallLoggingInterceptor.class);
    private HttpServer server;

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        if (server != null) {
            server.stop(0);
        }
    }

    private void start(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    private LlmClient client() {
        return new LlmClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "sk-secret-key", "m", 30L);
    }

    @Test
    void 成功したgenerateはtarget_llmの行を1行だけINFOで出す() throws IOException {
        start(200, "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");

        client().generate("secret prompt text");

        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.INFO, event.getLevel());
        String line = event.getFormattedMessage();
        assertTrue(line.startsWith("external call: target=llm host=127.0.0.1 method=POST path=/v1/chat/completions"
                + " status=200 duration_ms="), line);
        assertTrue(line.endsWith("outcome=success"), line);
        assertFalse(line.contains("sk-secret-key") || line.contains("secret prompt"), line);
    }

    @Test
    void 失敗した応答はWARNで出る() throws IOException {
        start(500, "{\"error\":\"x\"}");

        LlmClient client = client();
        assertThrows(RuntimeException.class, () -> client.generate("p"));

        assertEquals(1, appender.list.size());
        assertEquals(Level.WARN, appender.list.get(0).getLevel());
        assertTrue(appender.list.get(0).getFormattedMessage().contains("status=500"));
    }
}
