package com.letsblog.content.contentcache;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * {@link PlaywrightPageFetcher} が共有 {@code Browser} の {@code Connection} を複数スレッドから
 * 同時に触っていないことの回帰テスト(issue #1047)。
 *
 * <p>{@code RechartsRendererConcurrentAccessTest}(media-service)と同じ手法。実ブラウザは
 * 確率的にしか失敗しない(本Issueの本文実測でも4並行で4/4失敗した回・していない回がある)ため、
 * {@code newPage()}から{@code close()}までの区間を意図的に引き延ばして同時侵入を検知する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("content-service: 並行fetchHtml()呼び出しはBrowserへのアクセスを直列化する(issue #1047)")
class PlaywrightPageFetcherConcurrentAccessTest {

    @Mock
    private Browser browser;
    @Mock
    private OutboundUrlGuard outboundUrlGuard;

    @Test
    @DisplayName("newPage()からclose()までの区間は常に1スレッドしか実行していない")
    void fetchHtml呼び出しはnewPageからcloseまで直列に実行される() throws Exception {
        lenient().when(outboundUrlGuard.isAllowed(anyString())).thenReturn(true);

        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);
        int concurrency = 8;

        lenient().when(browser.newPage()).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxObserved.accumulateAndGet(current, Math::max);
            Thread.sleep(30);
            Page page = mock(Page.class);
            lenient().when(page.content()).thenReturn("<html><body>ok</body></html>");
            doAnswer(inv -> {
                active.decrementAndGet();
                return null;
            }).when(page).close();
            return page;
        });

        PlaywrightPageFetcher fetcher = new PlaywrightPageFetcher(browser, outboundUrlGuard, new ReentrantLock());
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                awaitQuietly(go);
                fetcher.fetchHtml("https://example.com/?probe" + index);
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(maxObserved.get())
                .as("newPage()からclose()までの区間で同時に実行されていたfetchHtml()呼び出し数の最大値。"
                        + "1を超えると、複数スレッドが共有Browserの単一Connectionを同時に使っている"
                        + "(issue #1047の実際の欠陥と同じ形の競合)")
                .isEqualTo(1);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
