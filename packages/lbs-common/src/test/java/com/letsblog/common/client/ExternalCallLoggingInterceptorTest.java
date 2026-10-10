package com.letsblog.common.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 外部呼び出しログのインターセプタの単体テスト(issue #1734)。時計を差し替えて閾値の境界を検証する。 */
class ExternalCallLoggingInterceptorTest {

    private static final long MS = 1_000_000L;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ExternalCallLoggingInterceptor.class);
    private final AtomicLong nanoClock = new AtomicLong();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private ExternalCallLoggingInterceptor interceptor(long thresholdMs) {
        return new ExternalCallLoggingInterceptor("brave", thresholdMs, nanoClock::get);
    }

    private static MockClientHttpRequest request(String method, String uri) {
        return new MockClientHttpRequest(HttpMethod.valueOf(method), URI.create(uri));
    }

    private ClientHttpRequestExecution executionTaking(long millis, HttpStatus status) {
        return (req, body) -> {
            nanoClock.addAndGet(millis * MS);
            return new MockClientHttpResponse("secret-response-body".getBytes(StandardCharsets.UTF_8), status);
        };
    }

    private ILoggingEvent onlyEvent() {
        assertEquals(1, appender.list.size(), "1呼び出しにつきちょうど1行");
        return appender.list.get(0);
    }

    @Test
    void 成功はINFOで_target_host_method_path_status_duration_outcomeを1行に出す() throws Exception {
        interceptor(5000).intercept(request("GET", "https://api.search.brave.com/res/v1/web/search"), new byte[0],
                executionTaking(120, HttpStatus.OK));

        ILoggingEvent event = onlyEvent();
        assertEquals(Level.INFO, event.getLevel());
        assertEquals("external call: target=brave host=api.search.brave.com method=GET"
                + " path=/res/v1/web/search status=200 duration_ms=120 outcome=success", event.getFormattedMessage());
    }

    @Test
    void レスポンスはそのまま返し本文を読み切らない() throws Exception {
        MockClientHttpResponse response = new MockClientHttpResponse(new byte[] {1, 2, 3}, HttpStatus.OK);
        ClientHttpResponse returned = interceptor(5000).intercept(
                request("GET", "https://example.com/x"), new byte[0], (req, body) -> response);

        assertSame(response, returned);
        assertEquals(3, returned.getBody().readAllBytes().length);
    }

    @Test
    void 閾値ちょうどはINFOで超えるとWARNになりslow_trueが付く() throws Exception {
        interceptor(1000).intercept(request("POST", "https://example.com/a"), new byte[0],
                executionTaking(1000, HttpStatus.OK));
        assertEquals(Level.INFO, onlyEvent().getLevel());
        assertFalse(onlyEvent().getFormattedMessage().contains("slow=true"));

        appender.list.clear();
        interceptor(1000).intercept(request("POST", "https://example.com/a"), new byte[0],
                executionTaking(1001, HttpStatus.OK));
        ILoggingEvent event = onlyEvent();
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().endsWith("outcome=success slow=true"), event.getFormattedMessage());
    }

    @Test
    void 既定の閾値は5000ミリ秒() throws Exception {
        ExternalCallLoggingInterceptor interceptor = new ExternalCallLoggingInterceptor("brave");
        interceptor.intercept(request("GET", "https://example.com/a"), new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK));

        assertEquals(Level.INFO, onlyEvent().getLevel());
    }

    @Test
    void 公開コンストラクタの閾値が使われる() throws Exception {
        ExternalCallLoggingInterceptor interceptor = new ExternalCallLoggingInterceptor("llm", 120_000L);
        interceptor.intercept(request("GET", "https://example.com/a"), new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK));

        assertTrue(onlyEvent().getFormattedMessage().startsWith("external call: target=llm "));
    }

    @Test
    void 四百番台はWARNでoutcomeはhttp_error() throws Exception {
        interceptor(5000).intercept(request("GET", "https://example.com/a"), new byte[0],
                executionTaking(10, HttpStatus.NOT_FOUND));

        ILoggingEvent event = onlyEvent();
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("status=404 duration_ms=10 outcome=http_error"),
                event.getFormattedMessage());
    }

    @Test
    void 五百番台はWARNでoutcomeはhttp_error() throws Exception {
        interceptor(5000).intercept(request("GET", "https://example.com/a"), new byte[0],
                executionTaking(10, HttpStatus.BAD_GATEWAY));

        ILoggingEvent event = onlyEvent();
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("status=502 duration_ms=10 outcome=http_error"));
    }

    @Test
    void 例外はWARNでstatusはnone_outcomeは例外の単純クラス名で再スローする() {
        IOException failure = new IOException("connect refused");
        IOException thrown = assertThrows(IOException.class, () -> interceptor(5000).intercept(
                request("GET", "https://example.com/a"), new byte[0], (req, body) -> {
                    nanoClock.addAndGet(7 * MS);
                    throw failure;
                }));

        assertSame(failure, thrown);
        ILoggingEvent event = onlyEvent();
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(event.getFormattedMessage().contains("status=none duration_ms=7 outcome=IOException"),
                event.getFormattedMessage());
    }

    @Test
    void 実行時例外も1行だけ出して再スローする() {
        assertThrows(IllegalStateException.class, () -> interceptor(5000).intercept(
                request("GET", "https://example.com/a"), new byte[0], (req, body) -> {
                    throw new IllegalStateException("boom");
                }));

        assertTrue(onlyEvent().getFormattedMessage().contains("outcome=IllegalStateException"));
    }

    @Test
    void クエリ_Authorizationヘッダ_本文_userinfoはどのログ行にも出ない() throws Exception {
        String token = "sk-super-secret-token";
        MockClientHttpRequest request = request("POST",
                "https://user:" + token + "@api.example.com/v1/items?api_key=" + token + "&q=" + token);
        request.getHeaders().add("Authorization", "Bearer " + token);
        request.getHeaders().add("X-Api-Key", token);
        byte[] body = ("{\"password\":\"" + token + "\"}").getBytes(StandardCharsets.UTF_8);

        interceptor(5000).intercept(request, body, executionTaking(5, HttpStatus.OK));
        assertNoSecret(token);

        appender.list.clear();
        assertThrows(IOException.class, () -> interceptor(5000).intercept(request, body, (req, b) -> {
            throw new IOException("failed for " + token);
        }));
        assertNoSecret(token);
        assertTrue(onlyEvent().getFormattedMessage().contains("host=api.example.com"));
        assertTrue(onlyEvent().getFormattedMessage().contains("path=/v1/items "));
    }

    private void assertNoSecret(String token) {
        for (ILoggingEvent event : appender.list) {
            assertFalse(event.getFormattedMessage().contains(token), event.getFormattedMessage());
            assertFalse(event.getFormattedMessage().contains("Bearer"));
            assertFalse(event.getFormattedMessage().contains("api_key"));
            assertTrue(event.getThrowableProxy() == null, "例外メッセージ(秘密を含みうる)をスタックで出さない");
        }
    }

    @Test
    void パスが空のURIでもpathは空でなくスラッシュを出す() throws Exception {
        interceptor(5000).intercept(request("GET", "https://example.com"), new byte[0],
                executionTaking(1, HttpStatus.OK));

        assertTrue(onlyEvent().getFormattedMessage().contains("path=/ "), onlyEvent().getFormattedMessage());
    }
}
