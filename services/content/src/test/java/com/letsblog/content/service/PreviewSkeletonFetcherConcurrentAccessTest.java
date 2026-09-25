package com.letsblog.content.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * {@link PreviewSkeletonFetcher} が共有 {@code Browser} の {@code Connection} を複数スレッドから
 * 同時に触っていないことの回帰テスト(issue #1047)。
 *
 * <p>本クラスは{@code fetchAndSplice}(= {@code browser.newPage()})と
 * {@code fetchRealPost}(= {@code browser.newContext()} → {@code context.newPage()})の
 * <b>2つの入口</b>を持つ。どちらも同じ{@code Browser}の{@code Connection}を触るため、
 * 2つの入口を<b>混ぜて同時に</b>叩いても直列化されることを検証する
 * (どちらか一方だけを直列化しても、実際の欠陥は塞ぎきれない)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("content-service: 並行fetchAndSplice/fetchRealPost呼び出しはBrowserへのアクセスを直列化する(issue #1047)")
class PreviewSkeletonFetcherConcurrentAccessTest {

    @Mock
    private Browser browser;

    @Test
    @DisplayName("newPage()/newContext()からclose()までの区間は常に1スレッドしか実行していない")
    void 両入口を混ぜて叩いても直列に実行される() throws Exception {
        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);
        int concurrencyPerEntryPoint = 4;

        lenient().when(browser.newPage()).thenAnswer(invocation -> {
            enter(active, maxObserved);
            Page page = mock(Page.class);
            lenient().when(page.evaluate(anyString(), any())).thenReturn(spliceResult());
            doAnswer(inv -> {
                active.decrementAndGet();
                return null;
            }).when(page).close();
            return page;
        });

        lenient().when(browser.newContext()).thenAnswer(invocation -> {
            enter(active, maxObserved);
            BrowserContext context = mock(BrowserContext.class);
            Page page = mock(Page.class);
            lenient().when(context.newPage()).thenReturn(page);
            lenient().when(page.evaluate(anyString())).thenReturn(realPostResult());
            doAnswer(inv -> {
                active.decrementAndGet();
                return null;
            }).when(context).close();
            return context;
        });

        PreviewSkeletonFetcher fetcher = new PreviewSkeletonFetcher(browser, new ReentrantLock());
        int totalThreads = concurrencyPerEntryPoint * 2;
        ExecutorService pool = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch ready = new CountDownLatch(totalThreads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < concurrencyPerEntryPoint; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                awaitQuietly(go);
                fetcher.fetchAndSplice("https://example.com/post/" + index, "題", "<p>本文</p>",
                        "新しい題", "<p>新しい本文</p>", null);
            }));
            futures.add(pool.submit(() -> {
                ready.countDown();
                awaitQuietly(go);
                fetcher.fetchRealPost("https://example.com/preview/" + index, "auth", "value" + index);
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(maxObserved.get())
                .as("newPage()/newContext()からclose()までの区間で同時に実行されていた呼び出し数の"
                        + "最大値。1を超えると、fetchAndSpliceとfetchRealPostが共有Browserの単一"
                        + "Connectionを同時に使っている(issue #1047の実際の欠陥と同じ形の競合)")
                .isEqualTo(1);
    }

    private static void enter(AtomicInteger active, AtomicInteger maxObserved) throws InterruptedException {
        int current = active.incrementAndGet();
        maxObserved.accumulateAndGet(current, Math::max);
        Thread.sleep(30);
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

    private static Map<String, Object> realPostResult() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("available", true);
        map.put("reason", null);
        map.put("html", "<body></body>");
        map.put("eyecatchSpliced", false);
        map.put("css", "");
        return map;
    }
}
