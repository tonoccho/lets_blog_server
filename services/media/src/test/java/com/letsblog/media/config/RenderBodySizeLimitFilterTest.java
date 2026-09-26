package com.letsblog.media.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code /api/render/**} のJSON本文サイズ上限(issue #1135)。JacksonがJSONをメモリへ読み込む
 * 前に、宣言サイズ(Content-Length)またはストリームの実読み取り量で拒否する。
 */
class RenderBodySizeLimitFilterTest {

    private static final long LIMIT = 100;

    private final RenderBodySizeLimitFilter filter = new RenderBodySizeLimitFilter(LIMIT, new ObjectMapper());

    private static MockHttpServletRequest post(String uri, int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContent("a".repeat(bytes).getBytes(StandardCharsets.UTF_8));
        return request;
    }

    /** チャンク転送(Content-Lengthなし)を模す。 */
    private static HttpServletRequest chunked(MockHttpServletRequest request) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
    }

    @Test
    void 宣言サイズが上限を超えるとチェーンへ進まず413と理由を返す() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> reached.set(true);

        filter.doFilter(post("/api/render/plantuml", 101), response, chain);

        assertThat(reached).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("100").contains("error");
    }

    @Test
    void 上限ちょうどの本文は通す() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean(false);

        filter.doFilter(post("/api/render/recharts", 100), response, (req, res) -> reached.set(true));

        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void render以外のパスは上限を掛けない() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean(false);

        filter.doFilter(post("/api/media/other", 5000), response, (req, res) -> reached.set(true));

        assertThat(reached).isTrue();
    }

    @Test
    void チャンク転送でも読み取り量が上限を超えた時点で例外になる() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> req.getInputStream().readAllBytes();

        assertThatThrownBy(() -> filter.doFilter(chunked(post("/api/render/penpot/design-file", 500)), response, chain))
                .isInstanceOf(RequestBodyTooLargeException.class)
                .hasMessageContaining("100");
    }

    @Test
    void チャンク転送でも上限以内なら全て読める() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        byte[][] read = new byte[1][];
        FilterChain chain = (req, res) -> read[0] = req.getInputStream().readAllBytes();

        filter.doFilter(chunked(post("/api/render/plantuml", 100)), response, chain);

        assertThat(read[0]).hasSize(100);
    }

    @Test
    void 一バイトずつ読んでも上限を超えたら例外になる() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            while (req.getInputStream().read() != -1) {
                // 読み進めるだけ
            }
        };

        assertThatThrownBy(() -> filter.doFilter(chunked(post("/api/render/plantuml", 101)), response, chain))
                .isInstanceOf(RequestBodyTooLargeException.class);
    }

    @Test
    void 読み取り済みの上限超過後はIOExceptionとして扱える() {
        assertThat(new RequestBodyTooLargeException(100)).isInstanceOf(IOException.class);
    }
}
