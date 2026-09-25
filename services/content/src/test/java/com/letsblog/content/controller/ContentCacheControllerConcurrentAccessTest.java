package com.letsblog.content.controller;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.content.contentcache.OutboundUrlGuard;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * {@code GET /api/content-cache} を実際に {@link ContentCacheController} 経由(Spring Security の
 * 認証ゲート・{@code GlobalExceptionHandler}・実DBを含む)で並行(キャッシュミス)に叩いても
 * 全リクエストが200を返すことの回帰テスト(issue #1047)。
 *
 * <p>設計は{@code com.letsblog.media.controller.RenderControllerConcurrentAccessTest}と同じ
 * (MockMvcを選んだ理由・限界・決定論的なレース検出の説明はそちらのJavadocを参照。本サービスにも
 * {@code RANDOM_PORT}の前例は無い)。{@link Browser}に加えて{@link OutboundUrlGuard}も
 * {@code @MockitoBean}で置き換える。実DNS解決(issue #902対策)はこのテストの関心事ではなく、
 * 宛先検証を経路に残したままだとテスト環境のネットワーク到達性に結果が左右されてしまうため。
 *
 * <p>各リクエストは別々のURL(クエリ文字列だけが異なる、かつ実行のたびに変わる乱数を含む)を使い、
 * コンテンツキャッシュへのヒットではなく常に
 * {@link com.letsblog.content.contentcache.PlaywrightPageFetcher#fetchHtml}
 * (=共有{@link Browser}への実際のアクセス)を経由させる(issue本文の再現手順と同じ)。実行のたびに
 * 変わる乱数を挟むのは、実MySQL(application-test.yml参照)へ書き込む都合上、固定URLだと前回実行分の
 * キャッシュにヒットしてfetchHtmlを経由しないまま200が返ってしまうため(このテストを別worktreeで
 * RED確認する際に実際に踏んだ)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("content-service: GET /api/content-cache の並行HTTPリクエストは全て200を返す(issue #1047)")
class ContentCacheControllerConcurrentAccessTest {

    private static final int CONCURRENCY = 8;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private Browser browser;

    @MockitoBean
    private OutboundUrlGuard outboundUrlGuard;

    @Test
    @DisplayName("4並行以上・キャッシュミスのHTTPリクエストが全て200を返す")
    void 並行httpリクエストは全て200を返す() throws Exception {
        lenient().when(outboundUrlGuard.isAllowed(anyString())).thenReturn(true);
        doNothing().when(outboundUrlGuard).requireAllowed(anyString());

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
                // 実運用で観測された例外(issue本文: [blogcard]経路は4並行で4/4失敗)と同じ形。
                // PlaywrightPageFetcher#fetchHtmlのcatch(PlaywrightException)を経て
                // ContentScrapingExceptionになり、GlobalExceptionHandlerが502へ写像する。
                throw new PlaywrightException(
                        "Cannot find object to call pausedStateChanged: debugger@simulated (issue #1047)");
            }
            Page page = mock(Page.class);
            lenient().when(page.content()).thenReturn("<html><head></head><body>ok</body></html>");
            return page;
        });

        // 実行のたびに変わる接頭辞を挟む。DBは実MySQL(application-test.yml参照)で、
        // テスト間でデータが引き継がれる。固定URLだと前回実行分がキャッシュヒットしてしまい、
        // pageFetcher.fetchHtml()(= 共有Browserへの実際のアクセス)を経由しないまま
        // 200が返ってしまう(このテストを別worktreeでRED確認する際に実際に踏んだ不具合)。
        String runPrefix = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            int index = i;
            tasks.add(() -> {
                ready.countDown();
                awaitQuietly(go);
                MvcResult result = mockMvc.perform(get("/api/content-cache")
                                .param("url", "https://example.com/?issue1047probe" + runPrefix + "-" + index)
                                .with(JwtTestFixtures.jwtRequestPostProcessor("sub-1047", "user")))
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
                .as("GET /api/content-cache を%d並行・キャッシュミスで叩いたときの各リクエストのHTTP"
                        + "ステータス。1件でも200以外があれば、Browserへのアクセスがコントローラ経由でも"
                        + "直列化されていない(issue #1047)", CONCURRENCY)
                .allMatch(status -> status == 200);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
