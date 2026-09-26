package com.letsblog.media.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.web.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * {@code /api/render/**} のJSON本文にサイズ上限を掛ける(issue #1135)。
 *
 * <p>{@code @RequestBody} のJSON読み取りには、multipartの{@code max-request-size}のような上限が
 * 掛からず、巨大な{@code source}/{@code data}を送るとPlantUMLへの転送やレンダリングに到達する前に
 * デシリアライズだけでメモリを使い切りうる。Content-Lengthが宣言されていれば読み取り前に413で
 * 断り、チャンク転送(宣言なし)は読み取り量を数えて超えた時点で{@link RequestBodyTooLargeException}を
 * 投げる。上限値と根拠は {@code docs/API_RATE_LIMITING.md} を参照。
 */
@Component
public class RenderBodySizeLimitFilter extends OncePerRequestFilter {

    private static final String RENDER_PATH_PREFIX = "/api/render/";

    private final long maxBytes;
    private final ObjectMapper objectMapper;

    public RenderBodySizeLimitFilter(
            @Value("${app.render.max-body-bytes:10485760}") long maxBytes, ObjectMapper objectMapper) {
        this.maxBytes = maxBytes;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(RENDER_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(),
                    ErrorResponse.of(new RequestBodyTooLargeException(maxBytes).getMessage()));
            return;
        }
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    private static final class LimitedRequest extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream limited;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (limited == null) {
                limited = new LimitedInputStream(super.getInputStream(), maxBytes);
            }
            return limited;
        }
    }

    private static final class LimitedInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long count;

        LimitedInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        private void add(long n) throws IOException {
            if (n > 0) {
                count += n;
                if (count > maxBytes) {
                    throw new RequestBodyTooLargeException(maxBytes);
                }
            }
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = delegate.read(buf, off, len);
            add(n);
            return n;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
