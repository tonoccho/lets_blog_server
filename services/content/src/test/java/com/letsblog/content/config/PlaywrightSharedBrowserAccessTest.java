package com.letsblog.content.config;

import com.letsblog.content.contentcache.OutboundUrlGuard;
import com.letsblog.content.contentcache.PlaywrightPageFetcher;
import com.letsblog.content.service.PreviewSkeletonFetcher;
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
 * content-serviceの{@code Browser}消費者は{@link PlaywrightPageFetcher}と
 * {@link PreviewSkeletonFetcher}の<b>2クラス</b>あり、production環境ではどちらも同じ
 * {@code Browser}Bean(= 同じ{@code Connection})を注入される(issue #1047)。
 *
 * <p>media-serviceと違い、content-serviceは<b>クラス単体の直列化だけでは塞ぎきれない</b>。
 * {@code [blogcard]}のスクレイピング({@code PlaywrightPageFetcher}側)と記事プレビューの
 * 骨格取得({@code PreviewSkeletonFetcher}側)が同時に来れば、別クラスの2スレッドが同じ
 * Connectionを同時に触ってしまう。このテストは<b>2つのクラスをまたいで</b>同時に叩き、
 * 直列化されることを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("content-service: PlaywrightPageFetcherとPreviewSkeletonFetcherはBrowserへのアクセスを"
        + "クラスをまたいで直列化する(issue #1047)")
class PlaywrightSharedBrowserAccessTest {

    @Mock
    private Browser browser;
    @Mock
    private OutboundUrlGuard outboundUrlGuard;

    @Test
    @DisplayName("[blogcard]取得と記事プレビュー骨格取得を混ぜて同時に叩いても直列に実行される")
    void クラスをまたいで直列に実行される() throws Exception {
        lenient().when(outboundUrlGuard.isAllowed(anyString())).thenReturn(true);

        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);

        lenient().when(browser.newPage()).thenAnswer(invocation -> {
            enter(active, maxObserved);
            Page page = mock(Page.class);
            lenient().when(page.content()).thenReturn("<html><body>ok</body></html>");
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

        // production同様、両方のクラスが「同じ」Browserインスタンス・「同じ」ロックインスタンスを
        // 注入される想定(PlaywrightConfig#browserAccessLock()は単一Beanとして両方へ注入される)。
        ReentrantLock sharedLock = new ReentrantLock();
        PlaywrightPageFetcher pageFetcher = new PlaywrightPageFetcher(browser, outboundUrlGuard, sharedLock);
        PreviewSkeletonFetcher skeletonFetcher = new PreviewSkeletonFetcher(browser, sharedLock);

        int perKind = 3;
        int totalThreads = perKind * 3;
        ExecutorService pool = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch ready = new CountDownLatch(totalThreads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < perKind; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                awaitQuietly(go);
                pageFetcher.fetchHtml("https://example.com/?probe" + index);
            }));
            futures.add(pool.submit(() -> {
                ready.countDown();
                awaitQuietly(go);
                skeletonFetcher.fetchAndSplice("https://example.com/post/" + index, "題", "<p>本文</p>",
                        "新しい題", "<p>新しい本文</p>", null);
            }));
            futures.add(pool.submit(() -> {
                ready.countDown();
                awaitQuietly(go);
                skeletonFetcher.fetchRealPost("https://example.com/preview/" + index, "auth", "value" + index);
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(maxObserved.get())
                .as("PlaywrightPageFetcherとPreviewSkeletonFetcherを合わせて、同時にBrowserの"
                        + "Connectionを使っていたスレッド数の最大値。1を超えると、クラスをまたいだ"
                        + "競合が残っている(issue #1047)")
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
