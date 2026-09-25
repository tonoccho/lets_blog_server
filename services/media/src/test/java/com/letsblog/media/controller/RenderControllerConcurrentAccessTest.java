package com.letsblog.media.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.media.render.RechartsChartConfig;
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
import java.util.HashMap;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@code POST /api/render/recharts} を実際に {@link RenderController} 経由(Spring Security の
 * 認証ゲート・{@code GlobalExceptionHandler} を含む)で並行に叩いても全リクエストが200を返すことの
 * 回帰テスト(issue #1047)。
 *
 * <p><b>なぜ必要か。</b>{@code RechartsRendererConcurrentAccessTest}は{@link
 * com.letsblog.media.render.RechartsRenderer}を直接インスタンス化する素の単体テストで、
 * {@link RenderController}・{@code GlobalExceptionHandler}・Spring Securityの認証フィルタを
 * 一切通らない。受入基準の文言は「{@code POST /api/render/recharts}を4並行以上で叩いても、
 * 全リクエストが200でSVGを返す」というHTTPエンドポイントの挙動であり、これを実際に検証するには
 * コントローラ層を通す必要がある(Review #1047 note_4020)。
 *
 * <p><b>{@code MockMvc}を選んだ理由。</b>本サービスの既存統合テスト({@code
 * AuthorizationMatrixIntegrationTest}・{@code AdminAuthorizationIntegrationTest})はいずれも
 * {@code @SpringBootTest}+{@code @AutoConfigureMockMvc}+{@code MockMvc}の形であり、
 * {@code RANDOM_PORT}+実HTTPクライアントの前例は本サービスにも他サービスにも無い。
 * 新しい流儀を持ち込まず既存の作法に合わせた。
 *
 * <p><b>限界。</b>{@code MockMvc}は呼び出し元スレッド(このテストが{@link ExecutorService}で
 * 作るスレッド)上でDispatcherServletの処理を同期的に実行するため、Spring
 * (Tomcat)が実際のHTTPリクエストに使うリクエスト処理スレッドプールそのものは通らない。
 * ただし{@link RenderController}・{@link com.letsblog.media.render.RechartsRenderer}・
 * {@code GlobalExceptionHandler}はいずれも通常のSpring管理シングルトンBeanであり、
 * どちらのスレッドプールから呼ばれても共有状態(={@link Browser})へのアクセスは同一である。
 * 本テストが検証したい「複数スレッドから同時にコントローラ経由で{@code render()}が
 * 呼ばれても{@link Browser}への排他アクセスが破れない」という性質には無関係な差である。
 *
 * <p><b>Chromiumは使わない。</b>{@link Browser}を{@code @MockitoBean}で置き換える
 * ({@code PlaywrightLazyBrowserTest}と同じ、Chromium不在ホストでの回避)。
 *
 * <p><b>決定論的なレース検出。</b>{@code RechartsRendererConcurrentAccessTest}と同じ手法(モックの
 * {@code newPage()}応答内でスリープして観測窓を広げる)を使うが、ここでは<b>「同時に2本以上の
 * スレッドが{@code newPage()}の中にいたら実際に{@link PlaywrightException}を投げる」</b>ところまで
 * 踏み込む。実運用の502(issue本文のスタックトレース参照)を模した挙動にすることで、
 * 「HTTPステータスが全部200である」という受入基準の文言をそのまま検証できる
 * (単に排他区間の最大同時実行数を数えるだけでは、コントローラ層で本当に200が返るかまでは
 * 保証できない)。修正前の{@code render()}(排他制御なし)に対しては、この例外が
 * {@code GlobalExceptionHandler}で502へ写像されるため、本テストは必ずRED(200以外の応答)になる。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("media-service: POST /api/render/recharts の並行HTTPリクエストは全て200を返す(issue #1047)")
class RenderControllerConcurrentAccessTest {

    private static final String RESULT_EXPRESSION =
            "() => ({ result: window.__chartResult, error: window.__chartError })";
    private static final int CONCURRENCY = 8;

    private static final RechartsChartConfig CONFIG = new RechartsChartConfig(
            "bar", List.of(Map.of("month", "2024-01", "revenue", 100000.0)), "month", List.of("revenue"),
            List.of("#4e79a7"), false, 700, 300, "#333333", "#e0e0e0", "売上");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private Browser browser;

    @Test
    @DisplayName("4並行以上のHTTPリクエストが全て200でSVGを返す")
    void 並行httpリクエストは全て200を返す() throws Exception {
        AtomicInteger active = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);

        lenient().when(browser.newPage()).thenAnswer(invocation -> {
            int current = active.incrementAndGet();
            maxObserved.accumulateAndGet(current, Math::max);
            try {
                // 実際のConnectionが往復に使う時間帯を模して、レース窓を毎回確実に開かせる
                // (「たまたま通った」を証拠にしないため。Readiness Report参照)。
                Thread.sleep(30);
            } finally {
                active.decrementAndGet();
            }
            if (current > 1) {
                // 実運用で観測された例外(issue本文のスタックトレース)と同じ形。
                // RechartsRenderer#renderのcatch(PlaywrightException)を経てRechartsRenderExceptionになり、
                // GlobalExceptionHandlerが502へ写像する。
                throw new PlaywrightException(
                        "Cannot find object to call pausedStateChanged: debugger@simulated (issue #1047)");
            }
            Page page = mock(Page.class);
            lenient().when(page.evaluate(eq(RESULT_EXPRESSION))).thenReturn(result());
            return page;
        });

        String requestBody = objectMapper.writeValueAsString(CONFIG);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < CONCURRENCY; i++) {
            tasks.add(() -> {
                ready.countDown();
                awaitQuietly(go);
                MvcResult result = mockMvc.perform(post("/api/render/recharts")
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
                .as("POST /api/render/recharts を%d並行で叩いたときの各リクエストのHTTPステータス。"
                        + "1件でも200以外があれば、Browserへのアクセスがコントローラ経由でも"
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

    private static Map<String, Object> result() {
        Map<String, Object> map = new HashMap<>();
        map.put("result", "<div class=\"recharts-wrapper\"><svg/></div>");
        map.put("error", null);
        return map;
    }
}
