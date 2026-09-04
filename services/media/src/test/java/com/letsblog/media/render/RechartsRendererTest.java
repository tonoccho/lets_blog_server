package com.letsblog.media.render;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RechartsRenderer} の単体テスト(issue #1020)。
 *
 * <p>#1020 で {@code Browser} の注入点へ {@code @Lazy} を付けるにあたり、
 * <b>レンダリングの実行時経路が従来どおり動く</b>ことを確かめる必要がある。ヘッドレス
 * Chromium は外部境界なので {@link Browser}/{@link Page} をモックへ差し替える
 * (ADR-0006 のモック方針)。実ブラウザを通す経路の確認は受入基準2
 * (コンテナで {@code POST /api/render/recharts} が SVG を返すこと)が担う。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("media-service: RechartsRendererのレンダリング経路(issue #1020)")
class RechartsRendererTest {

    private static final String RESULT_EXPRESSION =
            "() => ({ result: window.__chartResult, error: window.__chartError })";

    private static final String RENDER_EXPRESSION = "(config) => window.renderChart(config)";

    private static final RechartsChartConfig CONFIG = new RechartsChartConfig(
            "bar", List.of(Map.of("month", "2024-01", "revenue", 100000.0)), "month", List.of("revenue"),
            List.of("#4e79a7"), false, 700, 300, "#333333", "#e0e0e0", "売上");

    @Mock
    private Browser browser;

    private RechartsRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new RechartsRenderer(browser);
    }

    @Test
    @DisplayName("harnessが返した.recharts-wrapperのouterHTMLをそのまま返す")
    void 正常系はレンダリング結果を返す() {
        Page page = pageReturning(result("<div class=\"recharts-wrapper\"><svg/></div>", null));
        when(browser.newPage()).thenReturn(page);

        String html = renderer.render(CONFIG);

        assertThat(html).isEqualTo("<div class=\"recharts-wrapper\"><svg/></div>");
        verify(page).close();
    }

    @Test
    @DisplayName("設定はharnessが期待するキーと順序でwindow.renderChartへ渡される")
    void 設定はharnessの形式で渡される() {
        Page page = pageReturning(result("<div class=\"recharts-wrapper\"></div>", null));
        when(browser.newPage()).thenReturn(page);

        renderer.render(CONFIG);

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(page).evaluate(eq(RENDER_EXPRESSION), args.capture());
        assertThat(args.getValue()).isInstanceOf(LinkedHashMap.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> passed = (Map<String, Object>) args.getValue();
        assertThat(passed.keySet()).containsExactly("type", "data", "xAxisKey", "seriesKeys", "colors",
                "stacked", "width", "height", "textColor", "gridColor", "yAxisLabel");
        assertThat(passed).containsEntry("type", "bar")
                .containsEntry("xAxisKey", "month")
                .containsEntry("seriesKeys", List.of("revenue"))
                .containsEntry("stacked", false)
                .containsEntry("width", 700)
                .containsEntry("height", 300)
                .containsEntry("yAxisLabel", "売上");
    }

    @Test
    @DisplayName("harnessがwindow.__chartErrorを立てたらRechartsRenderException")
    void harnessのエラーは例外になる() {
        Page page = pageReturning(result(null, "unsupported chart type"));
        when(browser.newPage()).thenReturn(page);

        assertThatThrownBy(() -> renderer.render(CONFIG))
                .isInstanceOf(RechartsRenderException.class)
                .hasMessageContaining("unsupported chart type");
    }

    @Test
    @DisplayName("結果が文字列でなければRechartsRenderException")
    void 結果が文字列でなければ例外になる() {
        Page page = pageReturning(result(null, null));
        when(browser.newPage()).thenReturn(page);

        assertThatThrownBy(() -> renderer.render(CONFIG))
                .isInstanceOf(RechartsRenderException.class)
                .hasMessageContaining("取得できませんでした");
    }

    @Test
    @DisplayName("結果が空白だけならRechartsRenderException")
    void 結果が空白なら例外になる() {
        Page page = pageReturning(result("   ", null));
        when(browser.newPage()).thenReturn(page);

        assertThatThrownBy(() -> renderer.render(CONFIG))
                .isInstanceOf(RechartsRenderException.class)
                .hasMessageContaining("取得できませんでした");
    }

    @Test
    @DisplayName("Playwright側の失敗はRechartsRenderExceptionへ包み直す")
    void Playwrightの失敗は包み直される() {
        when(browser.newPage())
                .thenThrow(new PlaywrightException("Target page, context or browser has been closed"));

        assertThatThrownBy(() -> renderer.render(CONFIG))
                .isInstanceOf(RechartsRenderException.class)
                .hasMessageContaining("レンダリングに失敗しました")
                .cause().isInstanceOf(PlaywrightException.class);
    }

    @Test
    @DisplayName("2回目以降はrenderer.bundle.jsを読み直さない(同一インスタンスを渡す)")
    void バンドルは一度だけ読まれる() {
        Page first = pageReturning(result("<div class=\"recharts-wrapper\">1</div>", null));
        Page second = pageReturning(result("<div class=\"recharts-wrapper\">2</div>", null));
        when(browser.newPage()).thenReturn(first, second);

        renderer.render(CONFIG);
        renderer.render(CONFIG);

        String firstBundle = capturedBundle(first);
        assertThat(firstBundle).contains("renderChart");
        assertThat(capturedBundle(second)).isSameAs(firstBundle);
    }

    /**
     * 二重チェックロックの内側 {@code if (bundleJs == null)} が false になる経路。
     *
     * <p>volatile の読みで null を見た後にロックを取るまでの間に別スレッドが読み込みを終える、
     * という競合そのものを再現する。テストスレッドが renderer のモニタを保持したまま
     * <b>再入して</b>読み込みを済ませることで、待たされていた側は必ずこの分岐を通る。
     */
    @Test
    @DisplayName("読み込み待ちの間に別スレッドが完了していれば二重には読み込まない")
    void 競合時も二重には読み込まない() throws InterruptedException {
        Page blocked = pageReturning(result("<div class=\"recharts-wrapper\">blocked</div>", null));
        Page winner = pageReturning(result("<div class=\"recharts-wrapper\">winner</div>", null));
        when(browser.newPage()).thenReturn(blocked, winner);

        Thread waiting = new Thread(() -> renderer.render(CONFIG), "recharts-waiting");
        synchronized (renderer) {
            waiting.start();
            awaitBlockedOn(waiting, renderer);
            renderer.render(CONFIG);
        }
        waiting.join(10_000);

        assertThat(waiting.isAlive()).as("待機スレッドが終了していない").isFalse();
        assertThat(capturedBundle(blocked)).isSameAs(capturedBundle(winner));
    }

    /** {@code thread} が {@code monitor} のモニタ待ちに入るまで待つ(他のロック待ちと区別する)。 */
    private static void awaitBlockedOn(Thread thread, Object monitor) throws InterruptedException {
        final long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED) {
                LockInfo lock = info.getLockInfo();
                if (lock != null && lock.getIdentityHashCode() == System.identityHashCode(monitor)) {
                    return;
                }
            }
            Thread.sleep(5);
        }
        throw new IllegalStateException("待機スレッドが renderer のモニタ待ちになりませんでした");
    }

    private static String capturedBundle(Page page) {
        ArgumentCaptor<Page.AddScriptTagOptions> options =
                ArgumentCaptor.forClass(Page.AddScriptTagOptions.class);
        verify(page, times(1)).addScriptTag(options.capture());
        return options.getValue().content;
    }

    /** {@code window.__chartResult} / {@code window.__chartError} を模した戻り値。 */
    private static Map<String, Object> result(Object html, Object error) {
        Map<String, Object> map = new HashMap<>();
        map.put("result", html);
        map.put("error", error);
        return map;
    }

    /**
     * {@code window.__chartResult}/{@code window.__chartError} を返す Page のモック。
     *
     * <p>{@code lenient()} なのは、{@link RechartsRenderer} が同じ {@code evaluate} を
     * 2引数版(harnessの起動)と1引数版(結果の取り出し)で意図的に呼び分けるため。
     * strict stubs は<b>メソッド名</b>で突き合わせるので、そのままでは
     * {@code PotentialStubbingProblem} になる(Mockito の javadoc が挙げる正当なケース)。
     */
    private static Page pageReturning(Map<String, Object> evaluated) {
        Page page = mock(Page.class);
        lenient().when(page.evaluate(RESULT_EXPRESSION)).thenReturn(evaluated);
        return page;
    }
}
