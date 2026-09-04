package com.letsblog.content.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.content.dto.FetchAndSpliceRequest;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@code POST /api/internal/content/preview-skeleton/fetch-and-splice} を実際に
 * {@link InternalPreviewSkeletonController} 経由(Spring Security の認証ゲート・
 * {@code GlobalExceptionHandler}を含む)で並行に叩いても全リクエストが200を返すことの
 * 回帰テスト(issue #1047)。
 *
 * <p>設計は{@code com.letsblog.media.controller.RenderControllerConcurrentAccessTest}と同じ
 * (MockMvcを選んだ理由・限界・決定論的なレース検出の説明はそちらのJavadocを参照)。
 * {@link com.letsblog.content.service.PreviewSkeletonFetcher}の2つの入口
 * ({@code fetchAndSplice}/{@code fetchRealPost})のうち、本Issueの受入基準が名指しする
 * {@code fetch-and-splice}エンドポイント(= {@code browser.newPage()})だけを対象にする。
 * 両入口を混ぜた排他検証は既存の{@code PreviewSkeletonFetcherConcurrentAccessTest}
 * (サービス層の単体テスト)が担う。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("content-service: POST fetch-and-splice の並行HTTPリクエストは全て200を返す(issue #1047)")
class InternalPreviewSkeletonControllerConcurrentAccessTest {

    private static final String PATH = "/api/internal/content/preview-skeleton/fetch-and-splice";
    private static final int CONCURRENCY = 8;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private Browser browser;

    @Test
    @DisplayName("4並行以上のHTTPリクエストが全て200を返す")
    void 並行httpリクエストは全て200を返す() throws Exception {
        AtomicInteger active = new AtomicInteger(0);

        lenient().when(browser.newPage()).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            try {
                // 実際のConnectionが往復に使う時間帯を模して、レース窓を毎回確実に開かせる。
                Thread.sleep(30);
            } finally {
                active.decrementAndGet();
            }
            if (current > 1) {
                // 実運用で観測された例外(issue本文: 記事プレビュー経路は4並行で一部が502)と
                // 同じ形。PreviewSkeletonFetcher#fetchAndSpliceのcatch(PlaywrightException)を経て
                // ContentScrapingExceptionになり、GlobalExceptionHandlerが502へ写像する。
                throw new PlaywrightException(
                        "Cannot find object to call pausedStateChanged: debugger@simulated (issue #1047)");
            }
            Page page = mock(Page.class);
            lenient().when(page.evaluate(anyString(), any())).thenReturn(spliceResult());
            return page;
        });

        FetchAndSpliceRequest request = new FetchAndSpliceRequest(
                "https://example.com/post/1047", "題", "<p>本文</p>", "新しい題", "<p>新しい本文</p>", null);
        String requestBody = objectMapper.writeValueAsString(request);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            tasks.add(() -> {
                ready.countDown();
                awaitQuietly(go);
                MvcResult result = mockMvc.perform(post(PATH)
                                .with(JwtTestFixtures.jwtRequestPostProcessor("sub-1047", "user"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody))
                        .andReturn();
                return result.getResponse().getStatus();
            });
        }

        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        ready.await();
        go.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(statuses)
                .as("POST %s を%d並行で叩いたときの各リクエストのHTTPステータス。1件でも200以外が"
                        + "あれば、Browserへのアクセスがコントローラ経由でも直列化されていない"
                        + "(issue #1047)", PATH, CONCURRENCY)
                .allMatch(status -> status == 200);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<String, Object> spliceResult() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("available", false);
        map.put("reason", "本文の位置を特定できませんでした");
        map.put("html", null);
        map.put("eyecatchSpliced", false);
        map.put("css", "");
        return map;
    }
}
