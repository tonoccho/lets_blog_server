package com.letsblog.content.contentcache;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlaywrightPageFetcher} がブラウザへ仕掛けるルートハンドラの分岐を直接動かす。
 *
 * <p>{@link ContentCacheSsrfGuardTest} は「ブラウザを起こす<b>前</b>に最初のURLを弾く」ことを
 * 見ている。こちらはその先、<b>ブラウザが実際に出す各リクエスト(リダイレクト先・サブリソースを
 * 含む)を毎回検査して通す/遮断する</b>という issue #902 の本体を見る。ここが素通しになると、
 * 外部ホストから内部アドレスへ302された時点でガードが意味を失う。
 *
 * <p>Chromium は要らない。{@link Browser}/{@link Page} をモックし、{@code page.route()} に
 * 渡されたハンドラを捕まえて直接呼ぶ。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("content-service: スクレイピング中の各リクエストも宛先を検査する(issue #902)")
class PlaywrightPageFetcherRouteTest {

    @Mock
    private Browser browser;
    @Mock
    private Page page;
    @Mock
    private OutboundUrlGuard outboundUrlGuard;
    @Mock
    private Route route;
    @Mock
    private Request request;

    /** {@code fetchHtml} を1回通し、{@code page.route()} に登録されたハンドラを取り出す。 */
    @SuppressWarnings("unchecked")
    private Consumer<Route> capturedRouteHandler() {
        when(browser.newPage()).thenReturn(page);
        when(page.content()).thenReturn("<html><head></head><body>ok</body></html>");

        String html = new PlaywrightPageFetcher(browser, outboundUrlGuard, new ReentrantLock())
                .fetchHtml("https://example.com/article");

        assertThat(html)
                .as("レンダリング済みHTMLをそのまま返す")
                .contains("ok");
        verify(outboundUrlGuard).requireAllowed("https://example.com/article");

        ArgumentCaptor<Consumer<Route>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(page).route(eq("**/*"), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("許可された宛先へのリクエストはそのまま通す")
    void 許可された宛先は通す() {
        Consumer<Route> handler = capturedRouteHandler();
        when(route.request()).thenReturn(request);
        when(request.url()).thenReturn("https://example.com/style.css");
        when(outboundUrlGuard.isAllowed("https://example.com/style.css")).thenReturn(true);

        handler.accept(route);

        verify(route).resume();
        verify(route, never()).abort();
    }

    @Test
    @DisplayName("拒否された宛先へのリクエストは遮断する(リダイレクト先・サブリソースも含む)")
    void 拒否された宛先は遮断する() {
        Consumer<Route> handler = capturedRouteHandler();
        when(route.request()).thenReturn(request);
        when(request.url()).thenReturn("http://169.254.169.254/latest/meta-data/");
        when(outboundUrlGuard.isAllowed("http://169.254.169.254/latest/meta-data/")).thenReturn(false);

        handler.accept(route);

        verify(route).abort();
        verify(route, never()).resume();
    }

    @Test
    @DisplayName("ブラウザ側の失敗はContentScrapingExceptionに変換される")
    void ブラウザの失敗は変換される() {
        when(browser.newPage()).thenReturn(page);
        when(page.navigate(any(String.class), any(Page.NavigateOptions.class)))
                .thenThrow(new com.microsoft.playwright.PlaywrightException("net::ERR_ABORTED"));

        PlaywrightPageFetcher fetcher = new PlaywrightPageFetcher(browser, outboundUrlGuard, new ReentrantLock());

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(
                ContentScrapingException.class,
                () -> fetcher.fetchHtml("https://example.com/gone")))
                .as("取得できなかったURLを添えて呼び出し元へ返す")
                .hasMessageContaining("https://example.com/gone");
    }
}
