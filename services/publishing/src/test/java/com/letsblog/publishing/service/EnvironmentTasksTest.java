package com.letsblog.publishing.service;

import com.letsblog.publishing.config.EnvironmentFetchExecutorConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvironmentTasksTest {

    private final ExecutorService executor = EnvironmentFetchExecutorConfig.newExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void runAll_結果は投入順で返る() {
        Supplier<String> slow = () -> {
            sleep(100);
            return "a";
        };

        List<String> results = EnvironmentTasks.runAll(executor, List.of(slow, () -> "b", () -> "c"));

        assertEquals(List.of("a", "b", "c"), results);
    }

    @Test
    void runAll_複数が失敗したら投入順で最初の例外をそのまま投げ_他のタスクも完了まで待つ() {
        IllegalStateException first = new IllegalStateException("first");
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        Supplier<String> failFirst = () -> {
            throw first;
        };
        Supplier<String> failSecond = () -> {
            sleep(50);
            throw new IllegalArgumentException("second");
        };
        Supplier<String> slowOk = () -> {
            sleep(150);
            finished.set(true);
            return "ok";
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> EnvironmentTasks.runAll(executor, List.of(failFirst, failSecond, slowOk)));

        assertSame(first, thrown);
        assertEquals(true, finished.get());
    }

    @Test
    void runAll_実行時例外でない失敗はCompletionExceptionのまま投げる() {
        Supplier<String> failWithError = () -> {
            throw new AssertionError("boom");
        };

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> EnvironmentTasks.runAll(executor, List.of(failWithError)));

        assertEquals("boom", thrown.getCause().getMessage());
    }

    @Test
    void runAll_タスクが無ければ空リスト() {
        assertEquals(List.of(), EnvironmentTasks.runAll(executor, List.<Supplier<String>>of()));
    }

    /** 実運用のクライアントと同じ、リクエストスコープのプロキシ越しにヘッダを読む対象。 */
    public static class AuthHeaderReader {
        private final jakarta.servlet.http.HttpServletRequest request;

        public AuthHeaderReader(jakarta.servlet.http.HttpServletRequest request) {
            this.request = request;
        }

        public String authorization() {
            return request.getHeader("Authorization");
        }
    }

    @Configuration
    static class RequestScopedConfig {
        @Bean
        @Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
        AuthHeaderReader authHeaderReader(jakarta.servlet.http.HttpServletRequest request) {
            return new AuthHeaderReader(request);
        }
    }

    private AnnotationConfigWebApplicationContext webContext() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(RequestScopedConfig.class);
        context.refresh();
        return context;
    }

    private void bindRequest(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", authorization);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void runAll_ワーカースレッドでもリクエストスコープのBeanがリクエストスレッドと同じリクエストを読める() {
        AnnotationConfigWebApplicationContext context = webContext();
        try {
            AuthHeaderReader reader = context.getBean(AuthHeaderReader.class);
            bindRequest("Bearer t1");

            List<String> results = EnvironmentTasks.runAll(executor,
                    List.of(reader::authorization, reader::authorization, reader::authorization));

            assertEquals(List.of("Bearer t1", "Bearer t1", "Bearer t1"), results);
        } finally {
            RequestContextHolder.resetRequestAttributes();
            context.close();
        }
    }

    @Test
    void submit_SecurityContextとMDCもワーカーへ引き継ぐ() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p"));
        MDC.put("correlationId", "c-1");
        try {
            List<String> results = EnvironmentTasks.runAll(executor, List.of(
                    () -> SecurityContextHolder.getContext().getAuthentication().getName() + "/" + MDC.get("correlationId")));

            assertEquals(List.of("u/c-1"), results);
        } finally {
            SecurityContextHolder.clearContext();
            MDC.clear();
        }
    }

    @Test
    void submit_タスクが成功しても失敗してもワーカースレッドの文脈を元に戻し_次のリクエストへ漏らさない() throws Exception {
        ExecutorService single = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            bindRequest("Bearer t1");
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p"));
            MDC.put("correlationId", "c-1");
            CompletableFuture<String> failing = EnvironmentTasks.submit(single, () -> {
                throw new IllegalStateException("boom");
            });
            assertThrows(CompletionException.class, failing::join);
            RequestContextHolder.resetRequestAttributes();
            SecurityContextHolder.clearContext();
            MDC.clear();

            // 文脈を持たない呼び出し元から同じスレッドへ投入しても、前のリクエストの文脈は残っていない。
            String leaked = EnvironmentTasks.submit(single, () ->
                    RequestContextHolder.getRequestAttributes() + "/"
                            + SecurityContextHolder.getContext().getAuthentication() + "/" + MDC.get("correlationId")).join();

            assertEquals("null/null/null", leaked);
        } finally {
            single.shutdownNow();
        }
    }

    @Test
    void submit_呼び出し側に文脈が無ければワーカーにも文脈を作らない() {
        assertNull(RequestContextHolder.getRequestAttributes());

        Object attributes = EnvironmentTasks.submit(executor, RequestContextHolder::getRequestAttributes).join();

        assertNull(attributes);
    }

    @Test
    void awaitQuietly_失敗やキャンセルがあっても全futureの完了を待ち_例外は投げない() {
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        CompletableFuture<String> failing = EnvironmentTasks.submit(executor, () -> {
            throw new IllegalStateException("boom");
        });
        CompletableFuture<String> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        CompletableFuture<String> slow = EnvironmentTasks.submit(executor, () -> {
            sleep(150);
            finished.set(true);
            return "ok";
        });

        EnvironmentTasks.awaitQuietly(List.of(failing, cancelled, slow));

        assertEquals(true, finished.get());
    }

    @Test
    void submit_ワーカースレッドに元からあったMDCは完了後に復元される() throws Exception {
        ExecutorService single = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            single.submit(() -> MDC.put("worker", "w-1")).get();
            assertNull(MDC.getCopyOfContextMap());

            EnvironmentTasks.submit(single, () -> MDC.get("worker")).join();

            assertEquals("w-1", single.submit(() -> MDC.get("worker")).get());
        } finally {
            single.shutdownNow();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
