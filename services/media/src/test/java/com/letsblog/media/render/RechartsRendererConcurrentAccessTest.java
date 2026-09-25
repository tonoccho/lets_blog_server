package com.letsblog.media.render;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * {@link RechartsRenderer} が共有 {@code Browser} の {@code Connection} を複数スレッドから
 * 同時に触っていないことの回帰テスト(issue #1047)。
 *
 * <p>Playwright JavaバインディングのConnection(Browser1個につき1本のメッセージパイプ)は
 * スレッドセーフではない({@code com.microsoft.playwright.impl.Connection}に
 * {@code synchronized}は無い。playwright-1.62.0.jarを逆アセンブルして確認済み)。実運用では
 * {@code newPage()}呼び出しが競合すると{@code PlaywrightException: Cannot find object to call
 * pausedStateChanged}でHTTP 502になる(issue本文のスタックトレース参照)。
 *
 * <p>この欠陥はタイミング依存で確率的にしか再現しないため({@code render()}の実行時間は
 * ミリ秒〜数百ms、Chromiumの応答タイミング次第)、実ブラウザを使ったテストは無罪放免(たまたま
 * 通っただけ)になりかねない。そこで{@link Page}をモックし、{@code newPage()}が返ってから
 * {@code close()}が呼ばれるまでの区間を意図的にスリープで引き延ばして「同時に何本の
 * render()呼び出しがこの区間に入っているか」を数える。この区間に複数スレッドが同時に
 * 入れてしまうこと自体が、実際の欠陥(Connectionへの排他なきアクセス)と同じ形の競合である。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("media-service: 並行render()呼び出しはBrowserへのアクセスを直列化する(issue #1047)")
class RechartsRendererConcurrentAccessTest {

    private static final String RESULT_EXPRESSION =
            "() => ({ result: window.__chartResult, error: window.__chartError })";

    private static final RechartsChartConfig CONFIG = new RechartsChartConfig(
            "bar", List.of(Map.of("month", "2024-01", "revenue", 100000.0)), "month", List.of("revenue"),
            List.of("#4e79a7"), false, 700, 300, "#333333", "#e0e0e0", "売上");

    @Mock
    private Browser browser;

    /**
     * {@code newPage()}から{@code close()}までの区間に同時に何本の呼び出しが入っているかを数える。
     * ここが常に1であれば、直列化できている(=Connectionを同時に触るスレッドは高々1本)。
     */
    @Test
    @DisplayName("newPage()からclose()までの区間は常に1スレッドしか実行していない")
    void render呼び出しはnewPageからcloseまで直列に実行される() throws Exception {
        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);
        int concurrency = 8;

        lenient().when(browser.newPage()).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxObserved.accumulateAndGet(current, Math::max);
            try {
                // 実際のConnectionが往復に使う時間帯を模して、競合の観測窓を広げる。
                Thread.sleep(30);
            } finally {
                // ここでは減らさない。close()まで「使用中」とみなすのが実際のクリティカル
                // セクション(newPage〜pageの評価〜close)と一致する。
            }
            Page page = mock(Page.class);
            lenient().when(page.evaluate(eq(RESULT_EXPRESSION))).thenReturn(result());
            doAnswer(inv -> {
                active.decrementAndGet();
                return null;
            }).when(page).close();
            return page;
        });

        RechartsRenderer renderer = new RechartsRenderer(browser);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                awaitQuietly(go);
                renderer.render(CONFIG);
            }));
        }
        ready.await();
        go.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(maxObserved.get())
                .as("newPage()からclose()までの区間で同時に実行されていたrender()呼び出し数の最大値。"
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

    private static Map<String, Object> result() {
        Map<String, Object> map = new HashMap<>();
        map.put("result", "<div class=\"recharts-wrapper\"><svg/></div>");
        map.put("error", null);
        return map;
    }
}
